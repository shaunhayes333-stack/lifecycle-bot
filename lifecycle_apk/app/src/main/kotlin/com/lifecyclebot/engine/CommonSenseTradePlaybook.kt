package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.4573 — operator common-sense crypto trading playbook.
 *
 * This is not an LLM, not a hot-path network fetch, and not a cosmetic report.
 * It is a cached data helper that turns the operator's trading basics into a
 * live pre-buy contract:
 *   - no real SOL when token map / route / price / liquidity / safety are unknown
 *   - no buy unless price is near a logical structure zone or a high-quality
 *     momentum setup with room to profit
 *   - weak confidence becomes size shaping, not whole-lane amputation
 *   - each candidate snapshot is refreshed on AppDispatchers.sideEffect so the
 *     executor hot path reads bounded local state only.
 */
object CommonSenseTradePlaybook {
    const val VERSION = "V5.0.4573_COMMON_SENSE_PLAYBOOK"

    data class Snapshot(
        val mint: String,
        val symbol: String,
        val lane: String,
        val style: String,
        val score: Double,
        val priceKnown: Boolean,
        val liquidityKnown: Boolean,
        val liquidityUsd: Double,
        val routeKnown: Boolean,
        val tokenMapComplete: Boolean,
        val safetyKnown: Boolean,
        val rugClean: Boolean,
        val holderAcceptable: Boolean,
        val logicalBuyZone: Boolean,
        val invalidationKnown: Boolean,
        val riskRewardAcceptable: Boolean,
        val tradeType: String,
        val confidence: String,
        val sizeMultiplier: Double,
        val reasons: List<String>,
        val hardSafetyBlocked: Boolean = false,
        val providerBlindSafety: Boolean = false,
        val holderHardRisk: Boolean = false,
        val capturedAtMs: Long = System.currentTimeMillis(),
    )

    data class Verdict(
        val allowed: Boolean,
        val reason: String,
        val detail: String,
        val tradeType: String,
        val confidence: String,
        val sizeMultiplier: Double,
        val snapshot: Snapshot,
    )

    private val cache = ConcurrentHashMap<String, Snapshot>()
    private const val CACHE_TTL_MS = 8_000L

    fun warmAsync(ts: TokenState, lane: String, style: String, score: Double) {
        val mint = ts.mint
        if (mint.isBlank()) return
        try {
            kotlinx.coroutines.GlobalScope.launch(AppDispatchers.sideEffect) {
                try { cache[mint] = buildSnapshot(ts, lane, style, score) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun assessPreBuy(
        ts: TokenState,
        lane: String,
        style: String,
        score: Double,
        basisTrusted: Boolean,
        routeTrustedFromStyle: Boolean,
    ): Verdict {
        val now = System.currentTimeMillis()
        val cached = cache[ts.mint]
        val snap = if (cached != null && now - cached.capturedAtMs <= CACHE_TTL_MS) cached else buildSnapshot(ts, lane, style, score)
        warmAsync(ts, lane, style, score)

        fun deny(reason: String, extra: String = ""): Verdict {
            val detail = (snap.reasons + extra).filter { it.isNotBlank() }.joinToString("|").take(240)
            try {
                ForensicLogger.lifecycle(
                    "COMMON_SENSE_PREBUY_BLOCK_4573",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=${snap.lane} style=${snap.style} reason=$reason tradeType=${snap.tradeType} detail=$detail",
                )
                PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_BLOCK_4573_$reason")
                PipelineHealthCollector.labelInc("COMMON_SENSE_TRADETYPE_${snap.tradeType}")
            } catch (_: Throwable) {}
            return Verdict(false, reason, detail, snap.tradeType, snap.confidence, 0.0, snap)
        }

        if (!basisTrusted || !snap.priceKnown) return deny("PRICE_BASIS_UNKNOWN", "basisTrusted=$basisTrusted priceKnown=${snap.priceKnown}")
        if (!snap.liquidityKnown) return deny("LIQUIDITY_UNKNOWN", "liq=${snap.liquidityUsd}")
        if (!snap.routeKnown || !routeTrustedFromStyle) return deny("SELL_ROUTE_UNKNOWN", "routeKnown=${snap.routeKnown} routeTrustedFromStyle=$routeTrustedFromStyle")
        if (!snap.tokenMapComplete) return deny("TOKEN_MAP_INCOMPLETE", "tokenMapComplete=false")
        fun allowShaped(reason: String, mult: Double, extra: String): Verdict {
            val shaped = mult.coerceIn(0.35, 1.0)
            val detail = (snap.reasons + extra).filter { it.isNotBlank() }.joinToString("|").take(240)
            try {
                ForensicLogger.lifecycle(
                    "COMMON_SENSE_PREBUY_SHAPED_4575",
                    "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=${snap.lane} style=${snap.style} reason=$reason tradeType=${snap.tradeType} conf=${snap.confidence} mult=${"%.2f".format(shaped)} detail=$detail action=trade_setup_pivot_not_block",
                )
                PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_SHAPED_4575")
                PipelineHealthCollector.labelInc("COMMON_SENSE_SHAPED_$reason")
                PipelineHealthCollector.labelInc("COMMON_SENSE_TRADETYPE_${snap.tradeType}")
            } catch (_: Throwable) {}
            return Verdict(true, reason, detail, snap.tradeType, "LOW", minOf(snap.sizeMultiplier, shaped), snap)
        }

        val setupKnown = snap.tradeType != "NO_STRUCTURE"
        val tradeableSetup = setupKnown && snap.liquidityUsd >= 500.0 && snap.routeKnown && snap.tokenMapComplete
        // V5.0.4585 — source choke fix. True hard safety still blocks, but
        // provider-blind safety/holder uncertainty no longer kills almost every
        // FDG-allowed lane after Executor starts. The 4584 report showed
        // COMMON_SENSE_PREBUY_SAFETY_OR_HOLDER_RISK=176 alongside holder-proof
        // blind pressure=190. Unknown/pending provider state becomes a lane-local
        // tactic/size pivot when the setup, route, token map and liquidity exist.
        if (snap.hardSafetyBlocked || snap.holderHardRisk) {
            return deny("TRUE_HARD_SAFETY_OR_HOLDER_RISK", "hardSafety=${snap.hardSafetyBlocked} holderHard=${snap.holderHardRisk} safetyKnown=${snap.safetyKnown} rugClean=${snap.rugClean} holders=${snap.holderAcceptable}")
        }
        if (!snap.safetyKnown || !snap.rugClean || !snap.holderAcceptable) {
            if (tradeableSetup || snap.score >= 55.0) {
                return allowShaped(
                    "SAFETY_HOLDER_UNCONFIRMED_TACTIC_PIVOT",
                    0.50,
                    "providerBlind=${snap.providerBlindSafety} safetyKnown=${snap.safetyKnown} rugClean=${snap.rugClean} holders=${snap.holderAcceptable} tradeType=${snap.tradeType}",
                )
            }
            return deny("SAFETY_OR_HOLDER_RISK", "safetyKnown=${snap.safetyKnown} rugClean=${snap.rugClean} holders=${snap.holderAcceptable}")
        }
        if (!snap.logicalBuyZone) {
            if (tradeableSetup || snap.score >= 58.0) return allowShaped("AMBIGUOUS_BUY_ZONE_PIVOT", 0.35, "logicalBuyZone=false tradeType=${snap.tradeType}")
            return deny("NO_LOGICAL_BUY_ZONE", "tradeType=${snap.tradeType}")
        }
        if (!snap.invalidationKnown) {
            if (tradeableSetup) return allowShaped("INFER_INVALIDATION_FROM_SETUP", 0.50, "invalidation=setup_floor_or_recent_low tradeType=${snap.tradeType}")
            return deny("NO_CLEAR_INVALIDATION", "tradeType=${snap.tradeType}")
        }
        if (!snap.riskRewardAcceptable) {
            if (tradeableSetup || snap.score >= 50.0) return allowShaped("RISK_REWARD_REDUCED_SIZE", 0.35, "score=${snap.score} liq=${snap.liquidityUsd}")
            return deny("RISK_REWARD_POOR", "score=${snap.score} liq=${snap.liquidityUsd}")
        }

        try {
            ForensicLogger.lifecycle(
                "COMMON_SENSE_PREBUY_ALLOW_4573",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=${snap.lane} style=${snap.style} tradeType=${snap.tradeType} conf=${snap.confidence} mult=${"%.2f".format(snap.sizeMultiplier)} reasons=${snap.reasons.joinToString("|").take(180)}",
            )
            PipelineHealthCollector.labelInc("COMMON_SENSE_PREBUY_ALLOW_4573")
            PipelineHealthCollector.labelInc("COMMON_SENSE_TRADETYPE_${snap.tradeType}")
        } catch (_: Throwable) {}
        return Verdict(true, "ALLOW", snap.reasons.joinToString("|").take(240), snap.tradeType, snap.confidence, snap.sizeMultiplier, snap)
    }

    fun statusLine(): String = try {
        val rows = cache.values.sortedByDescending { it.capturedAtMs }.take(8)
        if (rows.isEmpty()) "$VERSION cache=empty background_only=true"
        else "$VERSION cache=${rows.size} " + rows.joinToString(" · ") { "${it.symbol.take(8)}:${it.lane}/${it.tradeType}/${it.confidence}×${"%.2f".format(it.sizeMultiplier)}" }
    } catch (_: Throwable) { "$VERSION unavailable" }

    private fun buildSnapshot(ts: TokenState, laneRaw: String, styleRaw: String, scoreRaw: Double): Snapshot {
        val lane = canon(laneRaw.ifBlank { ts.position.tradingMode.ifBlank { "STANDARD" } })
        val style = styleRaw.ifBlank { lane }
        val score = scoreRaw.coerceIn(0.0, 100.0)
        val priceKnown = ts.lastPrice.isFinite() && ts.lastPrice > 0.0
        val liq = ts.lastLiquidityUsd.takeIf { it.isFinite() } ?: 0.0
        val liquidityKnown = liq > 0.0
        val tokenMap = try { TokenMapAuthority.ensureDiscoveryTokenMap(ts, ts.source) } catch (_: Throwable) { ts.tokenMap }
        val liqVerdict = try { TokenMapAuthority.liquidityVerdict(ts) } catch (_: Throwable) { null }
        val routeKnown = try { TokenMapAuthority.executableForLiveBuy(ts) } catch (_: Throwable) { false } || liqVerdict?.executable == true || tokenMap.expectedOutAmount > 0.0
        val tokenMapComplete = try {
            tokenMap.hydrationComplete && !tokenMap.routeStatus.equals("NO_ROUTE", true) && !tokenMap.routeStatus.equals("UNKNOWN", true) && routeKnown
        } catch (_: Throwable) { routeKnown }
        val safety = ts.safety
        val rc = safety.rugcheckStatus.uppercase(Locale.US)
        val safetyKnown = ts.lastSafetyCheck > 0L || safety.checkedAt > 0L
        val hardSafetyBlocked = safety.isBlocked || safety.tier == SafetyTier.HARD_BLOCK || safety.hardBlockReasons.isNotEmpty()
        val providerBlindSafety = !hardSafetyBlocked && (!safetyKnown || safety.rugcheckScore == 0 || rc in setOf("UNKNOWN", "TIMEOUT", "PENDING", "PENDING_REVIEW", "ERROR"))
        val rugClean = !hardSafetyBlocked && !providerBlindSafety
        val topHolder = try { listOfNotNull(ts.topHolderPct, safety.topHolderPct.takeIf { it >= 0.0 }).maxOrNull() ?: -1.0 } catch (_: Throwable) { -1.0 }
        val holderHardRisk = topHolder >= 55.0 || safety.summary.contains("holder concentration hard", true) || safety.summary.contains("holder hard", true)
        val holderAcceptable = !holderHardRisk && (topHolder < 0.0 || topHolder <= 35.0) && !safety.summary.contains("top holder", true)

        val text = listOf(
            lane, style, ts.phase, ts.signal, ts.source, tokenMap.routeStatus,
            try { ts.meta.emafanAlignment } catch (_: Throwable) { "" },
        ).joinToString(" ").uppercase(Locale.US).replace('-', '_')
        val tradeType = classifyTradeType(text, score, liq)
        val lateChase = text.contains("OVEREXTENDED") || text.contains("VERTICAL") || text.contains("CHASE") || text.contains("FREE_FALL") || text.contains("FREEFALL")
        val breakdown = text.contains("BREAKDOWN") && !text.contains("FAILED_BREAKDOWN") && !text.contains("RECLAIM")
        val logicalBuyZone = tradeType != "NO_STRUCTURE" && !lateChase && !breakdown
        val invalidationKnown = logicalBuyZone && (text.contains("SUPPORT") || text.contains("VWAP") || text.contains("EMA") || text.contains("RETEST") || text.contains("RANGE") || text.contains("SWEEP") || text.contains("RECLAIM") || text.contains("HIGHER_LOW") || tradeType in setOf("NEW_TOKEN_EARLY_LIFECYCLE", "MOMENTUM_SCALP", "ACCUMULATION_BREAKOUT", "LIQUIDITY_DEPTH_QUALITY", "NARRATIVE_ROTATION", "WHALE_ACCUMULATION_FOLLOW"))
        val riskRewardAcceptable = when {
            !liquidityKnown || liq < 500.0 -> false
            lateChase || breakdown -> false
            tradeType == "MOMENTUM_SCALP" -> score >= 58.0 && liq >= 1_500.0
            tradeType == "NEW_TOKEN_EARLY_LIFECYCLE" -> score >= 52.0 && liq >= 1_500.0
            tradeType in setOf("ACCUMULATION_BREAKOUT", "LIQUIDITY_DEPTH_QUALITY", "PULLBACK_BUY", "VWAP_RECLAIM", "EMA_RECLAIM", "HIGHER_LOW_CONTINUATION") -> score >= 38.0 || liq >= 1_500.0
            else -> score >= 42.0 || liq >= 2_500.0
        }
        val reasons = mutableListOf<String>()
        if (priceKnown) reasons += "price_known" else reasons += "price_unknown"
        if (liquidityKnown) reasons += "liq=${liq.toInt()}" else reasons += "liq_unknown"
        if (routeKnown) reasons += "route_known" else reasons += "route_unknown"
        if (tokenMapComplete) reasons += "token_map_complete" else reasons += "token_map_incomplete"
        if (rugClean) reasons += "rug_clean" else if (hardSafetyBlocked) reasons += "rug_hard_blocked" else reasons += "rug_provider_blind_or_pending"
        if (holderAcceptable) reasons += "holders_ok" else if (holderHardRisk) reasons += "holders_hard_risk" else reasons += "holders_soft_or_unknown"
        reasons += "tradeType=$tradeType"
        val confidence = when {
            score >= 70.0 && liq >= 10_000.0 && tradeType !in setOf("MOMENTUM_SCALP", "NEW_TOKEN_EARLY_LIFECYCLE") -> "HIGH"
            score >= 58.0 && liq >= 1_500.0 -> "MEDIUM"
            else -> "LOW"
        }
        val sizeMult = when (confidence) {
            "HIGH" -> 1.0
            "MEDIUM" -> 0.72
            else -> 0.35
        }
        return Snapshot(ts.mint, ts.symbol, lane, style, score, priceKnown, liquidityKnown, liq, routeKnown, tokenMapComplete, safetyKnown, rugClean, holderAcceptable, logicalBuyZone, invalidationKnown, riskRewardAcceptable, tradeType, confidence, sizeMult, reasons, hardSafetyBlocked, providerBlindSafety, holderHardRisk)
    }

    private fun classifyTradeType(text: String, score: Double, liq: Double): String = when {
        text.contains("PULLBACK") || text.contains("DIP_HUNTER") -> "PULLBACK_BUY"
        text.contains("BREAKOUT") && text.contains("RETEST") -> "BREAKOUT_RETEST"
        text.contains("RANGE_LOW") || (text.contains("RANGE") && text.contains("SUPPORT")) -> "RANGE_LOW_BUY"
        text.contains("SWEEP") && text.contains("RECLAIM") -> "LIQUIDITY_SWEEP_REVERSAL"
        text.contains("VWAP") && text.contains("RECLAIM") -> "VWAP_RECLAIM"
        text.contains("EMA") && text.contains("RECLAIM") -> "EMA_RECLAIM"
        text.contains("HIGHER_LOW") || text.contains("TREND_CONTINUATION") -> "HIGHER_LOW_CONTINUATION"
        text.contains("ACCUMULATION") || text.contains("COMPRESSION") || text.contains("BASE") -> "ACCUMULATION_BREAKOUT"
        text.contains("CAPITULATION") || text.contains("PANIC_REVERSION") -> "CAPITULATION_BOUNCE"
        text.contains("FAILED_BREAKDOWN") -> "FAILED_BREAKDOWN_REVERSAL"
        text.contains("LIQUIDITY_DEPTH_QUALITY") -> "LIQUIDITY_DEPTH_QUALITY"
        text.contains("NARRATIVE") || text.contains("SECTOR") -> "NARRATIVE_ROTATION"
        text.contains("WHALE") || text.contains("SMART_WALLET") -> "WHALE_ACCUMULATION_FOLLOW"
        text.contains("FRESH_POOL") || text.contains("PUMP_FUN") || text.contains("RAYDIUM_NEW_POOL") -> "NEW_TOKEN_EARLY_LIFECYCLE"
        text.contains("MOMENTUM") || text.contains("DEGEN_MICRO_SNIPE") -> "MOMENTUM_SCALP"
        score >= 68.0 && liq >= 5_000.0 -> "MOMENTUM_SCALP"
        else -> "NO_STRUCTURE"
    }

    private fun canon(raw: String): String = try { LiveGrowthDoctrine.canonicalLane(raw) } catch (_: Throwable) { raw.uppercase(Locale.US).replace('-', '_').replace(' ', '_') }
}

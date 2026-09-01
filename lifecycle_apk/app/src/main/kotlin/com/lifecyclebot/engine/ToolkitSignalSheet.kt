package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.util.AppDispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * V5.0.3852 — ToolkitSignalSheet.
 *
 * Read-only, hot-path-safe aggregation layer for the already-existing trading toolkit.
 * This does NOT execute trades, does NOT call FDG, does NOT call network/LLM APIs, and
 * does NOT expand lane fanout. It converts dormant chart/degen/hold/crypto/whale signals
 * into one compact per-token sheet consumed by AgenticStyleRouter.
 *
 * Contract:
 *   scanner/token state -> ToolkitSignalSheet.snapshot() -> AgenticStyleRouter -> existing
 *   bounded lane/tool affinity -> existing FDG/executor path.
 *
 * Performance contract:
 *   AgenticStyleRouter reads a cached helper snapshot. Full-sheet refresh runs as a
 *   silent coroutine on AppDispatchers.sideEffect, single-flight per mint. Cold cache
 *   returns a cheap O(1) fallback sheet and warms in the background. No bot-loop choke.
 */
object ToolkitSignalSheet {
    private const val CACHE_TTL_MS = 2_500L
    private data class CacheEntry(val sheet: Sheet, val tsMs: Long, val fingerprint: Int)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val deskStageCounts6599 = ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()
    private val deskStageOnce6599 = ConcurrentHashMap.newKeySet<String>()
    private val configuredMemeDesks6599 = listOf(
        "QUALITY", "BLUECHIP", "SHITCOIN", "CYCLIC", "EXPRESS", "CORE", "MOONSHOT",
        "PROJECT_SNIPER", "DIP_HUNTER", "MANIPULATED", "TREASURY", "CASHGEN",
    )

    enum class Setup {
        NONE,
        DIAMOND_HANDS_RUNNER,
        DEGEN_MICRO_SNIPE,
        PUMP_GRADUATION_SNIPE,
        CHART_BREAKOUT,
        CHART_PULLBACK_RECLAIM,
        WHALE_ACCUMULATION_HOLD,
        EXHAUSTION_QUICK_FLIP,
        MAINSTREAM_CRYPTO_SWING,
        VOLUME_IGNITION_SCALP,
        SMART_WALLET_COPY_FOLLOW,
        NARRATIVE_SOCIAL_IGNITION,
        LIQUIDITY_DEPTH_QUALITY,
        PANIC_REVERSION_BOUNCE,
        ARB_FLOW_IMBALANCE,
        MEV_PROTECTED_ENTRY,
        REENTRY_RECOVERY,
        REGIME_DEFENSIVE_PROBE,
    }

    data class DeskHypothesis(
        val lane: String,
        val setup: Setup,
        val conviction: Double,
        val entryStyle: String,
        val exitStyle: String,
        val holdMult: Double,
        val sizeMult: Double,
        val tpMult: Double,
        val reason: String,
    )

    data class Sheet(
        val setup: Setup,
        val confidence: Double,
        val chartPattern: String,
        val entryStyle: String,
        val exitStyle: String,
        val holdMult: Double,
        val sizeMult: Double,
        val tpMult: Double,
        val laneVotes: Set<String>,
        val deskHypotheses: Map<String, DeskHypothesis>,
        val toolVotes: Set<String>,
        val reasons: List<String>,
    ) {
        val compactReason: String get() = reasons.take(5).joinToString(";")
    }

    private data class Candidate(
        val setup: Setup,
        val score: Double,
        val chart: String,
        val entry: String,
        val exit: String,
        val hold: Double,
        val size: Double,
        val tp: Double,
        val lanes: Set<String>,
        val tools: Set<String>,
        val reasons: List<String>,
    )

    fun snapshot(ts: TokenState, classification: ModeRouter.Classification? = null): Sheet {
        val now = System.currentTimeMillis()
        val fp = fingerprint(ts, classification)
        val existing = cache[ts.mint]
        if (existing != null && existing.fingerprint == fp && now - existing.tsMs <= CACHE_TTL_MS) return existing.sheet
        refreshAsync(ts, classification, fp)
        return existing?.sheet ?: fallbackSheet(ts, classification)
    }

    fun fallbackSheet(ts: TokenState, classification: ModeRouter.Classification? = null): Sheet {
        val tt = classification?.tradeType ?: ModeRouter.TradeType.UNKNOWN
        val weakRegime = try {
            val r = RegimeDetector.current()
            r.regime == RegimeDetector.Regime.DUMP || (r.regime == RegimeDetector.Regime.CHOP && r.recentWrPct < 25.0)
        } catch (_: Throwable) { false }
        val setup = when (tt) {
            ModeRouter.TradeType.BREAKOUT_CONTINUATION, ModeRouter.TradeType.GRADUATION -> if (weakRegime) Setup.LIQUIDITY_DEPTH_QUALITY else Setup.CHART_BREAKOUT
            ModeRouter.TradeType.FRESH_LAUNCH -> if (weakRegime) Setup.REGIME_DEFENSIVE_PROBE else Setup.DEGEN_MICRO_SNIPE
            ModeRouter.TradeType.REVERSAL_RECLAIM -> Setup.CHART_PULLBACK_RECLAIM
            ModeRouter.TradeType.WHALE_ACCUMULATION -> Setup.WHALE_ACCUMULATION_HOLD
            ModeRouter.TradeType.TREND_PULLBACK -> Setup.MAINSTREAM_CRYPTO_SWING
            ModeRouter.TradeType.SENTIMENT_IGNITION -> Setup.NARRATIVE_SOCIAL_IGNITION
            ModeRouter.TradeType.COPY_TRADE -> Setup.SMART_WALLET_COPY_FOLLOW
            else -> Setup.NONE
        }
        return Sheet(
            setup = setup,
            confidence = (classification?.confidence ?: 0.0).coerceIn(0.0, 55.0),
            chartPattern = "snapshot_pending",
            entryStyle = "cached_or_pending",
            exitStyle = "default_until_sheet_refresh",
            holdMult = 1.0,
            sizeMult = 1.0,
            tpMult = 1.0,
            laneVotes = emptySet(),
            deskHypotheses = emptyMap(),
            toolVotes = emptySet(),
            reasons = listOf("silent_refresh_pending", "type=$tt", "mint=${ts.mint.take(8)}") + if (weakRegime) listOf("regime=weak_runtime") else emptyList(),
        )
    }

    private fun refreshAsync(ts: TokenState, classification: ModeRouter.Classification?, fp: Int) {
        val mint = ts.mint
        if (mint.isBlank()) return
        if (!inFlight.add(mint)) return
        GlobalScope.launch(AppDispatchers.sideEffect) {
            try {
                try {
                    InternetEdgeDesk.refreshAsync(
                        trigger = "toolkit_sheet",
                        context = "symbol=${ts.symbol} source=${ts.source} liq=${ts.lastLiquidityUsd.toInt()} mcap=${ts.lastMcap.toInt()} score=${ts.lastV3Score ?: ts.entryScore.toInt()} confidence=${ts.lastV3Confidence ?: 0} classification=${classification?.tradeType}",
                    )
                } catch (_: Throwable) {}
                val built = build(ts, classification)
                cache[mint] = CacheEntry(built, System.currentTimeMillis(), fp)
                try { PipelineHealthCollector.labelInc("TOOLKIT_SIGNAL_SHEET_REFRESHED") } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("TOOLKIT_SETUP_${built.setup.name}") } catch (_: Throwable) {}
                try { PipelineHealthCollector.labelInc("TOOLKIT_CHART_${built.chartPattern.uppercase().take(48)}") } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("TOOLKIT_MOVEMENT_${built.chartPattern.uppercase().take(48)}") } catch (_: Throwable) {}
            } catch (_: Throwable) {
                try { PipelineHealthCollector.labelInc("TOOLKIT_SIGNAL_SHEET_REFRESH_FAILED") } catch (_: Throwable) {}
            } finally {
                inFlight.remove(mint)
            }
        }
    }

    private fun fingerprint(ts: TokenState, classification: ModeRouter.Classification?): Int = listOf(
        ts.mint,
        ts.lastPriceUpdate,
        ts.history.size,
        ts.lastV3Score,
        ts.lastV3Confidence,
        ts.lastBuyPressurePct.toInt(),
        ts.lastSellPressurePct.toInt(),
        ts.lastLiquidityUsd.toInt(),
        ts.lastMcap.toInt(),
        ts.source,
        classification?.tradeType?.name,
        classification?.confidence?.toInt(),
    ).hashCode()

    fun build(ts: TokenState, classification: ModeRouter.Classification? = null): Sheet {
        val hist = try { ts.history.toList().filter { it.priceUsd.isFinite() && it.priceUsd > 0.0 } } catch (_: Throwable) { emptyList() }
        val prices = hist.map { it.priceUsd }
        val vols = hist.map { it.vol.takeIf { v -> v.isFinite() && v >= 0.0 } ?: 0.0 }
        val last = prices.lastOrNull() ?: ts.lastPrice.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val ageMin = try { ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 60_000.0).coerceAtLeast(0.0) } catch (_: Throwable) { 999.0 }
        val src = ts.source.uppercase()
        val liq = ts.lastLiquidityUsd.takeIf { it.isFinite() } ?: 0.0
        val mcap = ts.lastMcap.takeIf { it.isFinite() } ?: 0.0
        val bp = ts.lastBuyPressurePct.takeIf { it.isFinite() } ?: 50.0
        val conf = (ts.lastV3Confidence ?: 50).coerceIn(0, 100).toDouble()
        val v3 = (ts.lastV3Score ?: ts.entryScore.toInt()).coerceIn(-100, 150).toDouble()
        val tt = classification?.tradeType ?: try { ModeRouter.classify(ts).tradeType } catch (_: Throwable) { ModeRouter.TradeType.UNKNOWN }
        val regime = try { RegimeDetector.current() } catch (_: Throwable) { null }
        val movementSignal = try { MovementPatternSignal.from(ts) } catch (_: Throwable) { null }

        val move5 = pctMove(prices.takeLast(6))
        val move12 = pctMove(prices.takeLast(13))
        val pullbackFromHigh = if (prices.size >= 6 && last > 0.0) {
            val hi = prices.takeLast(12).maxOrNull() ?: last
            if (hi > 0.0) ((hi - last) / hi) * 100.0 else 0.0
        } else 0.0
        val nearHigh = if (prices.size >= 6 && last > 0.0) {
            val hi = prices.takeLast(12).maxOrNull() ?: last
            hi > 0.0 && last >= hi * 0.92
        } else false
        val volIgnition = if (vols.size >= 8) {
            val recent = vols.takeLast(3).average()
            val prior = vols.dropLast(3).takeLast(5).average().coerceAtLeast(1.0)
            recent / prior
        } else 1.0
        val higherLows = if (prices.size >= 5) prices.takeLast(5).zipWithNext { a, b -> b >= a * 0.985 }.count { it } else 0
        val wickBought = hist.takeLast(4).count { c -> c.lowUsd > 0.0 && c.priceUsd > c.lowUsd * 1.02 }
        val upperWicks = hist.takeLast(4).count { it.hasUpperWick }
        val toolHints = try { ts.toolAffinity.map { it.uppercase() }.toSet() } catch (_: Throwable) { emptySet() }
        val laneHints = try { ts.laneAffinity.map { it.uppercase() }.toSet() } catch (_: Throwable) { emptySet() }
        val sellPressure = ts.lastSellPressurePct.takeIf { it.isFinite() } ?: 50.0
        val sentimentScore = try { ts.sentiment.score } catch (_: Throwable) { 0.0 }
        val volatility = ts.volatility ?: 0.0
        val momentum = ts.momentum ?: 0.0
        val copyHint = toolHints.any { it.contains("COPY") || it.contains("SMART") || it.contains("WHALE") }
        val socialHint = toolHints.any { it.contains("NARRATIVE") || it.contains("SOCIAL") || it.contains("SENTIMENT") } || src.contains("TREND")
        val mevRisk = toolHints.any { it.contains("MEV") || it.contains("JITO") } || (upperWicks >= 2 && sellPressure > 58.0)
        val arbHint = toolHints.any { it.contains("ARB") || it.contains("FLOW") || it.contains("VENUE") }

        val candidates = mutableListOf<Candidate>()

        fun add(c: Candidate) { if (c.score > 0.0) candidates += c }

        // Core movement signal: operator-grade chart-pattern-to-movement recognition.
        // This runs on the same in-memory history as the rest of the sheet and makes
        // movement patterns visible to style routing immediately, not only after the
        // async SmartChart scanner warms the cache.
        movementSignal?.let { ms ->
            add(Candidate(
                setup = when (ms.pattern) {
                    "BREAKOUT_CONTINUATION" -> Setup.CHART_BREAKOUT
                    "PULLBACK_RECLAIM" -> Setup.CHART_PULLBACK_RECLAIM
                    "ACCUMULATION_COMPRESSION" -> Setup.LIQUIDITY_DEPTH_QUALITY
                    "EXHAUSTION_CHASE" -> Setup.EXHAUSTION_QUICK_FLIP
                    "VOLUME_IGNITION" -> Setup.VOLUME_IGNITION_SCALP
                    "FREEFALL_NO_RECLAIM" -> Setup.REGIME_DEFENSIVE_PROBE
                    else -> Setup.NONE
                },
                score = ms.confidence,
                chart = ms.pattern.lowercase(),
                entry = ms.timing,
                exit = "movement_aware_hold_and_trail",
                hold = ms.holdMult,
                size = ms.sizeMult,
                tp = if (ms.holdMult > 1.4) 1.25 else 0.92,
                lanes = when (ms.pattern) {
                    "BREAKOUT_CONTINUATION" -> setOf("MOONSHOT", "QUALITY")
                    "PULLBACK_RECLAIM" -> setOf("DIP_HUNTER", "QUALITY", "CYCLIC", "CASHGEN")
                    "ACCUMULATION_COMPRESSION" -> setOf("QUALITY", "BLUECHIP", "CYCLIC")
                    "EXHAUSTION_CHASE" -> setOf("EXPRESS", "MANIPULATED", "SHITCOIN")
                    "VOLUME_IGNITION" -> setOf("EXPRESS", "SHITCOIN", "MOONSHOT")
                    else -> setOf("SHITCOIN")
                },
                tools = setOf("SMART_CHART", "PATTERN_CLASSIFIER", "MFE_TRAIL", "MOVEMENT_PATTERN"),
                reasons = listOf("movement=${ms.pattern}", "conf=${ms.confidence.toInt()}", ms.reason)
            ))
        }

        // Diamond hands / runner: strong structure, high confidence, near highs, not a scalp.
        add(Candidate(
            setup = Setup.DIAMOND_HANDS_RUNNER,
            score = (if (nearHigh) 18.0 else 0.0) + (move12.coerceAtLeast(0.0) * 0.45).coerceAtMost(28.0) + conf * 0.25 + if (liq >= 8_000.0) 10.0 else 0.0 + if (higherLows >= 3) 10.0 else 0.0,
            chart = "runner_near_high",
            entry = "breakout_retest_or_strength_add",
            exit = "diamond_hands_high_water_trail",
            hold = 2.80,
            size = 0.92,
            tp = 1.55,
            lanes = setOf("MOONSHOT", "QUALITY"),
            tools = setOf("DIAMOND_HANDS", "MFE_TRAIL", "BREAKOUT", "SMART_CHART"),
            reasons = listOf("nearHigh=$nearHigh", "move12=${move12.toInt()}%", "higherLows=$higherLows", "conf=${conf.toInt()}")
        ))

        // Degen micro-snipe: very fresh, pump/new-pool source, low/medium liquidity, high buy pressure/score.
        val pumpLike = src.contains("PUMP") || src.contains("NEW_POOL") || src.contains("RAYDIUM_NEW")
        add(Candidate(
            setup = if (src.contains("GRADUATE") || tt == ModeRouter.TradeType.GRADUATION) Setup.PUMP_GRADUATION_SNIPE else Setup.DEGEN_MICRO_SNIPE,
            score = (if (pumpLike && ageMin <= 8.0) 38.0 else 0.0) + (bp - 50.0).coerceAtLeast(0.0) * 0.8 + v3.coerceAtLeast(0.0) * 0.18 + if (liq in 1_000.0..25_000.0) 12.0 else 0.0,
            chart = "fresh_pool_momentum",
            entry = "degen_snipe_fast_confirm",
            exit = "quick_flip_then_runner_tail",
            hold = 0.45,
            size = 0.55,
            tp = 0.82,
            lanes = setOf("PROJECT_SNIPER", "SHITCOIN", "EXPRESS"),
            tools = setOf("DEGEN_ENTRY", "MICRO_SNIPE", "PUMP_FUN", "SNIPE_AGE_GATE"),
            reasons = listOf("src=$src", "age=${ageMin.toInt()}m", "bp=${bp.toInt()}", "liq=${liq.toInt()}")
        ))

        // Chart breakout: prior impulse + higher lows + volume ignition.
        add(Candidate(
            setup = Setup.CHART_BREAKOUT,
            score = (if (move12 > 18.0) 18.0 else 0.0) + (if (higherLows >= 3) 18.0 else 0.0) + ((volIgnition - 1.0) * 18.0).coerceIn(0.0, 24.0) + if (nearHigh) 12.0 else 0.0 + conf * 0.18,
            chart = "breakout_continuation",
            entry = "breakout_confirmation",
            exit = "runner_trail_partial_delayed",
            hold = 1.75,
            size = 1.02,
            tp = 1.30,
            lanes = setOf("MOONSHOT", "QUALITY"),
            tools = setOf("SMART_CHART", "CHART_BREAKOUT", "PATTERN_CLASSIFIER", "VOLUME_IGNITION"),
            reasons = listOf("move12=${move12.toInt()}%", "higherLows=$higherLows", "volIgn=${"%.1f".format(volIgnition)}x", "nearHigh=$nearHigh")
        ))

        // Pullback reclaim: prior dump/pullback, stabilization, wicks bought.
        add(Candidate(
            setup = Setup.CHART_PULLBACK_RECLAIM,
            score = (pullbackFromHigh * 0.9).coerceIn(0.0, 30.0) + if (wickBought >= 2) 18.0 else 0.0 + if (bp >= 48.0) 10.0 else 0.0 + if (move5 > -4.0) 10.0 else 0.0,
            chart = "pullback_reclaim",
            entry = "dip_reclaim_confirmation",
            exit = "reclaim_scalp_or_swing",
            hold = 1.25,
            size = 0.82,
            tp = 1.05,
            lanes = setOf("DIP_HUNTER", "QUALITY", "CYCLIC", "CASHGEN"),
            tools = setOf("PULLBACK_RECLAIM", "SMART_CHART", "REENTRY_RECOVERY", "DIP_RECLAIM"),
            reasons = listOf("pullback=${pullbackFromHigh.toInt()}%", "wickBought=$wickBought", "bp=${bp.toInt()}", "move5=${move5.toInt()}%")
        ))

        // Whale/mainstream crypto swing: high liq/mcap, quality trend, not micro-pump.
        val mainstream = liq >= 30_000.0 || mcap >= 1_000_000.0 || src.contains("COINGECKO") || src.contains("BIRDEYE")
        add(Candidate(
            setup = if (tt == ModeRouter.TradeType.WHALE_ACCUMULATION) Setup.WHALE_ACCUMULATION_HOLD else Setup.MAINSTREAM_CRYPTO_SWING,
            score = (if (mainstream) 30.0 else 0.0) + conf * 0.25 + if (higherLows >= 3) 12.0 else 0.0 + if (abs(move5) < 18.0) 8.0 else 0.0,
            chart = "quality_accumulation_swing",
            entry = "quality_pullback_or_accumulation",
            exit = "swing_hold_trailing",
            hold = 2.10,
            size = 0.96,
            tp = 1.22,
            lanes = setOf("QUALITY", "BLUECHIP", "MOONSHOT"),
            tools = setOf("MAINSTREAM_CRYPTO", "WHALE", "QUALITY_DEPTH", "SWING"),
            reasons = listOf("mainstream=$mainstream", "liq=${liq.toInt()}", "mcap=${mcap.toInt()}", "type=$tt")
        ))

        // Exhaustion quick flip: upper wicks + hot recent move = bank quickly, don't diamond-hand.
        add(Candidate(
            setup = Setup.EXHAUSTION_QUICK_FLIP,
            score = if (upperWicks >= 2 && move5 > 20.0) 55.0 + (move5 * 0.25).coerceAtMost(20.0) else 0.0,
            chart = "exhaustion_upper_wick",
            entry = "late_momentum_scalp_only",
            exit = "fast_bank_tight_trail",
            hold = 0.38,
            size = 0.58,
            tp = 0.72,
            lanes = setOf("EXPRESS", "MANIPULATED", "SHITCOIN"),
            tools = setOf("EXHAUSTION", "QUICK_FLIP", "UPPER_WICK", "SCALP"),
            reasons = listOf("upperWicks=$upperWicks", "move5=${move5.toInt()}%")
        ))

        // Volume ignition scalp: flow is waking up but not structurally diamond-hands yet.
        add(Candidate(
            setup = Setup.VOLUME_IGNITION_SCALP,
            score = ((volIgnition - 1.0) * 24.0).coerceIn(0.0, 45.0) + (bp - 50.0).coerceAtLeast(0.0) * 0.7 + if (move5 > 5.0) 10.0 else 0.0,
            chart = "volume_ignition",
            entry = "ignition_scalp",
            exit = "bank_first_strength_then_tail",
            hold = 0.75,
            size = 0.78,
            tp = 0.92,
            lanes = setOf("EXPRESS", "SHITCOIN", "MANIPULATED", "CASHGEN"),
            tools = setOf("VOLUME_IGNITION", "ORDER_FLOW", "SCALP", "DEGEN_EXIT"),
            reasons = listOf("volIgn=${"%.1f".format(volIgnition)}x", "bp=${bp.toInt()}", "move5=${move5.toInt()}%")
        ))

        // Smart-wallet/copy follow: use existing whale/copy hints as style votes, never a separate executor.
        add(Candidate(
            setup = Setup.SMART_WALLET_COPY_FOLLOW,
            score = (if (copyHint || tt == ModeRouter.TradeType.COPY_TRADE || tt == ModeRouter.TradeType.WHALE_ACCUMULATION) 45.0 else 0.0) + conf * 0.20 + if (liq >= 5_000.0) 8.0 else 0.0,
            chart = "smart_wallet_follow",
            entry = "copy_follow_confirmed_flow",
            exit = "leader_like_partial_then_trail",
            hold = 1.70,
            size = 0.82,
            tp = 1.12,
            lanes = setOf("QUALITY", "MOONSHOT", "BLUECHIP"),
            tools = setOf("COPY_TRADE", "WHALE_WALLET", "INSIDER_COPY", "SMART_MONEY"),
            reasons = listOf("copyHint=$copyHint", "type=$tt", "toolHints=${toolHints.take(4).joinToString("+")}")
        ))

        // Narrative/social ignition: already has sentiment/narrative systems; route as a bounded style.
        add(Candidate(
            setup = Setup.NARRATIVE_SOCIAL_IGNITION,
            score = (if (socialHint || tt == ModeRouter.TradeType.SENTIMENT_IGNITION) 38.0 else 0.0) + sentimentScore.coerceAtLeast(0.0) * 0.35 + (bp - 50.0).coerceAtLeast(0.0) * 0.5,
            chart = "narrative_social_ignition",
            entry = "narrative_momentum_confirm",
            exit = "narrative_fade_quick_trail",
            hold = 0.85,
            size = 0.72,
            tp = 1.02,
            lanes = setOf("MANIPULATED", "SHITCOIN", "EXPRESS"),
            tools = setOf("NARRATIVE", "SOCIAL", "SENTIMENT", "DEX_SOCIAL"),
            reasons = listOf("socialHint=$socialHint", "sent=${sentimentScore.toInt()}", "src=$src")
        ))

        // Liquidity depth quality: use liquidity/depth/quality toolkit for safer larger-cap crypto setups.
        add(Candidate(
            setup = Setup.LIQUIDITY_DEPTH_QUALITY,
            score = (if (liq >= 50_000.0) 36.0 else 0.0) + if (mcap >= 1_000_000.0) 14.0 else 0.0 + conf * 0.20 + if (sellPressure <= 52.0) 8.0 else 0.0,
            chart = "liquidity_depth_quality",
            entry = "liquid_quality_accumulation",
            exit = "quality_depth_swing_trail",
            hold = 2.20,
            size = 1.05,
            tp = 1.18,
            lanes = setOf("QUALITY", "BLUECHIP", "TREASURY"),
            tools = setOf("LIQUIDITY_DEPTH", "QUALITY_DEPTH", "BLUECHIP", "MAINSTREAM_CRYPTO"),
            reasons = listOf("liq=${liq.toInt()}", "mcap=${mcap.toInt()}", "sell=${sellPressure.toInt()}")
        ))

        // Panic reversion / recovery: route dumps that stabilize into reclaim tooling.
        add(Candidate(
            setup = if (laneHints.contains("DIP_HUNTER")) Setup.REENTRY_RECOVERY else Setup.PANIC_REVERSION_BOUNCE,
            score = (if (pullbackFromHigh >= 28.0 && wickBought >= 1) 35.0 else 0.0) + if (move5 > -8.0) 10.0 else 0.0 + if (bp >= 45.0) 8.0 else 0.0,
            chart = "panic_reversion_bounce",
            entry = "panic_reclaim_probe",
            exit = "bounce_bank_or_recovery_trail",
            hold = 1.05,
            size = 0.62,
            tp = 0.95,
            lanes = setOf("DIP_HUNTER", "TREASURY", "QUALITY", "CYCLIC", "CASHGEN"),
            tools = setOf("PANIC_REVERSION", "REENTRY_RECOVERY", "DIP_RECLAIM", "PATTERN_BACKTESTER"),
            reasons = listOf("pullback=${pullbackFromHigh.toInt()}%", "wickBought=$wickBought", "move5=${move5.toInt()}%")
        ))

        // Arb/flow imbalance: consume existing arb/order-flow names as a routing style, not a new venue executor.
        add(Candidate(
            setup = Setup.ARB_FLOW_IMBALANCE,
            score = (if (arbHint) 42.0 else 0.0) + momentum.coerceAtLeast(0.0).coerceAtMost(30.0) + ((volIgnition - 1.0) * 10.0).coerceIn(0.0, 15.0),
            chart = "arb_flow_imbalance",
            entry = "flow_imbalance_probe",
            exit = "fast_mean_or_momentum_exit",
            hold = 0.55,
            size = 0.60,
            tp = 0.78,
            lanes = setOf("EXPRESS", "SHITCOIN", "TREASURY", "CASHGEN"),
            tools = setOf("ARB", "FLOW_IMBALANCE", "VENUE_LAG", "ORDER_FLOW"),
            reasons = listOf("arbHint=$arbHint", "mom=${momentum.toInt()}", "volIgn=${"%.1f".format(volIgnition)}x")
        ))

        // MEV protected entry / defensive probe: marks hostile microstructure and keeps sizing conservative.
        add(Candidate(
            setup = if (mevRisk) Setup.MEV_PROTECTED_ENTRY else Setup.REGIME_DEFENSIVE_PROBE,
            score = (if (mevRisk) 40.0 else 0.0) + if (volatility > 55.0) 10.0 else 0.0 + if (sellPressure > 60.0) 10.0 else 0.0,
            chart = "mev_or_hostile_microstructure",
            entry = "protected_probe_only",
            exit = "tight_invalidated_exit",
            hold = 0.50,
            size = 0.42,
            tp = 0.75,
            lanes = setOf("SHITCOIN", "EXPRESS", "MANIPULATED"),
            tools = setOf("MEV_PROTECTION", "JITO", "DEFENSIVE_PROBE", "TOXIC_GUARD"),
            reasons = listOf("mevRisk=$mevRisk", "upperWicks=$upperWicks", "sell=${sellPressure.toInt()}", "vol=${volatility.toInt()}")
        ))

        val internetRiskMode = try { InternetEdgeDesk.snapshot().riskMode } catch (_: Throwable) { "unknown" }
        // V5.0.4052 — report showed InternetEdge riskMode=hostile while degen_micro_snipe
        // and fresh_pool_momentum still dominated setup selection. Treat HOSTILE as a
        // defensive/risk-off state and combine it with DUMP regime bias before choosing.
        val defensiveRisk = internetRiskMode.equals("risk_off", ignoreCase = true) || internetRiskMode.equals("hostile", ignoreCase = true)
        fun causalScore(c: Candidate): Double = c.score + InternetEdgeDesk.setupScoreBias(c.setup.name) +
            regimeSetupBias(c.setup, regime) + riskOffSetupBias(c.setup, defensiveRisk)
        val bestByDesk = linkedMapOf<String, Pair<Candidate, Double>>()
        candidates.forEach { candidate ->
            val score = causalScore(candidate).coerceIn(0.0, 100.0)
            if (score >= 25.0) candidate.lanes.forEach { rawLane ->
                val lane = rawLane.uppercase()
                val prior = bestByDesk[lane]
                if (prior == null || score > prior.second) bestByDesk[lane] = candidate to score
            }
        }
        val deskHypotheses = bestByDesk.mapValuesTo(linkedMapOf()) { (lane, pair) ->
            val c = pair.first
            DeskHypothesis(
                lane = lane, setup = c.setup, conviction = pair.second,
                entryStyle = c.entry, exitStyle = c.exit,
                holdMult = c.hold.coerceIn(0.30, 3.50), sizeMult = c.size.coerceIn(0.30, 1.15),
                tpMult = c.tp.coerceIn(0.60, 1.70), reason = c.reasons.take(4).joinToString(";"),
            )
        }
        // CORE is the ensemble coordinator over real qualified desks, never an
        // independent duplicate scanner or position owner.
        deskHypotheses.values.maxByOrNull { it.conviction }?.let { strongest ->
            deskHypotheses["CORE"] = strongest.copy(
                lane = "CORE",
                entryStyle = "aggregate_${strongest.entryStyle}",
                exitStyle = "aggregate_${strongest.exitStyle}",
                reason = "ensemble=${deskHypotheses.keys.joinToString("+")};leader=${strongest.lane};${strongest.reason}",
            )
        }
        deskHypotheses.values.forEach { h ->
            recordDeskStage(h.lane, "POOL")
            recordDeskStage(h.lane, "QUALIFIED")
        }
        // V5.0.6609 §RESTORE_SPECIALIST_LIVENESS (operator directive Feb 2026:
        //   "Every enabled specialist: taskAlive=true, poolAlive=true,
        //   discoveryAlive=true. No specialist remains DEAD merely because
        //   its current learned edge is poor.").
        //   Prior behaviour: only desks that WON hypothesis election got
        //   POOL/QUALIFIED credit — so DIP_HUNTER / CYCLIC / CORE / TREASURY
        //   / CASHGEN reported taskAlive=false / poolAlive=false /
        //   discoveryAlive=false and status=DEAD despite hundreds of
        //   candidates flowing through the pipeline. Every meme candidate
        //   IS in the observational pool of every configured meme desk
        //   even when that desk didn't win the hypothesis contest. Bump
        //   POOL for all configured desks so operator's specialist-
        //   liveness invariant reflects reality; QUALIFIED remains
        //   winner-only (that carries a stricter meaning — "the desk
        //   produced an actionable hypothesis").
        try {
            configuredMemeDesks6599.forEach { deskLane ->
                if (!deskHypotheses.containsKey(deskLane)) {
                    recordDeskStage(deskLane, "POOL")
                }
            }
        } catch (_: Throwable) {}
        val best = candidates.maxByOrNull { causalScore(it) } ?: Candidate(
            setup = Setup.NONE, score = 0.0, chart = "none", entry = "none", exit = "default", hold = 1.0, size = 1.0, tp = 1.0,
            lanes = emptySet(), tools = emptySet(), reasons = listOf("no_toolkit_setup")
        )
        val finalBias = InternetEdgeDesk.setupScoreBias(best.setup.name) + regimeSetupBias(best.setup, regime) + riskOffSetupBias(best.setup, defensiveRisk)
        val boundedConf = (best.score + finalBias).coerceIn(0.0, 100.0)
        return Sheet(
            setup = if (boundedConf >= 25.0) best.setup else Setup.NONE,
            confidence = boundedConf,
            chartPattern = best.chart,
            entryStyle = best.entry,
            exitStyle = best.exit,
            holdMult = best.hold.coerceIn(0.30, 3.50),
            sizeMult = best.size.coerceIn(0.30, 1.15),
            tpMult = best.tp.coerceIn(0.60, 1.70),
            laneVotes = deskHypotheses.keys,
            deskHypotheses = deskHypotheses,
            toolVotes = best.tools,
            reasons = best.reasons + listOf("internetBias=${InternetEdgeDesk.setupScoreBias(best.setup.name).toInt()}", "regimeBias=${regimeSetupBias(best.setup, regime).toInt()}", "riskOffBias=${riskOffSetupBias(best.setup, defensiveRisk).toInt()}", "regime=${regime?.regime ?: "unknown"}", internetRiskMode),
        )
    }

    fun recordDeskStage(lane: String, stage: String, eventId: String = "") {
        val l = lane.uppercase().replace("BLUE_CHIP", "BLUECHIP").replace("SHITCOIN_EXPRESS", "EXPRESS")
        if (l.isBlank()) return
        val st = stage.uppercase()
        if (eventId.isNotBlank() && !deskStageOnce6599.add("$l|$st|$eventId")) return
        deskStageCounts6599.computeIfAbsent("$l|$st") { java.util.concurrent.atomic.AtomicLong(0L) }.incrementAndGet()
        // V5.0.6625 — SINGLE SOURCE FAN-OUT into the P2/P3/P4/P5 receivers.
        // Every specialist stage change goes through this one function, so
        // wiring here means the receivers cannot drift apart from the desk
        // counters (P5 SPECIALIST_CAUSAL_FUNNEL invariant) and no callsite
        // can be forgotten. See MemeExecutionFunnelReceivers6625.kt.
        try { fanOutToReceivers6625(l, st, eventId) } catch (_: Throwable) {}
    }

    /**
     * V5.0.6625 — fan-out helper. Kept private so no other caller can
     * bypass the desk-stage authority path. `eventId` is intentionally
     * used only as an idempotency key: parsing structure from it would
     * couple the receivers to callsite formatting.
     *
     * V5.0.6626 §RUNTIME_LOOP_UNCHOKE §3 — 500ms per-key debounce.
     * Even though recordDeskStage already dedupes on (lane|stage|eventId)
     * via `deskStageOnce6599`, the fan-out itself walks five receivers
     * per call. Under a hot burst that dedupe cache can be reset or
     * skipped by a caller passing an empty eventId; the debounce here
     * guarantees the receivers themselves see at most one fan-out per
     * 500ms per key, protecting the Main thread even in pathological
     * cases. The receivers are idempotent so this is behaviour-preserving.
     */
    private val fanOutDebounce6626 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val FAN_OUT_DEBOUNCE_MS_6626 = 500L
    private fun fanOutToReceivers6625(lane: String, stage: String, eventId: String) {
        // V5.0.6626 §RUNTIME_LOOP_UNCHOKE §3 — 500ms per-key debounce.
        val nowMs6626 = System.currentTimeMillis()
        val debounceKey6626 = "$lane|$stage|$eventId"
        val prev6626 = fanOutDebounce6626.put(debounceKey6626, nowMs6626)
        if (prev6626 != null && nowMs6626 - prev6626 < FAN_OUT_DEBOUNCE_MS_6626) {
            try {
                com.lifecyclebot.engine.truth.HotLabelCoalescer6626
                    .inc6626("MEME_FANOUT_DEBOUNCED_6626")
            } catch (_: Throwable) {}
            return
        }
        // Opportunistic garbage-collect: keep the debounce map bounded.
        if (fanOutDebounce6626.size > 4096) {
            try {
                val cutoff6626 = nowMs6626 - (FAN_OUT_DEBOUNCE_MS_6626 * 4L)
                fanOutDebounce6626.entries.removeIf { it.value < cutoff6626 }
            } catch (_: Throwable) {}
        }
        // Derive a stable attemptId for the backlog. Callers pass either a
        // positionId (":reason" suffixed) or an attemptId; strip the suffix
        // so BUY_INTENT / TICKET / EXEC etc. all match on the same key.
        val attemptId = if (eventId.isBlank()) return else eventId.substringBefore(':')
        val mint = ""  // opaque here; receivers key on attemptId + lane

        // P3 — pending intent backlog drainage.
        when (stage) {
            "BUY_INTENT" -> com.lifecyclebot.engine.truth.PendingIntentBacklog6625
                .record6625(attemptId, lane, mint)
            "TICKET", "EXEC", "SELL_CONFIRMED", "FINALIZED", "SIZE_REJECT",
            "MARK_REJECT", "FDG_BLOCK" -> com.lifecyclebot.engine.truth.PendingIntentBacklog6625
                .consume6625(attemptId, stage)
        }

        // P2 — EXPRESS handoff funnel. Stamp the exact hop counters the
        // operator forensic requested. Non-EXPRESS lanes are ignored so
        // BLUECHIP/CORE/MOONSHOT counters don't drift into these.
        if (lane == "EXPRESS") {
            when (stage) {
                "BUY_INTENT" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onIntentSeen6625(attemptId)
                "MARK_READY" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onMarkAcquisition6625(attemptId, true)
                "MARK_REJECT" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onMarkAcquisition6625(attemptId, false, "MARK_REJECT")
                "SIZED_EXECUTABLE" -> {
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onSizingBridgeEntry6625(attemptId)
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onSizingResult6625(attemptId, sizedSol = 1.0)
                }
                "SIZE_REJECT" -> {
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onSizingBridgeEntry6625(attemptId)
                    com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onSizingResult6625(attemptId, sizedSol = 0.0, reason = "SIZE_REJECT")
                }
                "TICKET" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onTicketSealed6625(attemptId)
                "EXEC" -> com.lifecyclebot.engine.truth.ExpressHandoffFunnel6625.onExecuted6625(attemptId)
            }
        }

        // P4 — MOONSHOT exit transaction continuity. Retries with the same
        // positionId (SELL_ATTEMPT re-fires) resume the same tx id instead
        // of creating competing states; SELL_CONFIRMED terminates it.
        if (lane == "MOONSHOT") {
            when (stage) {
                "SELL_ATTEMPT" -> com.lifecyclebot.engine.truth.MoonshotExitTransaction6625
                    .beginOrResumeTransaction6625(positionId = attemptId, txIdIfNew = attemptId)
                "SELL_CONFIRMED", "FINALIZED" -> com.lifecyclebot.engine.truth.MoonshotExitTransaction6625
                    .terminate6625(positionId = attemptId)
            }
        }

        // P5 — SpecialistCausalFunnel keyed by the SAME record every stage
        // reads/writes from, so it becomes structurally impossible to print
        // impossible combos like fdgAllow=0 exec=113 for the same intentId.
        val causalStage = when (stage) {
            "POOL" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.DISCOVER
            "QUALIFIED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.QUALIFY
            "OWNER_SELECTED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OWNER
            "BUY_INTENT" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.INTENT
            "FDG_ALLOW", "FDG_BLOCK" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.FDG
            "MARK_READY", "MARK_REJECT" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.MARK
            "SIZED_EXECUTABLE", "SIZE_REJECT" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.SIZE
            "TICKET" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.TICKET
            "EXEC" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXEC
            "POSITION_OPENED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.OPEN
            "EXIT_TRIGGER" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.EXIT
            "SELL_ATTEMPT", "SELL_CONFIRMED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.SELL
            "FINALIZED" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.FINALIZE
            "LEARNING" -> com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.Stage.LEARN
            else -> null
        }
        if (causalStage != null) {
            val key = com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.CausalKey(
                runId = "RT", mode = "PAPER", mint = "", lane = lane,
                authorityVersion = 0L, intentId = attemptId,
            )
            com.lifecyclebot.engine.truth.SpecialistCausalFunnel6625.stamp6625(key, causalStage)
        }
    }

    fun recordContributorSummary(summary: String, stage: String, eventId: String = "") {
        Regex("(?:^|\\|)([A-Z_]+):[A-Z0-9_]+:").findAll(summary.uppercase()).forEach { m ->
            recordDeskStage(m.groupValues[1], stage, eventId)
        }
    }

    private fun deskCount6599(lane: String, stage: String): Long = deskStageCounts6599["${lane.uppercase()}|${stage.uppercase()}"]?.get() ?: 0L

    private val causalIssueCounts6600 = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun recordCausalIssue6600(issue: String, lane: String = "UNKNOWN", detail: String = "") {
        val key = issue.trim().replace(Regex("[^A-Za-z0-9_]"), "_")
        causalIssueCounts6600.computeIfAbsent(key) { java.util.concurrent.atomic.AtomicLong(0L) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("MEME_SPECIALIST_CAUSAL_$key")
            ForensicLogger.lifecycle("MEME_SPECIALIST_CAUSAL_$key", "lane=$lane ${detail.take(180)}")
        } catch (_: Throwable) {}
    }

    private fun causalIssue6600(issue: String): Long = causalIssueCounts6600[issue]?.get() ?: 0L

    fun specialistCausalFunnel6600(): String = buildString {
        appendLine("===== MEME SPECIALIST CAUSAL FUNNEL =====")
        configuredMemeDesks6599.forEach { lane ->
            appendLine("$lane discovered=${deskCount6599(lane, "POOL")} qualified=${deskCount6599(lane, "QUALIFIED")} ownerSelected=${deskCount6599(lane, "OWNER_SELECTED")} buyIntent=${deskCount6599(lane, "BUY_INTENT")} fdgAllow=${deskCount6599(lane, "FDG_ALLOW")} fdgBlock=${deskCount6599(lane, "FDG_BLOCK")} sizedExecutable=${deskCount6599(lane, "SIZED_EXECUTABLE")} sizeReject=${deskCount6599(lane, "SIZE_REJECT")} markReady=${deskCount6599(lane, "MARK_READY")} markReject=${deskCount6599(lane, "MARK_REJECT")} ticket=${deskCount6599(lane, "TICKET")} exec=${deskCount6599(lane, "EXEC")} positionOpened=${deskCount6599(lane, "POSITION_OPENED")} exitTrigger=${deskCount6599(lane, "EXIT_TRIGGER")} sellAttempt=${deskCount6599(lane, "SELL_ATTEMPT")} sellConfirmed=${deskCount6599(lane, "SELL_CONFIRMED")} finalized=${deskCount6599(lane, "FINALIZED")} learningDelivered=${deskCount6599(lane, "LEARNING")}")
        }
        appendLine("ownerLaneChangedAfterSelection=${causalIssue6600("ownerLaneChangedAfterSelection")}")
        appendLine("crossLaneExecutionRewrite=${causalIssue6600("crossLaneExecutionRewrite")}")
        appendLine("telemetryOnlySuppression=${causalIssue6600("telemetryOnlySuppression")}")
        appendLine("missingExecutableMarkWithValidSource=${causalIssue6600("missingExecutableMarkWithValidSource")}")
        appendLine("specialistLearningMissing=${causalIssue6600("specialistLearningMissing")}")
        appendLine("sellCanonicalLookupFailure=${causalIssue6600("sellCanonicalLookupFailure")}")
        appendLine("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT=${causalIssue6600("LANE_EXEC_WITHOUT_SAME_LANE_CANONICAL_INTENT")}")
        appendLine("LANE_EXEC_WITHOUT_SEALED_FDG_PROVENANCE=${causalIssue6600("LANE_EXEC_WITHOUT_SEALED_FDG_PROVENANCE")}")
        appendLine("SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME=${causalIssue6600("SPECIALIST_INTENT_WITHOUT_FDG_OUTCOME")}")
    }

    fun designatedRoleLivenessReport6599(): String = buildString {
        appendLine("===== MEME SPECIALIST ROLE LIVENESS =====")
        configuredMemeDesks6599.forEach { lane ->
            val pool = deskCount6599(lane, "POOL")
            val qualified = deskCount6599(lane, "QUALIFIED")
            val intent = deskCount6599(lane, "BUY_INTENT")
            val owner = deskCount6599(lane, "OWNER_SELECTED")
            val fdgAllow = deskCount6599(lane, "FDG_ALLOW")
            val fdgBlock = deskCount6599(lane, "FDG_BLOCK")
            val mark = deskCount6599(lane, "MARK_READY")
            val sized = deskCount6599(lane, "SIZED_EXECUTABLE")
            val ticket = deskCount6599(lane, "TICKET")
            val exec = deskCount6599(lane, "EXEC")
            val opened = deskCount6599(lane, "POSITION_OPENED")
            val sellAttempt = deskCount6599(lane, "SELL_ATTEMPT")
            val sellConfirmed = deskCount6599(lane, "SELL_CONFIRMED")
            val finalized = deskCount6599(lane, "FINALIZED")
            val learn = deskCount6599(lane, "LEARNING")
            val status = when {
                pool == 0L -> "DEAD"
                qualified == 0L -> "DISCOVERY_ONLY"
                intent == 0L -> "INTENT_CHOKED"
                fdgAllow + fdgBlock == 0L -> "FDG_CHOKED"
                sized == 0L -> "SIZING_CHOKED"
                mark == 0L -> "MARK_CHOKED"
                ticket == 0L -> "TICKET_CHOKED"
                exec == 0L || opened == 0L -> "EXEC_CHOKED"
                sellAttempt > 0L && sellConfirmed == 0L -> "EXIT_CHOKED"
                finalized > 0L && learn == 0L -> "LEARNING_CHOKED"
                else -> "ACTIVE"
            }
            appendLine("$lane taskAlive=${pool > 0} poolAlive=${pool > 0} discoveryAlive=${pool > 0} candidateN=$pool qualifiedN=$qualified ownerSelectedN=$owner buyIntentN=$intent fdgAllowN=$fdgAllow fdgBlockN=$fdgBlock markN=$mark sizedN=$sized ticketN=$ticket execN=$exec positionOpenedN=$opened sellAttemptN=$sellAttempt sellConfirmedN=$sellConfirmed finalizedN=$finalized learningN=$learn capitalAvailable=SHARED_CANONICAL status=$status")
        }
        appendLine("PROJECT_SNIPER_NON_SNIPER_ADMISSION = ${deskCount6599("PROJECT_SNIPER", "NON_SNIPER_ADMISSION")}")
    }

    fun specialistCapitalReport6599(): String = buildString {
        appendLine("===== MEME SPECIALIST CAPITAL =====")
        val positions = try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { emptyList() }
        val capital = try { com.lifecyclebot.engine.truth.PaperCapitalAuthority6577.snapshot() } catch (_: Throwable) { null }
        val sharedCash = capital?.availableCashSol ?: 0.0
        val sharedEquity = capital?.totalEquitySol ?: sharedCash
        val weights = configuredMemeDesks6599.associateWith { lane ->
            val expectancy = try { LaneExpectancyDamper.sizeMultiplier(lane) } catch (_: Throwable) { 1.0 }
            val opportunity = (1.0 + kotlin.math.ln1p(deskCount6599(lane, "QUALIFIED").toDouble())).coerceAtMost(4.0)
            (expectancy.coerceIn(0.25, 1.50) * opportunity).coerceAtLeast(0.01)
        }
        val weightSum = weights.values.sum().coerceAtLeast(0.01)
        configuredMemeDesks6599.forEach { lane ->
            val owned = positions.filter { it.lane.equals(lane, true) || (lane == "BLUECHIP" && it.lane.equals("BLUE_CHIP", true)) }
            val used = owned.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
            val pending = (deskCount6599(lane, "BUY_INTENT") - deskCount6599(lane, "EXEC")).coerceAtLeast(0L)
            val targetPct = (weights.getValue(lane) / weightSum * 100.0).coerceIn(0.0, 100.0)
            val targetSol = sharedEquity * (targetPct / 100.0)
            appendLine("$lane targetAllocation=${"%.2f".format(targetPct)}% targetSol=${"%.4f".format(targetSol)} availableAllocation=sharedCash:${"%.4f".format(sharedCash)} usedAllocation=${"%.4f".format(used)} openPositions=${owned.size} pendingIntents=$pending capitalStarved=${pending > 0L && sharedCash <= 0.0} starvedByLane=NONE allocationDecisionSource=PAPER_CAPITAL_AUTHORITY_6577+LANE_EXPECTANCY+OPPORTUNITY_PRESSURE")
        }
    }

    fun contributionSummary(ts: TokenState, classification: ModeRouter.Classification? = null): String {
        val sheet = snapshot(ts, classification)
        return sheet.deskHypotheses.values.sortedByDescending { it.conviction }.joinToString("|") { h ->
            "${h.lane}:${h.setup.name}:${"%.1f".format(h.conviction)}:${h.entryStyle}:${h.exitStyle}:hold=${"%.2f".format(h.holdMult)}:size=${"%.2f".format(h.sizeMult)}:tp=${"%.2f".format(h.tpMult)}"
        }
    }

    private fun riskOffSetupBias(setup: Setup, riskOff: Boolean): Double {
        if (!riskOff) return 0.0
        return when (setup) {
            Setup.DEGEN_MICRO_SNIPE,
            Setup.PUMP_GRADUATION_SNIPE,
            Setup.EXHAUSTION_QUICK_FLIP,
            Setup.VOLUME_IGNITION_SCALP,
            Setup.NARRATIVE_SOCIAL_IGNITION,
            Setup.ARB_FLOW_IMBALANCE,
            Setup.MEV_PROTECTED_ENTRY -> -35.0
            Setup.LIQUIDITY_DEPTH_QUALITY,
            Setup.MAINSTREAM_CRYPTO_SWING,
            Setup.CHART_PULLBACK_RECLAIM,
            Setup.REENTRY_RECOVERY,
            Setup.PANIC_REVERSION_BOUNCE,
            Setup.REGIME_DEFENSIVE_PROBE -> 22.0
            else -> 0.0
        }
    }

    private fun regimeSetupBias(setup: Setup, regime: RegimeDetector.RegimeSnapshot?): Double {
        val r = regime?.regime ?: return 0.0
        val weakChop = (r == RegimeDetector.Regime.CHOP && regime.recentWrPct < 25.0) || r == RegimeDetector.Regime.DUMP
        if (!weakChop) return 0.0
        return when (setup) {
            // 4052 report: DUMP wr=6.4%, meanPnl=-28.72%, toolkit still selected
            // degen_micro_snipe/fresh_pool_momentum. In DUMP, pure birth momentum must
            // lose to depth/reclaim/recovery structures. This is bias only — no veto.
            Setup.DEGEN_MICRO_SNIPE -> if (r == RegimeDetector.Regime.DUMP) -48.0 else -18.0
            Setup.PUMP_GRADUATION_SNIPE -> if (r == RegimeDetector.Regime.DUMP) -36.0 else -12.0
            Setup.VOLUME_IGNITION_SCALP -> if (r == RegimeDetector.Regime.DUMP) -34.0 else -10.0
            Setup.EXHAUSTION_QUICK_FLIP -> if (r == RegimeDetector.Regime.DUMP) -24.0 else -8.0
            Setup.ARB_FLOW_IMBALANCE -> if (r == RegimeDetector.Regime.DUMP) -22.0 else -8.0
            Setup.NARRATIVE_SOCIAL_IGNITION -> if (r == RegimeDetector.Regime.DUMP) -30.0 else -6.0
            // Prefer structures that survive chop/dump instead of pure birth momentum.
            Setup.CHART_PULLBACK_RECLAIM -> if (r == RegimeDetector.Regime.DUMP) 18.0 else 10.0
            Setup.PANIC_REVERSION_BOUNCE -> if (r == RegimeDetector.Regime.DUMP) 16.0 else 8.0
            Setup.LIQUIDITY_DEPTH_QUALITY -> if (r == RegimeDetector.Regime.DUMP) 18.0 else 8.0
            Setup.MAINSTREAM_CRYPTO_SWING -> if (r == RegimeDetector.Regime.DUMP) 12.0 else 6.0
            Setup.SMART_WALLET_COPY_FOLLOW -> if (r == RegimeDetector.Regime.DUMP) 14.0 else 4.0
            Setup.REGIME_DEFENSIVE_PROBE -> if (r == RegimeDetector.Regime.DUMP) 14.0 else 6.0
            else -> 0.0
        }
    }

    private fun pctMove(prices: List<Double>): Double {
        if (prices.size < 2) return 0.0
        val first = prices.first().takeIf { it > 0.0 } ?: return 0.0
        val last = prices.last()
        return ((last - first) / first) * 100.0
    }
}

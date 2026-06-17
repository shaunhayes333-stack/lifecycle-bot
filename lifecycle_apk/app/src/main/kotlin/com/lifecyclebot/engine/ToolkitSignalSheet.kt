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
        val setup = when (tt) {
            ModeRouter.TradeType.BREAKOUT_CONTINUATION, ModeRouter.TradeType.GRADUATION -> Setup.CHART_BREAKOUT
            ModeRouter.TradeType.FRESH_LAUNCH -> Setup.DEGEN_MICRO_SNIPE
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
            toolVotes = emptySet(),
            reasons = listOf("silent_refresh_pending", "type=$tt", "mint=${ts.mint.take(8)}"),
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
            lanes = setOf("MOONSHOT", "PROJECT_SNIPER"),
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
            lanes = setOf("DIP_HUNTER", "QUALITY"),
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
            lanes = setOf("EXPRESS", "SHITCOIN", "MANIPULATED"),
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
            lanes = setOf("DIP_HUNTER", "TREASURY", "QUALITY"),
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
            lanes = setOf("EXPRESS", "SHITCOIN", "TREASURY"),
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
            lanes = setOf("SHITCOIN", "PROJECT_SNIPER", "EXPRESS"),
            tools = setOf("MEV_PROTECTION", "JITO", "DEFENSIVE_PROBE", "TOXIC_GUARD"),
            reasons = listOf("mevRisk=$mevRisk", "upperWicks=$upperWicks", "sell=${sellPressure.toInt()}", "vol=${volatility.toInt()}")
        ))

        val best = candidates.maxByOrNull { it.score + InternetEdgeDesk.setupScoreBias(it.setup.name) + regimeSetupBias(it.setup, regime) } ?: Candidate(
            setup = Setup.NONE, score = 0.0, chart = "none", entry = "none", exit = "default", hold = 1.0, size = 1.0, tp = 1.0,
            lanes = emptySet(), tools = emptySet(), reasons = listOf("no_toolkit_setup")
        )
        val finalBias = InternetEdgeDesk.setupScoreBias(best.setup.name) + regimeSetupBias(best.setup, regime)
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
            laneVotes = best.lanes,
            toolVotes = best.tools,
            reasons = best.reasons + listOf("internetBias=${InternetEdgeDesk.setupScoreBias(best.setup.name).toInt()}", "regimeBias=${regimeSetupBias(best.setup, regime).toInt()}", "regime=${regime?.regime ?: "unknown"}", InternetEdgeDesk.snapshot().riskMode),
        )
    }

    private fun regimeSetupBias(setup: Setup, regime: RegimeDetector.RegimeSnapshot?): Double {
        val r = regime?.regime ?: return 0.0
        val weakChop = (r == RegimeDetector.Regime.CHOP && regime.recentWrPct < 25.0) || r == RegimeDetector.Regime.DUMP
        if (!weakChop) return 0.0
        return when (setup) {
            // Current report: CHOP wr=19.3%, toolkit selected DEGEN_MICRO_SNIPE 128/201,
            // EXPRESS 0% WR. Pivot away from fresh-pool scalps during weak chop.
            Setup.DEGEN_MICRO_SNIPE -> -18.0
            Setup.PUMP_GRADUATION_SNIPE -> -12.0
            Setup.VOLUME_IGNITION_SCALP -> -10.0
            Setup.EXHAUSTION_QUICK_FLIP -> -8.0
            Setup.ARB_FLOW_IMBALANCE -> -8.0
            // Prefer structures that survive chop instead of pure birth momentum.
            Setup.CHART_PULLBACK_RECLAIM -> 10.0
            Setup.PANIC_REVERSION_BOUNCE -> 8.0
            Setup.LIQUIDITY_DEPTH_QUALITY -> 8.0
            Setup.MAINSTREAM_CRYPTO_SWING -> 6.0
            Setup.REGIME_DEFENSIVE_PROBE -> 6.0
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

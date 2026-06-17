package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
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
 *   scanner/token state -> ToolkitSignalSheet -> AgenticStyleRouter -> existing bounded
 *   lane/tool affinity -> existing FDG/executor path.
 */
object ToolkitSignalSheet {
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

        val best = candidates.maxByOrNull { it.score } ?: Candidate(
            setup = Setup.NONE, score = 0.0, chart = "none", entry = "none", exit = "default", hold = 1.0, size = 1.0, tp = 1.0,
            lanes = emptySet(), tools = emptySet(), reasons = listOf("no_toolkit_setup")
        )
        val boundedConf = best.score.coerceIn(0.0, 100.0)
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
            reasons = best.reasons,
        )
    }

    private fun pctMove(prices: List<Double>): Double {
        if (prices.size < 2) return 0.0
        val first = prices.first().takeIf { it > 0.0 } ?: return 0.0
        val last = prices.last()
        return ((last - first) / first) * 100.0
    }
}

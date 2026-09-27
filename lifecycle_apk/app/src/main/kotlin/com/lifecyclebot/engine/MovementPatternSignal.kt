package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import kotlin.math.abs

/**
 * V5.0.3948 — MOVEMENT PATTERN SIGNAL.
 *
 * Hot-path-safe chart/movement recognition for live growth. This is deliberately
 * synchronous and cheap: it reads only TokenState candle history already in memory.
 * It does NOT call network, LLMs, FDG, scanners, or persistence.
 *
 * Purpose: make chart-pattern-to-movement recognition a core input to live
 * sizing/hold dynamics, not just an optional dashboard/router decoration.
 */
object MovementPatternSignal {
    data class Signal(
        val pattern: String,
        val confidence: Double,
        val sizeMult: Double,
        val holdMult: Double,
        val timing: String,
        val reason: String,
    )

    // V5.0.7389 — minimum real (non-synthetic, volume>0) candles before volume-ratio patterns may fire.
    private const val MIN_REAL_VOLUME_CANDLES = 8

    fun from(ts: TokenState): Signal {
        val hist = try { ts.history.toList().filter { it.priceUsd.isFinite() && it.priceUsd > 0.0 } } catch (_: Throwable) { emptyList() }
        val prices = hist.map { it.priceUsd }
        // V5.0.7389 — volume ratios only from real candles: synthetic tick-appended candles and volume<=0 candles
        // made a flat price read volIgnition=0 and always classified ACCUMULATION_COMPRESSION @72.
        val vols = hist.filter { !it.synthetic }.map { it.vol }.filter { it.isFinite() && it > 0.0 }
        val volumeLowData = vols.size < MIN_REAL_VOLUME_CANDLES
        if (prices.size < 4) {
            return Signal("LOW_DATA", 15.0, 0.78, 1.20, "probe_until_chart_forms", "candles=${prices.size}")
        }
        val last = prices.last()
        fun pct(window: List<Double>): Double {
            val first = window.firstOrNull() ?: return 0.0
            val end = window.lastOrNull() ?: return 0.0
            return if (first > 0.0) ((end - first) / first) * 100.0 else 0.0
        }
        val move3 = pct(prices.takeLast(4))
        val move8 = pct(prices.takeLast(9))
        val move15 = pct(prices.takeLast(16))
        val hi12 = prices.takeLast(12).maxOrNull() ?: last
        val lo12 = prices.takeLast(12).minOrNull() ?: last
        val nearHigh = hi12 > 0.0 && last >= hi12 * 0.92
        val pullbackFromHigh = if (hi12 > 0.0) ((hi12 - last) / hi12) * 100.0 else 0.0
        val reboundFromLow = if (lo12 > 0.0) ((last - lo12) / lo12) * 100.0 else 0.0
        val higherLows = prices.takeLast(5).zipWithNext { a, b -> b >= a * 0.985 }.count { it }
        val lowerHighs = prices.takeLast(5).zipWithNext { a, b -> b <= a * 1.012 }.count { it }
        // V5.0.7389 — without enough real-volume candles the volume ratio is unknown; use neutral 1.0 and
        // block the volume-dependent patterns below (BREAKOUT/ACCUMULATION/VOLUME_IGNITION).
        val volIgnition = if (volumeLowData) 1.0 else {
            val recentVol = vols.takeLast(3).average().takeIf { it.isFinite() } ?: 0.0
            val priorVol = vols.dropLast(3).takeLast(6).average().takeIf { it.isFinite() && it > 0.0 } ?: 1.0
            recentVol / priorVol.coerceAtLeast(1.0)
        }
        val upperWicks = hist.takeLast(4).count { it.hasUpperWick }
        val wickBought = hist.takeLast(4).count { c -> c.lowUsd > 0.0 && c.priceUsd > c.lowUsd * 1.02 }
        val buyPressure = ts.lastBuyPressurePct.takeIf { it.isFinite() } ?: 50.0
        val sellPressure = ts.lastSellPressurePct.takeIf { it.isFinite() } ?: 50.0

        return when {
            upperWicks >= 2 && move3 > 18.0 && sellPressure >= 55.0 -> Signal(
                "EXHAUSTION_CHASE", 82.0, 0.52, 0.65, "late_entry_scalp_only",
                "upperWicks=$upperWicks move3=${move3.toInt()} sell=${sellPressure.toInt()}"
            )
            !volumeLowData && move8 > 18.0 && higherLows >= 3 && nearHigh && volIgnition >= 1.10 -> Signal(
                "BREAKOUT_CONTINUATION", 88.0, 1.28, 1.95, "enter_strength_or_retest_hold_runner",
                "move8=${move8.toInt()} higherLows=$higherLows nearHigh=$nearHigh vol=${"%.1f".format(volIgnition)}x"
            )
            pullbackFromHigh in 8.0..38.0 && wickBought >= 2 && reboundFromLow >= 3.0 -> Signal(
                "PULLBACK_RECLAIM", 80.0, 1.15, 1.55, "enter_reclaim_not_freefall",
                "pullback=${pullbackFromHigh.toInt()} wickBought=$wickBought rebound=${reboundFromLow.toInt()}"
            )
            !volumeLowData && abs(move8) <= 8.0 && higherLows >= 2 && lowerHighs >= 2 && volIgnition < 0.85 -> Signal(
                "ACCUMULATION_COMPRESSION", 72.0, 1.08, 1.85, "wait_for_expansion_or_probe",
                "range=${((hi12-lo12)/lo12.coerceAtLeast(1e-12)*100.0).toInt()} higherLows=$higherLows lowerHighs=$lowerHighs vol=${"%.1f".format(volIgnition)}x"
            )
            move15 < -22.0 && buyPressure < 48.0 && wickBought == 0 -> Signal(
                "FREEFALL_NO_RECLAIM", 78.0, 0.45, 0.85, "do_not_size_up_until_reclaim",
                "move15=${move15.toInt()} bp=${buyPressure.toInt()} wickBought=$wickBought"
            )
            !volumeLowData && move3 > 12.0 && volIgnition >= 1.35 && buyPressure >= 55.0 -> Signal(
                "VOLUME_IGNITION", 74.0, 1.10, 1.20, "fast_confirm_then_runner_tail",
                "move3=${move3.toInt()} vol=${"%.1f".format(volIgnition)}x bp=${buyPressure.toInt()}"
            )
            // V5.0.7389 — fewer than MIN_REAL_VOLUME_CANDLES real-volume candles and no price-only pattern:
            // low-data, low confidence, neutral sizing (same size/hold as NEUTRAL_STRUCTURE, so no size cut).
            volumeLowData -> Signal(
                "LOW_DATA", 20.0, 0.92, 1.15, "neutral_probe",
                "realVolCandles=${vols.size} move3=${move3.toInt()} move8=${move8.toInt()} higherLows=$higherLows"
            )
            else -> Signal(
                "NEUTRAL_STRUCTURE", 45.0, 0.92, 1.15, "neutral_probe",
                "move3=${move3.toInt()} move8=${move8.toInt()} higherLows=$higherLows vol=${"%.1f".format(volIgnition)}x"
            )
        }
    }
}

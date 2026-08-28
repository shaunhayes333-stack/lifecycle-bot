package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6574 — MEME PROFITABILITY RESTORATION.
 *
 * Operator directive (verbatim):
 *   "fix the fucking meme trader. previous winrate was above 70% and trade
 *    volume was over 500 a day!!!"
 *
 * V5.9.1352 flattened the raw-score curve from 1.50×/1.30×/1.15× down to
 * 1.20×/1.12×/1.05× based on ONE bad calibration window claiming the scorer
 * was "anti-predictive". The operator's own long-run experience (70% WR /
 * 500 trades/day) proved the opposite: high conviction bets HAVE won.
 * Flattening the curve made winners take identical dust bets to losers —
 * pure fee bleed with zero compound edge.
 *
 * These asserts pin the source-level knob positions so a future troubleshoot
 * pass doesn't silently re-flatten the meme lane:
 *
 *   1. rawScoreMult ≥80 stays at 1.50 (the highest-conviction bucket).
 *   2. qualityMult "Unknown" is 1.00, not 0.80 — every meme launch starts
 *      life labeled Unknown; punishing them for being fresh reproduces the
 *      exact dust-sizing pattern that starved the meme lane pre-6572.
 *   3. Paper perfMult floor never dips below 0.90 on WR<40% (was 0.70) —
 *      the paper bot must be allowed to learn its way OUT of a bad window.
 *   4. Paper drawdownMult floor never dips below 0.75 (was 0.50) — same
 *      reason: drawdown must not compound with all the other multipliers
 *      to guarantee dust-size entries during recovery.
 */
class MemeProfitabilityRestore6574Test {

    private val sizerSrc = File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()

    @Test
    fun raw_score_conviction_curve_is_restored() {
        assertTrue(
            "rawScoreMult ≥80 must be 1.50 (V5.9.1352 flatten reverted)",
            sizerSrc.contains("entryScore >= 80 -> 1.50")
        )
        assertTrue(
            "rawScoreMult ≥65 must be 1.30 (V5.9.1352 flatten reverted)",
            sizerSrc.contains("entryScore >= 65 -> 1.30")
        )
        assertTrue(
            "rawScoreMult ≥50 must be 1.15 (V5.9.1352 flatten reverted)",
            sizerSrc.contains("entryScore >= 50 -> 1.15")
        )
    }

    @Test
    fun unknown_quality_is_neutral_not_penalty() {
        assertTrue(
            "Unknown-quality meme launches must not be sized down (else 0.80 kills every fresh mint)",
            sizerSrc.contains("else -> 1.00   // V5.0.6574: Unknown = neutral (was 0.80)")
        )
    }

    @Test
    fun paper_perf_multiplier_never_death_spirals() {
        assertTrue(
            "Paper perfMult on WR<40% must be ≥0.90 (was 0.70 — compounded with other mults it dust-sized every meme entry)",
            sizerSrc.contains("fluidWinRate < 40 && fluidTrades >= 10  -> 0.90")
        )
        assertTrue(
            "Paper perfMult on WR<50% must be ≥0.95",
            sizerSrc.contains("fluidWinRate < 50 && fluidTrades >= 10  -> 0.95")
        )
    }

    @Test
    fun paper_drawdown_multiplier_never_death_spirals() {
        assertTrue(
            "Paper drawdownMult on <50% recovery must be ≥0.75 (was 0.50 — kills meme learning volume during recovery)",
            sizerSrc.contains("fluidRecovery < 0.50 -> 0.75")
        )
    }
}

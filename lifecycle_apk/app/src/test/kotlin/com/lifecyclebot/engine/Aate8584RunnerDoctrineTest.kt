package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8584 §RUNNER_DOCTRINE — owner: "the pipeline self adjustments are too aggressive... the goal is 5x and 10x, not 5 %".
 * A meme winner sells on the 2x / 5x / 10x ladder or when it falls back to the doctrine line; nothing else takes it early.
 */
class Aate8584RunnerDoctrineTest {
    @Test fun the_ladder_banks_at_2x_5x_and_10x() {
        assertEquals(listOf(100.0 to 0.25, 400.0 to 0.18, 900.0 to 0.67), RunnerGrab7967.TIERS_7967)
        for (lane in RunnerDoctrine8584.MEME_LANES_8584) assertTrue(lane, RunnerDoctrine8584.rides8584(lane.lowercase()))
        for (lane in listOf("BLUECHIP", "QUALITY", "TREASURY", "CASHGEN", "", null)) assertFalse("$lane", RunnerDoctrine8584.rides8584(lane))
        assertTrue(RunnerDoctrine8584.memeLane8584("MOONSHOT"))
    }

    @Test fun tenXRunnerCanBankFiveHundredPercentAfterTierSlippageAndRetainMoonbag() {
        // Slow path: fills all three rungs at their thresholds, peaks at 10x, and trails to 5.5x.
        // SpikeCapture's pure replay deducts 5 % assumed fill slippage at each rung.
        val tiers = RunnerGrab7967.TIERS_7967
        val trailingGrossPct = (RunnerDoctrine8584.line8584(10.0) - 1.0) * 100.0
        assertEquals(450.0, trailingGrossPct, 1e-9)
        assertEquals(514.695, SpikeCapture7943.realisableGrossPct(900.0, trailingGrossPct, tiers), 1e-6)
        assertTrue("a real 10x winner can now bank +500% across the entire position in the modeled path",
            SpikeCapture7943.realisableGrossPct(900.0, trailingGrossPct, tiers) >= 500.0)

        // Only completed tier proceeds count as recovered cash; do not confuse unrealised MFE with realised SOL.
        val first = tiers[0].second
        val second = (1.0 - first) * tiers[1].second
        val capitalReturnedAt5x = (first * 2.0 + second * 5.0) * 0.95
        assertTrue("the first two tier fills return the original stake despite 5% slippage", capitalReturnedAt5x >= 1.0)
        assertEquals(0.615, 1.0 - first - second, 1e-9)
        val originalRemainderAt10x = (1.0 - first - second) * (1.0 - tiers[2].second)
        assertEquals(0.20295, originalRemainderAt10x, 1e-9)
        assertTrue("live share-budget's 20% reserve must not silently clamp the +900% rung", originalRemainderAt10x >= RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136)
        assertEquals(tiers[2].second, RunnerExitContract8136.budgetedFraction8136(
            tiers[2].second, 1.0 - first - second, RunnerExitContract8136.RUNNER_RESIDUAL_SHARE_8136,
            "SPIKE_CAPTURE_7943_T3_900PCT"), 1e-9)
        // A 5% additional penalty on the closing trail still models a whole-position 500%+ capture.
        val afterTrailingSlip = SpikeCapture7943.realisableGrossPct(900.0, trailingGrossPct, tiers) -
            originalRemainderAt10x * (1.0 + trailingGrossPct / 100.0) * 0.05 * 100.0
        assertTrue(afterTrailingSlip >= 500.0)

        // Merely reaching 2x is not a promise of a 500% trade; stop/fill/safety exits still apply.
        assertTrue(SpikeCapture7943.realisableGrossPct(100.0, -35.0, tiers) < 0.0)
    }

    @Test fun the_line_keeps_half_the_gain_then_trails_45_percent_from_2x() {
        assertEquals(1.05, RunnerDoctrine8584.line8584(1.0), 1e-9)
        assertEquals(1.25, RunnerDoctrine8584.line8584(1.5), 1e-9)
        assertEquals(1.5, RunnerDoctrine8584.line8584(2.0), 1e-9)
        assertEquals(2.75, RunnerDoctrine8584.line8584(5.0), 1e-9)
        assertEquals(5.5, RunnerDoctrine8584.line8584(10.0), 1e-9)
    }

    @Test fun profit_exits_are_held_above_the_line_and_released_below_it() {
        RunnerDoctrine8584.enabled8584 = true
        // the 485eacbd MOONSHOT winner: sold by partial_25pct / partial_43pct / TAIL_SET_ASIDE / RAPID_DRAWDOWN_FROM_PEAK
        for (r in listOf("partial_25pct", "partial_43pct", "TAIL_SET_ASIDE", "RAPID_DRAWDOWN_FROM_PEAK", "TAKE_PROFIT",
                "MOONBAG_BANK_7322_peak60_now55", "RAPID_INSTANT_PROFIT_CAPTURE_4301", "TRAILING_STOP"))
            assertTrue(r, RunnerDoctrine8584.holds8584("MOONSHOT", r, 55.0, 60.0))
        assertFalse("below half the peak gain it sells (no stop read; 8719 widens it by the stop)", RunnerDoctrine8584.holds8584("MOONSHOT", "TRAILING_STOP", 25.0, 60.0, Double.NaN))
        assertTrue("a 6x runner rides", RunnerDoctrine8584.holds8584("SHITCOIN", "RAPID_DRAWDOWN_FROM_PEAK", 400.0, 500.0))
        assertFalse("a 45 % give-back from 6x sells", RunnerDoctrine8584.holds8584("SHITCOIN", "RAPID_DRAWDOWN_FROM_PEAK", 220.0, 500.0))
        assertFalse("nothing at or below +5 % is held", RunnerDoctrine8584.holds8584("SHITCOIN", "TAKE_PROFIT", 5.0, 5.0))
    }

    @Test fun stops_safety_and_the_ladder_always_pass() {
        RunnerDoctrine8584.enabled8584 = true
        for (r in listOf("STRICT_SL_-15", "STOP_LOSS", "HARD_FLOOR", "RUG_LIQUIDITY_PULLED", "KILL_SWITCH_HOLD", "MANUAL_SELL",
                "EMERGENCY_EXIT", "TOKEN_FROZEN", "HONEYPOT_DETECTED", "SPIKE_CAPTURE_7943_T1_120PCT", "", null))
            assertFalse("$r", RunnerDoctrine8584.holds8584("MOONSHOT", r, 80.0, 90.0))
        assertFalse("a flat coin's clock passes", RunnerDoctrine8584.holds8584("SHITCOIN", "MAX_HOLD_TIME", 20.0, 30.0))
        assertTrue("a running coin's clock is held", RunnerDoctrine8584.holds8584("SHITCOIN", "MAX_HOLD_TIME", 70.0, 80.0))
        assertFalse("other lanes keep their exits", RunnerDoctrine8584.holds8584("BLUECHIP", "TAKE_PROFIT", 55.0, 60.0))
        assertFalse(RunnerDoctrine8584.holds8584("MOONSHOT", "TAKE_PROFIT", Double.NaN, 60.0))
        RunnerDoctrine8584.enabled8584 = false
        try { assertFalse(RunnerDoctrine8584.holds8584("MOONSHOT", "TAKE_PROFIT", 55.0, 60.0)) } finally { RunnerDoctrine8584.enabled8584 = true }
    }

    @Test fun the_tuner_never_shrinks_meme_exits_and_the_paper_book_trails_the_line() {
        assertEquals(1.0, RunnerDoctrine8584.tunerFloor8584("MOONSHOT", 0.91), 0.0)
        assertEquals(1.3, RunnerDoctrine8584.tunerFloor8584("MOONSHOT", 1.3), 0.0)
        assertEquals(0.7, RunnerDoctrine8584.tunerFloor8584("BLUECHIP", 0.7), 0.0)
        assertEquals(-15.0, RunnerDoctrine8584.paperStopLevel8584(15.0, 5.0), 1e-9)
        assertEquals(25.0, RunnerDoctrine8584.paperStopLevel8584(15.0, 50.0), 1e-9)
        assertEquals(175.0, RunnerDoctrine8584.paperStopLevel8584(15.0, 400.0), 1e-9)
    }

    @Test fun both_sell_doors_the_paper_book_the_tuner_and_the_brief_carry_it() {
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val gate = ex.substringAfter("private fun moonbagGate7322(").substringBefore("\n    }\n")
        assertTrue(gate.indexOf("Tier1Deference8141.tier1(requestReason)") < gate.indexOf("runnerDoctrineHolds8584(ts, requestReason, \"FULL\")"))
        assertTrue(gate.indexOf("runnerDoctrineHolds8584(ts, requestReason, \"FULL\")") < gate.indexOf("LaneExitContract8468.acts8468("))
        val partial = ex.substringAfter("private fun requestPartialSellWithDelivery8527(").substringBefore("rehydratePositionFromSubTraders(ts)")
        assertTrue(partial.indexOf("runnerDoctrineHolds8584(ts, reason, \"PARTIAL\")") in 0 until partial.indexOf("NetEdge8030.exitRefusal8030("))
        assertTrue(ex.substringAfter("private fun moonbagWouldAct7349(").take(200).contains("runnerDoctrineHolds8584(ts, reason, null) ||"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/RunnerGrab7967.kt").readText()
            .contains("planTiers8584(ts).let { if (RunnerDoctrine8584.rides8584(RunnerDoctrine8584.entryLane8630(ts))) TIERS_7967 else it }"))
        // superseded by V5.0.8630 — the ladder keys on the folded entry lane: was rides8584(ts.position.tradingMode).
        val paper = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemePaperTrader8544.kt").readText()
        assertTrue(paper.contains("RunnerDoctrine8584.paperStopLevel8584(stopPct, p.peak)"))
        val tuner = File("src/main/kotlin/com/lifecyclebot/engine/learning/LaneExitTuner.kt").readText()
        assertEquals(2, Regex("RunnerDoctrine8584\\.tunerFloor8584\\(lane,").findAll(tuner).count())
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("RunnerDoctrine8584.statusLine8584()"))
        RunnerDoctrine8584.noteHeld8584("FULL", "MOONSHOT", "TAKE_PROFIT")
        assertTrue(RunnerDoctrine8584.heldCount8584() >= 1L)
        assertTrue(RunnerDoctrine8584.statusLine8584().startsWith("runnerDoctrine8584[on="))
    }

    @Test fun rapidLegacyPeakProfitRulesCannotOutraceTheDoctrineOnMemeRunners() {
        val rapid = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val legacyPeak = rapid.substringAfter("val doctrineOwnsProfit8642 =").substringBefore("val explicitPeakLockFloor4301")
        assertTrue("legacy full cuts and trails are not a second meme profit owner", legacyPeak.contains("!doctrineOwnsProfit8642"))
        assertTrue("actual distribution safety remains live", legacyPeak.contains("Verdict.DISTRIBUTION_EXIT"))
        assertTrue(rapid.contains("doctrineOwnsProfit8642 -> Double.NEGATIVE_INFINITY"))
        assertTrue(rapid.contains("val giveBackTrigger = !RunnerDoctrine8584.profitTakingOwned8630("))
        assertTrue(rapid.contains("if (doctrineTrail8630(ts, pnlPct, peakGainPct, wallet, effectiveBalance)) return true"))
    }
}

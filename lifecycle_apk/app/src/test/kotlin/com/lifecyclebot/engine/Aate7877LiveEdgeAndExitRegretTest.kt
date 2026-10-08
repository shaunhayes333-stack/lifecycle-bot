package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExitRegret7752
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.TradePlan7739
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7877LiveEdgeAndExitRegretTest {
    private fun cell(n: Int, mean: Double, se: Double, n240: Int = 0, mean240: Double = 0.0, run: Double = 0.05) =
        CellStat(key = "K", n60 = n, meanNet60Pct = mean, winRate60 = 0.2, runnerRate60 = run,
            stderr60Pct = se, lost = 0, n240 = n240, meanNet240Pct = mean240)

    @Test fun liveTradesOnlyWherePredictedEdgeClearsTheMargin() {
        // PROJECT_SNIPER early cell from 5.0.7876: n=68 net +25.1%.
        assertTrue(LiveEdgeGate7877.judge(cell(68, 25.1, 6.0), laneProven = false).allow)
        // CASHGEN-shaped: n=54 net -3.0% — refused even if the lane were proven.
        assertFalse(LiveEdgeGate7877.judge(cell(54, -3.0, 1.0), laneProven = true).allow)
        // Positive but inside slippage margin: refused.
        assertFalse(LiveEdgeGate7877.judge(cell(200, 2.4, 1.0), laneProven = false).allow)
        // Thin cell: lane evidence decides.
        assertFalse(LiveEdgeGate7877.judge(cell(5, 40.0, 10.0), laneProven = false).allow)
        assertTrue(LiveEdgeGate7877.judge(cell(5, 40.0, 10.0), laneProven = true).allow)
        assertFalse(LiveEdgeGate7877.judge(null, laneProven = false).allow)
        // Launch cell paying at four hours.
        assertTrue(LiveEdgeGate7877.judge(cell(40, -1.0, 2.0, n240 = 35, mean240 = 12.0), laneProven = false).allow)
    }

    @Test fun stopsWidenWhenTheyCutRunnersAndTightenWhenTheySaveMoney() {
        // 5.0.7876 HARD_STOP: n=48 realised -5.2 hold +21.2 after +24.9 holdBeat 21/48.
        val cutRunners = ExitRegret7752.Read7877(48, -5.2, 21.2, 24.9, 21.0 / 48)
        val m = ExitRegret7752.stopMult7877(cutRunners)
        assertTrue("$m", m > 1.4 && m <= ExitRegret7752.STOP_MULT_MAX_7877)
        // RUG_DRAIN-shaped: price kept falling after the stop.
        assertEquals(ExitRegret7752.STOP_MULT_MIN_7877, ExitRegret7752.stopMult7877(ExitRegret7752.Read7877(20, -12.0, -30.0, -20.0, 0.1)), 1e-9)
        // Thin evidence changes nothing.
        assertEquals(1.0, ExitRegret7752.stopMult7877(ExitRegret7752.Read7877(5, -5.0, 30.0, 30.0, 0.8)), 1e-9)
        assertEquals(1.0, ExitRegret7752.stopMult7877(null), 1e-9)
    }

    @Test fun underwaterStopWaitsOnlyWhenHoldingProvablyPaid() {
        assertTrue(ExitRegret7752.holdingPaid7877(ExitRegret7752.Read7877(51, -9.2, 16.2, 24.2, 25.0 / 51)))
        assertFalse(ExitRegret7752.holdingPaid7877(ExitRegret7752.Read7877(2, -5.5, -4.2, 1.4, 0.5)))
        // exitFor honours the horizon it is given.
        val at50min = 50L * 60_000L
        assertTrue(TradePlan7739.exitFor(null, -3.0, 2.0, at50min, false, 3.0)?.reason?.startsWith("UNDERWATER_TIME_STOP_7739") == true)
        assertNull(TradePlan7739.exitFor(null, -3.0, 2.0, at50min, false, 3.0, underwaterMs = 120L * 60_000L))
    }

    @Test fun runnerLanesTradeTheCohortThatCarriesTheTail() {
        val moonshotOwnCell = cell(65, -14.6, 3.0)            // MOONSHOT picks: runner-rate 2%
        val launchRefused = cell(1043, 3.1, 1.2)              // PLANWAIT_LAUNCH_REFUSED: runner-rate 15%
        val sellDominant = cell(222, -24.3, 4.0)
        // Own cell proven clearly negative refuses, even with a paying cohort.
        assertFalse(LiveEdgeGate7877.judgeRunner(moonshotOwnCell, listOf(launchRefused), laneProven = false).allow)
        // Own cell thin or merely flat: the paying cohort carries it.
        assertTrue(LiveEdgeGate7877.judgeRunner(cell(10, -20.0, 9.0), listOf(launchRefused), laneProven = false).allow)
        assertTrue(LiveEdgeGate7877.judgeRunner(cell(80, -1.0, 2.0), listOf(launchRefused), laneProven = false).allow)
        // A token whose tape puts it in a losing cohort stays paper.
        assertFalse(LiveEdgeGate7877.judgeRunner(null, listOf(sellDominant), laneProven = false).allow)
        // Same cohort fails the non-runner +2% slippage margin.
        assertFalse(LiveEdgeGate7877.judge(launchRefused, laneProven = false).allow)
        assertTrue(LiveEdgeGate7877.runnerCohortAllows(launchRefused))
        assertFalse(LiveEdgeGate7877.runnerCohortAllows(sellDominant))
    }

    @Test fun fatTailCohortTradesOnItsMeanWhenTheRunnersAreMeasured() {
        // 5.0.7878 on device: LAUNCH_REFUSED n=1043 mean +3.1% runner-rate 15% was overruled 0 times —
        // the runners that make the mean also make the standard error wider than the mean.
        val launchRefusedFatTail = cell(1043, 3.1, 4.5, run = 0.15)
        assertTrue(LiveEdgeGate7877.runnerCohortAllows(launchRefusedFatTail))
        assertTrue(LiveEdgeGate7877.judgeRunner(null, listOf(launchRefusedFatTail), laneProven = false).allow)
        // Positive mean without a measured tail, or too few labels: not enough.
        assertFalse(LiveEdgeGate7877.runnerCohortAllows(cell(1043, 3.1, 4.5, run = 0.04)))
        assertFalse(LiveEdgeGate7877.runnerCohortAllows(cell(60, 3.1, 4.5, run = 0.20)))
        // A tail never rescues a negative mean (SELL_DOMINANT_TAPE: run 9%, net -24.3%).
        assertFalse(LiveEdgeGate7877.runnerCohortAllows(cell(222, -24.3, 4.0, run = 0.09)))
        // Non-runner lanes are unaffected.
        assertFalse(LiveEdgeGate7877.judge(launchRefusedFatTail, laneProven = false).allow)
    }
}

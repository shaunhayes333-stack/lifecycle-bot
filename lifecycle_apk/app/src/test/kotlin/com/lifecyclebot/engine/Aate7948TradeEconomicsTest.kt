package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.ExitRegret7752
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.CellStat
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7948 — trade economics: selection by the larger proven sample, measured stop room, compounding size. */
class Aate7948TradeEconomicsTest {
    private fun cohort(n: Int, mean: Double, se: Double, run: Double = 0.05, lost: Int = 0) =
        CellStat(key = "LANE|PLANWAIT_LAUNCH_NEGATIVE", n60 = n, meanNet60Pct = mean, winRate60 = 0.13, runnerRate60 = run,
            stderr60Pct = se, lost = lost)

    private fun stat(vararg ys: Double, runner: Boolean = false): CortexLedger7885.Stat =
        CortexLedger7885.Stat().also { s -> ys.forEach { s.add(it, runner) } }

    @Test fun aCohortProvenOnItsOwnLabelsOutweighsASmallerContradictingSample() {
        // 5.0.7947: PLANWAIT_LAUNCH_NEGATIVE n=2,164 +13.1% (13% winners).
        val launchNegative = cohort(2164, 13.1, 3.0)
        assertTrue(LiveEdgeGate7877.cohortProvenPositive7948(launchNegative))
        // MOONSHOT NO_TRIGGER n40 -14.1%, a lane mean on 251 labels: both smaller, both outweighed.
        assertTrue(LiveEdgeGate7877.cohortOverrulesSmaller7948(launchNegative, 40))
        assertTrue(LiveEdgeGate7877.cohortOverrulesSmaller7948(launchNegative, 251))
        // A refusal measured on MORE labels than the cohort stands.
        assertFalse(LiveEdgeGate7877.cohortOverrulesSmaller7948(launchNegative, 5000))
        // Unproven cohorts outweigh nothing: thin, negative, noisy without a tail, mostly unresolved.
        assertFalse(LiveEdgeGate7877.cohortOverrulesSmaller7948(cohort(80, 20.0, 2.0), 0))
        assertFalse(LiveEdgeGate7877.cohortOverrulesSmaller7948(cohort(500, -1.0, 0.5), 0))
        assertFalse(LiveEdgeGate7877.cohortProvenPositive7948(cohort(500, 1.0, 2.0, run = 0.04)))
        assertFalse(LiveEdgeGate7877.cohortProvenPositive7948(cohort(200, 9.0, 1.0, lost = 200)))
        assertFalse(LiveEdgeGate7877.cohortOverrulesSmaller7948(null, 0))
        // A runner tail alone is not proof when the standard error swamps the mean.
        assertFalse(LiveEdgeGate7877.cohortProvenPositive7948(cohort(300, 3.0, 5.0, run = 0.15)))
    }

    @Test fun aMeasuredSetupExpectedToLoseIsRefusedAndAProvenOneIsRecognised() {
        // SHITCOIN LAUNCH_CONTINUATION n6 -11.0%, PROBE prior 0: shrunk (-66 + 0) / 26 = -2.5%.
        val cont = stat(-11.0, -11.0, -11.0, -11.0, -11.0, -11.0)
        assertTrue(LanePlaybook7907.expectedNegative7948(cont, -66.0 / 26.0, runnerLane = false))
        // PRE_IGNITION_BASE n6 +24.1%: expected positive, never refused.
        assertFalse(LanePlaybook7907.expectedNegative7948(stat(24.1, 24.1, 24.1, 24.1, 24.1, 24.1), 5.9, runnerLane = false))
        // Under five labels the prior still rules; a runner lane with a 10% tail keeps its shots.
        assertFalse(LanePlaybook7907.expectedNegative7948(stat(-20.0, -20.0), -1.8, runnerLane = false))
        assertFalse(LanePlaybook7907.expectedNegative7948(stat(-11.0, -11.0, -11.0, -11.0, -11.0, -11.0, runner = true), -2.5, runnerLane = true))
        // Proven positive needs 30 labels and mean - SE > 0.
        assertFalse(LanePlaybook7907.setupProvenPositive7948(stat(24.1, 24.1, 24.1, 24.1, 24.1, 24.1)))
        assertTrue(LanePlaybook7907.setupProvenPositive7948(stat(*DoubleArray(40) { if (it % 2 == 0) 20.0 else 4.0 })))
        assertFalse(LanePlaybook7907.setupProvenPositive7948(stat(*DoubleArray(40) { if (it % 2 == 0) 20.0 else -22.0 })))
        assertFalse(LanePlaybook7907.setupProvenPositive7948(null))
    }

    @Test fun ordinaryStopsGetMeasuredRoomAndCatastrophicExitsDoNot() {
        // MEME stops n19: realised -2.4% vs hold +1.5% (price +4.0% after), hold beat in half.
        val meme = ExitRegret7752.Read7877(19, -2.4, 1.5, 4.0, 0.5)
        assertEquals(1.0, ExitRegret7752.stopMult7877(meme), 1e-9)          // 7877 alone never moved
        assertEquals(1.39, ExitRegret7752.stopMult7948(meme), 1e-9)
        // HARD_STOP n27: -2.2% vs -0.1%.
        assertEquals(1.21, ExitRegret7752.stopMult7948(ExitRegret7752.Read7877(27, -2.2, -0.1, 2.1, 0.45)), 1e-9)
        // Bounded by the 7877 maximum; the 7877 widening is kept when it is larger.
        assertEquals(ExitRegret7752.STOP_MULT_MAX_7877, ExitRegret7752.stopMult7948(ExitRegret7752.Read7877(30, -9.0, 12.0, 20.0, 0.6)), 1e-9)
        // RUG_DRAIN-shaped (hold worse) and thin samples: no room.
        assertEquals(ExitRegret7752.STOP_MULT_MIN_7877, ExitRegret7752.stopMult7948(ExitRegret7752.Read7877(20, -29.7, -44.8, -20.0, 0.1)), 1e-9)
        assertEquals(1.0, ExitRegret7752.stopMult7948(ExitRegret7752.Read7877(3, -2.4, 1.5, 4.0, 0.6)), 1e-9)
        assertEquals(1.0, ExitRegret7752.stopMult7948(null), 1e-9)
        // Catastrophic families are never ordinary stops.
        assertTrue(ExitRegret7752.isCatastrophicFamily7948("RUG_DRAIN"))
        assertTrue(ExitRegret7752.isCatastrophicFamily7948("RAPID_CATASTROPHE_STOP"))
        assertTrue(ExitRegret7752.isCatastrophicFamily7948("HARD_FLOOR"))
        assertFalse(ExitRegret7752.isCatastrophicFamily7948("HARD_STOP"))
        assertFalse(ExitRegret7752.isCatastrophicFamily7948("STRICT_SL"))
    }

    @Test fun provenEvidenceSizesWithEquityAndNeverBelowTheRequest() {
        // Quarter-Kelly on a proven record scales with the wallet: double the equity, double the stake.
        val v = Cortex7885.cohortVariance7948(2.0, 100)          // sd 20% -> variance 400
        assertEquals(400.0, v, 1e-9)
        val small = Cortex7885.kellyStakeSol(6.0, v, 0.1)
        val large = Cortex7885.kellyStakeSol(6.0, v, 0.2)
        assertEquals(2.0 * small, large, 1e-12)
        // Multiplier: never below 1 (the request already cleared the route minimum), at most 2.
        assertEquals(1.0, Cortex7885.stakeMult7948(0.0, 0.05), 1e-12)
        assertEquals(1.0, Cortex7885.stakeMult7948(0.01, 0.05), 1e-12)
        assertEquals(1.5, Cortex7885.stakeMult7948(0.075, 0.05), 1e-12)
        assertEquals(2.0, Cortex7885.stakeMult7948(1.0, 0.05), 1e-12)
        assertEquals(1.0, Cortex7885.stakeMult7948(Double.NaN, 0.05), 1e-12)
        assertEquals(0.0, Cortex7885.cohortVariance7948(Double.NaN, 100), 1e-12)
    }
}

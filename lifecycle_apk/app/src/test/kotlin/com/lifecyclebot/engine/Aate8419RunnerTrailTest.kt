package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8419 — item 1: the runner trail is 35 % under the peak PRICE, owned by one authority. Reproduces the 22.75-point
 * stack (35-point table x 0.65 patience) and proves every layer that read it now reads RunnerTrail8419.
 */
class Aate8419RunnerTrailTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val eps = 1e-9

    @Test fun theOldHoldLayerArithmeticReproducesTheReportedTwentyTwoPointSevenFive() {
        // HoldingLogicLayer :214 before 8419: LaneHolds8418.trailFor8418(EXPRESS, 35) x patience floored at 0.65.
        val tablePoints = LaneHolds8418.trailFor8418("EXPRESS", "EXPRESS", 10.0)
        assertEquals(35.0, tablePoints, eps)
        val patienceFloor = 0.65
        assertEquals(22.75, tablePoints * patienceFloor, eps)
        // :363 then subtracted those POINTS from the peak GAIN: a +100 % peak was "exit now" under +77.25 %.
        assertEquals(77.25, 100.0 - tablePoints * patienceFloor, eps)
    }

    @Test fun theTrailIsSixtyFivePercentOfThePeakPrice() {
        assertEquals(30.0, RunnerTrail8419.trailLevelPct8419(100.0), eps)
        assertEquals(160.0, RunnerTrail8419.trailLevelPct8419(300.0), eps)
        assertEquals(290.0, RunnerTrail8419.trailLevelPct8419(500.0), eps)
        assertEquals(70.0, RunnerTrail8419.pointsUnderPeak8419(100.0), eps)   // not 22.75, not 35
        assertTrue(RunnerTrail8419.trailLevelPct8419(Double.NaN).isNaN())
        assertTrue(RunnerTrail8419.trailLevelPct8419(0.0).isNaN())
    }

    @Test fun theHoldLayerPointsAreTheTrailsOwnOnARunnerLaneAndTheTableTimesPatienceElsewhere() {
        // Runner lane: never 22.75 whatever the patience says.
        assertEquals(70.0, RunnerTrail8419.holdLayerTrailPoints8419("EXPRESS", "EXPRESS", 35.0, 0.65, 100.0), eps)
        assertEquals(210.0, RunnerTrail8419.holdLayerTrailPoints8419("STANDARD", "SHITCOIN", 8.0, 0.65, 500.0), eps)
        assertEquals(35.0, RunnerTrail8419.holdLayerTrailPoints8419("EXPRESS", "EXPRESS", 35.0, 0.65, Double.NaN), eps)
        // Scalp desk: unchanged table x patience.
        assertEquals(1.95, RunnerTrail8419.holdLayerTrailPoints8419("TREASURY", "TREASURY", 3.0, 0.65, 100.0), eps)
        assertEquals(5.0, RunnerTrail8419.holdLayerTrailPoints8419("BLUECHIP", "BLUECHIP", 5.0, 1.0, 100.0), eps)
    }

    @Test fun theHoldLayerLevelIsGreenFloorsMaxOfTheOwnersRuleAndTheTrail() {
        val at100 = RunnerTrail8419.holdLayerLevel8419(100.0, Double.NaN)
        assertNotNull(at100)
        assertEquals(40.0, at100!!.levelPct, eps)                       // owner 8337 3a (40 % of the peak) above the +30 trail
        assertEquals(RunnerTrail8419.SOURCE_RULE_3A_8419, at100.source)
        val at500 = RunnerTrail8419.holdLayerLevel8419(500.0, Double.NaN)!!
        assertEquals(290.0, at500.levelPct, eps)                        // the trail owns it from +300 % up
        assertEquals(210.0, at500.pointsUnderPeak, eps)
        assertEquals(RunnerTrail8419.SOURCE_TRAIL_8419, at500.source)
        assertEquals(RunnerTrail8419.exitLevelPct8419(500.0, Double.NaN), GreenFloor8363.floor8370(500.0, Double.NaN), eps)
        assertNull(RunnerTrail8419.holdLayerLevel8419(Double.NaN, Double.NaN))
        assertNull(RunnerTrail8419.holdLayerLevel8419(0.0, 3.0))
        assertTrue(RunnerTrail8419.breached8419(500.0, 289.0))
        assertFalse(RunnerTrail8419.breached8419(500.0, 291.0))
    }

    @Test fun peakDrawdownLockFiresAtTheTrailOnARunnerLaneAndKeepsItsRatchetElsewhere() {
        // 7282 ratchet: peak +500 % -> lock +418.6 %. On a runner lane that sat inside the 35 % trail (+290 %).
        assertEquals(418.6, PeakDrawdownLock.lockPrice(500.0), 0.05)
        assertFalse("inside the trail is not a runner exit", PeakDrawdownLock.shouldLock(500.0, 410.0, "EXPRESS"))
        assertFalse(PeakDrawdownLock.shouldLock(500.0, 291.0, "PROJECT_SNIPER"))
        assertTrue(PeakDrawdownLock.shouldLock(500.0, 289.0, "EXPRESS"))
        assertTrue("non-runner desks keep the ratchet", PeakDrawdownLock.shouldLock(500.0, 410.0, "BLUECHIP"))
        // The 8337 3a floor keeps the small-peak behaviour the golden tape pins (peak +30 -> +12 floor).
        assertTrue(PeakDrawdownLock.shouldLock(30.0, 5.0, "MOONSHOT"))
        assertFalse(PeakDrawdownLock.shouldLock(20.0, 5.0, "MOONSHOT"))   // MOONSHOT arms at +25
    }

    @Test fun theTickLockIsHeldToTheTrailOnARunnerLaneOnly() {
        assertEquals(290.0, RunnerTrail8419.tickLockLevel8419("EXPRESS", 500.0, 487.0), eps)   // fluidProfitFloor's +487 at a +500 peak
        assertEquals(40.0, RunnerTrail8419.tickLockLevel8419("SHITCOIN", 100.0, 91.0), eps)
        assertEquals(91.0, RunnerTrail8419.tickLockLevel8419("TREASURY", 100.0, 91.0), eps)
        assertEquals(91.0, RunnerTrail8419.tickLockLevel8419("", 100.0, 91.0), eps)
        assertEquals(26.0, RunnerTrail8419.tickLockLevel8419("EXPRESS", 30.0, 26.0) + 14.0, eps)   // +30 peak: floor 12
    }

    @Test fun everyLayerReadsTheOneAuthority() {
        val hold = src("engine/HoldingLogicLayer.kt")
        assertTrue(hold.contains("RunnerTrail8419.holdLayerTrailPoints8419(mode, learningLane8384, params.trailingStopPct, ssiExitPatience6091, position.peakGainPct)"))
        assertTrue(hold.contains("RunnerTrail8419.holdLayerLevelFor8419(ts, mode, learningLane8384)"))
        assertTrue(hold.contains("if (diamondHandsMode6091 && !runnerLane8419 && position.peakGainPct >= 150.0)"))
        assertFalse(hold.contains("LaneHolds8418.trailFor8418(mode, learningLane8384, params.trailingStopPct) * ssiExitPatience6091"))
        assertTrue(src("engine/PeakDrawdownLock.kt").contains("if (RunnerExitProfile7277.isRunnerLane(lane)) return RunnerTrail8419.lockFires8419(peakPnlPct, currentPnlPct)"))
        assertTrue(src("engine/Executor.kt").contains("if (PeakDrawdownLock.settleLock8419(ts, peakPnlPct, curPnlPct)) {"))
        val fluid = src("v3/scoring/FluidLearningAI.kt")
        assertTrue(fluid.contains("return com.lifecyclebot.engine.RunnerTrail8419.tickLockLevel8419(lane, peakPnlPct, out)"))
        assertTrue(fluid.contains("return maxOf(com.lifecyclebot.engine.RunnerTrail8419.tickLockLevel8419(lane, peakClamped, continuousLock), breakEvenFloor)"))
    }
}

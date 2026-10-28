package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneGradeHorizon8406
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8406 — a strategy is graded at its own mandate horizon; 5-minute evidence does not retire patience. */
class Aate8406MandateHorizonGradingTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun the_mandate_decides_the_horizon() {
        assertTrue(LaneGradeHorizon8406.hourGradedByMandate8406(50.0))   // RUNNER
        assertTrue(LaneGradeHorizon8406.hourGradedByMandate8406(30.0))   // MOMENTUM
        assertTrue(LaneGradeHorizon8406.hourGradedByMandate8406(25.0))   // DIP
        assertTrue(LaneGradeHorizon8406.hourGradedByMandate8406(20.0))   // QUALITY
        assertFalse(LaneGradeHorizon8406.hourGradedByMandate8406(12.0))  // TREASURY / CRYPTO range reversion
        assertFalse(LaneGradeHorizon8406.hourGradedByMandate8406(6.0))   // CARRY
        assertFalse(LaneGradeHorizon8406.hourGradedByMandate8406(Double.NaN))
        assertTrue(LaneGradeHorizon8406.hourGraded8406("MOONSHOT"))
        assertTrue(LaneGradeHorizon8406.hourGraded8406("QUALITY"))
        assertFalse(LaneGradeHorizon8406.hourGraded8406("TREASURY"))
        assertFalse(LaneGradeHorizon8406.hourGraded8406(""))
    }

    @Test fun five_minute_evidence_does_not_retire_patience_on_an_hour_graded_lane() {
        // the accumulation family was retired on 5-minute labels; on an hour-graded lane that retirement is suspended
        assertFalse(LanePlaybook7907.retired8383("MOONSHOT", "QUIET_ACCUMULATION"))
        assertFalse(LanePlaybook7907.retired8383("SHITCOIN", "PRE_IGNITION_BASE"))
        // superseded by V5.0.8418 — the suspension needs the lane's hour book (30 hour labels, LaneGradeHorizon8406.suspensionEarned8418);
        // with none, QUALITY's 5-minute retirement of BUYERS_DOMINATING stands (was assertFalse). MOONSHOT / SHITCOIN above keep
        // their early-band (8389) exemption, which is independent of the clock.
        assertTrue(LanePlaybook7907.retired8383("QUALITY", "BUYERS_DOMINATING"))
        assertFalse(LaneGradeHorizon8406.suspensionEarned8418(29L)); assertTrue(LaneGradeHorizon8406.suspensionEarned8418(30L))
        // a range-reversion lane keeps its 5-minute evidence
        assertTrue(LanePlaybook7907.retired8383("TREASURY", "QUIET_ACCUMULATION"))
        // the chase family is not in the patient set: nothing here un-retires it
        assertFalse(LaneGradeHorizon8406.retirementSuspended8406("MOONSHOT", "IGNITION_5M"))
        assertFalse(LaneGradeHorizon8406.retirementSuspended8406("MOONSHOT", "NEW_HIGH_PUSH"))
    }

    @Test fun the_hour_read_owns_every_hour_graded_lane() {
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt").substringAfter("fun runnerSlot8337(").substringBefore("private fun reviseToHour8337(")
        assertTrue(lab.contains("LaneGradeHorizon8406.hourGraded8406(lane)"))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.substringAfter("private fun runnerHorizonLane8144(").substringBefore("private class Grade8076").contains("LaneGradeHorizon8406.hourGraded8406(lane)"))
        assertTrue(pb.substringAfter("fun setupHoldsLong8080(").substringBefore("fun reviseLabel7997(").contains("LaneGradeHorizon8406.hourGraded8406(lane)"))
        assertTrue(pb.substringAfter("fun retired8383(").substringBefore("internal val EARLY_FAMILY_8389").contains("LaneGradeHorizon8406.retirementSuspended8406(lane, setup)"))
        val cx = src("engine/cortex/Cortex7885.kt").substringAfter("private fun runnerHorizon8144(").substringBefore("private fun decisionCtx8144(")
        assertTrue(cx.contains("LaneGradeHorizon8406.hourGraded8406(lane)"))
    }
}

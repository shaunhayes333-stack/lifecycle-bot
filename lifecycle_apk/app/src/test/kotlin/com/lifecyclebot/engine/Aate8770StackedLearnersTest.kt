package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.GovernorRecovery6388
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8770 — code audit (learning 2, 3, 8, 9): a cell veto beats the lane, size is one bounded shape, no inverted safety. */
class Aate8770StackedLearnersTest {
    private fun stat(n: Int, mean: Double, se: Double) = ForwardReturnLabeler7731.CellStat("k", n, mean, 0.3, 0.05, se, 0)

    @Test fun a_cohort_veto_must_be_worse_than_what_the_lane_keeps() {
        assertFalse("a losing cohort in a losing market is not a veto", LaneAuthority8577.cohortWorseThanKept8770(stat(80, -6.0, 1.0), stat(120, -5.0, 1.0)))
        assertTrue("clearly worse than the lane's kept coins", LaneAuthority8577.cohortWorseThanKept8770(stat(80, -14.0, 1.0), stat(120, -5.0, 1.0)))
        assertFalse("no kept record of 30: no veto", LaneAuthority8577.cohortWorseThanKept8770(stat(80, -14.0, 1.0), stat(10, -5.0, 1.0)))
        assertFalse(LaneAuthority8577.cohortWorseThanKept8770(null, stat(120, -5.0, 1.0)))
    }

    @Test fun learned_size_is_one_bounded_shape() {
        assertEquals("0.3 x 0.35 x 0.25 no longer collapses to the floor", SizeShape8770.MIN_8770, SizeShape8770.bounded8770(0.3 * 0.35 * 0.25), 1e-12)
        assertEquals(0.8, SizeShape8770.bounded8770(0.8), 1e-12)
        assertEquals(SizeShape8770.MAX_8770, SizeShape8770.bounded8770(4.0), 1e-12)
        assertEquals(1.0, SizeShape8770.bounded8770(Double.NaN), 0.0)
        val s = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
        assertTrue(s.contains("com.lifecyclebot.engine.SizeShape8770.bounded8770("))
        assertTrue("the score bucket is read once", s.contains("if (calMult.isFinite() && calMult < 1.0 && !scoreBucketShaped8770) {"))
    }

    @Test fun a_demotion_on_the_governors_own_record_is_not_undone_by_a_tick_or_a_restart() {
        GovernorRecovery6388.resetForTest(GovernorRecovery6388.State.SOFT_TIGHT)
        assertFalse(GovernorRecovery6388.isProbationByRecord8770())
        val g = File("src/main/kotlin/com/lifecyclebot/engine/truth/GovernorRecovery6388.kt").readText()
        assertTrue(g.contains("if (s == State.HOLD_PROBATION && !governorHold && !probationByRecord8770) {"))
        assertTrue(g.contains("if (probationByRecord8770) promote(State.HOLD_PROBATION, \"INFRA_HEALTHY_PROBATION_BY_RECORD_8770\")"))
        assertTrue(g.contains("demote(State.HOLD_PROBATION, PROBATION_BY_RECORD_8770)"))
        assertEquals("SOFT_TIGHT_PF_OR_STREAK_BREACH", GovernorRecovery6388.PROBATION_BY_RECORD_8770)
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/truth/LearningResetSweep7781.kt").readText().contains("GovernorRecovery6388.load8770(app)"))
    }

    @Test fun a_worse_record_never_loosens_the_fluid_gates() {
        val f = File("src/main/kotlin/com/lifecyclebot/v3/scoring/FluidLearningAI.kt").readText()
        assertTrue(f.contains("blendedWinRate < 30  -> cappedBaseProgress.coerceAtLeast(adaptiveFloor)"))
        assertFalse(f.contains("blendedWinRate < 25 -> cappedBaseProgress * 0.80"))
    }
}

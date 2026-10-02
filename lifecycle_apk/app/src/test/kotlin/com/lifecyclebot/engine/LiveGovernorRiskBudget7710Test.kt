package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGovernorRiskBudget7710Test {
    @Test
    fun weakerLiveEvidenceNeverRaisesTheGovernorStakeMultiplier() {
        fun size(s: LiveEntrySafetyHold.GovernorState) = LiveEntrySafetyHold.governorSizeMultiplier6324(s)

        assertEquals(1.00, size(LiveEntrySafetyHold.GovernorState.BASELINE), 1e-9)
        assertEquals(0.60, size(LiveEntrySafetyHold.GovernorState.CAUTION), 1e-9)
        assertEquals(0.50, size(LiveEntrySafetyHold.GovernorState.SOFT_TIGHT), 1e-9)
        assertEquals(0.65 / 1.50, size(LiveEntrySafetyHold.GovernorState.TIGHTENED), 1e-9)
        assertEquals(0.50 / 1.50, size(LiveEntrySafetyHold.GovernorState.HOLD), 1e-9)
        assertTrue(size(LiveEntrySafetyHold.GovernorState.RECOVERY) < 1.0)

        for (s in LiveEntrySafetyHold.GovernorState.values()) {
            val worstCaseStack = size(s) * LiveEntrySafetyHold.MAX_LANE_EDGE_SIZE_MULTIPLIER_7710
            assertTrue("${s.name} must stay within its risk budget", worstCaseStack <= LiveEntrySafetyHold.governorRiskBudget7710(s) + 1e-9)
        }
    }

    @Test
    fun liveConcentratorCannotUndoAnAdverseGovernorBudget() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val concentrator = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneEdgeConcentrator6334.kt").readText()
        assertTrue(executor.contains("val govMult = try { com.lifecyclebot.engine.LiveEntrySafetyHold.currentSizeMultiplier()"))
        assertTrue(concentrator.contains("WINNER_MAX_MULT") && concentrator.contains("1.0 + winScore"))
        assertTrue(executor.contains("val combinedMult = (govMult * concentratorMult * defensiveMult).coerceIn(0.0, ceiling)"))
    }
}

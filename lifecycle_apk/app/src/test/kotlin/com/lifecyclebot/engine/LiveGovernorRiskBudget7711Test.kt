package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGovernorRiskBudget7711Test {
    @Test
    fun weakerLiveEvidenceNeverRaisesTheGovernorStakeMultiplier() {
        fun size(s: LiveEntrySafetyHold.GovernorState) = LiveEntrySafetyHold.governorSizeMultiplier6324(s)

        assertEquals(1.00, size(LiveEntrySafetyHold.GovernorState.BASELINE), 1e-9)
        assertEquals(0.97, size(LiveEntrySafetyHold.GovernorState.CAUTION), 1e-9)
        assertEquals(0.93, size(LiveEntrySafetyHold.GovernorState.SOFT_TIGHT), 1e-9)
        assertEquals(0.90, size(LiveEntrySafetyHold.GovernorState.TIGHTENED), 1e-9)
        assertEquals(0.80, size(LiveEntrySafetyHold.GovernorState.HOLD), 1e-9)
        assertTrue(size(LiveEntrySafetyHold.GovernorState.RECOVERY) < 1.0)

        for (s in LiveEntrySafetyHold.GovernorState.values()) {
            assertEquals("${s.name} stake adjustment matches global budget", size(s), LiveEntrySafetyHold.governorRiskBudget7711(s), 1e-9)
        }
    }

    @Test
    fun smallGlobalSampleDoesNotShapeLiveStakeAndConfidenceFloorNeverBlocksEvidence() {
        assertEquals(20, LiveEntrySafetyHold.GOVERNOR_MIN_SAMPLE_7711)
        assertTrue(!LiveEntrySafetyHold.performanceSampleReady7711(19))
        assertTrue(LiveEntrySafetyHold.performanceSampleReady7711(20))
        assertTrue(LiveEntrySafetyHold.performanceSampleReady7711(200))
        val governor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LiveEntrySafetyHold.kt").readText()
        assertTrue(governor.contains("!performanceSampleReady7711(stats.canonicalN)"))
        assertTrue(governor.contains("private const val CAUTION_MIN_N: Int = GOVERNOR_MIN_SAMPLE_7711"))
        assertTrue(governor.contains("private const val SOFT_TIGHT_MIN_N: Int = GOVERNOR_MIN_SAMPLE_7711"))
        assertTrue(governor.contains("private const val FLOOR_ADJUSTMENT_HOLD: Double = 0.0"))
        assertTrue(governor.contains("Eligible finalized closes still reach learners starting with close one"))
    }

    @Test
    fun liveSizingKeepsLaneConvictionAfterGentleGlobalShape() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val concentrator = java.io.File("src/main/kotlin/com/lifecyclebot/engine/LaneEdgeConcentrator6334.kt").readText()
        assertTrue(executor.contains("val govMult = try { com.lifecyclebot.engine.LiveEntrySafetyHold.currentSizeMultiplier()"))
        assertTrue(concentrator.contains("WINNER_MAX_MULT") && concentrator.contains("1.0 + winScore"))
        assertTrue(executor.contains("val combinedMult = (govMult * concentratorMult * defensiveMult).coerceIn(0.0, ceiling)"))
    }
}


package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7641ReasoningSelfRepairTest {
    @Test fun reasoningTrustIsLaneLocalSampleGatedAndRecoverable() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("fun reasoningTrust7641(lane: String, family: String)"))
        assertTrue(s.contains("if (total < 8L) return 1.0"))
        assertTrue(s.contains("MEMORY_OVERTRUST"))
        assertTrue(s.contains("TREE_CONVICTION_POLICY_WRONG"))
        assertTrue(s.contains("CRITIC_TOO_WEAK"))
        assertTrue(s.contains("LATENT_STATE_OVERBULLISH"))
        assertTrue(s.contains("coerceIn(0.65, 1.05)"))
    }

    @Test fun arbiterConsumesLearnedFamilyTrust() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperReasoningArbiter7639.kt").readText()
        assertTrue(s.contains("reasoningTrust7641(world.lane, \"WORLD\")"))
        assertTrue(s.contains("reasoningTrust7641(world.lane, \"CRITIC\")"))
        assertTrue(s.contains("reasoningTrust7641(world.lane, \"MEMORY\")"))
        assertTrue(s.contains("reasoningTrust7641(world.lane, \"TREE\")"))
    }

    @Test fun reasoningFailureMemoryPersists() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("reasonFailures7641"))
        assertTrue(s.contains("reasonOutcomes7641"))
    }
}

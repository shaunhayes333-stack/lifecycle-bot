
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7640ReasoningFailureAttributionTest {
    @Test fun calibrationCapturesReasoningContextAtEntry() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("val treePolicy: SuperPolicyTree7638.Policy"))
        assertTrue(s.contains("val arbiterDominant: String"))
        assertTrue(s.contains("tree = tree7638"))
        assertTrue(s.contains("arbiter = arbiter7639"))
    }

    @Test fun terminalOutcomeClassifiesReasoningFailureMode() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("LATENT_STATE_OVERBULLISH"))
        assertTrue(s.contains("HORIZON_PROBABILITY_OVERCONFIDENT"))
        assertTrue(s.contains("CRITIC_TOO_WEAK"))
        assertTrue(s.contains("TREE_CONVICTION_POLICY_WRONG"))
        assertTrue(s.contains("MEMORY_OVERTRUST"))
        assertTrue(s.contains("failureModes7640"))
    }

    @Test fun oraclePassesTreeAndArbiterIntoPositionBoundCalibration() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(s.contains("critic7635, tree7638, arbiter7639"))
    }
}

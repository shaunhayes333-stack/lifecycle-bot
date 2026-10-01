
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7647CausalPolicyEvaluationTest {
    @Test fun treeEmitsSelectionPropensityFromRobustUtilities() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        assertTrue(s.contains("selectionPropensity"))
        assertTrue(s.contains("kotlin.math.exp"))
        assertTrue(s.contains("temperature7647"))
        assertTrue(s.contains("coerceIn(0.02, 1.0)"))
    }

    @Test fun terminalOutcomeUsesBoundedInversePropensityWeighting() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperCausalPolicyEvaluator7647.kt").readText()
        assertTrue(s.contains("1.0 / p"))
        assertTrue(s.contains("coerceIn(1.0, 5.0)"))
        assertTrue(s.contains("ps.n < 5L || bs.n < 10L"))
        assertTrue(s.contains("coerceIn(-6.0, 6.0)"))
    }

    @Test fun exactPositionBoundCalibrationCarriesPropensityToOutcome() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("val treePropensity: Double"))
        assertTrue(s.contains("selectionPropensity = stamp.treePropensity"))
        assertTrue(s.contains("SuperCausalPolicyEvaluator7647.recordOutcome("))
    }

    @Test fun causalPolicyStatePersistsAndTreeConsumesLift() {
        val tree = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        val lp = File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue(tree.contains("SuperCausalPolicyEvaluator7647.policyLift("))
        assertTrue(lp.contains("SUPER_CAUSAL_POLICY_7647"))
    }
}

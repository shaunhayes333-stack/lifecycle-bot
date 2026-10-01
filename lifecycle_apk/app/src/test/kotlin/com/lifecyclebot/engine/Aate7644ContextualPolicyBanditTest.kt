
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7644ContextualPolicyBanditTest {
    @Test fun exactTerminalOutcomeTrainsContextualPolicy() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("SuperPolicyBandit7644.recordOutcome("))
        assertTrue(s.contains("policy = stamp.treePolicy"))
        assertTrue(s.contains("state = stamp.world.latentState"))
    }

    @Test fun treeConsumesLearnedPolicyPrior() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        assertTrue(s.contains("SuperPolicyBandit7644.policyPrior("))
        assertTrue(s.contains("baseUtility = b.utility + learnedPrior7644"))
    }

    @Test fun policyLearnerIsBoundedPersistentAndExploratory() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyBandit7644.kt").readText()
        val lp = File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue(s.contains("coerceIn(-8.0, 8.0)"))
        assertTrue(s.contains("uncertaintyBonus"))
        assertTrue(s.contains("if (s.n < 3L)"))
        assertTrue(lp.contains("SUPER_POLICY_BANDIT_7644"))
    }
}

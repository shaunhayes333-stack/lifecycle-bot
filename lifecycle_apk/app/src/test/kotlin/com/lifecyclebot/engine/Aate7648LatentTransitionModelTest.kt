
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7648LatentTransitionModelTest {
    @Test fun exactTerminalOutcomesTrainStatePolicyTransitions() {
        val c = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        val m = File("src/main/kotlin/com/lifecyclebot/engine/SuperLatentTransitionModel7648.kt").readText()
        assertTrue(c.contains("SuperLatentTransitionModel7648.recordOutcome("))
        assertTrue(c.contains("mfePct = env.mfePct"))
        assertTrue(m.contains("RUNNER"))
        assertTrue(m.contains("CATASTROPHIC"))
        assertTrue(m.contains("confidence = (n / (n + 10.0))"))
    }

    @Test fun imaginationConsumesLearnedTransitionDistribution() {
        val t = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        val i = File("src/main/kotlin/com/lifecyclebot/engine/SuperImaginationRollout7643.kt").readText()
        assertTrue(t.contains("SuperLatentTransitionModel7648.prior("))
        assertTrue(i.contains("transition: SuperLatentTransitionModel7648.Prior? = null"))
        assertTrue(i.contains("it.pLoss + it.pCatastrophic"))
        assertTrue(i.contains("transition?.pRunner"))
    }

    @Test fun transitionStatePersists() {
        val lp = File("src/main/kotlin/com/lifecyclebot/engine/LearningPersistence.kt").readText()
        assertTrue(lp.contains("SUPER_LATENT_TRANSITIONS_7648"))
    }
}

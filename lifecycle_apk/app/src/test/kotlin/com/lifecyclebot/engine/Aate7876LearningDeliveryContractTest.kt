package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Contract test for the canonical first-trade learning delivery and replay path. */
class Aate7876LearningDeliveryContractTest {
    @Test fun deliveryRequiresBothConsumersAndSupportsIdempotentReplay() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/LearnerRewardBridge6440.kt").readText()
        assertTrue(bridge.contains("val fullyDelivered = sentienceAccepted && labAccepted"))
        assertTrue(bridge.contains("return fullyDelivered"))
        assertFalse(bridge.contains("|| true"))
        assertFalse(bridge.contains("SentienceHooks.run { true }"))
        assertTrue(bridge.contains("hasCanonicalEngineOutcome6486(positionId)"))
        assertTrue(bridge.contains("hasCanonicalOutcome6486(positionId)"))
    }

    @Test fun underlyingConsumersExposeExactPositionIdempotency() {
        val senti = File("src/main/kotlin/com/lifecyclebot/engine/SentienceHooks.kt").readText()
        val lab = File("src/main/kotlin/com/lifecyclebot/engine/lab/LlmLabEngine.kt").readText()
        assertTrue(senti.contains("fun hasCanonicalEngineOutcome6486(positionId: String): Boolean"))
        assertTrue(lab.contains("fun hasCanonicalOutcome6486(positionId: String): Boolean"))
        assertTrue(senti.contains("canonicalEngineOutcomeIds6486.add(positionId)"))
        assertTrue(lab.contains("canonicalExternalIds6486.add(positionId)"))
    }
}

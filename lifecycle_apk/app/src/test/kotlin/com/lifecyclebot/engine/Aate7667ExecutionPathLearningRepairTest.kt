package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7667ExecutionPathLearningRepairTest {
    @Test fun canonicalReceiptsFeedExecutionPathLearning() {
        val c = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalExecutionTruth6394.kt").readText()
        assertTrue(c.contains("ExecutionPathAI.recordCanonicalReceipt7667("))
        assertTrue(c.contains("finalizedAt - r.createdAt"))
        assertTrue(c.contains("actualConsumedRawAmount < r.requestedRawAmount"))
    }

    @Test fun endpointFailuresFeedTheSameLearner() {
        val h = File("src/main/kotlin/com/lifecyclebot/engine/ExecutionEndpointHealth.kt").readText()
        assertTrue(h.contains("ExecutionPathAI.recordEndpointFailure7667(endpoint)"))
    }

    @Test fun unknownSlippageIsNotInventedAsZero() {
        val a = File("src/main/kotlin/com/lifecyclebot/v4/meta/ExecutionPathAI.kt").readText()
        assertTrue(a.contains("slippageBps = Double.NaN"))
        assertTrue(a.contains("filter { it.isFinite() && it >= 0.0 }"))
    }

    @Test fun liveReadersAlreadyConsumeExecutionConfidence() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SymbolicExitReasoner.kt").readText()
        assertTrue(s.contains("ExecutionPathAI.getExecutionConfidenceMultiplier()"))
    }
}

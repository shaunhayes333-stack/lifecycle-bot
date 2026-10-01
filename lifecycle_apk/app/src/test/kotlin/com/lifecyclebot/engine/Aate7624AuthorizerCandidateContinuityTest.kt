package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7624AuthorizerCandidateContinuityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/engine/" + rel).readText()

    @Test fun `authorizer pins one generation across intent election release and attempt id`() {
        val s = src("TradeAuthorizer.kt")
        val fn = s.substringAfter("fun authorize(").substringBefore("fun release(")
        assertTrue(fn.contains("val candidateVersion7624 = LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(fn.contains("\${mint}:\$candidateVersion7624:\${requestedBook.name}"))
        assertTrue(fn.contains("candidateVersion = candidateVersion7624"))
        assertTrue(fn.contains("candidateVersion = receipt?.candidateVersion ?: candidateVersion7624"))
        assertTrue(fn.contains("nextAttemptId(mint, requestedBook.name, laneElection.candidateVersion)"))
        assertEquals(1, Regex("candidateVersionFor\\(mint\\)").findAll(fn).count())
    }

    @Test fun `attempt id generator accepts immutable election generation`() {
        val s = src("ExecutableOpenGate.kt")
        val fn = s.substringAfter("fun nextAttemptId(").substringBefore("fun canonicalExecutionKey(")
        assertTrue(fn.contains("candidateVersion: Long = LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(fn.contains("candidateVersion = candidateVersion"))
    }
}

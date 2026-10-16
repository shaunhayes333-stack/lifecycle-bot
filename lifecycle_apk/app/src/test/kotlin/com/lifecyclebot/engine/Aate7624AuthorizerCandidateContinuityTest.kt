package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7624AuthorizerCandidateContinuityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/engine/" + rel).readText()

    @Test fun `authorizer pins one generation across intent election release and attempt id`() {
        val s = src("TradeAuthorizer.kt")
        val fn = s.substringAfter("fun authorize(").substringBefore("fun releasePosition(")
        // V5.0.7835 — the generation is pinned from the exact FDG decision, never re-read
        // from the mutable bucket; the attempt id is the sealed intent's own.
        assertTrue(fn.contains("val candidateVersion7624 = fdgDecision7835?.candidateVersion7835 ?: 0L"))
        assertTrue(fn.contains("candidateVersion = candidateVersion7624"))
        assertTrue(fn.contains("candidateVersion = receipt?.candidateVersion ?: candidateVersion7624"))
        assertTrue(fn.contains("val finalityAttemptId = sealedIntent7812.attemptId"))
        assertEquals(0, Regex("candidateVersionFor\\(mint\\)").findAll(fn).count())
    }

    @Test fun `attempt id generator accepts immutable election generation`() {
        val s = src("ExecutableOpenGate.kt")
        val fn = s.substringAfter("fun nextAttemptId(").substringBefore("fun canonicalExecutionKey(")
        assertTrue(fn.contains("candidateVersion: Long = LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(fn.contains("candidateVersion = candidateVersion"))
    }
}

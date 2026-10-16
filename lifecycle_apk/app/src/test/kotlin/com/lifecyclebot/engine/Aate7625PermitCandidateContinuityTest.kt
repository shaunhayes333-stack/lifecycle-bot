package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7625PermitCandidateContinuityTest {
    @Test fun `permit pins one generation across release attempt and ticket validation`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()
        val fn = s.substringAfter("fun tryAcquireExecution(")
        // V5.0.7628 — the pinned generation is the sealed attempt's own version,
        // falling back to the coordinator only when no ticket exists.
        assertTrue(fn.contains("val candidateVersion7628 = existingAttemptTicket7628"))
        assertTrue(fn.contains("?: LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(fn.contains("candidateVersion = ticket6494?.candidateVersion ?: candidateVersion7628"))
        assertTrue(fn.contains("nextAttemptId(mint, layer, candidateVersion7628)"))
        assertTrue(fn.contains("val currentVersion6513 = candidateVersion7628"))
        assertEquals(1, Regex("candidateVersionFor\\(mint\\)").findAll(fn).count())
    }
}

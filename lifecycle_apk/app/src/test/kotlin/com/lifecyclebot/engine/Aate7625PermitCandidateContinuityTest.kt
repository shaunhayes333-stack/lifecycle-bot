package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7625PermitCandidateContinuityTest {
    @Test fun `permit pins one generation across release attempt and ticket validation`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()
        val fn = s.substringAfter("fun tryAcquireExecution(")
        assertTrue(fn.contains("val candidateVersion7625 = LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(fn.contains("candidateVersion = ticket6494?.candidateVersion ?: candidateVersion7625"))
        assertTrue(fn.contains("nextAttemptId(mint, layer, candidateVersion7625)"))
        assertTrue(fn.contains("val currentVersion6513 = candidateVersion7625"))
        assertEquals(1, Regex("candidateVersionFor\\(mint\\)").findAll(fn).count())
    }
}

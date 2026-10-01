package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7628PermitTicketGenerationContinuityTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/FinalExecutionPermit.kt").readText()

    @Test fun `sealed attempt generation is inherited by permit`() {
        val s = src()
        assertTrue(s.contains("existingAttemptTicket7628"))
        assertTrue(s.contains("val candidateVersion7628 = existingAttemptTicket7628"))
        assertTrue(s.contains("?: LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(s.contains("ExecutableOpenGate.nextAttemptId(mint, layer, candidateVersion7628)"))
        assertTrue(s.contains("val currentVersion6513 = candidateVersion7628"))
    }
}

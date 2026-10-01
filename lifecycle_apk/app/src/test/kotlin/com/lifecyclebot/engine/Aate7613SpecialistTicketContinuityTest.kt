package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7613SpecialistTicketContinuityTest {
    private fun src()=File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()

    @Test fun canonicalTicketProducerMirrorsSpecialistTicketStage() {
        val s=src()
        val fn=s.substringAfter("private fun publishTicket(ticket: ExecutionIntent)")
            .substringBefore("private fun trueHardTicketKill")
        assertTrue(fn.contains("EXEC_TICKET_CREATED"))
        assertTrue(fn.contains("ToolkitSignalSheet.recordDeskStage(lane7613, \"TICKET\", ticket.attemptId)"))
        assertTrue(fn.contains("SPECIALIST_CANONICAL_TICKET_MIRRORED_7613"))
    }

    @Test fun ticketMirrorUsesCanonicalLaneIdentity() {
        val s=src()
        val fn=s.substringAfter("private fun publishTicket(ticket: ExecutionIntent)")
            .substringBefore("private fun trueHardTicketKill")
        assertTrue(fn.contains("canonicalLane(ticket.canonicalLane.ifBlank { ticket.lane })"))
    }
}

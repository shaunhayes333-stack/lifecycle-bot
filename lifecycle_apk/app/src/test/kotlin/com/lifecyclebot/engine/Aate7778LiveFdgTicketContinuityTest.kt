package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7778LiveFdgTicketContinuityTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test fun sealedLiveFdgBuyDoesNotUseUnprovenLearnedEvidenceAsHardVeto() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("liveSealedFdgBuy7778"))
        assertTrue(s.contains("liveLaneCloses7778"))
        // V5.0.7846 — a sealed FDG decision now carries its own entry authority, so the
        // learned entry gate is not re-run (and cannot veto) for it at all.
        assertTrue(s.contains("if (sealedEntryAuthority7846(immutableAuthority6513, ticketAuthority6564))"))
        assertTrue(s.contains("sealed_execution_intent_authority_7846"))
        assertTrue(s.contains("LIVE_UNPROVEN_TERMINAL_COHORT_ADVISORY_7778"))
        assertTrue(s.contains("LIVE_UNPROVEN_SHADOW_BUCKET_ADVISORY_7778"))
    }

    @Test fun everyPostFdgPreticketRejectAndTicketPublishIsObservable() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("LIVE_FDG_ALLOW_PRETICKET_REJECT_7778"))
        assertTrue(s.contains("LIVE_FDG_ALLOW_TICKET_PUBLISHED_7778"))
        assertTrue(s.contains("LIVE_FDG_ALLOW_TICKET_PUBLISH_EXCEPTION_7778"))
    }

    @Test fun canonicalGatePublishesTheTicketAndNamesEverySealedLiveReject() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("LIVE_FDG_ALLOW_PRETICKET_REJECT_7778"))
        assertTrue(s.contains("LIVE_FDG_ALLOW_TICKET_PUBLISHED_7778"))
        assertTrue(s.contains("publishTicket("))
        assertTrue(s.contains("EXEC_GATE_ALLOW"))
    }
}

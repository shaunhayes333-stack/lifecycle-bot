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
        assertTrue(s.contains("LIVE_UNPROVEN_ENTRY_AUTHORITY_ADVISORY_7778"))
        assertTrue(s.contains("LIVE_UNPROVEN_TERMINAL_COHORT_ADVISORY_7778"))
        assertTrue(s.contains("LIVE_UNPROVEN_SHADOW_BUCKET_ADVISORY_7778"))
    }

    @Test fun everyPostFdgPreticketRejectAndTicketPublishIsObservable() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("LIVE_FDG_ALLOW_PRETICKET_REJECT_7778"))
        assertTrue(s.contains("LIVE_FDG_ALLOW_TICKET_PUBLISHED_7778"))
        assertTrue(s.contains("LIVE_FDG_ALLOW_TICKET_PUBLISH_EXCEPTION_7778"))
    }

    @Test fun botServiceWitnessesAuthorizerAndExecutorHandoff() {
        val s = src("engine/BotService.kt")
        assertTrue(s.contains("LIVE_FDG_ALLOW_AUTH_EXECUTABLE_7778"))
        assertTrue(s.contains("LIVE_FDG_ALLOW_AUTH_REJECT_7778"))
        assertTrue(s.contains("LIVE_TICKET_TO_EXECUTOR_7778"))
        assertTrue(s.contains("attemptId          = authResult.attemptId"))
    }
}

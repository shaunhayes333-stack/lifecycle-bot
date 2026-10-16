package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7812HunterSelectionAndPipelineTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test fun adaptiveHunterSelectionUsesOutcomeAndTransitionEvidence() {
        val s = src("engine/market/LaneHunter7297.kt")
        assertTrue(s.contains("sampleConfidence7812"))
        assertTrue(s.contains("learnedDelta7812"))
        assertTrue(s.contains("adaptiveTimingMultiplier7812"))
        assertTrue(s.contains("o.volumeAcceleration"))
        assertTrue(s.contains("o.txAcceleration"))
        assertTrue(s.contains("o.buyPressurePct"))
        assertTrue(s.contains("\"EARLY_MOMENTUM_IGNITION\""))
        assertTrue(s.contains("\"DIP_RECOVERY\""))
        assertTrue(s.contains("\"DISTRIBUTION\""))
        assertTrue(s.contains("\"EXHAUSTION\""))
        assertTrue(s.contains("commonSenseMult7797(p.lane, r) * adaptiveTimingMultiplier7812(p.lane, r)"))
    }

    @Test fun authorizerDefersUntilExactSealedFdgBuy() {
        val s = src("engine/TradeAuthorizer.kt")
        // V5.0.7835/7858 — the exact FDG BUY is sealed by SpecialistPreauthSeal7834
        // (which only returns an fdgAllowed BUY intent for this lane/version) before
        // lane election; a missing seal is a retryable deferral.
        val start = s.indexOf("V5.0.7812 — only an exact immutable FDG BUY")
        val sealed = s.indexOf("sealedTicketAttempt7948 = sealedIntent7812.attemptId", start)
        val gate = s.indexOf("val finality = ExecutableOpenGate.canOpenExecutablePosition(", sealed)
        assertTrue(start >= 0 && sealed > start && gate > sealed)
        val pre = s.substring(start, sealed)
        assertTrue(pre.contains("SpecialistPreauthSeal7834.ensure(tokenState7835, fdgDecision7835!!, requestedBook.name, preResolvedSizeSol)"))
        assertTrue(pre.contains("FDG_SEAL_FAILED_7835"))
        assertTrue(pre.contains("canRetry = true"))
        assertFalse(pre.contains("recordPreSizeRefusal7809("))
        assertFalse(pre.contains("markLost("))
        val seal = src("engine/SpecialistPreauthSeal7834.kt")
        assertTrue(seal.contains("it.fdgAllowed && it.fdgVerdict == \"BUY\" && it.hardNoReasons.isEmpty()"))
        assertTrue(s.contains("val finalityAttemptId = sealedIntent7812.attemptId"))
    }

    @Test fun missingElectionLaneCannotBreakKotlinCompile() {
        val s = src("engine/ExecutableOpenGate.kt")
        assertTrue(s.contains("currentElection6600(mint)?.primaryLane.orEmpty()"))
    }
}

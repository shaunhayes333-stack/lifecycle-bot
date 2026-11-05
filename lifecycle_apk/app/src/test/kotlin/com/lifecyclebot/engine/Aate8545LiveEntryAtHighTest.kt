package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8545 — owner: "go deeper into why live trading is so shit". The 8542 live entries: at the high n3 w0, near the high
 * n5 w0, CONTROLLED_MARKUP n5 w0; lifetime LOSS_BOUGHT_TOP 28 of 48 runner-style live buys; BREAKOUT / PEAK stage records
 * -12 % .. -22 % in every runner lane. LIVE no longer buys into the high on an unproven read; and a ticket whose mint's
 * last attempt failed outright is ended and re-decided instead of being retried for 90 s on the stale decision.
 */
class Aate8545LiveEntryAtHighTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    private fun markup(lane: String = "MOONSHOT") = CandidateAdmission8136.Candidate8136(
        mint = "high8545-$lane", lane = lane, selectedSetup = null, live = true, stage = "CONTROLLED_MARKUP",
        priceAgeMs = 10L, expectedGrossPct = 30.0, costPct = 4.5, genericScore = 70.0, genericFloor = 15.0,
        nativeQualified8213 = true, nativeSetup8213 = "BREAKOUT_RUNNER", liveEdge8114 = "CHART_BUY",
    )

    @Test fun liveRefusesAnUnprovenBuyIntoTheHigh() {
        for (lane in listOf("MOONSHOT", "SHITCOIN", "EXPRESS", "QUALITY", "CASHGEN")) {
            val d = CandidateAdmission8136.decide8136(markup(lane))
            assertEquals(lane, CandidateAdmission8136.Terminal8136.REFUSE, d.terminal)
            assertEquals(lane, CandidateAdmission8136.LIVE_INTO_HIGH_UNPROVEN_8545, d.reason)
            assertTrue(d.trace.any { it.startsWith("liveIntoHigh8545 stage=CONTROLLED_MARKUP") })
        }
        assertEquals(CandidateAdmission8136.LIVE_INTO_HIGH_UNPROVEN_8545,
            CandidateAdmission8136.decide8136(markup().copy(stage = "PEAK_EXHAUSTION")).reason)
    }

    @Test fun aSupportedStageRecordOrPaperOrADemotedDoorStillAdmits() {
        val proven = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_STAGE, "CONTROLLED_MARKUP", 60, 9.0, 1.0)
        assertTrue("the stage's own supported record carries LIVE",
            CandidateAdmission8136.decide8136(markup().copy(stageLoss8169 = proven, lossEpochAware8169 = true)).reason != CandidateAdmission8136.LIVE_INTO_HIGH_UNPROVEN_8545)
        assertTrue("paper keeps entering and grading these coins",
            CandidateAdmission8136.decide8136(markup().copy(live = false)) { false }.reason != CandidateAdmission8136.LIVE_INTO_HIGH_UNPROVEN_8545)
        assertTrue("a door its refused record has demoted no longer binds",
            CandidateAdmission8136.decide8136(markup()) { it != CandidateAdmission8136.LIVE_INTO_HIGH_UNPROVEN_8545 }.reason != CandidateAdmission8136.LIVE_INTO_HIGH_UNPROVEN_8545)
    }

    @Test fun onlyMarkupAndPeakAreIntoTheHigh() {
        assertTrue(CandidateAdmission8136.liveIntoHigh8545("CONTROLLED_MARKUP"))
        assertTrue(CandidateAdmission8136.liveIntoHigh8545("peak_exhaustion"))
        for (s in listOf("DUMPING", "BASE_START", "MID_ACCUMULATION", "FRESH_LAUNCH", "NO_SETUP", "UNKNOWN", "", null))
            assertFalse("$s", CandidateAdmission8136.liveIntoHigh8545(s))
        val adm = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue("before the lane-opinion admit", adm.indexOf("if (c.live && liveIntoHigh8545(c.stage)") < adm.indexOf("if (nativeLive8259 && c.live) {"))
    }

    @Test fun aBackedOffMintEndsTheTicketInsteadOfRetryingTheStaleDecision() {
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("lastLiveBuyStop8252[ts.mint] = if (buyLease.reason == \"BACKOFF_ACTIVE\") \"BUY_LEASE_BACKOFF_ENDS_TICKET_8545\""))
        assertEquals(null, TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:BUY_LEASE_BACKOFF_ENDS_TICKET_8545"))
        assertEquals("a busy lease still holds", "BUY_LEASE_DEFERRED_8496",
            TicketRetry8327.transientCause8327("LIVE_BUY_NOT_OPENED_7948:BUY_LEASE_DEFERRED_8496"))
    }
}

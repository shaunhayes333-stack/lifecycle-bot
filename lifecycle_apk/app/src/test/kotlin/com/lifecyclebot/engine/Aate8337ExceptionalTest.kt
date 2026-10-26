package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EvidenceHold8336
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.8337 — owner: "obviously if it sees a 100x opportunity take it but for the most part yes it will be kind of quiet."
 * The evidence hold (8336) lets ONE kind of live entry through an unproven lane: an exceptional runner opportunity whose
 * four legs are all provable now (runner grab, exit-safe venue, top run-odds bucket / early-run evidence, lane not shut by
 * its realised losses), bounded to one per lane per 30 minutes and three an hour across lanes.
 */
class Aate8337ExceptionalTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun fresh() { EvidenceHold8336.resetForTests8337() }

    @After fun back() {
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = false)
        EvidenceHold8336.enabled8336 = true
        EvidenceHold8336.resetForTests8337()
    }

    @Test fun everyLegIsRequired() {
        val venue = EvidenceHold8336.VENUE_ACTIVE_CURVE_8337
        val odds = EvidenceHold8336.ODDS_CORTEX_STRONG_8337
        assertNull("no runner grab", EvidenceHold8336.exceptionalReason8337(false, venue, odds, true))
        assertNull("no exit-safe venue", EvidenceHold8336.exceptionalReason8337(true, null, odds, true))
        assertNull("blank venue", EvidenceHold8336.exceptionalReason8337(true, "", odds, true))
        assertNull("not in the top run-odds bucket", EvidenceHold8336.exceptionalReason8337(true, venue, null, true))
        assertNull("lane shut by its realised live losses", EvidenceHold8336.exceptionalReason8337(true, venue, odds, false))
        val r = EvidenceHold8336.exceptionalReason8337(true, venue, odds, true)
        assertNotNull(r)
        assertTrue(r!!, r.contains("grab=RUNNER_GRAB_7967") && r.contains("venue=$venue") && r.contains("odds=$odds") && r.contains("lane=OPEN"))
        val noBook = EvidenceHold8336.exceptionalReason8337(true, EvidenceHold8336.VENUE_POOL_FLOOR_8337, EvidenceHold8336.ODDS_NO_BOOK_EARLY_RUN_8337, true)
        assertTrue("a lane without a run-odds book says so in the reason", noBook != null && noBook.contains("NO_RUN_ODDS_BOOK"))
    }

    @Test fun oneExceptionPerLanePerThirtyMinutes() {
        val t0 = 1_000_000_000L
        assertTrue(EvidenceHold8336.rateAllows8337("SHITCOIN", t0))
        EvidenceHold8336.noteException8337("SHITCOIN", "mintA", "r", t0)
        assertFalse("same lane 10 min later", EvidenceHold8336.rateAllows8337("SHITCOIN", t0 + 10 * 60_000L))
        assertFalse("same lane just under the gap", EvidenceHold8336.rateAllows8337("SHITCOIN", t0 + EvidenceHold8336.LANE_GAP_MS_8337 - 1L))
        assertTrue("another lane is free", EvidenceHold8336.rateAllows8337("MOONSHOT", t0 + 10 * 60_000L))
        assertTrue("same lane after the gap", EvidenceHold8336.rateAllows8337("SHITCOIN", t0 + EvidenceHold8336.LANE_GAP_MS_8337))
        assertEquals(1L, EvidenceHold8336.exceptionCount8337())
    }

    @Test fun atMostThreeAnHourAcrossLanes() {
        val t0 = 2_000_000_000L
        EvidenceHold8336.noteException8337("SHITCOIN", "m1", "r", t0)
        EvidenceHold8336.noteException8337("MOONSHOT", "m2", "r", t0 + 60_000L)
        EvidenceHold8336.noteException8337("EXPRESS", "m3", "r", t0 + 120_000L)
        assertFalse("a fourth lane inside the hour", EvidenceHold8336.rateAllows8337("PROJECT_SNIPER", t0 + 180_000L))
        assertFalse("the first lane again after its own gap but still inside the hour cap", EvidenceHold8336.rateAllows8337("SHITCOIN", t0 + 40 * 60_000L))
        assertTrue("an hour after the first grant one slot frees", EvidenceHold8336.rateAllows8337("PROJECT_SNIPER", t0 + 3_600_000L))
        assertEquals(3L, EvidenceHold8336.exceptionCount8337())
        assertEquals(3, EvidenceHold8336.MAX_PER_HOUR_8337)
        assertEquals(30 * 60_000L, EvidenceHold8336.LANE_GAP_MS_8337)
    }

    @Test fun theAdmittedCandidatePassesTheMintlessCallForSixtySeconds() {
        val t0 = 3_000_000_000L
        EvidenceHold8336.noteException8337("SHITCOIN", "mintX", "grab=RUNNER_GRAB_7967", t0)
        assertEquals("grab=RUNNER_GRAB_7967", EvidenceHold8336.admitted8337("mintX", "SHITCOIN", t0 + 59_000L))
        assertNull("another mint is not admitted", EvidenceHold8336.admitted8337("mintY", "SHITCOIN", t0 + 1_000L))
        assertNull("after 60 s the pass is gone", EvidenceHold8336.admitted8337("mintX", "SHITCOIN", t0 + EvidenceHold8336.PASS_TTL_MS_8337 + 1_000L))
        assertTrue(EvidenceHold8336.laneAdmitted8337("SHITCOIN", t0 + 59_000L))
        assertFalse(EvidenceHold8336.laneAdmitted8337("MOONSHOT", t0 + 1_000L))
        assertFalse(EvidenceHold8336.laneAdmitted8337("SHITCOIN", t0 + 61_000L))
        // The old-signature (TradePlan) call while LIVE and held: the admitted lane passes, another lane is still refused.
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        EvidenceHold8336.enabled8336 = true
        assertNull(EvidenceHold8336.refusal8336("SHITCOIN", paper = false, nowMs = t0 + 30_000L))
        val other = EvidenceHold8336.refusal8336("MOONSHOT", paper = false, nowMs = t0 + 30_000L)
        assertTrue("MOONSHOT has no pass and no paper proof: $other", other == null || other == "EVIDENCE_HOLD_8336_MOONSHOT")
        assertEquals("EVIDENCE_HOLD_8336_SHITCOIN", EvidenceHold8336.refusal8336("SHITCOIN", paper = false, nowMs = t0 + 61_000L))
    }

    @Test fun aCandidateNobodyIsGrabbingIsNoException() {
        val ts = TokenState(mint = "mint8337-unknown", symbol = "NOPE")
        assertNull(EvidenceHold8336.exceptional8336(ts, "SHITCOIN", 4_000_000_000L))
        assertEquals(0L, EvidenceHold8336.exceptionCount8337())
        assertTrue(EvidenceHold8336.statusLine8336(4_000_000_000L).contains("exceptions=0 last=-"))
    }

    @Test fun decide8326ConsultsTheExceptionBeforeTheHoldRefuses() {
        val gate=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("canonicalRefusal8392(ts, l, paper, nowMs, candidateVersion, candidateAdmission)"))
        assertTrue(gate.contains("FdgGate8365.inContext8365(ts, l, basis)"))
        assertTrue(gate.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs)"))
        assertTrue(gate.contains("if (paper && final == null) Rehearsal8339.notePaperAdmit8342"))
        assertFalse(gate.contains("entryRefusal8114"))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.LaneAuthority8577
import com.lifecyclebot.engine.truth.LaunchSniper8748
import com.lifecyclebot.engine.truth.SignalReplay8828
import com.lifecyclebot.engine.truth.SignalTape8828
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9025 — a launch is bought in the seconds it pays: an armed fire inside its graded window is not stopped by a learned
 * short-term refusal, every armed fire's seconds are kept, the quiet open joins the tape for the phone's replay to grade,
 * and the +200 % ledger books a close once.
 */
class Aate9025LaunchSecondsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun cand(live: Boolean = true) =
        CandidateAdmission8136.Candidate8136(mint = "Fire9025AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", lane = "MOONSHOT", selectedSetup = "IGNITION_5M",
            costPct = 4.0, live = live)
    private fun refused(reason: String, net: Double = -1.0, trace: List<String> = listOf("t")) =
        CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.REFUSE, reason, true, "IGNITION_5M", net, trace)

    @Test fun anArmedFireInsideItsWindowIsNotStoppedByALearnedRefusal() {
        for (r in listOf("EXECUTION_ECONOMICS_8136", "TIMING_RECORD_NEGATIVE_8094", "CHASING_SPIKE_8065", "LIVE_SOURCE_PROVEN_LOSING_8536",
            "LANE_REALISED_LOSING_PROBE_OCCUPIED_8424", "NO_PLAN_WAIT_7739:CURRENT_STAGE_PULLBACK_TOO_DEEP", "CANONICAL_V3_SCORE_FLOOR_7243")) {
            val d = LaneAuthority8577.fireDecision9025(cand(), refused(r), 6.3)
            assertTrue(r, d.admitted); assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
            assertEquals("the fire's record, net of cost, is the expected net", 6.3, d.expectedNetPct, 0.0)
            assertTrue(d.trace.any { it.startsWith(LaneAuthority8577.FIRE_TRACE_9025) && it.contains("advisory=$r") })
        }
        assertEquals("a better own figure is kept", 9.0, LaneAuthority8577.fireDecision9025(cand(), refused("CHASING_SPIKE_8065", 9.0), 6.3).expectedNetPct, 0.0)
    }

    @Test fun safetyTheDoorsProofsTheHighAndDataStillStopIt() {
        for (r in listOf("CANDIDATE_ADMISSION_8136_SAFETY_REFUSED_8136", "TOP_HOLDER_CONCENTRATION", "LP_OWNERSHIP_PROOF_PENDING_8522",
            "RUGCHECK_PENDING_OR_UNKNOWN", "DATA_INCOMPLETE_DEFER_8136", "MEME_BUYS_THE_HIGH_8582", "KILL_SWITCH_EQUITY_UNAVAILABLE_7835", "SOME_OTHER_RULE")) {
            assertFalse(r, LaneAuthority8577.fireAdvisory9025(r))
            assertFalse(r, LaneAuthority8577.fireDecision9025(cand(), refused(r), 6.3).admitted)
        }
        assertFalse(LaneAuthority8577.fireAdvisory9025(null)); assertFalse(LaneAuthority8577.fireAdvisory9025(""))
    }

    @Test fun withoutALiveArmedFireNothingChanges() {
        val d0 = refused("EXECUTION_ECONOMICS_8136")
        assertEquals("no fire", d0, LaneAuthority8577.fireDecision9025(cand(), d0, null))
        assertEquals("a record that is not positive", d0, LaneAuthority8577.fireDecision9025(cand(), d0, -0.4))
        assertEquals(d0, LaneAuthority8577.fireDecision9025(cand(), d0, Double.NaN))
        assertEquals("paper is not changed", d0, LaneAuthority8577.fireDecision9025(cand(live = false), d0, 6.3))
        assertEquals("a coin with no fire reads no record", d0, LaneAuthority8577.fireDecision9025(cand(), d0))
        val pick = refused("EXECUTION_ECONOMICS_8136", trace = listOf("${LaneAuthority8577.PICK_TRACE_9022} pred=12.0"))
        assertEquals("already the model's pick", pick, LaneAuthority8577.fireDecision9025(cand(), pick, 6.3))
        assertNull(LaunchSniper8748.fireRecord9025("NoFire9025AAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", System.currentTimeMillis()))
    }

    @Test fun theGatePassesOnlyAnAdmittedFiresLearnedRefusal() {
        val admitted = LaneAuthority8577.fireDecision9025(cand(), refused("EXECUTION_ECONOMICS_8136"), 6.3)
        assertTrue(LaneAuthority8577.firePassesAtGate9025("CHASING_SPIKE_8065", admitted))
        assertFalse(LaneAuthority8577.firePassesAtGate9025("MEME_BUYS_THE_HIGH_8582", admitted))
        assertFalse("not a fire", LaneAuthority8577.firePassesAtGate9025("CHASING_SPIKE_8065", refused("X")))
        assertFalse(LaneAuthority8577.firePassesAtGate9025("CHASING_SPIKE_8065", null))
        assertTrue(LaneAuthority8577.fireLine9025().startsWith("launchFires9025[{"))
        val auth = src("engine/truth/LaneAuthority8577.kt")
        assertTrue(auth.contains("val d = fireDecision9025(c, pickDecision9022(c, d0))"))
        assertTrue(auth.contains("firePassesAtGate9025(reason, admission))"))
    }

    @Test fun everyArmedFiresSecondsAreKept() {
        val s = LaunchSniper8748
        s.resetClocksForTest9025()
        val m = "Clock9025AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"; val t0 = 1_795_630_000_000L
        s.noteAdmission9025(m, "ADMIT", "SPECIFIC_EDGE_PROVEN_8136", t0 + 1_000L)   // no fire on this coin: nothing kept
        assertTrue(s.clockLine9025(t0).contains("fires=0 "))
        s.openClockForTest9025(m, t0, 20_000L)
        s.noteAdmission9025(m, "REFUSE", "CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136: gross=3 cost=5", t0 + 2_000L)
        s.noteAdmission9025(m, "ADMIT", "SPECIFIC_EDGE_PROVEN_8136", t0 + 5_000L)
        s.noteGate9025(m, false, "CHASING_SPIKE_8065", t0 + 6_000L)
        var line = s.clockLine9025(t0 + 300_000L)
        assertTrue(line, line.contains("fires=1 admittedInWindow=1 gateAllowInWindow=0 landed=0 landedInWindow=0"))
        assertTrue(line, line.contains("adm+2s:REFUSE:EXECUTION_ECONOMICS_8136"))
        assertTrue(line, line.contains("admit+5s") && line.contains("gate+6s:CHASING_SPIKE_8065"))
        assertTrue(line, line.contains("ended{GATE:CHASING_SPIKE_8065=1}"))
        s.noteGate9025(m, true, null, t0 + 9_000L)
        line = s.clockLine9025(t0 + 300_000L)
        assertTrue(line, line.contains("gateAllowInWindow=1") && line.contains("ended{GATE_ALLOWED_NOT_LANDED=1}"))
        // an admission after the window is not "in window"
        s.openClockForTest9025(m, t0, 20_000L)
        s.noteAdmission9025(m, "ADMIT", "SPECIFIC_EDGE_PROVEN_8136", t0 + 60_000L)
        line = s.clockLine9025(t0 + 300_000L)
        assertTrue(line, line.contains("admittedInWindow=0") && line.contains("ended{ADMITTED_NO_GATE_VERDICT=1}"))
        assertEquals("EXECUTION_ECONOMICS_8136", s.clockWhy9025("CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136: x"))
        assertEquals("UNNAMED", s.clockWhy9025(null))
        s.resetClocksForTest9025()
        assertTrue(src("engine/truth/CandidateAdmission8136.kt").contains("LaunchSniper8748.noteAdmission9025(ts.mint, it.terminal.name, it.reason, nowMs,"))
        assertTrue(src("engine/SpecialistOwnership7951.kt").contains("LaunchSniper8748.noteGate9025(mint, shouldTrade, blockReason, nowMs, lane, candidateVersion)"))
        assertTrue(src("engine/truth/LaunchSniper8748.kt").contains("try { openClock9025(f) } catch"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("LaunchSniper8748.clockLine9025()"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("LaneAuthority8577.fireLine9025()"))
    }

    /** Feeds a first bar of [buys] buys ending at [closeX] times the first print, then one print at [nextMs]. */
    private fun quiet(buys: Int, closeX: Double, nextMs: Long = 10_400L, live: Boolean = true): List<String> {
        val t = SignalTape8828()
        for (i in 0 until buys) t.onPrint(500L + i * 800L, if (i == buys - 1) closeX else 1.0 + 0.01 * i, true, 0.2, -1L, true)
        if (buys == 0) t.onPrint(500L, closeX, false, 0.1, -1L, true)
        return t.onPrint(nextMs, closeX, true, 0.2, -1L, live)
    }

    @Test fun theQuietOpenIsTheFirstBarUnderTenBuysAndUnderOneAndAHalfTimesItsFirstPrint() {
        val id = SignalTape8828.QUIET_OPEN_9025
        assertEquals("O9025A", id)
        assertTrue(id in SignalTape8828.IDS); assertFalse("bars alone: no wallet needed", id in SignalTape8828.WALLET_IDS)
        assertEquals(10, SignalTape8828.QUIET_OPEN_MAX_BUYS_9025); assertEquals(1.5, SignalTape8828.QUIET_OPEN_MAX_OVER_9025, 0.0)
        assertTrue(id in quiet(4, 1.2))
        assertTrue(id in quiet(9, 1.5))
        assertFalse("a crowd is already in", id in quiet(10, 1.2))
        assertFalse("already run", id in quiet(4, 1.6))
        assertFalse("the next print came a bar late: no longer the open", id in quiet(4, 1.2, nextMs = 21_000L))
        assertFalse("a late print fires nothing", id in quiet(4, 1.2, live = false))
        // it fires once, at the first bar's close only
        val t = SignalTape8828()
        for (i in 0 until 3) t.onPrint(500L + i * 800L, 1.0, true, 0.2, -1L, true)
        assertTrue(id in t.onPrint(10_400L, 1.0, true, 0.2, -1L, true))
        assertFalse(id in t.onPrint(20_400L, 1.0, true, 0.2, -1L, true))
    }

    @Test fun aFirstSecondsFireIsEvidenceForItsGradedDelayPlusOneBar() {
        assertEquals(setOf("O9025A"), SignalTape8828.FAST_IDS_9025)
        assertEquals(10_000L, SignalReplay8828.FAST_SLACK_MS_9025)
        assertEquals(23_000L, SignalReplay8828.windowFor9025("O9025A", 13_000L))
        assertEquals("never graded: the default window", 0L, SignalReplay8828.windowFor9025("O9025A", 0L))
        assertEquals("the other signals keep their window", 0L, SignalReplay8828.windowFor9025("O9010A", 13_000L))
        val sniper = src("engine/truth/LaunchSniper8748.kt")
        assertTrue(sniper.contains("gradedDelayMs8968 = SignalReplay8828.windowFor9025(rec.id, rec.gradedDelayMs9047)"))
        assertTrue("the replay delay is frozen separately from the signal's evidence window",
            sniper.contains("proofDelayMs9047 = rec.gradedDelayMs9047"))
    }

    @Test fun aCloseIsBookedOnceAcrossTheBusReplay() {
        val h = DegenHarvest9011
        fun reached() = Regex("reached\\+200%=(\\d+)").find(h.statusLine9011())?.groupValues?.get(1)?.toLong() ?: 0L
        val before = reached()
        val id = "trade-9025-${System.nanoTime()}"
        h.onClose9011("LIVE", true, "pos-a", "HARD_STOP", -31.0, 420.0, 240_000L, id, 1_795_628_000_000L)
        h.onClose9011("LIVE", true, "pos-a", "HARD_STOP", -31.0, 420.0, 240_000L, id, 1_795_628_000_000L)   // the start-up replay
        h.onClose9011("LIVE", true, "pos-a", "HARD_STOP", -31.0, 420.0, 240_000L, id, 1_795_628_000_000L)
        assertEquals(before + 1L, reached())
        assertTrue(h.statusLine9011().contains("recent9025{"))
        assertEquals("DegenHarvest9011.close", h.LEARNER_9025)
        val row = h.row9025(1_795_628_000_000L, 420.0, -31.0, "HARD_STOP", 240_000L)
        assertTrue(row, Regex("^\\d{4}-\\d{2} peak\\+420% banked-31% HARD_STOP 4m$").matches(row))
        assertEquals("- peak+417% banked+81% SPIKE_CAPTURE_T -", h.row9025(0L, 417.0, 80.8, "SPIKE_CAPTURE_7943_T2_417PCT", 0L))
        val b = DegenHarvest9011.Book9011()
        repeat(h.RECENT_MAX_9025 + 5) { b.add(300.0 + it, 20.0, "HARD_STOP", false, 60_000L, 1_795_628_000_000L) }
        assertEquals(h.RECENT_MAX_9025, b.recent.size)
        val r = DegenHarvest9011.Book9011().also { it.decode(b.encode()) }
        assertEquals(b.line(), r.line())
        val srcH = src("engine/DegenHarvest9011.kt")
        assertTrue(srcH.contains("LearnerAttribution8418.admit8418(LEARNER_9025, tradeId.ifBlank { positionId }, mode)"))
        assertTrue(srcH.contains("\"degen_harvest_ledger_9025\""))
    }
}

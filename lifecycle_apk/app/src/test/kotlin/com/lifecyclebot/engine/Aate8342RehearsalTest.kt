package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.Rehearsal8339
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
 * 8342 — in PAPER under rehearsal the label / record EVIDENCE doors are advisory (recorded, the decision continues);
 * hard safety, chase and economics bind in both books; LIVE is untouched. The hold reads both records per meme lane.
 */
class Aate8342RehearsalTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Before fun fresh() {
        Rehearsal8339.resetForTests8342()
        EvidenceHold8336.enabled8336 = true
        EvidenceHold8336.rehearsal8336 = true
    }

    @After fun back() {
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = false)
        EvidenceHold8336.enabled8336 = true
        EvidenceHold8336.rehearsal8336 = true
        Rehearsal8339.resetForTests8342()
    }

    private val evidence = listOf(
        "LIVE_PROVEN_SETUP_ONLY_8059", "PICKED_SETUP_RECORD_NEGATIVE_8094", "TIMING_RECORD_NEGATIVE_8094",
        "TACTIC_RECORD_LOSING_8094", "WATCH_FIRST_7994_CELL_NEGATIVE", "WATCH_FIRST_7994_THIN_CELL",
        "WATCH_FIRST_8019_BAND_NEGATIVE", "CORTEX_7885_C6_SETUP_DEAD_8082_SHITCOIN", "REPEAT_LOSER_8022",
        "PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_LAUNCH_CONTINUATION", "PLAYBOOK_NO_TRIGGER_PROVEN_LOSING_7907_EXPRESS",
        "PLAYBOOK_SETUP_EXPECTED_NEGATIVE_7948_SHITCOIN_X", "SHEET_AVOID_8323", "CELL_PROVEN_LOSING_8136",
        "STAGE_PROVEN_LOSING_8136", "SETUP_PROVEN_LOSING_8136", "STAGE_PROVEN_LOSING_7928_SHITCOIN_EARLY",
        "LIVE_LANE_LABELS_NOT_POSITIVE_8325",
    )
    private val binding = listOf(
        "CHASING_SPIKE_8065", "SWING_LANE_ALREADY_RAN_8054", "NET_EDGE_COIN_TOO_SLOW_8035", "NET_EDGE_BELOW_2X_COST_8030",
        "NON_ECONOMIC_APPROVAL_7835", "SIZE_NOT_EXECUTABLE_7835", "EXECUTION_ECONOMICS_8136", "CURVE_NOBODY_BOUGHT_8046",
        "CURVE_EXIT_SAFETY_8320", "CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136", "SAFETY_REFUSED_RUG", "FREEZE_AUTHORITY",
        "TOKEN_2022_HOSTILE", "LANE_LIQ_BELOW_FLOOR", "EXIT_SAFE_FLOOR", "WALLET_BLIND_8074", "REBUY_LOCKOUT_8019",
        "FALLING_KNIFE_8130", "BIG_LOSER_8130", "MAYHEM_7943", "COST_PRIOR_7962_SHITCOIN", "EDGE_7877_SHAPE_7883",
        "LIVE_SETUP_LOSING_8083", "EVIDENCE_HOLD_8336_SHITCOIN", "DATA_INCOMPLETE_DEFER_8136", "CANONICAL_ENTRY_EVAL_FAILED_8392",
    )

    @Test fun doorClassificationEvidenceVersusBinding() {
        for (r in evidence) assertTrue(r, Rehearsal8339.evidenceDoor8342(r))
        for (r in binding) assertFalse(r, Rehearsal8339.evidenceDoor8342(r))
        assertFalse(Rehearsal8339.evidenceDoor8342(null))
        assertFalse(Rehearsal8339.evidenceDoor8342(""))
        // a hard marker inside an evidence-looking reason binds
        assertFalse(Rehearsal8339.evidenceDoor8342("PLAYBOOK_SETUP_RUG_PRONE"))
    }

    @Test fun paperUnderRehearsalLetsEvidenceThroughLiveStillRefuses() {
        for (r in evidence) {
            assertFalse("paper+rehearsal: $r advisory", Rehearsal8339.evidenceDoorBinds8342(paper = true, rehearsal = true, reason = r))
            assertTrue("live: $r binds", Rehearsal8339.evidenceDoorBinds8342(paper = false, rehearsal = true, reason = r))
            assertTrue("paper, rehearsal off: $r binds", Rehearsal8339.evidenceDoorBinds8342(paper = true, rehearsal = false, reason = r))
        }
        for (r in binding) {
            assertTrue("paper+rehearsal: $r binds", Rehearsal8339.evidenceDoorBinds8342(paper = true, rehearsal = true, reason = r))
            assertTrue("live: $r binds", Rehearsal8339.evidenceDoorBinds8342(paper = false, rehearsal = true, reason = r))
        }
    }

    @Test fun paperRuntimeRecordsTheAdvisoryDoorAndContinues() {
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        val (verdict, advisories) = Rehearsal8339.collecting8342 {
            val a = Rehearsal8339.doorBindsNow8342("LIVE_PROVEN_SETUP_ONLY_8059", "SHITCOIN")
            val b = Rehearsal8339.doorBindsNow8342("WATCH_FIRST_7994_CELL_NEGATIVE", "SHITCOIN")
            val c = Rehearsal8339.doorBindsNow8342("CHASING_SPIKE_8065", "SHITCOIN")
            Triple(a, b, c)
        }
        assertFalse("proven-only is advisory in paper", verdict.first)
        assertFalse("watch-first is advisory in paper", verdict.second)
        assertTrue("the chase rule binds in paper", verdict.third)
        assertEquals(listOf("LIVE_PROVEN_SETUP_ONLY_8059", "WATCH_FIRST_7994_CELL_NEGATIVE"), advisories)
    }

    @Test fun liveRuntimeBindsEveryDoor() {
        RuntimeModeAuthority.publishConfig(paperMode = false, autoTrade = true)
        val (bound, advisories) = Rehearsal8339.collecting8342 {
            evidence.all { Rehearsal8339.doorBindsNow8342(it, "MOONSHOT") }
        }
        assertTrue(bound)
        assertTrue(advisories.isEmpty())
    }

    @Test fun paperEntryTagFollowsItsCloseAndSurvivesEncoding() {
        RuntimeModeAuthority.publishConfig(paperMode = true, autoTrade = true)
        val ts = TokenState(mint = "mint8342-a", symbol = "AAA")
        Rehearsal8339.notePaperAdmit8342(ts, "SHITCOIN", listOf("LIVE_PROVEN_SETUP_ONLY_8059", "WATCH_FIRST_7994_THIN_CELL"), 1_000L)
        val tag = Rehearsal8339.entryTag8342("mint8342-a", "SHITCOIN", 5_000L)
        assertNotNull(tag)
        assertEquals("LIVE_PROVEN_SETUP_ONLY_8059", tag!!.liveWouldRefuse)
        assertEquals(Rehearsal8339.LIVE_REFUSE_8342, Rehearsal8339.closeTag8342(tag))
        assertEquals(Rehearsal8339.LIVE_REFUSE_8342, Rehearsal8339.tagPaperClose8342("row1", "mint8342-a", "SHITCOIN", 5_000L))
        // the live policy would have admitted this one
        Rehearsal8339.notePaperAdmit8342(TokenState(mint = "mint8342-b", symbol = "BBB"), "MOONSHOT", emptyList(), 2_000L)
        assertEquals(Rehearsal8339.LIVE_ADMIT_8342, Rehearsal8339.tagPaperClose8342("row2", "mint8342-b", "MOONSHOT", 9_000L))
        // an entry nobody decided here is untagged; a close before the entry is not that entry's close
        assertEquals(Rehearsal8339.UNTAGGED_8342, Rehearsal8339.tagPaperClose8342("row3", "mint8342-zzz", "SHITCOIN", 9_000L))
        assertNull(Rehearsal8339.entryTag8342("mint8342-b", "MOONSHOT", 1_000L))
        val row = Rehearsal8339.encodeTag8342("mint8342-a|SHITCOIN", Rehearsal8339.EntryTag8342(1_000L, "LIVE_PROVEN_SETUP_ONLY_8059"))
        val back = Rehearsal8339.decodeTag8342(row)
        assertEquals("mint8342-a|SHITCOIN", back!!.first)
        assertEquals(1_000L, back.second.atMs)
        assertEquals("LIVE_PROVEN_SETUP_ONLY_8059", back.second.liveWouldRefuse)
        assertNull(Rehearsal8339.decodeTag8342(Rehearsal8339.encodeTag8342("k", Rehearsal8339.EntryTag8342(7L, null)))!!.second.liveWouldRefuse)
        assertNull(Rehearsal8339.decodeTag8342("garbage"))
    }

    @Test fun theHoldReadsOnlyMemeLanesAndShowsBothRecords() {
        assertTrue(EvidenceHold8336.heldLane8342("SHITCOIN"))
        assertTrue(EvidenceHold8336.heldLane8342("MOONSHOT"))
        assertTrue(EvidenceHold8336.heldLane8342("core"))
        assertFalse("CRYPTO_ALT is an LLM desk, not a held meme lane", EvidenceHold8336.heldLane8342("CRYPTO_ALT"))
        assertFalse(EvidenceHold8336.heldLane8342("CRYPTO_MAJOR"))
        val all = EvidenceHold8336.proof8336(List(40) { if (it % 4 == 0) -8.0 else 15.0 }, List(40) { if (it % 4 == 0) -0.008 else 0.015 })
        val admit = EvidenceHold8336.proof8336(List(5) { 3.0 }, List(5) { 0.003 })
        val line = EvidenceHold8336.laneLine8342("SHITCOIN", EvidenceHold8336.LaneRecord8342(all, admit, liveRefuse = 35, untagged = 0))
        assertTrue(line, line.startsWith("SHITCOIN all n40/30"))
        assertTrue(line, line.contains(" LIVE · liveAdmit n5/30"))
        assertTrue(line, line.contains("liveRefuse35 untagged0"))
        val status = EvidenceHold8336.statusLine8336(4_000_000_000L)
        assertTrue(status, status.contains("bar=all,n>=30,mean-se>2.0%,pf>=1.3"))
        assertTrue(status, status.contains("lanes=${com.lifecyclebot.engine.truth.EvidenceHold8336.HELD_LANES_8392.size}"))
    }

    @Test fun paperAdmissionNeverReadsAsLive() {
        assertEquals("PAPER_REHEARSAL", Rehearsal8339.assessmentSource8342(paper = true))
        assertEquals("LIVE", Rehearsal8339.assessmentSource8342(paper = false))
        val k = CandidateAdmission8136.ledgerKey8136(Rehearsal8339.assessmentSource8342(true), CandidateAdmission8136.Terminal8136.REFUSE, "LIVE_PROVEN_SETUP_ONLY_8059")
        assertTrue(k, k.startsWith("PAPER_REHEARSAL|REFUSE:"))
        assertFalse(k, k.startsWith("LIVE|"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("CandidateAdmission8136.noteVerdict8136(Rehearsal8339.assessmentSource8342(paper8342), verdict.name, reason)"))
        assertFalse(gate.contains("CandidateAdmission8136.noteVerdict8136(\"LIVE\", verdict.name, reason)"))
        val admission = src("engine/truth/CandidateAdmission8136.kt")
        assertTrue(admission.contains("live = try { !com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false },"))
        assertTrue(admission.contains("val mode = try { if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) \"PAPER\" else \"LIVE\" } catch (_: Throwable) { \"UNKNOWN\" }"))
    }

    @Test fun hookPointsArePinned() {
        val gate=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("canonicalRefusal8392(ts, l, paper, nowMs, candidateVersion, candidateAdmission)"))
        assertTrue(gate.contains("FdgGate8365.inContext8365(ts, l, basis)"))
        assertTrue(gate.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs)"))
        assertTrue(gate.contains("if (paper && final == null) Rehearsal8339.notePaperAdmit8342"))
        assertFalse(gate.contains("entryRefusal8114"))
    }

    @Test fun aFlatMarkIsALostMarkAtEverySlot() {
        val labeler = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(labeler.contains("else if (flatMarkNow8342(o, gross)) flatMarks8322.incrementAndGet() else captured7945(o, net, gross).let { (n, g) -> book(o, 15, n, g) }"))
        val twoForty = labeler.substringAfter("private fun bookTwoForty7944(").substringBefore("private fun flatMarkNow8342(")
        assertTrue(twoForty.contains("if (flatMarkNow8342(o, gross0)) { flatMarks8322.incrementAndGet(); markLost(o); return }"))
        assertTrue(twoForty.indexOf("flatMarkNow8342(o, gross0)") < twoForty.indexOf("book(o, 240, net, gross)"))
        assertTrue(labeler.contains("flatLostMarks8342=${'$'}{flatMarks8322.get()}"))
        assertFalse(labeler.contains("flatLabels8322="))
        // the Cortex books nothing on a flat mark (8322); the 60 slot is a lost mark (8337 FIX 2)
        assertTrue(labeler.contains("markLost(o)                       // counted as lost mark, never as a -cost label"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("if (flat) synchronized(this) { gradeBook8144.markFlat8322(\"${'$'}key|${'$'}{p.atMs}\") }"))
    }
}

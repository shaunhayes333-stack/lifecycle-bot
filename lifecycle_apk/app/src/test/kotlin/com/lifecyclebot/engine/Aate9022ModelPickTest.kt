package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9022 — the model's pick is bought: a LIVE candidate the model calls POSITIVE with a forecast of +5 % or more is not
 * stopped by a learned short-term refusal at the admission or the gate. Safety, structure, the high and critical data bind.
 */
class Aate9022ModelPickTest {
    private fun verdict(pred: Double, authority: Boolean = true) =
        EdgeModel8537.Verdict(pred, authority, "MIND8842", 25_000L, 500, 12.0, 2.0, 0.5)
    private fun cand(pred: Double, live: Boolean = true, authority: Boolean = true) =
        CandidateAdmission8136.Candidate8136(mint = "Pick9022AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", lane = "MOONSHOT", selectedSetup = "IGNITION_5M",
            costPct = 4.0, live = live, edge8537 = verdict(pred, authority))
    private fun refused(reason: String, net: Double = -1.0) =
        CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.REFUSE, reason, true, "IGNITION_5M", net, listOf("t"))

    @Test fun whatIsAPick() {
        assertTrue(LaneAuthority8577.modelPick9022(cand(5.0))); assertTrue(LaneAuthority8577.modelPick9022(cand(22.0)))
        assertFalse("a forecast under +5 % is not a pick", LaneAuthority8577.modelPick9022(cand(4.9)))
        assertFalse("paper is not changed", LaneAuthority8577.modelPick9022(cand(22.0, live = false)))
        assertFalse("a forecast with no authority behind it is not a pick", LaneAuthority8577.modelPick9022(cand(22.0, authority = false)))
        assertFalse(LaneAuthority8577.modelPick9022(cand(-3.0)))
        assertEquals(5.0, LaneAuthority8577.PICK_MIN_PRED_PCT_9022, 0.0)
    }

    @Test fun aLearnedShortTermRefusalDoesNotStopAPick() {
        for (r in listOf("EXECUTION_ECONOMICS_8136", "SHEET_AVOID_8323", "CELL_PROVEN_LOSING_8136", "SETUP_RECORD_LOSING_8136",
            "LIVE_SETUP_LOSES_8714", "TIMING_ADVERSE_8136", "EXECUTION_NET_NONPOSITIVE_8629")) {
            val d = LaneAuthority8577.pickDecision9022(cand(12.0), refused(r))
            assertTrue(r, d.admitted); assertEquals(CandidateAdmission8136.EDGE_MODEL_PROVEN_8537, d.reason)
            assertEquals("the forecast is the expected net the sizing reads", 12.0, d.expectedNetPct, 0.0)
            assertTrue(d.trace.any { it.startsWith(LaneAuthority8577.PICK_TRACE_9022) && it.contains("advisory=$r") })
        }
        for (r in listOf("TIMING_RECORD_NEGATIVE_8094", "LANE_REALISED_LOSING_PROBE_OCCUPIED_8424", "NET_EDGE_COIN_TOO_SLOW_8035",
            "CHASING_SPIKE_8065", "WATCH_FIRST_7994", "STAGE_PROVEN_LOSING_7928_CORE_PEAK", "CANDIDATE_ADMISSION_8136_SHEET_AVOID_8323"))
            assertTrue(r, LaneAuthority8577.pickAdvisory9022(r))
    }

    @Test fun whatProtectsTheWalletStillStopsAPick() {
        // superseded by V5.0.9023: STRUCTURAL_RISK_8136 (stage RUG_PRONE, a selection judgement) is advisory for a pick
        assertTrue(LaneAuthority8577.pickAdvisory9022("STRUCTURAL_RISK_8136"))
        assertTrue(LaneAuthority8577.pickDecision9022(cand(30.0), refused("STRUCTURAL_RISK_8136")).admitted)
        for (r in listOf("SAFETY_REFUSED_8136", "RUGCHECK_PENDING_OR_UNKNOWN", "MEME_BUYS_THE_HIGH_8582", "DATA_INCOMPLETE_DEFER_8136",
            "EDGE_MODEL_NEGATIVE_8537", "KILL_SWITCH_7835:MAX_DRAWDOWN", "LP_LOCK_BELOW_REQUIRED_8522", "TOP_HOLDER_CONCENTRATION")) {
            assertFalse(r, LaneAuthority8577.pickAdvisory9022(r))
            val d = LaneAuthority8577.pickDecision9022(cand(30.0), refused(r))
            assertFalse(r, d.admitted); assertEquals(r, d.reason)
        }
        val notPick = LaneAuthority8577.pickDecision9022(cand(3.0), refused("EXECUTION_ECONOMICS_8136"))
        assertFalse("not a pick: the refusal stands", notPick.admitted)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaneAuthority8577.kt").readText()
        assertTrue("the high still refuses whatever admitted it", src.indexOf("val d = pickDecision9022(c, d0)") in 0 until src.indexOf("if (highRefusal8582 != null) {"))
    }

    @Test fun anAdmittedPickIsMarkedForTheGateAndCounted() {
        val admit = CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, "SPECIFIC_EDGE_PROVEN_8136", true, "IGNITION_5M", 2.0, listOf("t"))
        val d = LaneAuthority8577.pickDecision9022(cand(9.0), admit)
        assertTrue(d.admitted); assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason); assertEquals(9.0, d.expectedNetPct, 0.0)
        assertTrue(LaneAuthority8577.pickPassesAtGate9022("TIMING_RECORD_NEGATIVE_8094", d))
        assertFalse(LaneAuthority8577.pickPassesAtGate9022("SAFETY_REFUSED_8136", d))
        assertFalse("an admission that is not a pick's gets nothing", LaneAuthority8577.pickPassesAtGate9022("TIMING_RECORD_NEGATIVE_8094", admit))
        val line = LaneAuthority8577.pickLine9022()
        assertTrue(line, line.startsWith("modelPicks9022[min=+5% {") && line.contains("GATE_PASSED:TIMING_RECORD_NEGATIVE_8094=") && line.contains("Pick90|MOONSHOT|+9|ADMIT"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("LaneAuthority8577.pickLine9022()"))
    }
}

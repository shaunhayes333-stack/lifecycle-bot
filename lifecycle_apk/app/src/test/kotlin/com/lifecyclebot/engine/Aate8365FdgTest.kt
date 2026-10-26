package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.FdgGate8365
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8365 — the live entry gate stack audit (lifecycle_apk/FDG_GATE_MAP_8365.md): one authoritative check per question.
 * An 8114 evidence door whose question the hold (paper ALL / exploration proof), the 8364 fired-setup rule, the setup's
 * realised live closes or the n >= 100 veto audit already answered is advisory in LIVE; a label refusal yields the
 * attribution to the structural checks; FDG and TradePlan7739 share one decision per candidate per pass.
 */
class Aate8365FdgTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    // ── 1. the hold is the authoritative "is there proof" check once it passed on a doorless proof ──

    @Test fun holdBasisOnlyForDoorlessProofsInALiveRuntime() {
        assertEquals(FdgGate8365.BASIS_PAPER_ALL_8365, FdgGate8365.holdBasisOf8365(true, true, true, false))
        assertEquals(FdgGate8365.BASIS_PAPER_ALL_8365, FdgGate8365.holdBasisOf8365(true, true, true, true))
        assertEquals(FdgGate8365.BASIS_EXPLORATION_8365, FdgGate8365.holdBasisOf8365(true, true, false, true))
        // unproven lane (or unlocked only by EvidenceShadow8344 / the 8337 exception), hold off, paper runtime: no basis
        assertNull(FdgGate8365.holdBasisOf8365(true, true, false, false))
        assertNull(FdgGate8365.holdBasisOf8365(false, true, true, true))
        assertNull(FdgGate8365.holdBasisOf8365(true, false, true, true))
    }

    @Test fun evidenceDoorsYieldToAPassedHoldInLiveOnly() {
        val basis = FdgGate8365.BASIS_PAPER_ALL_8365
        for (door in listOf("LIVE_PROVEN_SETUP_ONLY_8059", "PICKED_SETUP_RECORD_NEGATIVE_8094", "TIMING_RECORD_NEGATIVE_8094",
                "TACTIC_RECORD_LOSING_8094", "WATCH_FIRST_7994_CELL_NEGATIVE", "PLAYBOOK_NO_TRIGGER_PROVEN_LOSING_7907_MOONSHOT")) {
            assertEquals(door, "EVIDENCE_HOLD_$basis", FdgGate8365.demotedBy8365(door, true, basis, null, false))
            assertNull("paper: rehearsal already decides", FdgGate8365.demotedBy8365(door, false, basis, null, true))
        }
    }

    @Test fun hardSafetyEconomicsChaseRealisedAndDataNeverDemote() {
        val basis = FdgGate8365.BASIS_EXPLORATION_8365
        val ok = doubleArrayOf(20.0, 15.0)
        for (door in listOf("CANDIDATE_ADMISSION_8136_SAFETY_REFUSED_8136", "STRUCTURAL_RISK_8136", "NET_EDGE_BELOW_2X_COST_8030",
                "NET_EDGE_COIN_TOO_SLOW_8035", "CHASING_SPIKE_8065", "SWING_LANE_ALREADY_RAN_8054", "REBUY_LOCKOUT_8019",
                "LIVE_SETUP_LOSING_8083", "EVIDENCE_HOLD_8336_MOONSHOT", "DATA_INCOMPLETE_DEFER_8136", "MAYHEM_7943",
                "WALLET_BLIND_8074", "EDGE_7877_SHAPE_AGE", "COST_EXCEEDS_EDGE_7962_MOONSHOT", "LIVE_NO_FIRED_SETUP_8364_MOONSHOT")) {
            assertNull(door, FdgGate8365.demotedBy8365(door, true, basis, ok, true))
        }
    }

    // ── 2. the 8364 fired-setup rule is the one check of "did a named setup fire" ──

    @Test fun noTriggerPlaybookDoorYieldsToTheFiredSetupRule() {
        // superseded by V5.0.8370 — owner restored 8114 behaviour ("No fired-setup rule"): the 8364 rule is telemetry, so the
        // NO_TRIGGER playbook door is no longer its duplicate; it is as at 8114 (binds on a PROVEN losing NO_TRIGGER record,
        // with the 8114 overrides, the hold-basis and veto-audit demotions). It never refuses for "no named setup" alone.
        assertNull(FdgGate8365.demotedBy8365("PLAYBOOK_NO_TRIGGER_PROVEN_LOSING_7907_SHITCOIN", true, null, null, false))
        assertEquals("EVIDENCE_HOLD_" + FdgGate8365.BASIS_PAPER_ALL_8365,
            FdgGate8365.demotedBy8365("PLAYBOOK_NO_TRIGGER_PROVEN_LOSING_7907_SHITCOIN", true, FdgGate8365.BASIS_PAPER_ALL_8365, null, false))
        // a NAMED setup proven losing on labels is not the fired-setup question
        assertNull(FdgGate8365.demotedBy8365("PLAYBOOK_SETUP_PROVEN_LOSING_7907_SHITCOIN_IGNITION", true, null, null, false))
    }

    // ── 3. realised live closes outrank a label "setup losing" door ──

    @Test fun realisedSetupRecordOutranksLabelLossDoors() {
        val positive = doubleArrayOf(6.0, 3.5)
        for (door in listOf("PICKED_SETUP_RECORD_NEGATIVE_8094", "TIMING_RECORD_NEGATIVE_8094",
                "PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_IGNITION", "PLAYBOOK_SETUP_EXPECTED_NEGATIVE_7948_MOONSHOT_IGNITION")) {
            assertTrue(FdgGate8365.setupLossDoor8365(door))
            assertEquals(door, FdgGate8365.BY_REALISED_8365, FdgGate8365.demotedBy8365(door, true, null, positive, false))
            assertNull("4 closes are not a record", FdgGate8365.demotedBy8365(door, true, null, doubleArrayOf(4.0, 9.0), false))
            assertNull("realised losing confirms the door", FdgGate8365.demotedBy8365(door, true, null, doubleArrayOf(9.0, -0.1), false))
        }
        assertFalse(FdgGate8365.setupLossDoor8365("LIVE_PROVEN_SETUP_ONLY_8059"))
        assertNull("only setup-loss doors read the realised setup record",
            FdgGate8365.demotedBy8365("LIVE_PROVEN_SETUP_ONLY_8059", true, null, positive, false))
        assertTrue(FdgGate8365.realisedContradicts8365(doubleArrayOf(5.0, 0.0)))
        assertFalse(FdgGate8365.realisedContradicts8365(doubleArrayOf(5.0, Double.NaN)))
        assertFalse(FdgGate8365.realisedContradicts8365(null))
    }

    @Test fun playbookReasonNamesItsSetupWithMultiWordLanes() {
        assertEquals("IGNITION_5M", FdgGate8365.setupOfReason8365("PLAYBOOK_SETUP_PROVEN_LOSING_7907_SHITCOIN_EXPRESS_IGNITION_5M", "SHITCOIN_EXPRESS"))
        assertEquals("LAUNCH_CONTINUATION", FdgGate8365.setupOfReason8365("PLAYBOOK_SETUP_EXPECTED_NEGATIVE_7948_MOONSHOT_LAUNCH_CONTINUATION", "moonshot"))
        assertNull(FdgGate8365.setupOfReason8365("PICKED_SETUP_RECORD_NEGATIVE_8094", "MOONSHOT"))
        assertNull(FdgGate8365.setupOfReason8365("PLAYBOOK_SETUP_PROVEN_LOSING_7907_MOONSHOT_X", ""))
    }

    // ── 4. the veto audit demotes only on 100+ graded refusals proven to win ──

    @Test fun vetoAuditNeedsOneHundredRefusalsProvenToWin() {
        assertEquals(100.0, FdgGate8365.VETO_MIN_N_8365, 0.0)
        assertEquals(5, FdgGate8365.REALISED_MIN_N_8365)
        assertTrue(FdgGate8365.vetoDemotes8365(100.0, true))
        assertFalse(FdgGate8365.vetoDemotes8365(99.0, true))
        assertFalse(FdgGate8365.vetoDemotes8365(400.0, false))
        assertFalse(FdgGate8365.vetoDemotes8365(Double.NaN, true))
        assertEquals(FdgGate8365.BY_VETO_AUDIT_8365, FdgGate8365.demotedBy8365("TACTIC_RECORD_LOSING_8094", true, null, null, true))
    }

    // ── 5. attribution: a label refusal of the 8114 verdict yields to the structural checks, once ──

    @Test fun labelRefusalYieldsAttributionButHardAndRealisedDoNot() {
        assertTrue(FdgGate8365.oldYields8365("LIVE_PROVEN_SETUP_ONLY_8059", "LIVE_PROVEN_SETUP_ONLY_8059", null))
        assertTrue(FdgGate8365.oldYields8365("PICKED_SETUP_RECORD_NEGATIVE_8094", "PICKED_SETUP_RECORD_NEGATIVE_8094", null))
        // the realised / hold refusal decided
        assertFalse(FdgGate8365.oldYields8365("LIVE_PROVEN_SETUP_ONLY_8059", "EVIDENCE_HOLD_8336_MOONSHOT", "EVIDENCE_HOLD_8336_MOONSHOT"))
        // the current hard verdict decided
        assertFalse(FdgGate8365.oldYields8365("LIVE_PROVEN_SETUP_ONLY_8059", "CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136", null))
        // economics / chase / dead launch stay first
        assertFalse(FdgGate8365.oldYields8365("NET_EDGE_COIN_TOO_SLOW_8035", "NET_EDGE_COIN_TOO_SLOW_8035", null))
        assertFalse(FdgGate8365.oldYields8365("CHASING_SPIKE_8065", "CHASING_SPIKE_8065", null))
        assertFalse(FdgGate8365.oldYields8365(null, null, null))
    }

    @Test fun refusalsAreClassedOnce() {
        assertEquals(FdgGate8365.CLASS_HOLD, FdgGate8365.class8365("EVIDENCE_HOLD_8336_MOONSHOT"))
        assertEquals(FdgGate8365.CLASS_HOLD, FdgGate8365.class8365("ENTRY8114_LANE_REALISED_LOSING_BINDS_8329_SHITCOIN"))
        assertEquals(FdgGate8365.CLASS_HOLD, FdgGate8365.class8365("LIVE_SETUP_LOSING_8083"))
        assertEquals(FdgGate8365.CLASS_ECONOMICS, FdgGate8365.class8365("NET_EDGE_COIN_TOO_SLOW_8035"))
        assertEquals(FdgGate8365.CLASS_ECONOMICS, FdgGate8365.class8365("CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136"))
        assertEquals(FdgGate8365.CLASS_SAFETY, FdgGate8365.class8365("CANDIDATE_ADMISSION_8136_STRUCTURAL_RISK_8136"))
        assertEquals(FdgGate8365.CLASS_SAFETY, FdgGate8365.class8365("CURVE_NOBODY_BOUGHT_8046"))
        assertEquals(FdgGate8365.CLASS_LABEL, FdgGate8365.class8365("LIVE_PROVEN_SETUP_ONLY_8059"))
        assertEquals(FdgGate8365.CLASS_LABEL, FdgGate8365.class8365("EDGE_7877_SHAPE_AGE_MIN_3-15"))
        assertEquals(FdgGate8365.CLASS_DATA, FdgGate8365.class8365("DATA_INCOMPLETE_DEFER_8136"))
        assertEquals(FdgGate8365.CLASS_STRUCTURE, FdgGate8365.class8365("LIVE_NO_FIRED_SETUP_8364_MOONSHOT"))
        assertEquals(FdgGate8365.CLASS_STRUCTURE, FdgGate8365.class8365("NICHE_ONLY_8351_MOONSHOT_M:10K_100K"))
        assertEquals(FdgGate8365.CLASS_STRUCTURE, FdgGate8365.class8365("CHASING_SPIKE_8065"))
        assertNull(FdgGate8365.class8365(null))
    }

    // ── 6. one decision per candidate per pass ──

    @Test fun memoisedDecisionIsReusedOnlyInsideItsWindow() {
        assertTrue(FdgGate8365.memoFresh8365(1_000L, 1_000L))
        assertTrue(FdgGate8365.memoFresh8365(1_000L, 1_000L + FdgGate8365.MEMO_TTL_MS_8365))
        assertFalse(FdgGate8365.memoFresh8365(1_000L, 1_001L + FdgGate8365.MEMO_TTL_MS_8365))
        assertFalse("a clock step back is not fresh", FdgGate8365.memoFresh8365(5_000L, 4_000L))
        val k = FdgGate8365.memoKey8365("MintA", "MOONSHOT", 7L, false)
        assertTrue(k != FdgGate8365.memoKey8365("MintA", "MOONSHOT", 8L, false))
        assertTrue(k != FdgGate8365.memoKey8365("MintA", "MOONSHOT", 7L, true))
        FdgGate8365.memoPut8365(k, null, 10_000L)
        assertNull("an admit is reused as an admit", FdgGate8365.memoGet8365(k, 10_500L)?.second)
        assertTrue(FdgGate8365.memoGet8365(k, 10_500L) != null)
        assertNull("stale", FdgGate8365.memoGet8365(k, 20_000L))
        FdgGate8365.noteDemoted8365("LIVE_PROVEN_SETUP_ONLY_8059", "EVIDENCE_HOLD_PAPER_ALL_8336", "MOONSHOT")
        FdgGate8365.noteFinal8365("EVIDENCE_HOLD_8336_MOONSHOT", "MOONSHOT")
        val line = FdgGate8365.statusLine8365()
        assertTrue(line, line.startsWith("fdg8365[") && line.contains("refused{") && line.contains("EVIDENCE-HOLD=EVIDENCE_HOLD_8336=") &&
            line.contains("duplicatesDemoted=[") && line.contains("LIVE_PROVEN_SETUP_ONLY_8059>EVIDENCE_HOLD_PAPER_ALL_8336="))
    }

    // ── 7. lane identity: SHITCOIN_EXPRESS is EXPRESS across the 8114 stack (hold, realised, memo) ──

    @Test fun shitcoinExpressFilesAsExpress() {
        assertEquals("EXPRESS", com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.expressAlias8392("SHITCOIN_EXPRESS"))
        assertEquals("EXPRESS", com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.expressAlias8392("SHITCOINEXPRESS"))
        assertEquals("MOONSHOT", com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.expressAlias8392("MOONSHOT"))
        assertEquals("SHITCOIN", com.lifecyclebot.engine.truth.CanonicalLaneIdentity6506.expressAlias8392("SHITCOIN"))
        assertTrue(src("engine/truth/EvidenceHold8336.kt").contains("private fun canon(lane: String): String = CanonicalLaneIdentity6506.desk8392(lane)"))
    }

    // ── 8. source pins ──

    @Test fun wiringIsPinned() {
        val gate=src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("canonicalRefusal8392(ts, l, paper, nowMs, candidateVersion, candidateAdmission)"))
        assertTrue(gate.contains("FdgGate8365.inContext8365(ts, l, basis, paper, shadowOnly8393)"))
        assertTrue(gate.contains("EvidenceHold8336.refusal8336(ts, l, false, nowMs, candidateVersion8397 = candidateVersion)"))
        assertTrue(gate.contains("if (paper && final == null) Rehearsal8339.notePaperAdmit8342"))
        assertFalse(gate.contains("entryRefusal8114"))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Decision8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8577 §LANE_AUTHORITY — owner: LIVE admission is the designed authority again (V3 + the lane qualify; safety,
 * structure, the executable minimum and the kill switch veto); every learned / proof door is advisory for a lane-qualified
 * LIVE candidate and keeps learning. 8575 log: ~800 LIVE evaluations, 13 admitted, 0 bought.
 */
class Aate8577LaneAuthorityTest {
    // V5.0.8609 — these pin the lane layer, so the coin sits in the paying state (EXPANDING); the coin-state rule has its own test.
    private fun ap8609(c: CandidateAdmission8136.Candidate8136, d: CandidateAdmission8136.Decision8136) =
        LaneAuthority8577.apply8577(c, d, null, "EXPANDING")

    private fun qualified(live: Boolean = true, cost: Double = 2.0) = Candidate8136(
        mint = "Mint8577", lane = "SHITCOIN", selectedSetup = "LAUNCH_LADDER_PROVEN", priceAgeMs = 1_000L, costPct = cost,
        live = live, nativeQualified8213 = true, nativeSetup8213 = "LAUNCH_LADDER_PROVEN", genericScore = 42.0, genericFloor = 15.0,
    )
    private fun refusal(reason: String, trace: List<String> = emptyList(), terminal: Terminal8136 = Terminal8136.REFUSE) =
        Decision8136(terminal, reason, true, "LAUNCH_LADDER_PROVEN", 8.0, trace, costPct = 2.0)

    @Test fun anAdvisoryRefusalCannotHideAStaleRunnerOrACostLoss() {
        val soft = refusal("ENTRY_NO_RECLAIM_8506")
        val stale = LaneAuthority8577.LIVE_RUNNER_STALE_COIN_8627
        assertEquals(stale, LaneAuthority8577.apply8577(qualified(), soft, stale).reason)
        assertEquals("STRUCTURAL_RISK_8136", LaneAuthority8577.apply8577(qualified(), refusal("STRUCTURAL_RISK_8136"), stale).reason)
        for (net in listOf(-2.0, 0.0)) {
            val priced = ap8609(qualified(), soft.copy(terminal = Terminal8136.ADMIT, expectedNetPct = net))
            assertEquals(Terminal8136.REFUSE, priced.terminal)
            assertTrue(LaneAuthority8577.binding8577(priced.reason))
        }
    }

    @Test fun the_learned_doors_are_advisory_for_a_lane_qualified_live_candidate() {
        LaneAuthority8577.enabled8577 = true
        // superseded by V5.0.8582 — "LIVE_INTO_HIGH_UNPROVEN_8545" advisory: buying into the high binds again (Aate8582BuyThePullbackTest).
        for (door in listOf("LIVE_NATIVE_OPINION_UNPROVEN_8536", "LIVE_LAUNCH_UNPROVEN_8559",
                "LIVE_REGIME_COLD_8546", "ENTRY_NO_RECLAIM_8506", "ENTRY_NO_BUY_VOLUME_8506",
                "FALLING_HOUR_PROVEN_LOSING_8418", "STAGE_PROVEN_LOSING_8136", "LIVE_GENERIC_PRIOR_UNSUPPORTED_8238")) {
            val d = ap8609(qualified(), refusal(door))
            assertEquals(door, Terminal8136.ADMIT, d.terminal)
            assertEquals(LaneAuthority8577.REASON_8577, d.reason)
            assertFalse(d.specific); assertTrue(d.laneQualified8577)
            assertTrue(d.trace.any { it.startsWith("advisory8577=$door") })
            assertEquals("LAUNCH_LADDER_PROVEN", d.chosenSetup)
        }
        assertTrue(LaneAuthority8577.advisoryCount8577("ENTRY_NO_RECLAIM_8506") >= 1L)
        // the falling-hour WAIT and the entry-wait data defer are learned doors too
        assertEquals(Terminal8136.ADMIT, ap8609(qualified(), refusal(CandidateAdmission8136.FALLING_HOUR_DEFER_8418, terminal = Terminal8136.DEFER_DATA)).terminal)
        assertEquals(Terminal8136.ADMIT, ap8609(qualified(),
            refusal("DATA_INCOMPLETE_DEFER_8136", listOf("entryWait8506=FLOW_UNOBSERVED", "missing=swaps,bp,chg"), Terminal8136.DEFER_DATA)).terminal)
        // V5.0.8649 — a missing executable move is never upgraded to a LIVE buy
        // just because a native opinion and a generic V3 score passed.
        val missingGross = refusal("DATA_INCOMPLETE_DEFER_8136", listOf("missing=economics"), Terminal8136.DEFER_DATA)
            .copy(expectedNetPct = Double.NaN)
        assertEquals(Terminal8136.DEFER_DATA, ap8609(qualified(), missingGross).terminal)
        val smallGross = refusal("EXECUTION_ECONOMICS_8136").copy(expectedNetPct = 0.5)
        assertEquals(Terminal8136.REFUSE, ap8609(qualified(), smallGross).terminal)
    }

    @Test fun a_generic_lane_score_must_not_erase_its_own_proven_losing_setup_or_unknown_native_identity() {
        LaneAuthority8577.enabled8577 = true
        val localLoss = CandidateAdmission8136.Evidence8136(
            CandidateAdmission8136.KIND_SETUP, "LAUNCH_LADDER_PROVEN", 50, -16.0, 2.0)
        val c = qualified().copy(setups = listOf(localLoss), expectedGrossPct = 18.0, recordProbe8429 = false)
        val decided = CandidateAdmission8136.decide8136(c, CandidateAdmission8136::verifiedRecordDoor8650)
        assertEquals("SETUP_RECORD_LOSING_8136", decided.reason)
        assertEquals(Terminal8136.REFUSE, ap8609(c, decided).terminal)
        // A lane whose native snapshot has no executable named setup is not a buy authority.
        val unnamed = qualified().copy(nativeSetup8213 = null)
        assertFalse(CandidateAdmission8136.specialistQualified8539(unnamed))
        assertEquals(Terminal8136.REFUSE, ap8609(unnamed, refusal("LIVE_NATIVE_OPINION_UNPROVEN_8536")).terminal)
    }

    @Test fun finalGatePreservesVerifiedLossOnAQualifiedCandidate() {
        val candidate = qualified()
        for (reason in listOf("CELL_PROVEN_LOSING_8136", "SETUP_RECORD_LOSING_8136", "TIMING_ADVERSE_8136",
                "SHEET_AVOID_8323", "EDGE_MODEL_NEGATIVE_8537")) {
            val decision = ap8609(candidate, refusal(reason))
            assertEquals(Terminal8136.REFUSE, decision.terminal)
            assertTrue(decision.laneQualified8577)
            assertFalse(LaneAuthority8577.advisoryAtGate8577("CANDIDATE_ADMISSION_8136_${decision.reason}", decision))
            assertFalse(LaneAuthority8577.advisoryAtGate8577("WATCH_FIRST_8019", decision))
        }
        val alternative = refusal("SPECIFIC_EDGE_PROVEN_8136").copy(terminal = Terminal8136.ADMIT)
        assertTrue(LaneAuthority8577.advisoryAtGate8577("WATCH_FIRST_8019", alternative))
        assertFalse(LaneAuthority8577.binding8577("CANDIDATE_ADMISSION_8136_LIVE_REGIME_COLD_8546"))
    }

    @Test fun cachedGateVerdictIsBoundToTheActualAdmission() {
        val gate = com.lifecyclebot.engine.truth.FdgGate8365
        val key = "8668_same_mint_lane_generation"
        val rejected = refusal("CELL_PROVEN_LOSING_8136")
        val allowed = rejected.copy(terminal = Terminal8136.ADMIT, reason = "SPECIFIC_EDGE_PROVEN_8136")
        gate.memoPut8365(key, null, 10_000L, allowed)
        assertTrue(gate.memoGet8365(key, 10_001L, allowed) != null)
        assertEquals(null, gate.memoGet8365(key, 10_001L, rejected))
        assertEquals(null, gate.memoGet8365(key, 10_001L))
    }

    @Test fun safety_structure_the_executable_minimum_and_the_v3_floor_still_bind() {
        LaneAuthority8577.enabled8577 = true
        for (door in listOf("SAFETY_REFUSED_8136", "STRUCTURAL_RISK_8136", "GENERIC_SCORE_FLOOR_8136")) {
            val d = ap8609(qualified(), refusal(door))
            assertEquals(door, Terminal8136.REFUSE, d.terminal); assertEquals(door, d.reason); assertTrue(d.laneQualified8577)
        }
        // stale price / a critical fact unobserved / unknown cost: the executable minimum
        val stale = refusal("DATA_INCOMPLETE_DEFER_8136", listOf("missing=shape:UNKNOWN,price"), Terminal8136.DEFER_DATA)
        assertEquals(Terminal8136.DEFER_DATA, ap8609(qualified(), stale).terminal)
        val critical = refusal("DATA_INCOMPLETE_DEFER_8136", listOf("missing=liquidity", "criticalUnobserved8170=liquidity"), Terminal8136.DEFER_DATA)
        assertEquals(Terminal8136.DEFER_DATA, ap8609(qualified(), critical).terminal)
        val costUnknown = refusal("DATA_INCOMPLETE_DEFER_8136", listOf("missing=economics"), Terminal8136.DEFER_DATA)
        assertEquals(Terminal8136.DEFER_DATA, ap8609(qualified(cost = Double.NaN), costUnknown).terminal)
    }

    @Test fun paper_and_unqualified_candidates_and_an_off_switch_change_nothing() {
        LaneAuthority8577.enabled8577 = true
        val r = refusal("LIVE_NATIVE_OPINION_UNPROVEN_8536")
        assertSame(r, ap8609(qualified(live = false), r))
        assertSame(r, ap8609(qualified().copy(nativeQualified8213 = false), r))
        assertSame(r, ap8609(qualified().copy(genericScore = 10.0), r))
        LaneAuthority8577.enabled8577 = false
        try { assertSame(r, ap8609(qualified(), r)) } finally { LaneAuthority8577.enabled8577 = true }
        // an ADMIT is only marked qualified
        val a = Decision8136(Terminal8136.ADMIT, "NATIVE_QUALIFIED_ADMIT_8259", false, "X", 5.0, emptyList())
        val m = ap8609(qualified(), a)
        assertEquals("NATIVE_QUALIFIED_ADMIT_8259", m.reason); assertTrue(m.laneQualified8577)
    }

    @Test fun at_the_gate_only_binding_refusals_hold_a_qualified_coin() {
        LaneAuthority8577.enabled8577 = true
        val q = Decision8136(Terminal8136.ADMIT, LaneAuthority8577.REASON_8577, false, "X", Double.NaN, emptyList(), laneQualified8577 = true)
        // superseded by V5.0.8582 — "CHASING_SPIKE_8065" advisory: buying the high is proven to lose (MEME_BUYS_THE_HIGH_8582),
        // the chase doors bind again (see Aate8582BuyThePullbackTest).
        for (advisory in listOf("TIMING_RECORD_NEGATIVE_8094", "PICKED_SETUP_RECORD_NEGATIVE_8094", "WATCH_FIRST_8019",
                "LIVE_UNPROVEN_EDGE_8536", "EVIDENCE_HOLD_8336_SHITCOIN", "LANE_REALISED_LOSING_8329_SHITCOIN", "NET_EDGE_COIN_TOO_SLOW_8035",
                "CORTEX_REFUSE_SHITCOIN", "NICHE_ONLY_8351", "STAGE_PROVEN_LOSING_7928_SHITCOIN_DUMPING"))
            assertTrue(advisory, LaneAuthority8577.advisoryAtGate8577(advisory, q))
        for (binding in listOf("MAYHEM_MODE_7943", "HARD_BLOCK_SAFETY", "RUG_PREFILTER_HARD_FAIL", "LIVE_BOOT_BLIND_8074", "DATA_INCOMPLETE_DEFER_8136",
                "DATA_DEFER_BUDGET_EXHAUSTED_8136", "EARLY_LAUNCH_EMPTY_CURVE_8048", "REBUY_LOCKOUT_8019", "KILL_SWITCH_HOLD", "CANONICAL_ENTRY_EVAL_FAILED_8392",
                "SIZE_NOT_EXECUTABLE_7835", "NATIVE_LANE_LIQ_BELOW_FLOOR", "WALLET_BLIND", "QUANTITY_QUARANTINED",
                "EXECUTION_ECONOMICS_8136", "COST_LEDGER_7962_ABOVE_GROSS", "SETUP_RECORD_LOSING_8136", "TIMING_ADVERSE_8136"))
            assertFalse(binding, LaneAuthority8577.advisoryAtGate8577(binding, q))
        // V5.0.8604 — an ADMITTED live coin (the paper-equivalent admission) is advisory at the gate whether or not a specialist
        // qualified it; a refused admission is not.
        assertTrue(LaneAuthority8577.advisoryAtGate8577("WATCH_FIRST_8019", q.copy(laneQualified8577 = false)))
        assertFalse(LaneAuthority8577.advisoryAtGate8577("WATCH_FIRST_8019",
            q.copy(terminal = Terminal8136.REFUSE, reason = "SETUP_RECORD_LOSING_8136", laneQualified8577 = false)))
        assertFalse(LaneAuthority8577.advisoryAtGate8577("WATCH_FIRST_8019", null))
        assertFalse(LaneAuthority8577.advisoryAtGate8577(null, q))
    }

    @Test fun the_runtime_applies_it_at_the_admission_and_the_gate_and_the_doors_keep_learning() {
        val adm = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        // V5.0.8650 — the source retains the single judgment, including its measured candidate-local negatives.
        assertTrue(adm.contains("LaneAuthority8577.apply8577(candidate,\n            decide8136(candidate) { reason -> candidate.live && verifiedRecordDoor8650(reason) },\n            if (candidate.live) LaneAuthority8577.memeEntryRefusal8626(ts, candidate.lane, candidate.stage, nowMs) else null,  // V5.0.8626\n            launchPhase8609 = "))
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        val fn = gate.substringAfter("fun liveRefusal(").substringBefore("private fun unprovenLive8536(")
        assertTrue(fn.contains("val advisory8577 = !paper && LaneAuthority8577.advisoryAtGate8577(doors8577, admission8577)"))
        assertTrue(fn.contains("val final = if (advisory8577) null else doors8577"))
        assertTrue("the door's own answer is filed on its forward record", fn.contains("observeTail7996(ts, l, doors8577, nowMs)"))
        assertTrue(fn.contains("FdgGate8365.noteFinal8365(doors8577, l)"))
        assertTrue("the FDG memo and the live admit read the verdict", fn.contains("FdgGate8365.memoPut8365(key, final, nowMs, candidateAdmission)") &&
            fn.contains("if (!paper && final == null) LiveTruth8064.noteLiveAdmit8083(ts.mint, l,"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("LaneAuthority8577.statusLine8577()"))
        assertTrue(LaneAuthority8577.statusLine8577().startsWith("laneAuthority8577[on="))
    }

    @Test fun the_binding_classifiers_are_pure() {
        assertTrue(LaneAuthority8577.binding8577("SAFETY_REFUSED_8136")); assertTrue(LaneAuthority8577.binding8577("kill_switch_hold"))
        assertTrue(LaneAuthority8577.binding8577("EXECUTION_ECONOMICS_8136")); assertFalse(LaneAuthority8577.binding8577(null)); assertFalse(LaneAuthority8577.binding8577(""))
        assertTrue(LaneAuthority8577.bindingAdmission8577(refusal("DATA_INCOMPLETE_DEFER_8136", listOf("missing=economics")), costKnown = false))
        assertFalse(LaneAuthority8577.bindingAdmission8577(refusal("DATA_INCOMPLETE_DEFER_8136", listOf("missing=swaps,bp,chg")), costKnown = true))
        assertTrue("cost known does not make the missing move executable",
            LaneAuthority8577.bindingAdmission8577(refusal("DATA_INCOMPLETE_DEFER_8136", listOf("missing=economics")).copy(expectedNetPct = Double.NaN), costKnown = true))
        assertFalse(LaneAuthority8577.bindingAdmission8577(refusal(CandidateAdmission8136.FALLING_HOUR_DEFER_8418), costKnown = true))
        assertTrue(LaneAuthority8577.bindingAdmission8577(refusal("STRUCTURAL_RISK_8136"), costKnown = true))
    }
}

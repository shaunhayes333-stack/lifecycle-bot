package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.FdgGate8365
import com.lifecyclebot.engine.truth.GateOutcome8418
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.Rehearsal8339
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8418 — buy-path audit (lifecycle_apk/BUY_PATH_AUDIT_8418.md), behaviour of each change:
 *  1. an unvalidated / inverted Cortex ordering does not vote in the 100x door's odds leg — the early-run evidence decides;
 *  2. a candidate the hold passed on its own exception carries BASIS_EXCEPTION_8418: label / record doors are advisory for it,
 *     safety / economics / chase / realised / candidate-evidence doors still bind;
 *  3. a live grab-lane admit still inside the 8117 early bound is not refused as a chase;
 *  4. every buy-path gate gets a forward record (GateOutcome8418) and the 8365 veto audit can read it.
 */
class Aate8418BuyPathTest {

    // ── 1. the inverted seat does not vote ──

    @Test fun validatedOrderingWithABookDecidesByItsTopBucketAlone() {
        var asked = false
        val strong = EvidenceHold8336.oddsLeg8418(true, true, 0.42, 7, true) { asked = true; true }
        assertTrue(strong != null && strong.startsWith(EvidenceHold8336.ODDS_CORTEX_STRONG_8337) && strong.contains("voters7"))
        assertNull("validated, booked, not STRONG: no odds", EvidenceHold8336.oddsLeg8418(true, false, 0.42, 7, true) { asked = true; true })
        assertFalse("the early-run evidence is not read when the validated book decides", asked)
    }

    @Test fun unvalidatedOrInvertedOrderingAbstainsAndTheEarlyRunDecides() {
        // 8416: TREASURY STRONG -14.3% vs REFUSE +113% — a STRONG read on that lane must neither qualify nor block.
        assertEquals(EvidenceHold8336.ODDS_CORTEX_ABSTAINS_EARLY_RUN_8418,
            EvidenceHold8336.oddsLeg8418(true, false, 0.30, 5, false) { true })
        assertNull("abstaining Cortex + no early run: no odds leg",
            EvidenceHold8336.oddsLeg8418(true, true, 0.90, 9, false) { false })
        // a validated lane without a run-odds book keeps the 8337 reason
        assertEquals(EvidenceHold8336.ODDS_NO_BOOK_EARLY_RUN_8337, EvidenceHold8336.oddsLeg8418(false, false, Double.NaN, 0, true) { true })
        assertNull(EvidenceHold8336.oddsLeg8418(false, false, Double.NaN, 0, true) { false })
    }

    @Test fun anUnknownLaneHasNoOrderingVote() {
        assertFalse(Cortex7885.orderingVotes8418("NO_SUCH_LANE_8418"))
    }

    @Test fun theAbstainingOddsLegStillMakesAnExceptionWithTheOtherLegs() {
        val r = EvidenceHold8336.exceptionalReason8337(true, EvidenceHold8336.VENUE_POOL_FLOOR_8337,
            EvidenceHold8336.ODDS_CORTEX_ABSTAINS_EARLY_RUN_8418, true)
        assertTrue(r != null && r.contains(EvidenceHold8336.ODDS_CORTEX_ABSTAINS_EARLY_RUN_8418))
        assertNull("the lane shut by realised losses still refuses", EvidenceHold8336.exceptionalReason8337(true,
            EvidenceHold8336.VENUE_POOL_FLOOR_8337, EvidenceHold8336.ODDS_CORTEX_ABSTAINS_EARLY_RUN_8418, false))
    }

    // ── 2. the 100x door is one check ──

    @Test fun exceptionBasisOnlyForALiveHeldUnprovenLanePassedOnItsOwnProof() {
        val proof = "grab=RUNNER_GRAB_7967 venue=POOL_EXIT_FLOOR_500_MET odds=X lane=OPEN"
        assertEquals(FdgGate8365.BASIS_EXCEPTION_8418, FdgGate8365.exceptionBasisOf8418(true, true, true, true, false, proof))
        assertNull("paper", FdgGate8365.exceptionBasisOf8418(false, true, true, true, false, proof))
        assertNull("hold off", FdgGate8365.exceptionBasisOf8418(true, false, true, true, false, proof))
        assertNull("not a held lane", FdgGate8365.exceptionBasisOf8418(true, true, false, true, false, proof))
        assertNull("the hold refused", FdgGate8365.exceptionBasisOf8418(true, true, true, false, false, proof))
        assertNull("a proven lane has its own basis", FdgGate8365.exceptionBasisOf8418(true, true, true, true, true, proof))
        assertNull("no exception proof", FdgGate8365.exceptionBasisOf8418(true, true, true, true, false, null))
        assertNull(FdgGate8365.exceptionBasisOf8418(true, true, true, true, false, ""))
    }

    @Test fun exceptionBasisDemotesLabelDoorsOnly() {
        val b = FdgGate8365.BASIS_EXCEPTION_8418
        for (door in listOf("LIVE_PROVEN_SETUP_ONLY_8059", "PICKED_SETUP_RECORD_NEGATIVE_8094", "TIMING_RECORD_NEGATIVE_8094",
                "WATCH_FIRST_7994_THIN_CELL", "CELL_PROVEN_LOSING_8136", "STAGE_PROVEN_LOSING_8136", "SHEET_AVOID_8323")) {
            assertEquals(door, "EVIDENCE_HOLD_$b", FdgGate8365.demotedBy8365(door, true, b, null, false))
        }
        for (door in listOf("CHASING_SPIKE_8065", "EXECUTION_ECONOMICS_8136", "NET_EDGE_BELOW_2X_COST_8030",
                "LIVE_SETUP_LOSING_8083", "SAFETY_REFUSED_8136", "DATA_INCOMPLETE_DEFER_8136", "SWING_LANE_ALREADY_RAN_8054",
                "MAYHEM_MODE_LIVE_REFUSED", "EDGE_7877_BUY_PRESSURE_55-65_MOONSHOT")) {
            assertNull(door, FdgGate8365.demotedBy8365(door, true, b, null, false))
        }
        // candidate-specific record losses stay binding live even when demoted (Rehearsal8339.binds8393)
        assertTrue(Rehearsal8339.binds8393("SETUP_RECORD_LOSING_8136", false, true, false, true))
        assertTrue(Rehearsal8339.binds8393("TIMING_ADVERSE_8136", false, true, false, true))
        assertFalse("a demoted label door is advisory live", Rehearsal8339.binds8393("CELL_PROVEN_LOSING_8136", false, true, false, true))
    }

    // ── 3. the grab lane's admit is the 8117 developing run ──

    @Test fun grabLaneAdmitInsideTheEarlyBoundIsNotAChase() {
        // a forming run: +95% off its low, at 95% of its high — the 8065 rule calls it a chase
        assertTrue(LiveEdgeGate7877.chasing8065(95.0, 0.95, 20.0))
        assertTrue(LiveEdgeGate7877.grabLaneEarly8418(true, 95.0, 20.0))
        assertFalse("no grab-lane admit: the chase rule binds", LiveEdgeGate7877.grabLaneEarly8418(false, 95.0, 20.0))
        assertFalse("past the 8117 bound (+150%) it waits for its pullback", LiveEdgeGate7877.grabLaneEarly8418(true, 160.0, 20.0))
        assertFalse("a 5-minute rip of +100% waits too", LiveEdgeGate7877.grabLaneEarly8418(true, 95.0, 120.0))
        assertTrue("an unknown 5-minute change does not block an early run", LiveEdgeGate7877.grabLaneEarly8418(true, 95.0, Double.NaN))
    }

    // ── 4. every gate has a forward record ──

    @Test fun theGateOfADecision() {
        assertEquals(GateOutcome8418.ADMIT_GATE_8418, GateOutcome8418.gateOf8418(true, null))
        assertNull("a native READY is a prediction, not an admit", GateOutcome8418.gateOf8418(true, "NATIVE_READY"))
        assertNull(GateOutcome8418.gateOf8418(false, "NATIVE_MCAP_TOO_LOW"))
        assertNull(GateOutcome8418.gateOf8418(false, "CORTEX_SHADOW_REFUSE"))
        assertNull(GateOutcome8418.gateOf8418(false, " "))
        assertEquals("EVIDENCE_HOLD_8336", GateOutcome8418.gateOf8418(false, "EVIDENCE_HOLD_8336_MOONSHOT"))
        assertEquals("ADMISSION8136_EXECUTION_ECONOMICS_8136", GateOutcome8418.gateOf8418(false, "CANDIDATE_ADMISSION_8136_EXECUTION_ECONOMICS_8136"))
        assertEquals("CHASING_SPIKE_8065", GateOutcome8418.gateOf8418(false, "CHASING_SPIKE_8065"))
    }

    @Test fun theFirstGateOnAFreshMemeObservationIsFiled() {
        val t0 = 1_000_000L
        // the FDG refusal of a coin whose native READY opened the observation 3 s earlier: filed
        assertEquals("CHASING_SPIKE_8065", GateOutcome8418.attachGate8418("", t0, false, "CHASING_SPIKE_8065", "MOONSHOT", t0 + 3_000L))
        // a second refusal does not overwrite the first
        assertNull(GateOutcome8418.attachGate8418("CHASING_SPIKE_8065", t0, false, "EVIDENCE_HOLD_8336_MOONSHOT", "MOONSHOT", t0 + 5_000L))
        // an admit replaces a refusal (the coin was bought), once
        assertEquals(GateOutcome8418.ADMIT_GATE_8418, GateOutcome8418.attachGate8418("CHASING_SPIKE_8065", t0, true, null, "MOONSHOT", t0 + 9_000L))
        assertNull(GateOutcome8418.attachGate8418(GateOutcome8418.ADMIT_GATE_8418, t0, true, null, "MOONSHOT", t0 + 9_500L))
        // too old to be this decision's outcome / not a meme lane / a pseudo lane / a prediction
        assertNull(GateOutcome8418.attachGate8418("", t0, false, "CHASING_SPIKE_8065", "MOONSHOT", t0 + GateOutcome8418.ATTACH_MAX_AGE_MS_8418 + 1L))
        assertNull(GateOutcome8418.attachGate8418("", t0, false, "CHASING_SPIKE_8065", "CRYPTO_ALT", t0 + 1_000L))
        assertNull(GateOutcome8418.attachGate8418("", t0, false, "X_7739", "PLANWAIT_MOONSHOT", t0 + 1_000L))
        assertNull(GateOutcome8418.attachGate8418("", t0, true, "NATIVE_READY", "MOONSHOT", t0 + 1_000L))
        // SHITCOIN_EXPRESS files as the EXPRESS desk
        assertEquals("WATCH_FIRST_7994", GateOutcome8418.attachGate8418("", t0, false, "WATCH_FIRST_7994_THIN_CELL", "SHITCOIN_EXPRESS", t0 + 1_000L))
    }

    @Test fun aGateRetiresOnlyOnOneHundredGradedRefusalsProvenToWin() {
        GateOutcome8418.resetForTests8418()
        val gate = "LIVE_PROVEN_SETUP_ONLY_8059"
        repeat(99) { i -> GateOutcome8418.book8418(gate, "MOONSHOT", if (i % 5 < 3) 60.0 else -20.0, i % 5 == 0) }
        assertFalse("99 refusals are not an audit", GateOutcome8418.retiresAt100_8418(gate, "MOONSHOT"))
        GateOutcome8418.book8418(gate, "MOONSHOT", 60.0, false)
        val st: GateOutcome8418.Stat8418? = GateOutcome8418.stat8418(gate, "MOONSHOT")
        assertEquals(100.0, st!!.n, 0.0)
        assertTrue(GateOutcome8418.refusesWinners8418(st))
        assertTrue("the all-lane record is kept beside the lane's", GateOutcome8418.refusesWinners8418(GateOutcome8418.stat8418(gate)))
        assertTrue("100 refusals, mean - SE far above +2%: the door refuses winners", GateOutcome8418.retiresAt100_8418(gate, "MOONSHOT"))
        assertFalse("another lane has its own record", GateOutcome8418.retiresAt100_8418(gate, "SHITCOIN"))
        assertTrue(GateOutcome8418.statusLine8418().contains("REFUSES_WINNERS"))

        val losers = "CHASING_SPIKE_8065"
        repeat(150) { GateOutcome8418.book8418(losers, "SHITCOIN", -12.0 + (it % 3), false) }
        assertFalse(GateOutcome8418.retiresAt100_8418(losers, "SHITCOIN"))
        assertTrue(GateOutcome8418.statusLine8418().contains("$losers n150/-11.0%/wr0%/run0% refuses_losers"))
        GateOutcome8418.resetForTests8418()
    }

    @Test fun anImplausibleTailNeverRetiresAGate() {
        GateOutcome8418.resetForTests8418()
        // 100 refusals: 5 "labels" at +5,000% (above the 8185 cap) and 95 losers — the robust read refuses the proof
        repeat(95) { GateOutcome8418.book8418("TIMING_RECORD_NEGATIVE_8094", "EXPRESS", -10.0, false) }
        repeat(5) { GateOutcome8418.book8418("TIMING_RECORD_NEGATIVE_8094", "EXPRESS", 5_000.0, true) }
        assertFalse(GateOutcome8418.retiresAt100_8418("TIMING_RECORD_NEGATIVE_8094", "EXPRESS"))
        GateOutcome8418.resetForTests8418()
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexAuthority8366
import com.lifecyclebot.engine.cortex.CortexPredictiveProof8395
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import org.junit.Assert.*
import org.junit.Test

class Aate8395PrecedenceProofTest {
    private fun ev(id: String, n: Int, mean: Double, se: Double, kind: String = CandidateAdmission8136.KIND_SETUP, suspect: Boolean = false) =
        Evidence8136(kind, id, n, mean, se, suspect8290 = suspect)
    private fun cand(sel: Evidence8136, laneLabel: Evidence8136?, laneR: DoubleArray? = null, other: Evidence8136? = null) = Candidate8136(
        mint = "MINT8395p", lane = "MOONSHOT", selectedSetup = sel.id, regime = "TRENDING", stage = "BASE_START",
        priceAgeMs = 2_000L, expectedGrossPct = 40.0, costPct = 6.0, downsidePct = 15.0,
        expectedGrossBySetup = mapOf(sel.id to 40.0), setups = listOfNotNull(sel, other), genericScore = 40.0, genericFloor = 30.0,
        live = true, laneRealised8325 = laneR, laneLabel8325 = laneLabel)
    private val weakLane = ev("MOONSHOT", 200, -3.0, 1.0, kind = "LANE")

    // ── R5 precedence ──
    @Test fun supportedExactSetupOutranksWeakLaneAverage() {
        assertTrue(CandidateAdmission8136.decide8136(cand(ev("A", 60, 20.0, 3.0), weakLane)).admitted)
    }
    @Test fun unsupportedSetupWithWeakLaneAverageIsRefusedByTheLanePrior() {
        assertEquals(CandidateAdmission8136.LIVE_LANE_LABELS_8325, CandidateAdmission8136.decide8136(cand(ev("A", 60, 1.0, 3.0), weakLane)).reason)
        assertFalse("suspect records never supersede", CandidateAdmission8136.setupProofSupersedesLanePrior8395(ev("A", 60, 20.0, 3.0, suspect = true), 6.0))
    }
    @Test fun supportedSetupStillMeetsBindingRealisedLosses() {
        val d = CandidateAdmission8136.decide8136(cand(ev("A", 60, 20.0, 3.0), weakLane, laneR = doubleArrayOf(7.0, -9.0)))
        assertEquals(CandidateAdmission8136.LIVE_LANE_LOSING_8325, d.reason)
    }
    @Test fun anotherSetupsProofIsNotInherited() {
        val d = CandidateAdmission8136.decide8136(cand(ev("A", 12, 1.0, 3.0), weakLane, other = ev("B", 80, 25.0, 3.0)))
        assertFalse(d.admitted && d.basis.contains("A:"))
        assertNotEquals("the chosen A is not proven by B's record", true, CandidateAdmission8136.setupProofSupersedesLanePrior8395(ev("A", 12, 1.0, 3.0), 6.0))
    }

    // ── R4 common proof ──
    private fun r(verdict: CortexAuthority8366.Verdict8366, matured: Int, missing: Int, sCov: Double, rCov: Double, oos: Int = 120) =
        CortexAuthority8366.Result8366(verdict, oos, 0.1, 5.0, -2.0, 30, 30, 1.0, 6.0, 0.5,
            matured8392 = matured, missing8392 = missing, strongCoverage8392 = sCov, restCoverage8392 = rCov)
    @Test fun incompleteOrMixedProofIsUnknownAndReservesNoSlot() {
        val valid = CortexPredictiveProof8395.of("MOONSHOT", 1, r(CortexAuthority8366.Verdict8366.GRANTED, 120, 0, 1.0, 0.9))
        assertEquals(CortexPredictiveProof8395.Status.VALID, valid.status)
        val incomplete = CortexPredictiveProof8395.of("MOONSHOT", 1, r(CortexAuthority8366.Verdict8366.INCOMPLETE_OUTCOMES, 40, 60, 0.4, 0.9))
        assertEquals(CortexPredictiveProof8395.Status.UNKNOWN, incomplete.status)
        val lowCover = CortexPredictiveProof8395.of("MOONSHOT", 1, r(CortexAuthority8366.Verdict8366.SPREAD_BELOW_COST, 100, 40, 0.5, 0.95))
        assertEquals(CortexPredictiveProof8395.Status.UNKNOWN, lowCover.status)
        assertFalse(CortexPredictiveProof8395.slotReservable(CortexPredictiveProof8395.Status.UNKNOWN, true))
        assertFalse("no eligible executable STRONG candidate: no slot", CortexPredictiveProof8395.slotReservable(CortexPredictiveProof8395.Status.VALID, false))
        assertTrue(CortexPredictiveProof8395.slotReservable(CortexPredictiveProof8395.Status.VALID, true))
    }
    @Test fun negativesStillBindAndSlotWiringUsesTheSharedProof() {
        val cx = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val c = cx.substringAfter("private fun constitutionRefusal(").substringBefore("// ── Cortex v9: self-consistency")
        assertTrue(c.contains("return negativeOrUnknown8395(\"C3_PROVEN_NEGATIVE_EDGE\", a.lane)"))
        assertTrue(c.contains("return negativeOrUnknown8395(\"C6_SETUP_DEAD_8082\", a.lane)"))
        assertTrue(c.contains("CortexPredictiveProof8395.slotReservable(proofStatus8395(a.lane), eligibleStrongNow8395(a.lane, ts.mint))"))
        val neg = cx.substringAfter("private fun negativeOrUnknown8395(").substringBefore("/** A STRONG read")
        assertTrue("the rule name returned is unchanged: still binding", neg.contains("return rule"))
    }
}

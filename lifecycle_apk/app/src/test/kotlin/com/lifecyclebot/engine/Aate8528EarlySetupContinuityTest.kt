package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136 as Admission
import com.lifecyclebot.engine.truth.AdmissionEdge8180
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.*
import org.junit.Test

class Aate8528EarlySetupContinuityTest {
    private fun early(lane: String = "MOONSHOT") = Admission.Candidate8136(
        mint = "early8528-$lane", lane = lane, selectedSetup = "PRE_IGNITION_BASE",
        live = true, candidateVersion = 8528L, stage = "NO_SETUP", entryWait8506 = "NO_PHASE_TRIGGER",
        priceAgeMs = 10L, expectedGrossPct = 12.0, costPct = 4.0, genericScore = 70.0, genericFloor = 40.0,
        nativeQualified8213 = true, nativeSetup8213 = "PRE_IGNITION_BASE", firedSetups8528 = setOf("PRE_IGNITION_BASE"),
    )

    @Test fun observed_early_setup_does_not_wait_for_generic_phase_or_historical_proof() {
        for (lane in listOf("MOONSHOT", "SHITCOIN", "EXPRESS", "QUALITY", "BLUECHIP", "TREASURY",
            "CASHGEN", "CORE", "CYCLIC", "MANIPULATED", "DIP_HUNTER", "PROJECT_SNIPER")) {
            val d = Admission.decide8136(early(lane))
            assertEquals(lane, Admission.Terminal8136.ADMIT, d.terminal)
            assertFalse("current setup is not fabricated statistical proof", d.specific)
            assertTrue(d.nativePhaseWitness8528)
            assertEquals("PRE_IGNITION_BASE", d.chosenSetup)
            assertTrue(d.trace.any { it.contains("observedSetup8528=") })
        }
    }

    @Test fun no_match_or_stale_native_opinion_does_not_gain_the_exception() {
        val c = early()
        for (unmatched in listOf(c.copy(firedSetups8528 = emptySet()),
            c.copy(firedSetups8528 = setOf("DIFFERENT_SETUP")), c.copy(nativeQualified8213 = false),
            c.copy(genericScore = 20.0), c.copy(expectedGrossPct = Double.NaN), c.copy(priceAgeMs = Long.MAX_VALUE))) {
            assertFalse(Admission.nativePhaseWitness8528(unmatched))
            assertFalse(Admission.decide8136(unmatched).admitted)
        }
    }

    @Test fun dumps_missing_flow_cost_and_measured_losses_still_refuse_or_defer() {
        val c = early()
        for (wait in listOf("NO_RECLAIM", "NO_BUY_VOLUME", "NO_IGNITION", "FLOW_UNOBSERVED", "STAGE_UNRESOLVED"))
            assertFalse(wait, Admission.decide8136(c.copy(entryWait8506 = wait)).admitted)
        for (gap in listOf("price:UNKNOWN", "quote:UNKNOWN", "liquidity:UNKNOWN", "sellability:UNKNOWN", "decimals:UNKNOWN"))
            assertFalse(gap, Admission.decide8136(c.copy(missingInputs = listOf(gap))).admitted)
        assertFalse(Admission.decide8136(c.copy(costPct = 10.0)).admitted)
        assertEquals("SAFETY_REFUSED_8136", Admission.decide8136(c.copy(safetyRefusal = "MINT_AUTH")).reason)
        assertEquals("STRUCTURAL_RISK_8136", Admission.decide8136(c.copy(structuralRisk = "RUG_PRONE")).reason)
        val losing = Admission.Evidence8136(Admission.KIND_SETUP, c.selectedSetup!!, 60, -12.0, 1.0)
        assertFalse(Admission.decide8136(c.copy(setups = listOf(losing))).admitted)
    }

    @Test fun same_phase_cannot_be_rejected_again_but_new_danger_and_costs_still_bind() {
        val c = early()
        val d = Admission.decide8136(c)
        assertTrue(LiveEdgeGate7877.stagePriorAlreadyResolved(d, c.lane, "STAGE_LANE_MISFIT_7928_MOONSHOT_NO_SETUP"))
        for (why in listOf("STAGE_LANE_MISFIT_7928_MOONSHOT_DUMPING", "STAGE_LANE_MISFIT_7928_QUALITY_NO_SETUP",
            "STAGE_PROVEN_LOSING_7928_MOONSHOT_NO_SETUP", "COST_ABOVE_GROSS", "STAGE_RUG_PRONE_7928_MOONSHOT"))
            assertFalse(why, LiveEdgeGate7877.stagePriorAlreadyResolved(d, c.lane, why))
        AdmissionEdge8180.note8180("LIVE", c.mint, c.lane, c.candidateVersion, d, 10_000L)
        val proof = AdmissionEdge8180.proof8198("LIVE", c.mint, c.lane, c.candidateVersion, 10_100L)!!
        assertFalse(proof.specificEdge)
        assertNull(AdmissionEdge8180.remainingLiveWait(c.mint, c.lane, c.candidateVersion, c.stage, c.entryWait8506, 10_100L, proof))
        assertEquals("NO_RECLAIM", AdmissionEdge8180.remainingLiveWait(c.mint, c.lane, c.candidateVersion, "DUMPING", "NO_RECLAIM", 10_100L, proof))
        assertEquals(c.entryWait8506, AdmissionEdge8180.remainingLiveWait(c.mint, c.lane, c.candidateVersion + 1, c.stage, c.entryWait8506, 10_100L, proof))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.*
import org.junit.Test

/** The composed production entry authority is exercised by the Android module unit-test gate. */
class Aate9043EntryContinuityTest {
    private fun candidate(authority: Boolean = true) = CandidateAdmission8136.Candidate8136(
        mint = "entry-9043", lane = "EXPRESS", selectedSetup = "IGNITION_5M", costPct = 4.0, live = true,
        edge8537 = EdgeModel8537.Verdict(12.0, authority, "MIND8842", 25_000L, 500, 12.0, 2.0, 0.5),
        launchSnapshotCaptured9043 = true)

    private fun refused(reason: String) = CandidateAdmission8136.Decision8136(
        CandidateAdmission8136.Terminal8136.REFUSE, reason, true, "IGNITION_5M", -1.0, listOf("fixture"), costPct = 4.0)

    @Test fun composedAdmissionKeepsModelExemptionForLearnedStageButHardGuardsStillBind() {
        val stage = "LIVE_STAGE_LOSSES_8626"
        val admitted = LaneAuthority8577.apply8577(candidate(), refused(stage), stage)
        assertTrue(admitted.compact, admitted.admitted)
        assertTrue(admitted.trace.any { it == "entryStateAdvisory9043=$stage" })
        for (hard in listOf("MEME_BUYS_THE_HIGH_8582", "SAFETY_REFUSED_8136", "DATA_INCOMPLETE_DEFER_8136", "LP_OWNERSHIP_PROOF_PENDING_8522")) {
            val stopped = LaneAuthority8577.apply8577(candidate(), refused(stage), hard)
            assertFalse(hard, stopped.admitted)
            assertEquals(hard, stopped.reason)
        }
        assertFalse("no authoritative model: no newly invented exemption",
            LaneAuthority8577.apply8577(candidate(false), refused(stage), stage).admitted)
    }

    @Test fun dataCauseListsExcludeEntryConditionsAlreadyAdjudicatedAsAdvisory() {
        assertEquals(listOf("PRICE_STALE"), CandidateAdmission8136.deferCauses8700(
            listOf("entryWait8506=NO_RECLAIM doorAdvisory", "missing=price")))
        assertEquals(listOf("CRITICAL_LIQUIDITY", "BUY_DOOR_PROOF_HOLDER_PENDING"), CandidateAdmission8136.deferCauses8700(
            listOf("entryWait8506=FLOW_UNOBSERVED overriddenBy=EDGE_MODEL_8868", "criticalUnobserved8170=liquidity:UNKNOWN",
                "missing=proof:HOLDER_PENDING")))
        assertEquals(listOf("ENTRY_WAIT_FLOW_UNOBSERVED"), CandidateAdmission8136.deferCauses8700(
            listOf("entryWait8506=FLOW_UNOBSERVED", "missing=swaps,bp,chg")))
    }

}

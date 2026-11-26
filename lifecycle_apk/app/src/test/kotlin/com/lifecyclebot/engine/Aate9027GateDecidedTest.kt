package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdmissionEdge8180
import com.lifecyclebot.engine.truth.LaneAuthority8577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9027 — the gate decided; the authorizer does not decide again: after the final gate's ALLOW a soft entry wait binds
 * only when its own graded record protects, a danger stage and every non-soft wait bind as before, and an allowed
 * candidate the authorizer refuses frees its lane's probe slot.
 */
class Aate9027GateDecidedTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val mint = "Gate9027AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAApump"
    private val now = 1_795_667_000_000L
    private fun after(stage: String, wait: String?, version: Long = 77L, proof: AdmissionEdge8180.Proof8198? = null, binds: Boolean = false) =
        AdmissionEdge8180.waitAfterGate9027(mint, "EXPRESS", version, stage, wait, now, proof) { binds }

    @Test fun aSoftWaitWithNoProofIsAdvisoryAfterTheGatesAllow() {
        for (w in listOf("NO_PHASE_TRIGGER", "FLOW_UNOBSERVED", "STAGE_UNRESOLVED", "NO_BUY_VOLUME", "NO_IGNITION")) {
            assertEquals("the old read keeps it", w, AdmissionEdge8180.remainingLiveWait(mint, "EXPRESS", 77L, "NO_SETUP", w, now, null))
            assertNull(w, after("NO_SETUP", w))
            assertNull("no candidate generation on the decision", after("NO_SETUP", w, version = 0L))
        }
        assertNull("nothing to wait for", after("BASE_START", null))
        assertTrue(AdmissionEdge8180.waitLine9027().contains("ADVISORY_AFTER_GATE:NO_PHASE_TRIGGER:NO_ADMIT_PROOF="))
        assertTrue(AdmissionEdge8180.waitLine9027().contains("ADVISORY_AFTER_GATE:NO_PHASE_TRIGGER:NO_VERSION="))
    }

    @Test fun itBindsWhenItsOwnRecordProtects() {
        assertEquals("FLOW_UNOBSERVED", after("NO_SETUP", "FLOW_UNOBSERVED", binds = true))
        assertTrue(AdmissionEdge8180.waitLine9027().contains("BOUND_BY_RECORD:FLOW_UNOBSERVED="))
        // the default test is the lane authority's own: none of the soft waits is a named wall there
        for (w in AdmissionEdge8180.SOFT_WAITS_9027) {
            val named = "ENTRY_${w}_8506"
            assertFalse(named, listOf("SAFETY", "RUG", "DATA_", "STALE", "LANE_LIQ", "KILL_SWITCH", "HALT", "WALLET", "BLIND", "IDENTITY").any { named.contains(it) })
        }
        assertFalse(LaneAuthority8577.binding8577(null))
    }

    @Test fun aDangerStageRugProneAndAnyOtherWaitStillBind() {
        assertEquals("NO_RECLAIM", after("DUMPING", "NO_RECLAIM"))
        assertEquals("NO_RECLAIM", after("PEAK_EXHAUSTION", "NO_RECLAIM"))
        assertEquals("a soft wait on a coin that has turned", "FLOW_UNOBSERVED", after("DUMPING", "FLOW_UNOBSERVED"))
        assertEquals("RUG_PRONE", after("RUG_PRONE", "RUG_PRONE"))
        assertEquals("RUG_PRONE", after("NO_SETUP", "RUG_PRONE"))
        assertEquals("SOMETHING_ELSE", after("NO_SETUP", "SOMETHING_ELSE"))
    }

    @Test fun whyNoProofIsNamed() {
        assertEquals("NO_VERSION", AdmissionEdge8180.whyNoProof9027(mint, "EXPRESS", 0L, now, null))
        assertEquals("NO_ADMIT_PROOF", AdmissionEdge8180.whyNoProof9027("NoProof9027AAAAAAAAAAAAAAAAAAAAAAAAAAAAApump", "EXPRESS", 5L, now, null))
        assertEquals(setOf("NO_PHASE_TRIGGER", "NO_RECLAIM", "NO_BUY_VOLUME", "NO_IGNITION", "FLOW_UNOBSERVED", "STAGE_UNRESOLVED"), AdmissionEdge8180.SOFT_WAITS_9027)
    }

    @Test fun theAuthorizerAndTheExecutorReadTheSameRuleAndARefusalFreesTheProbe() {
        val auth = src("engine/TradeAuthorizer.kt")
        assertTrue(auth.contains("AdmissionEdge8180.waitAfterGate9027(\n                mint, requestedBook.name, candidateVersion7624, fit.stage.name, observedWait, now)"))
        assertFalse("the authorizer no longer re-imposes the wait as a wall", auth.contains("AdmissionEdge8180.remainingLiveWait("))
        assertTrue(auth.contains("LaneRealisedProbe8424.release9024(requestedBook.name, mint, reason)"))
        assertTrue(src("engine/LiveEntryEvidence7863.kt").contains("AdmissionEdge8180.waitAfterGate9027(ts.mint, lane, intent.candidateVersion, it.stage.name, observed, now, intent.admissionProof)"))
        assertTrue(src("engine/DiagnosisBrief8438.kt").contains("AdmissionEdge8180.waitLine9027()"))
    }
}

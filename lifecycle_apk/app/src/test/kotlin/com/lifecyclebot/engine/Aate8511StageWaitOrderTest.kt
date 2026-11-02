package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** V5.0.8511 §4A — a generic stage/flow wait must not refuse before the candidate's own supported setup is evaluated. */
class Aate8511StageWaitOrderTest {
    private val base = CandidateAdmission8136.Candidate8136("m8511", "SHITCOIN", "PULLBACK", live = true,
        entryWait8506 = "NO_PHASE_TRIGGER", expectedGrossPct = 20.0, costPct = 4.5, genericScore = 80.0, genericFloor = 15.0)
    private val supported = Evidence8136(CandidateAdmission8136.KIND_SETUP, "PULLBACK", 60, 9.0, 1.0)

    @Test fun withoutSupportedSetupTheBindingStageWaitRefuses() {
        val d = CandidateAdmission8136.decide8136(base) { true }
        assertEquals(CandidateAdmission8136.Terminal8136.REFUSE, d.terminal)
        assertEquals("ENTRY_NO_PHASE_TRIGGER_8506", d.reason)
    }

    @Test fun aSupportedSetupIsEvaluatedPastTheGenericWait() {
        val d = CandidateAdmission8136.decide8136(base.copy(setups = listOf(supported))) { true }
        assertNotEquals("ENTRY_NO_PHASE_TRIGGER_8506", d.reason)
    }

    @Test fun aDemotedWaitDoorIsAdvisoryLikeEveryOtherDoor() {
        val d = CandidateAdmission8136.decide8136(base) { it != "ENTRY_NO_PHASE_TRIGGER_8506" }
        assertNotEquals("ENTRY_NO_PHASE_TRIGGER_8506", d.reason)
    }

    @Test fun missingFlowStillDefersEvenWithASupportedSetup() {
        val d = CandidateAdmission8136.decide8136(base.copy(entryWait8506 = "FLOW_UNOBSERVED", setups = listOf(supported))) { false }
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, d.terminal)
    }

    @Test fun aSuspectSetupRecordNeverOverridesTheWait() {
        val d = CandidateAdmission8136.decide8136(base.copy(setups = listOf(supported.copy(suspect8290 = true)))) { true }
        assertEquals("ENTRY_NO_PHASE_TRIGGER_8506", d.reason)
    }
}

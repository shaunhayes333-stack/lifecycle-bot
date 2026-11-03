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

    @Test fun exactAdmittedWaitSurvivesAuthorizationButChangedEvidenceDoesNot() {
        val book = com.lifecyclebot.engine.truth.AdmissionEdge8180
        val c = base.copy(stage = "NO_SETUP", candidateVersion = 731L, setups = listOf(supported))
        val d = CandidateAdmission8136.decide8136(c) { false }
        assertEquals(CandidateAdmission8136.Terminal8136.ADMIT, d.terminal)
        book.note8180("LIVE", c.mint, c.lane, c.candidateVersion, d, 5000L)
        fun remaining(wait: String, lane: String = c.lane, version: Long = c.candidateVersion, stage: String = c.stage) =
            book.remainingLiveWait(c.mint, lane, version, stage, wait, 5100L)
        assertEquals(null, remaining("NO_PHASE_TRIGGER"))
        assertEquals("NO_PHASE_TRIGGER", remaining("NO_PHASE_TRIGGER", version = 732L))
        assertEquals("NO_PHASE_TRIGGER", remaining("NO_PHASE_TRIGGER", lane = "QUALITY"))
        assertEquals("NO_PHASE_TRIGGER", remaining("NO_PHASE_TRIGGER", stage = "DUMPING"))
        for (wait in listOf("RUG_PRONE", "FLOW_UNOBSERVED", "STAGE_UNRESOLVED", "NO_BUY_VOLUME"))
            assertEquals(wait, remaining(wait))
        assertEquals("NO_PHASE_TRIGGER", book.remainingLiveWait(c.mint, c.lane, c.candidateVersion, c.stage,
            "NO_PHASE_TRIGGER", 5001L + book.TTL_MS_8180))
    }

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

    @Test fun observedTapeVelocityClearsOnlyAuxiliaryPhaseWaitNotSafetyOrExecutableData() {
        val run = base.copy(nativeQualified8213 = true, nativeSetup8213 = "RUNNER_FORMING_8260",
            highVelocityTape8520 = true, tapeLowerGross8520 = 30.0, entryWait8506 = "FLOW_UNOBSERVED",
            missingInputs = listOf("shape:UNKNOWN"), expectedGrossPct = 25.0, costPct = 4.5)
        assertEquals(true, CandidateAdmission8136.highVelocityFastTrack8520(run))
        val d = CandidateAdmission8136.decide8136(run) { true }
        assertNotEquals("DATA_INCOMPLETE_DEFER_8136", d.reason)
        assertEquals(true, d.trace.any { it.contains("fastTrack8520=FLOW_UNOBSERVED") })
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(highVelocityTape8520 = false)))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(tapeLowerGross8520 = Double.NaN)))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(tapeLowerGross8520 = 8.0)))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(missingInputs = listOf("quote:UNKNOWN"))))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(costPct = Double.NaN)))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(priceAgeMs = 90_000L, hotLaunch = true)))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(safetyRefusal = "MINT_AUTH")))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(structuralRisk = "RUG_PRONE")))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(watchlistRecheck8507 = true)))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(genericScore = 10.0)))
        assertEquals(false, CandidateAdmission8136.highVelocityFastTrack8520(run.copy(lane = "QUALITY")))
    }

    @Test fun observedTapeVelocityCanClearTheGenericPhaseAndReclaimDoor() {
        for (wait in listOf("NO_PHASE_TRIGGER", "NO_RECLAIM", "STAGE_UNRESOLVED")) {
            val run = base.copy(nativeQualified8213 = true, nativeSetup8213 = "RUNNER_FORMING_8260",
                highVelocityTape8520 = true, tapeLowerGross8520 = 30.0, entryWait8506 = wait, expectedGrossPct = 25.0, costPct = 4.5)
            val d = CandidateAdmission8136.decide8136(run) { true }
            assertNotEquals("ENTRY_${wait}_8506", d.reason)
            assertEquals(true, d.trace.any { it.contains("overriddenBy=FRESH_EXECUTED_TAPE_8520") })
        }
    }

    @Test fun aSuspectSetupRecordNeverOverridesTheWait() {
        val d = CandidateAdmission8136.decide8136(base.copy(setups = listOf(supported.copy(suspect8290 = true)))) { true }
        assertEquals("ENTRY_NO_PHASE_TRIGGER_8506", d.reason)
    }
}

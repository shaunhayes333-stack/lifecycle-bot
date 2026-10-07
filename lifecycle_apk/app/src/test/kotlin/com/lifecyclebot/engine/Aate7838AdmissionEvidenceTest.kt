package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurrentCandidateExpectancy7832
import com.lifecyclebot.engine.truth.ExecutableEntryAuthority6450
import com.lifecyclebot.engine.truth.LearnedAdmissionInputs6909
import com.lifecyclebot.engine.truth.PredictiveEntryOracle6915
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class Aate7838AdmissionEvidenceTest {
    private fun mode(paper: Boolean) {
        RuntimeModeAuthority.publishConfig(paper, true)
        RuntimeModeAuthority.publishUiMode(paper)
        RuntimeModeAuthority.publishExecutorMode(paper)
        RuntimeModeAuthority.publishPipelineMode(paper)
    }
    @Before fun setup() { mode(false) }
    @After fun cleanup() { mode(true) }

    private fun candidate(quality: String = "A", phase: String = "BREAKOUT_EXPANSION") =
        LearnedAdmissionInputs6909.build(
            lane = "EVIDENCE_TEST_7838", mint = "Candidate7838", requestedSizeSol = 0.05,
            entryScore = 85, minExecutableSol = 0.04, probeSizeSol = 0.04,
            qualityHint = quality, edgePhaseHint = phase, candidateConfidenceHint = 0.85,
            paperMode = false,
        )

    @Test fun advisory_numbers_do_not_gain_authority_before_degeneracy_alarm() {
        assertFalse(CurrentCandidateExpectancy7832.oracleNumericAuthority7838(true, false, false, false))
        assertTrue(CurrentCandidateExpectancy7832.oracleNumericAuthority7838(true, false, true, false))
        assertTrue(CurrentCandidateExpectancy7832.oracleNumericAuthority7838(true, false, false, true))
        assertFalse(CurrentCandidateExpectancy7832.oracleNumericAuthority7838(true, true, true, true))
        assertFalse(CurrentCandidateExpectancy7832.oracleNumericAuthority7838(false, false, true, true))
    }

    @Test fun labels_remain_forecasts_but_do_not_become_terminal_cohorts() {
        val lane = "LABEL7838"
        repeat(30) { ForwardOutcomeModel.recordLabel7734(lane, 85, "A", "NORMAL", "EXPANSION", -20.0) }
        val forecast = ForwardOutcomeModel.forecast(lane, 85, "A", "NORMAL", "EXPANSION")
        assertEquals("fine_label_prior_assessed6991", forecast.source)
        assertFalse(ForwardOutcomeModel.hasTerminalEvidence7838(forecast))
        assertEquals(0L, ForwardOutcomeModel.cohortEvidence6911(lane, 85).samples)
        val inputs = LearnedAdmissionInputs6909.build(
            lane = lane, mint = "LabelOnly7838", requestedSizeSol = 0.05,
            entryScore = 85, minExecutableSol = 0.04, probeSizeSol = 0.04,
            qualityHint = "A", edgePhaseHint = "EXPANSION", candidateConfidenceHint = 0.85, paperMode = false,
        )
        assertEquals(0, inputs.oracleRawCohortN7154)
        assertTrue("labels must not supply a terminal veto: $inputs", inputs.expectedPnl > 0.0)
        assertEquals(ExecutableEntryAuthority6450.Verdict.ALLOW, ExecutableEntryAuthority6450.gate(inputs).verdict)
        assertTrue(ForwardOutcomeModel.hasTerminalEvidence7838(forecast.copy(source = "fine")))
        for (source in listOf("fine_paper_prior", "fine_paper_prior_assessed6991", "fine_label_prior", "coarse_label_prior", "coarse_label_prior_assessed6991", "unknown")) {
            assertFalse(ForwardOutcomeModel.hasTerminalEvidence7838(forecast.copy(source = source)))
        }
    }

    @Test fun current_positive_setup_reaches_real_learned_gate_without_history() {
        val inputs = candidate()
        assertTrue("current setup must supply positive net EV: $inputs", inputs.expectedPnl > 0.0)
        assertEquals(0, inputs.oracleRawCohortN7154)
        assertEquals(ExecutableEntryAuthority6450.Verdict.ALLOW,
            ExecutableEntryAuthority6450.gate(inputs).verdict)
    }

    @Test fun structural_refusals_survive_positive_arithmetic_through_assembler() {
        for (inputs in listOf(candidate(phase = "WAIT"), candidate(quality = "F"))) {
            assertFalse(inputs.expectedPnl.isFinite() && inputs.expectedPnl > 0.0)
            assertEquals(ExecutableEntryAuthority6450.Verdict.DENY_LEARNED_NEGATIVE_6846,
                ExecutableEntryAuthority6450.gate(inputs).verdict)
        }
    }

    @Test fun measured_losses_and_safety_still_refuse_after_positive_setup() {
        val positive = candidate()
        for (inputs in listOf(
            positive.copy(expectedPnl = -0.05, oracleRawCohortN7154 = 30,
                oracleEvidencedRefuse7340 = true, oracleVerdict6915 = PredictiveEntryOracle6915.Verdict.REFUSE),
            positive.copy(oracleHardSafety7287 = true), positive.copy(policyHardBlock = true),
            positive.copy(expectedPnl = 0.0), positive.copy(expectedPnl = Double.NaN),
        )) assertEquals(ExecutableEntryAuthority6450.Verdict.DENY_LEARNED_NEGATIVE_6846,
            ExecutableEntryAuthority6450.gate(inputs).verdict)
    }

    @Test fun high_cost_or_invalid_current_estimate_never_becomes_permission() {
        val costly = CurrentCandidateExpectancy7832.estimate(60, 0.60, "B", "MOMENTUM", 0.5, 9.0)
        assertFalse(CurrentCandidateExpectancy7832.admissionNetPct7838(costly)!!.isFinite())
        assertNull(CurrentCandidateExpectancy7832.admissionNetPct7838(null))
        assertFalse(CurrentCandidateExpectancy7832.admissionNetPct7838(
            costly.copy(positive = true, netExpectancyPct = Double.NaN))!!.isFinite())
    }

    @Test fun unresolved_carry_is_not_presented_as_managed_after_settling() {
        val panel = com.lifecyclebot.engine.truth.OpenPositionPanel7807
        for (basis in listOf(
            com.lifecyclebot.engine.truth.OpenPositionPanel7807.BasisState7807.BASIS_UNCERTAIN,
            com.lifecyclebot.engine.truth.OpenPositionPanel7807.BasisState7807.QUARANTINED_ACCOUNTING,
        )) {
            assertEquals("BASIS REPAIR NEEDED", panel.managementState7807(false, 1L, 100_000L, basis))
            assertEquals("VERIFYING FILL", panel.managementState7807(true, 1L, 100_000L, basis))
        }
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CurrentCandidateExpectancy7832 as Expectancy
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8871CryptoExpectancyAuthorityTest {
    private fun estimate(pWin: Double?, slip: Double) = Expectancy.estimate(65, 0.65, "", "MOMENTUM", pWin, slip)

    @Test fun excluded_negative_oracle_cannot_veto_positive_current_candidate_arithmetic() {
        val before = estimate(0.0, 1.8)
        val after = estimate(Expectancy.oracleProbability8871(false, 0.0), 1.8)
        assertFalse(before.positive)
        assertEquals(-0.55, before.netExpectancyPct, 1e-9)
        assertTrue(after.positive)
        assertEquals(0.45, after.netExpectancyPct, 1e-9)
        assertEquals(estimate(null, 1.8), after)
    }

    @Test fun excluded_positive_oracle_cannot_create_permission_for_negative_current_candidate_arithmetic() {
        assertTrue(estimate(1.0, 2.6).positive)
        val after = estimate(Expectancy.oracleProbability8871(false, 1.0), 2.6)
        assertFalse(after.positive)
        assertEquals(-0.35, after.netExpectancyPct, 1e-9)
        assertFalse(Expectancy.admissionNetPct7838(after)!!.isFinite())
    }

    @Test fun unavailable_degenerate_and_invalid_probabilities_use_the_existing_neutral_default() {
        for (p in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1, 0.0, 0.5, 1.0)) {
            assertNull(Expectancy.oracleProbability8871(false, p))
            assertEquals(estimate(null, 1.8), estimate(Expectancy.oracleProbability8871(false, p), 1.8))
        }
        for (p in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1))
            assertNull(Expectancy.oracleProbability8871(true, p))
        assertEquals(0.2, Expectancy.oracleProbability8871(true, 0.2)!!, 0.0)
        val degenerate = Expectancy.oracleNumericAuthority7838(true, true, true, true)
        assertFalse(degenerate)
        assertNull(Expectancy.oracleProbability8871(degenerate, 0.2))
    }

    @Test fun authoritative_negative_oracle_or_terminal_cohort_remains_authoritative_over_positive_fallback() {
        val positive = estimate(null, 1.8)
        assertTrue(positive.positive)
        val oracle = Expectancy.admissionEstimate8871(true, -7.0, true, 10.0, positive)
        assertEquals(Expectancy.Basis8871.AUTHORITATIVE_ORACLE, oracle.basis)
        assertEquals(-7.0, oracle.netPct!!, 0.0)
        val terminal = Expectancy.admissionEstimate8871(false, 100.0, true, -4.0, positive)
        assertEquals(Expectancy.Basis8871.TERMINAL_FORWARD, terminal.basis)
        assertEquals(-4.0, terminal.netPct!!, 0.0)
    }

    @Test fun missing_evidence_is_named_unavailable_and_formula_estimates_are_named_priors() {
        val absent = Expectancy.admissionEstimate8871(false, -10.0, false, -10.0, null)
        assertEquals(Expectancy.Basis8871.UNAVAILABLE, absent.basis)
        assertNull(absent.netPct)
        val prior = Expectancy.admissionEstimate8871(false, -10.0, false, -10.0, estimate(null, 1.8))
        assertEquals(Expectancy.Basis8871.CURRENT_CANDIDATE_PRIOR, prior.basis)
        assertEquals(0.45, prior.netPct!!, 1e-9)
        assertFalse(Expectancy.admissionEstimate8871(false, null, false, null, estimate(null, 9.0)).netPct!!.isFinite())
    }

    @Test fun actual_non_entry_phase_and_weak_quality_are_not_promoted_by_probability_neutrality() {
        for (e in listOf(
            Expectancy.estimate(90, 0.9, "A", "WAIT", Expectancy.oracleProbability8871(false, 1.0), 0.5),
            Expectancy.estimate(90, 0.9, "F", "MOMENTUM", Expectancy.oracleProbability8871(false, 1.0), 0.5),
        )) assertFalse(Expectancy.admissionNetPct7838(e)!!.isFinite())
    }

    @Test fun both_existing_admission_assemblers_apply_the_probability_authority_contract() {
        val contract = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalAssetEntryContract6551.kt").readText()
        val learned = File("src/main/kotlin/com/lifecyclebot/engine/truth/LearnedAdmissionInputs6909.kt").readText()
        assertTrue(contract.contains("oracleProbability8871(oracleNumeric7838, oracle7259?.pWin)"))
        assertTrue(learned.contains("oracleProbability8871(oracleUsable7828, oracle6915?.pWin)"))
        assertFalse(contract.contains("oraclePWin = oracle7259?.pWin"))
        assertFalse(learned.contains("oraclePWin = oracle6915?.pWin"))
    }
}

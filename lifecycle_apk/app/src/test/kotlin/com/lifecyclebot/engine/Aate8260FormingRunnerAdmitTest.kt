package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8260 — a forming runner on an unlearned book admits as the runner lane's own qualification and is stamped as
 * a runner entry; hard safety, structure, stale price, critical facts and negative economics still refuse it.
 * V5.0.8279 §SELECT_LIKE_8114 — it admits as the lane's OPINION (specific = false): the 8114 proven-only door
 * (RunnerGrab7967.grab7967 on a proven runner cell, RunnerPlay ticket, cold start, measured edge) decides live money.
 */
class Aate8260FormingRunnerAdmitTest {
    private fun forming(lane: String) = CandidateAdmission8136.Candidate8136(
        mint = "forming-8260-$lane", lane = lane, selectedSetup = null,
        priceAgeMs = 500L,
        missingInputs = listOf("shape:INVALID"),
        expectedGrossPct = 40.0, costPct = 6.0,
        costSizeSol = 0.031, costBreakdown = "size=0.0310SOL known_liq_quote",
        genericScore = 20.0, genericFloor = 15.0,
        nativeQualified8213 = true, nativeSetup8213 = CandidateAdmission8136.RUNNER_FORMING_8260,
    )

    @Test fun formingRunnerAdmitsOnUnlearnedBookAndIsStampedAsRunnerEntry() {
        for (lane in listOf("MOONSHOT", "SHITCOIN", "EXPRESS")) {
            val c = forming(lane)
            val now = System.currentTimeMillis()
            val d = CandidateAdmission8136.decide8136(c.copy(live = true))
            assertEquals(lane, CandidateAdmission8136.Terminal8136.ADMIT, d.terminal)
            assertEquals(lane, "NATIVE_QUALIFIED_ADMIT_8259", d.reason)
            assertFalse(lane, d.specific)
            assertEquals(lane, CandidateAdmission8136.RUNNER_FORMING_8260, d.chosenSetup)
            assertTrue(lane, CandidateAdmission8136.runnerEntryAdmitted8252(c.mint, now + 1_000L))
        }
    }

    @Test fun formingRunnerStillRefusedOnSafetyStaleCriticalOrNegativeEconomics() {
        val c = forming("MOONSHOT")
        assertFalse(CandidateAdmission8136.decide8136(c.copy(safetyRefusal = "HARD_BLOCK")).terminal == CandidateAdmission8136.Terminal8136.ADMIT)
        assertFalse(CandidateAdmission8136.decide8136(c.copy(structuralRisk = "STAGE_RUG_PRONE")).terminal == CandidateAdmission8136.Terminal8136.ADMIT)
        assertEquals("DATA_INCOMPLETE_DEFER_8136", CandidateAdmission8136.decide8136(c.copy(priceAgeMs = 600_000L)).reason)
        assertEquals("DATA_INCOMPLETE_DEFER_8136", CandidateAdmission8136.decide8136(c.copy(missingInputs = listOf("ROUTE"))).reason)
        assertFalse(CandidateAdmission8136.decide8136(c.copy(expectedGrossPct = 3.0)).terminal == CandidateAdmission8136.Terminal8136.ADMIT)
        assertFalse(CandidateAdmission8136.runnerEntryAdmitted8252("never-admitted-8260", System.currentTimeMillis()))
    }
}

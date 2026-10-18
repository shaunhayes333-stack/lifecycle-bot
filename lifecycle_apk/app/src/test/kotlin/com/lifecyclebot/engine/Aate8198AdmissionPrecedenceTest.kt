package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import org.junit.Assert.*
import org.junit.Test

/** 8198 master directive P1 — admission precedence, behavioural (decide8136 outcomes, not source strings). */
class Aate8198AdmissionPrecedenceTest {
    private fun cand(
        setups: List<Evidence8136>, selected: String? = setups.firstOrNull()?.id, cell: Evidence8136? = null,
        stage: Evidence8136? = null, grossBySetup: Map<String, Double> = emptyMap(), gross: Double = 120.0,
    ) = CandidateAdmission8136.Candidate8136(
        mint = "m8198", lane = "SHITCOIN", selectedSetup = selected, stage = "FRESH_LAUNCH", priceAgeMs = 2_000L,
        expectedGrossPct = gross, costPct = 9.0, setups = setups, cell = cell, stageRecord = stage,
        expectedGrossBySetup = grossBySetup, genericScore = 70.0, genericFloor = 50.0,
    )
    private fun setup(id: String, n: Int, mean: Double, se: Double) = Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se)
    private val stage5mLosing = Evidence8136("STAGE", "FRESH_LAUNCH", 80, -12.0, 3.0)

    // Case 1a — the 8114 rule: a stage that loses at 5 minutes but pays at 60 minutes is a runner stage, not refused.
    @Test fun aStageThatPaysLateIsNotAStageLoss() {
        val thin = setup("THIN", 14, 20.0, 15.0)
        val refused = CandidateAdmission8136.decide8136(cand(listOf(thin), stage = stage5mLosing))
        assertEquals("STAGE_PROVEN_LOSING_8136", refused.reason)                     // no late read: unchanged
        val late = stage5mLosing.copy(lateN = 45, lateMeanPct = 38.0)
        val d = CandidateAdmission8136.decide8136(cand(listOf(thin), stage = late))
        assertNotEquals(d.compact, "STAGE_PROVEN_LOSING_8136", d.reason)
        assertEquals(Terminal8136.ADMIT, d.terminal)                                 // the generic prior then decides
        assertTrue(d.trace.any { it.contains("spared late60m") })
        // a thin or non-paying late read spares nothing
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(cand(listOf(thin), stage = stage5mLosing.copy(lateN = 10, lateMeanPct = 40.0))).reason)
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(cand(listOf(thin), stage = stage5mLosing.copy(lateN = 60, lateMeanPct = -1.0))).reason)
    }

    // Case 1b — a SUPPORTED specific setup survives a negative lane-stage prior and trades on its own economics.
    @Test fun aSupportedSetupSurvivesANegativeStagePrior() {
        val supported = setup("LAUNCH_LADDER", 60, 22.0, 4.0)
        val d = CandidateAdmission8136.decide8136(cand(listOf(supported), stage = stage5mLosing))
        assertEquals(d.compact, Terminal8136.ADMIT, d.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
    }

    // Case 2 — a proven-losing selected setup is not admitted because a broader (cell) record is profitable.
    @Test fun aLosingSetupIsNotRescuedByAProfitableCell() {
        val losing = setup("DIP_REVERSAL", 40, -9.0, 2.0)
        val goodCell = Evidence8136(CandidateAdmission8136.KIND_CELL, "PUMP|SHITCOIN|MC_LT10K", 80, 15.0, 3.0)
        val d = CandidateAdmission8136.decide8136(cand(listOf(losing), cell = goodCell))
        assertEquals(d.compact, Terminal8136.REFUSE, d.terminal)
        assertEquals("SETUP_RECORD_LOSING_8136", d.reason)
        // an independently supported ALTERNATIVE setup is a different strategy: it is chosen, on its own economics
        val alt = setup("VOLUME_IGNITION", 60, 25.0, 4.0)
        val withAlt = CandidateAdmission8136.decide8136(cand(listOf(losing, alt), selected = "DIP_REVERSAL", cell = goodCell,
            grossBySetup = mapOf("VOLUME_IGNITION" to 90.0)))
        assertEquals(withAlt.compact, Terminal8136.ADMIT, withAlt.terminal)
        assertEquals("VOLUME_IGNITION", withAlt.chosenSetup)
        // the alternative never inherits the losing default's forecast: unmeasured alt economics defer, never admit
        val unmeasured = CandidateAdmission8136.decide8136(cand(listOf(losing, alt), selected = "DIP_REVERSAL"))
        assertNotEquals(Terminal8136.ADMIT, unmeasured.terminal)
    }

    // Hard safety beats every positive record.
    @Test fun hardSafetyBeatsSpecificEdge() {
        val supported = setup("LAUNCH_LADDER", 60, 22.0, 4.0)
        val d = CandidateAdmission8136.decide8136(cand(listOf(supported)).copy(safetyRefusal = "MINT_AUTHORITY_ACTIVE"))
        assertEquals("SAFETY_REFUSED_8136", d.reason)
    }

    // One rule for both stage readers: the 7928 router and the admission record agree on the late exemption bar.
    @Test fun bothStageReadersUseTheSameLateBar() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/TokenMetricStageRouter.kt").readText()
        assertTrue(src.contains("CandidateAdmission8136.stageLatePays8198("))
        assertEquals(30, CandidateAdmission8136.STAGE_LATE_MIN_N_8198)
    }
}

/** 8198 P0-B — the generic prior is reachable only from a real snapshot score and floor; VALID != SUPPORTED. */
class Aate8198GenericPriorTest {
    private fun base(setups: List<Evidence8136>, score: Double, floor: Double, stage: Evidence8136? = null) =
        CandidateAdmission8136.Candidate8136(
            mint = "mG", lane = "QUALITY", selectedSetup = setups.firstOrNull()?.id, stage = "BASE_BUILDING", priceAgeMs = 1_000L,
            expectedGrossPct = 60.0, costPct = 8.0, setups = setups, stageRecord = stage, genericScore = score, genericFloor = floor,
        )
    private fun setup(id: String, n: Int, mean: Double, se: Double) = Evidence8136(CandidateAdmission8136.KIND_SETUP, id, n, mean, se)

    @Test fun aValidSetupReachesTheGenericPriorAndIsDecidedByTheRealNumbers() {
        val valid = setup("RECLAIM", 16, 18.0, 6.0)                           // mean - 1.5 SE = 9 > 0: VALID, not SUPPORTED (n < 30)
        val clears = CandidateAdmission8136.decide8136(base(listOf(valid), score = 62.0, floor = 48.0))
        assertEquals(clears.compact, "GENERIC_PRIOR_CLEARS_8136", clears.reason)
        assertFalse(clears.specific)
        assertTrue(clears.trace.any { it == "generic=+62.0 floor=+48.0" })
        val refused = CandidateAdmission8136.decide8136(base(listOf(valid), score = 41.0, floor = 48.0))
        assertEquals("GENERIC_SCORE_FLOOR_8136", refused.reason)
        assertFalse(refused.specific)
    }

    @Test fun aSupportedSetupIsASpecificEdgeNotAGenericAdmit() {
        val supported = setup("RECLAIM", 40, 18.0, 4.0)
        val d = CandidateAdmission8136.decide8136(base(listOf(supported), score = 41.0, floor = 48.0))
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)                       // its own record, not the prior, decides
        assertTrue(d.specific)
    }

    @Test fun withoutASnapshotScoreNothingIsGuessed() {
        val valid = setup("RECLAIM", 16, 18.0, 6.0)
        val d = CandidateAdmission8136.decide8136(base(listOf(valid), score = Double.NaN, floor = Double.NaN))
        assertEquals("NO_SUPPORTED_EDGE_8136", d.reason)
        assertFalse(d.specific)                                                   // non-binding: the gate's own floor decides
        assertNull(CandidateAdmission8136.floorVerdict8136(d))
    }

    @Test fun aValidCarryOverAStageLossStillEndsAtTheGenericPrior() {
        val valid = setup("RECLAIM", 16, 18.0, 6.0)
        val stage = Evidence8136("STAGE", "BASE_BUILDING", 80, -12.0, 3.0)
        val d = CandidateAdmission8136.decide8136(base(listOf(valid), score = 62.0, floor = 48.0, stage = stage))
        assertEquals(d.compact, "GENERIC_PRIOR_CLEARS_8136", d.reason)
        assertTrue(d.trace.any { it.contains("carried by setup") })
    }

    @Test fun theGateFeedsItsOwnSnapshotScoreAndFloor() {
        val fdg = java.io.File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val i = fdg.indexOf("genericScore = effectiveEntryScore7292, genericFloor = canonicalFloor7266")
        assertTrue(i > 0)
        assertTrue(fdg.lastIndexOf("val effectiveEntryScore7292 =", i) > 0)       // built after the score is known
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8200 — 5.0.8199 live: FDG allowed 0 of 7,004; lane-wide stage / cell loss records refused 3,849 while the lane's
 * Cortex STRONG reads graded positive on executable-net labels. The STRONG read on this candidate is candidate-specific
 * evidence: it carries a lane-wide stage loss and is an edge at the supported margin, never over the coin's own losing
 * cell, economics, data or safety.
 */
class Aate8200CortexCarryTest {
    private val losingStage = CandidateAdmission8136.Evidence8136("STAGE", "FRESH_LAUNCH", 80, -12.0, 3.0)
    private val strong = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_CORTEX_8200, "STRONG@MOONSHOT", 91, 37.0, 5.0)

    private fun candidate(
        cortex: CandidateAdmission8136.Evidence8136? = null,
        cell: CandidateAdmission8136.Evidence8136? = null,
        gross: Double = 60.0,
    ) = CandidateAdmission8136.Candidate8136(
        mint = "m8200", lane = "MOONSHOT", selectedSetup = null,
        stage = "FRESH_LAUNCH", priceAgeMs = 2_000L,
        expectedGrossPct = gross, costPct = 7.8,
        cell = cell, stageRecord = losingStage, cortexStrong8200 = cortex,
    )

    @Test fun withoutTheCortexTheStageLossStillRefuses() {
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(candidate()).reason)
    }

    @Test fun aStrongReadOnAPositiveExecutableCohortIsTheCandidatesEdge() {
        val d = CandidateAdmission8136.decide8136(candidate(cortex = strong))
        assertEquals(d.compact, CandidateAdmission8136.Terminal8136.ADMIT, d.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", d.reason)
        assertTrue(d.trace.any { it.contains("carried by cortex") })
    }

    @Test fun aThinOrLosingStrongCohortCarriesNothing() {
        val thin = strong.copy(n = 10)
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(candidate(cortex = thin)).reason)
        val losing = strong.copy(meanPct = -4.0, sePct = 2.0)
        assertEquals("STAGE_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(candidate(cortex = losing)).reason)
    }

    @Test fun theCoinsOwnLosingCellAndTheCostRuleStillDecide() {
        val losingCell = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_CELL, "SRC|MOONSHOT|MC_10K", 40, -10.0, 2.0)
        assertEquals("CELL_PROVEN_LOSING_8136", CandidateAdmission8136.decide8136(candidate(cortex = strong, cell = losingCell)).reason)
        assertEquals("EXECUTION_ECONOMICS_8136", CandidateAdmission8136.decide8136(candidate(cortex = strong, gross = 10.0)).reason)
    }

    @Test fun theLiveBuilderAsksTheCortexAndTheCortexGuardsItsRead() {
        val adm = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("cortexStrong8200 = try { com.lifecyclebot.engine.cortex.Cortex7885.strongCohortEvidence8200(ts, lane) }"))
        val cx = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val fn = cx.substringAfter("fun strongCohortEvidence8200(").substringBefore("/** Async (pool)")
        assertTrue(fn.contains("a.bucket != CortexScoreboard7885.Bucket.STRONG || a.staleMark"))
        assertTrue(fn.contains("SafetyTier.HARD_BLOCK"))
        assertTrue(fn.contains("MayhemMode7943.liveRefusal(ts)"))
        assertTrue(fn.contains("board.inverted7948(a.lane, accountMode8077())"))
        assertTrue(fn.contains("firstSightBarred8144(a.lane)"))
        assertTrue(fn.contains("gradeBook8144.executableSeries(a.lane, null, CortexScoreboard7885.Bucket.STRONG.ordinal)"))
    }
}

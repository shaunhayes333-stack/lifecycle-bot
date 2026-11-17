package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.EdgeModel8537
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 8843: a global negative return estimate is not a measured negative return of a narrower proven setup. */
class Aate8843SpecificProofAuthorityTest {
    private fun negative() = EdgeModel8537.Verdict(-19.0, authority = false, modelKey = "MIND8842",
        trainedN = 4_000, oosSelN = 2, oosSelMeanPct = -6.0, oosSelSePct = 3.0,
        oosAllMeanPct = -9.0, refuses8715 = true)
    private val c = CandidateAdmission8136.Candidate8136("test-mint", "MOONSHOT", "PULLBACK",
        expectedGrossPct = 25.0, costPct = 3.0, genericScore = 70.0, genericFloor = 15.0,
        edge8537 = negative())

    @Test fun strongLocalAfterCostRecordOutranksLaneWideNegative() {
        val local = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_SETUP, "PULLBACK", 60, 9.0, 1.0)
        val trace = mutableListOf<String>()
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(c, local, trace))
        assertTrue(trace.any { it.contains("Specific8843") })
        val result = CandidateAdmission8136.decide8136(c.copy(setups = listOf(local)))
        assertEquals(CandidateAdmission8136.Terminal8136.ADMIT, result.terminal)
        assertEquals("SPECIFIC_EDGE_PROVEN_8136", result.reason)
    }

    @Test fun thinSuspectAndUnprovedEvidenceRemainRefused() {
        val kind = CandidateAdmission8136.KIND_SETUP
        val thin = CandidateAdmission8136.Evidence8136(kind, "PULLBACK", 2, 60.0, 1.0)
        val suspect = CandidateAdmission8136.Evidence8136(kind, "PULLBACK", 100, 40.0, 1.0, suspect8290 = true)
        val losing = CandidateAdmission8136.Evidence8136(kind, "PULLBACK", 60, -2.0, 1.0)
        for (e in listOf(thin, suspect, losing))
            assertEquals(CandidateAdmission8136.EDGE_MODEL_NEGATIVE_8537,
                CandidateAdmission8136.edgeModelRefusal8537(c, e, mutableListOf()))
    }

    @Test fun provenRunnerGrabCanOutrankAggregateButNotHardSafety() {
        val grab = CandidateAdmission8136.Evidence8136(CandidateAdmission8136.KIND_RUNNER_GRAB_8245,
            "LATE_RUN", 24, 40.0, 6.0)
        assertNull(CandidateAdmission8136.edgeModelRefusal8537(c, grab, mutableListOf()))
        val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        val decision = source.substringAfter("fun decide8136(").substringBefore("internal fun edgeModelRefusal8537(")
        assertTrue(decision.indexOf("EXECUTION_ECONOMICS_8136") < decision.indexOf("edgeModelRefusal8537(c, edge, trace)"))
    }
}

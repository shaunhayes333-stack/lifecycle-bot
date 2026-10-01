package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7623PinnedFdgCandidateVersionTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

    @Test fun `fdg evaluation pins one candidate generation across all causal surfaces`() {
        val s = src()
        val evaluate = s.substringAfter("fun evaluate(")
        assertTrue(evaluate.contains("val candidateVersion7623 = try {"))
        assertTrue(evaluate.contains("LaneExecutionCoordinator.candidateVersionFor(ts.mint)"))
        assertTrue(evaluate.contains("val causalRoot7232 = candidateVersion7623.toString()"))
        assertTrue(evaluate.contains("fdgCacheKey(ts, candidate, laneName, fdgSide, laneScore, candidateVersion7623)"))
        assertTrue(evaluate.contains("\${ts.mint}:\$candidateVersion7623"))
        assertTrue(evaluate.contains("candidateVersion = candidateVersion7623"))
        assertEquals(
            1,
            Regex("LaneExecutionCoordinator\\.candidateVersionFor\\(ts\\.mint\\)")
                .findAll(evaluate)
                .count()
        )
    }

    @Test fun `fdg cache helper consumes supplied generation rather than rereading authority`() {
        val s = src()
        val helper = s.substringAfter("private fun candidateVersionOf(")
            .substringBefore("private fun runtimeGenerationKey")
        assertTrue(helper.contains("candidateVersion: Long"))
        assertTrue(helper.contains("val canonicalVersion = candidateVersion"))
        assertTrue(!helper.contains("candidateVersionFor(ts.mint)"))
    }
}

package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7622PinnedToolkitCandidateVersionTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/ToolkitSignalSheet.kt").readText()

    @Test fun `toolkit pins one candidate version for contest and causal identity`() {
        val s = src()
        val block = s.substringAfter("V5.0.7622 — pin one candidate generation")
            .substringBefore("val best = candidates.maxByOrNull")
        assertTrue(block.contains("val candidateVersion7622 = LaneExecutionCoordinator.candidateVersionFor(ts.mint)"))
        assertTrue(block.contains("LaneExecutionCoordinator.registerQualifiedContest7621("))
        assertTrue(block.contains("candidateVersion7622,"))
        assertTrue(block.contains("val causalId6647 = \"${ts.mint}:$candidateVersion7622\""))
        assertEquals(
            1,
            Regex("LaneExecutionCoordinator\\.candidateVersionFor\\(ts\\.mint\\)")
                .findAll(block)
                .count()
        )
    }
}

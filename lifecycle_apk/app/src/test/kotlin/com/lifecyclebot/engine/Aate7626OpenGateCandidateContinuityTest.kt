package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7626OpenGateCandidateContinuityTest {
    @Test fun `open gate pins current candidate observation once per attempt`() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val fn = s.substringAfter("private fun canOpenExecutablePositionInternal(")
        assertTrue(fn.contains("val currentCandidateVersion7626 ="))
        assertTrue(fn.contains("LaneExecutionCoordinator.candidateVersionFor(mint).takeIf { it > 0L } ?: 1L"))
        assertTrue(fn.contains("val cv = currentCandidateVersion7626"))
        assertTrue(fn.contains("?: currentCandidateVersion7626"))
        assertTrue(fn.contains("val currentCandidateVersion = currentCandidateVersion7626"))
    }
}

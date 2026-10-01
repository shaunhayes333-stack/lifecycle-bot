package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7621CandidateQualifiedElectionSetTest {
    private fun src(rel:String)=File("src/main/kotlin/com/lifecyclebot/"+rel).readText()

    @Test fun `toolkit publishes exact qualified specialists`() {
        val s=src("engine/ToolkitSignalSheet.kt")
        assertTrue(s.contains("LaneExecutionCoordinator.registerQualifiedContest7621("))
        assertTrue(s.contains("deskHypotheses.keys"))
        assertTrue(s.contains("LaneExecutionCoordinator.candidateVersionFor(ts.mint)"))
    }

    @Test fun `pre fdg election prefers candidate qualified set over source affinity`() {
        val s=src("engine/LaneExecutionCoordinator.kt")
        assertTrue(s.contains("private val qualifiedContests7621"))
        assertTrue(s.contains("fun registerQualifiedContest7621("))
        assertTrue(s.contains("currentQualifiedContest7621(mint, candidateVersion)"))
        assertTrue(s.contains("if (currentQualified7621.isNotEmpty()) return currentQualified7621.toList()"))
        assertTrue(s.contains("qualifiedLanesFor(mint, candidateVersion, laneUpper)"))
    }

    @Test fun `qualified contest resets with coordinator`() {
        val s=src("engine/LaneExecutionCoordinator.kt")
        val block=s.substringAfter("fun resetForTests()")
        assertTrue(block.contains("qualifiedContests7621.clear()"))
    }
}

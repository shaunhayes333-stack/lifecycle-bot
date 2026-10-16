package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7619LearnedFairFreshElectionTest {
    private fun src()=File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()

    @Test fun freshElectionUsesExistingLearnedFairSelector() {
        val s=src()
        val fn=s.substringAfter("fun elect(").substringBefore("fun currentElection6600(")
        assertTrue(fn.contains("pickFreshPrimary(mint, candidateVersion, clean)"))
        assertTrue(fn.contains("explicitPreferred7619"))
        assertFalse(fn.contains("clean.firstOrNull() ?: \"CORE\""))
    }

    @Test fun explicitPreferredStillWinsWhenValid() {
        val s=src()
        val fn=s.substringAfter("fun elect(").substringBefore("fun currentElection6600(")
        assertTrue(fn.contains("preferred?.uppercase()?.takeIf { it in clean }"))
        assertTrue(fn.contains("explicitPreferred7619 ?: pickFreshPrimary(mint, candidateVersion, clean)"))
    }

    @Test fun secondaryLaneUsesSamePriorityModel() {
        val s=src()
        val fn=s.substringAfter("fun elect(").substringBefore("fun currentElection6600(")
        assertTrue(fn.contains("secondaryFresh7803(mint, candidateVersion, clean, primary)"))
        // V5.0.7803 — primary and secondary are both ranked by electionScore7803.
        val primaryFn=s.substringAfter("private fun pickFreshPrimary(").substringBefore("internal fun preferredReadyOwner7820(")
        val secondaryFn=s.substringAfter("private fun secondaryFresh7803(").substringBefore("fun candidateVersionFor(")
        assertTrue(primaryFn.contains("maxByOrNull { electionScore7803(mint, candidateVersion, it, qualified, ready, q) }"))
        assertTrue(secondaryFn.contains("maxByOrNull { electionScore7803(mint, candidateVersion, it, qualified, ready, q) }"))
    }
}

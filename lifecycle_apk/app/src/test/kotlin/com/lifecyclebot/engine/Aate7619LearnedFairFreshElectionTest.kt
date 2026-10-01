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
        assertTrue(fn.contains("pickFreshPrimary(mint, clean)"))
        assertTrue(fn.contains("explicitPreferred7619"))
        assertFalse(fn.contains("clean.firstOrNull() ?: \"CORE\""))
    }

    @Test fun explicitPreferredStillWinsWhenValid() {
        val s=src()
        val fn=s.substringAfter("fun elect(").substringBefore("fun currentElection6600(")
        assertTrue(fn.contains("preferred?.uppercase()?.takeIf { it in clean }"))
        assertTrue(fn.contains("explicitPreferred7619 ?: pickFreshPrimary(mint, clean)"))
    }

    @Test fun secondaryLaneUsesSamePriorityModel() {
        val s=src()
        val fn=s.substringAfter("fun elect(").substringBefore("fun currentElection6600(")
        assertTrue(fn.contains("maxByOrNull { claimPriority(mint, it, clean) }"))
    }
}

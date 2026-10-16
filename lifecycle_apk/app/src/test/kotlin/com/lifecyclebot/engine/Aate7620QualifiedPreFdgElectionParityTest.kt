package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7620QualifiedPreFdgElectionParityTest {
    private fun src()=File("src/main/kotlin/com/lifecyclebot/engine/LaneExecutionCoordinator.kt").readText()

    @Test fun preFdgClaimUsesQualifiedContestNotSingletonCaller() {
        val s=src()
        val fn=s.substringAfter("fun canRequestExecution(").substringBefore("fun duplicateOpenSuppressions()")
        // The qualified contest is now candidate-version scoped.
        assertTrue(fn.contains("val qualified7620 = qualifiedLanesFor(mint, candidateVersion, laneUpper)"))
        assertTrue(fn.contains("filter { laneCanOwnExecution6910(it) }"))
        // V5.0.7774 — natively-refused contenders sit out; the qualified contest is the fallback.
        assertTrue(fn.contains("lanes = willing7774.ifEmpty { qualified7620.ifEmpty { listOf(laneUpper) } }"))
        assertTrue(fn.contains("preferred = null"))
        assertFalse(fn.contains("lanes = listOf(laneUpper),\n                preferred = laneUpper"))
    }

    @Test fun sealedPrimaryFeedsFairnessHistory() {
        val s=src()
        val fn=s.substringAfter("val allowed = e.primaryLane == laneUpper").substringBefore("fun duplicateOpenSuppressions()")
        assertTrue(fn.contains("recordPrimaryWin(e.primaryLane)"))
        assertTrue(fn.contains("LANE_PRIMARY_FAIR_WIN_RECORDED_7620"))
    }

    @Test fun sealedFdgOwnerStillWinsBeforeFairPresealContest() {
        val s=src()
        val fn=s.substringAfter("fun canRequestExecution(").substringBefore("val qualified7620")
        assertTrue(fn.contains("sealedFdgOwnerLane6679"))
        assertTrue(fn.contains("PRESEAL_OWNER_REPLACED_BY_FDG_7541"))
        assertTrue(fn.contains("preferred = sealedFdgOwner6679"))
    }
}

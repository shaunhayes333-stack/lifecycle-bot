package com.lifecyclebot.engine

import com.lifecyclebot.engine.LifecycleEvidence8189.Evidence8189
import com.lifecyclebot.engine.truth.PocketLedger8610
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8695 — a runner ticket is held as its contract says (it was cut on its lane's ordinary stops: 8689 paid 0 / died 3),
 * and a coin whose own forward cohort passes the runner bar may take one (83 of 84 asks were refused for kind).
 */
class Aate8695TicketHoldTest {
    @Test fun aTicketIsRunnerEvidenceAndAnUnprovenPositionStillIsNot() {
        assertTrue(LifecycleEvidence8189.holds8189(Evidence8189.RUNNER_TICKET))
        assertFalse(LifecycleEvidence8189.holds8189(Evidence8189.UNPROVEN))
        val e = src("engine/LifecycleEvidence8189.kt")
        val read = e.substringAfter("fun evidenceFor8189(").substringBefore("private fun sealedRunner8189(")
        assertTrue(read.indexOf("RunnerTicket8073.isTicket8073(ts.mint, nowMs)") in 1 until read.indexOf("if (!sealedRunner8189(ts))"))
    }

    @Test fun aTicketThatNeverRanStillTakesItsStops() {
        // the hold's own release: never reached +25 %, and -20 % deep or red for 45 minutes
        assertTrue(LifecycleHold8065.unprovenLoser8109(peakPct = 3.0, grossPct = -21.0, ageMs = 60_000L))
        assertTrue(LifecycleHold8065.unprovenLoser8109(peakPct = 3.0, grossPct = -6.0, ageMs = 46L * 60_000L))
        assertFalse("noise inside the first minutes is held", LifecycleHold8065.unprovenLoser8109(peakPct = 3.0, grossPct = -9.0, ageMs = 3L * 60_000L))
    }

    @Test fun theTicketGroupFollowsTheCoinsOwnCohort() {
        assertTrue(RunnerTicket8073.groupFluid8681(legacyGroup = false, pocket = PocketLedger8610.Call.LOSES, cohortPays8695 = true))
        assertFalse(RunnerTicket8073.groupFluid8681(legacyGroup = false, pocket = PocketLedger8610.Call.LOSES))
        assertTrue(RunnerTicket8073.groupFluid8681(legacyGroup = true, pocket = null))
        val t = src("engine/RunnerTicket8073.kt")
        assertTrue(t.contains("gate.runnerCohortAllows(cell)"))
        assertTrue(t.contains("else gate.runnerCohortAllows(labels.bandStatFor8019(ts, POCKET_LANE_8681))"))
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}

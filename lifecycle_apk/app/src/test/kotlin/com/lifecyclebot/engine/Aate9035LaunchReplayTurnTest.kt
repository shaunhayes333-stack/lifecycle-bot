package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9035 — the 30 s CPU sample taken with the 26 Nov 23:38 report: the launch-replay thread used 6.6 s of CPU (the runner
 * model's trees 3.7 s), more than any job the meter knew, and was neither measured nor in the batch turn.
 */
class Aate9035LaunchReplayTurnTest {
    @Test fun theLaunchReplayIsMeasuredAndTakesItsTurn() {
        assertTrue("launchReplay" in JobCost8813.GATED_9033)
        assertEquals(JobCost8813.TURN_WAIT_MAX_MS_9033, JobCost8813.turnWaitMs9033("launchReplay", waitsForHeadroom = true))
        assertTrue(JobCost8813.turnsLine9033().contains("launch-replay"))
        val rp = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchReplay8744.kt").readText()
        val measured = rp.indexOf("JobCost8813.measureBackground8817(\"launchReplay\") {")
        assertTrue(measured > 0)
        // the SOL price check stays outside the measured block (a run that cannot start is not a failed run of the job)
        assertTrue(rp.indexOf("if (!(solUsd > 0.0)) { status = \"no SOL price\"; return@execute }") in 1 until measured)
        // everything the replay does is inside it
        for (inside in listOf("LaunchHour8628.forEachLaunch8756(nowMs, MAX_LAUNCHES", "val res = evaluate8748(set, solUsd, cost, rulesFor8829(need))",
                "RunnerModel8861.evaluate8861(cost, need, nowMs)", "paths8755 = set; solUsd8755 = solUsd; cost8755 = cost"))
            assertTrue(inside, rp.indexOf(inside) > measured)
        // a measured run is counted like any other
        JobCost8813.resetForTest8813()
        JobCost8813.pressureForTest9033 = "NORMAL"
        try {
            assertEquals(3, JobCost8813.measureBackground8817("launchReplay") { 3 })
            assertTrue(JobCost8813.statusLine8813().contains("launchReplay:runs1"))
        } finally { JobCost8813.pressureForTest9033 = null; JobCost8813.resetForTest8813() }
    }

    @Test fun theBuildIsRecorded() {
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §220 — V5.0.9035: the launch replay takes its turn"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9035*'"))
    }
}

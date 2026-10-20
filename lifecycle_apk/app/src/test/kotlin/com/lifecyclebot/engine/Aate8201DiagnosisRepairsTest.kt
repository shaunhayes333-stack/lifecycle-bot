package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/** 5.0.8200 diagnosis: Cortex STRONG evidence silently null; PROJECT_SNIPER hunted 7 / specialist book 0. */
class Aate8201DiagnosisRepairsTest {
    @Test fun everyStrongCohortNullNamesItsDoor() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText()
        val body = src.substringAfter("fun strongCohortEvidence8200(").substringBefore("private fun <T> nullWhy8201")
        for (why in listOf("NO_ASSESSMENT", "NOT_STRONG", "STALE_MARK", "HARD_BLOCK", "MAYHEM", "UNCALIBRATED", "INVERTED", "BARRED", "NO_EXECUTABLE_COHORT", "EXCEPTION"))
            assertTrue(why, body.contains("nullWhy8201(\"$why\")"))
        assertFalse("no anonymous null exit remains", Regex("return null\\b").containsMatchIn(body.replace("} ?: return null", "")))
        assertTrue(src.contains("inc(\"STRONG_COHORT_NULL_8201_\$why\")"))
        assertTrue(body.contains("inc(\"STRONG_COHORT_OFFERED_8200\")"))   // the offered path is unchanged
    }

    @Test fun sniperPickPastWindowAtPublishIsNotASniperResident() {
        val h = com.lifecyclebot.engine.market.LaneHunter7297
        val w = com.lifecyclebot.v3.scoring.ProjectSniperAI.SNIPER_WINDOW_SECS_8169.toDouble()
        val t0 = 1_000_000L
        assertFalse(h.sniperPastWindowAtPublish8201(0.01, t0, t0))                       // 36 s old, published now
        assertTrue(h.sniperPastWindowAtPublish8201(0.01, t0, t0 + ((w - 30.0) * 1000).toLong()))  // 36 s + latency past window
        assertTrue(h.sniperPastWindowAtPublish8201(0.06, t0, t0))                        // 216 s old at sweep
        assertFalse(h.sniperPastWindowAtPublish8201(0.0, t0, t0 + 10_000L))              // unknown age (0) counts only latency
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/market/LaneHunter7297.kt").readText()
        val block = src.substringAfter("val past = lane == \"PROJECT_SNIPER\" && sniperPastWindowAtPublish8201(r.ageHours, snap.atMs, now)")
            .substringBefore("rows.forEach { r ->")
        assertTrue(block.contains("LANE_HUNT_SNIPER_PAST_WINDOW_AT_PUBLISH_8201"))
        assertTrue(block.contains("!past"))
    }
}

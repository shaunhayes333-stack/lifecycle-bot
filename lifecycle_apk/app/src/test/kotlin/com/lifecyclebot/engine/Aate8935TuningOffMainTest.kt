package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8935 — the Tuning screen's per-lane leaderboard is computed off the main thread and read from a cache. */
class Aate8935TuningOffMainTest {
    @Test fun theLeaderboardIsNeverComputedOnTheMainThread() {
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/TuningActivity.kt").readText()
        val render = ui.substringAfter("private fun renderAll() {")
        assertFalse("renderAll computes no leaderboard", render.substringBefore("private fun ").contains("StrategyTelemetry.computeLeaderboard()"))
        assertTrue(ui.contains("try { cachedBoard8935 = com.lifecyclebot.engine.StrategyTelemetry.computeLeaderboard() } catch (_: Throwable) {}"))
        assertTrue(ui.contains("refreshBoardAsync8935()   // V5.0.8935"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 123. The Tuning board off the main thread (8935)"))
    }
}

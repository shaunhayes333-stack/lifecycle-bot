package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8817 — the startup dataset replays run at background priority; the measured result is unchanged. */
class Aate8817BackgroundJobsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aBackgroundJobReturnsItsValueAndIsMeasured() {
        JobCost8813.resetForTest8813()
        assertEquals(42, JobCost8813.measureBackground8817("bg") { 42 })
        try { JobCost8813.measureBackground8817<Int>("bgThrows") { error("boom") } } catch (_: IllegalStateException) {}
        val line = JobCost8813.statusLine8813()
        assertTrue(line, line.contains("bg:runs1") && line.contains("bgThrows:runs1 failed1"))
        JobCost8813.resetForTest8813()
    }

    @Test fun theBatchReplaysYieldButTheCloseCommitDoesNot() {
        // superseded by V5.0.8863 — the pocket ledger, paying cells and managed bands are seeded by one read of the dataset
        // (DatasetReplay8863, job "datasetSeed") in place of three jobs of their own; it yields the same way.
        // superseded by V5.0.8866 — the tree refit the forecast waits for yields at nice 5, outside the background CPU group
        for ((f, n) in listOf("engine/truth/DatasetReplay8863.kt" to "datasetSeed", "engine/truth/EdgeModel8537.kt" to "edgeReplay",
                "engine/lab/StrategyLab8755.kt" to "strategyLab", "network/CoinGeckoSolanaTopMcap.kt" to "coingeckoCoinList"))
            assertTrue("$f $n", src(f).contains("JobCost8813.measureBackground8817(\"$n\")"))
        for (n in listOf("treeParse", "treeTrain"))
            assertTrue(n, src("engine/truth/TreeRanker8737.kt").contains("JobCost8813.measureYielding8866(\"$n\")"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("JobCost8813.measure8813(if (periodic)"))
        val jc = src("engine/JobCost8813.kt")
        assertTrue(jc.contains("android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)"))
        assertTrue(jc.contains("try { return measure8813(name, block) } finally { restorePriority8817(before) }"))
    }
}

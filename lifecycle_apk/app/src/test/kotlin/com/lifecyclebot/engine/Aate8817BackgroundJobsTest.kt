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
        for ((f, n) in listOf("engine/truth/PocketLedger8610.kt" to "pocketLedgerSeed", "engine/truth/PayingCell8723.kt" to "payingCellSeed",
                "engine/truth/ManagedBands8719.kt" to "managedBandsSeed", "engine/truth/TreeRanker8737.kt" to "treeParse",
                "engine/truth/TreeRanker8737.kt" to "treeTrain", "engine/truth/EdgeModel8537.kt" to "edgeReplay",
                "engine/lab/StrategyLab8755.kt" to "strategyLab", "network/CoinGeckoSolanaTopMcap.kt" to "coingeckoCoinList"))
            assertTrue("$f $n", src(f).contains("JobCost8813.measureBackground8817(\"$n\")"))
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("JobCost8813.measure8813(if (periodic)"))
        val jc = src("engine/JobCost8813.kt")
        assertTrue(jc.contains("android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)"))
        assertTrue(jc.contains("try { return measure8813(name, block) } finally { restorePriority8817(before) }"))
    }
}

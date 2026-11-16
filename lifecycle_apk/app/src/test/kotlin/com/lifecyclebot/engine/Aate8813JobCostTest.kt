package com.lifecyclebot.engine

import com.lifecyclebot.network.CoinGeckoSolanaTopMcap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8813 — 5.0.8809 ran out of memory after 2.5 hours at 1.4 GB/min of allocation, and the report could name the busy
 * threads but not what one run of a heavy job costs. Each heavy job is now measured where it runs; the CoinGecko coin list
 * is scanned in place.
 */
class Aate8813JobCostTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aJobIsMeasuredPerRunAndAFailureIsCounted() {
        JobCost8813.resetForTest8813()
        assertEquals(-1L, JobCost8813.delta8813(-1L, 10L))
        assertEquals(-1L, JobCost8813.delta8813(10L, 5L))
        assertEquals(5L, JobCost8813.delta8813(5L, 10L))
        JobCost8813.record8813("big", 1_000L, 2_000_000_000L, 300L * 1024 * 1024)
        JobCost8813.record8813("big", 500L, -1L, 100L * 1024 * 1024)
        JobCost8813.record8813("small", 10L, -1L, 1024L * 1024)
        assertEquals(7, JobCost8813.measure8813("inline") { 7 })
        try { JobCost8813.measure8813<Int>("throws") { error("boom") } } catch (_: IllegalStateException) {}
        val line = JobCost8813.statusLine8813()
        assertTrue(line, line.startsWith("jobCost8813[backgroundRuns8817=") && line.contains(" big:runs2 allocMB=400 last=100MB/500ms/cpu2000ms max=300MB/1000ms cpuTotal=2s"))
        assertTrue("largest total first", line.indexOf("big:") < line.indexOf("small:"))
        assertTrue(line.contains("throws:runs1 failed1"))
        JobCost8813.resetForTest8813()
    }

    @Test fun theCoinListIsScannedForIdAndSolanaOnly() {
        val sol = "So11111111111111111111111111111111111111112"
        val body = """[{"id":"bonk","symbol":"bonk","name":"Bonk \"dog\"","platforms":{"ethereum":"0xabc","solana":"DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263"}},
            {"id":"eth-only","symbol":"e","name":"E","platforms":{"ethereum":"0xdef"}},
            {"id":"nullsol","symbol":"n","name":"N","platforms":{"solana":null}},
            {"id":"wsol","symbol":"sol","name":"Wrapped é SOL","platforms":{ "solana" : "$sol" }},
            {"id":"short","symbol":"s","name":"S","platforms":{"solana":"abc"}}]"""
        val m = CoinGeckoSolanaTopMcap.idMintMap8813(body)
        assertEquals(mapOf("bonk" to "DezXAZ8z7PnrnRJjz3wXBoRgixCa6xjnB7YaB1pPB263", "wsol" to sol), m)
        assertTrue(CoinGeckoSolanaTopMcap.idMintMap8813("").isEmpty())
        assertTrue(CoinGeckoSolanaTopMcap.idMintMap8813("<html>rate limited</html>").isEmpty())
    }

    // superseded by V5.0.8817 — the batch replays are measured at background priority (measureBackground8817); the Cortex
    // close commit stays at its own priority.
    @Test fun theHeavyJobsAreMeasuredWhereTheyRun() {
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("JobCost8813.measure8813(if (periodic) \"cortexSnapshot\" else \"cortexCloseCommit\")"))
        val tree = src("engine/truth/TreeRanker8737.kt")
        assertTrue(tree.contains("JobCost8813.measureBackground8817(\"treeParse\")") && tree.contains("JobCost8813.measureBackground8817(\"treeTrain\")"))
        assertTrue(src("engine/lab/StrategyLab8755.kt").contains("JobCost8813.measureBackground8817(\"strategyLab\") { run(nowMs) }"))
        assertTrue(src("engine/truth/ManagedBands8719.kt").contains("JobCost8813.measureBackground8817(\"managedBandsSeed\") { seedFromDataset() }"))
        assertTrue(src("engine/truth/PayingCell8723.kt").contains("JobCost8813.measureBackground8817(\"payingCellSeed\") { seedFromDataset() }"))
        assertTrue(src("engine/truth/PocketLedger8610.kt").contains("JobCost8813.measureBackground8817(\"pocketLedgerSeed\") { seedFromDataset() }"))
        assertTrue(src("engine/truth/EdgeModel8537.kt").contains("JobCost8813.measureBackground8817(\"edgeReplay\")"))
        assertTrue(src("network/CoinGeckoSolanaTopMcap.kt").contains("idMintMap8813(body)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("JobCost8813.statusLine8813()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 20. What each heavy job costs (8813)"))
    }
}

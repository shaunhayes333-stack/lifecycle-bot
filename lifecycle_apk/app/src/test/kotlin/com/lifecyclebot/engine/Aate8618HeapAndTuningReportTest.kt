package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TuningReport8618
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8618 — the report carries a heap log and the tuning cuts (paper vs live on the same coin, exits, entry state, pockets). */
class Aate8618HeapAndTuningReportTest {
    @Test fun tuningCutsFileEachPaperCloseUnderTheLiveVerdict() {
        assertEquals("<1m", TuningReport8618.ageBand8618(0.4))
        assertEquals(">=1d", TuningReport8618.ageBand8618(3_000.0))
        assertEquals("?", TuningReport8618.ageBand8618(Double.NaN))
        assertEquals("LIVE_ADMIT", TuningReport8618.verdictKey8618(true, "anything"))
        assertEquals("LIVE_REFUSE:LIVE_POCKET_PROVEN_LOSING_8610", TuningReport8618.verdictKey8618(false, "LIVE_POCKET_PROVEN_LOSING_8610:detail"))
        TuningReport8618.noteLiveVerdict8618("mint8618", "shitcoin", false, "PAPER_LANE_PROVEN_LOSING_8608", 1_000L)
        assertEquals("LIVE_REFUSE:PAPER_LANE_PROVEN_LOSING_8608", TuningReport8618.liveVerdictFor8618("mint8618", "SHITCOIN", 2_000L))
        assertEquals("LIVE_UNSEEN", TuningReport8618.liveVerdictFor8618("other", "SHITCOIN", 2_000L))
        TuningReport8618.notePaperOpen8618("mint8618", "SHITCOIN", "LIVE_REFUSE:X", "EXPANDING", 0.5, 2_000L)
        TuningReport8618.notePaperClose8618("mint8618", "SHITCOIN", 140.0, 0.07, "TAKE_PROFIT_T1", 300_000L)
        val s = TuningReport8618.section8618()
        assertTrue(s.contains("SHITCOIN|LIVE_REFUSE:X"))
        assertTrue(s.contains("SHITCOIN|TAKE_PROFIT"))
        assertTrue(s.contains("SHITCOIN|phase=EXPANDING"))
        assertTrue(s.contains("SHITCOIN|age=<1m"))
    }

    @Test fun heapGrowthRanksTheClimbersAndTheReportCarriesBothSections() {
        val g = HeapLog8618.growth8618(mapOf("a" to 100L, "b" to 500L), mapOf("a" to 900L, "b" to 600L, "c" to 50L))
        assertEquals(listOf("a" to 800L, "b" to 100L, "c" to 50L), g)
        val phc = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(phc.contains("appendTuning8618(sb)"))
        assertTrue(phc.contains("HeapLog8618.section8618()"))
        assertTrue(phc.contains("TuningReport8618.section8618()"))
        assertTrue(phc.contains("PocketLedger8610.topPockets8618()"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/MemoryGuard7977.kt").readText().contains("HeapLog8618.sample8618(rows)"))
        val paper = File("src/main/kotlin/com/lifecyclebot/engine/truth/MemePaperTrader8544.kt").readText()
        assertTrue(paper.contains("TuningReport8618.notePaperOpen8618(") && paper.contains("TuningReport8618.notePaperClose8618("))
    }

    // V5.0.8622 — per-thread-family CPU, so the GB/min allocator is named; and the sizer reads the admission's own generation.
    @Test fun cpuIsFoldedByThreadFamilyFromProcStat() {
        assertEquals("mark-background-#", HeapLog8618.family8622("mark-background-8527-3"))
        val stats = listOf(
            "101 (mark-background-8527-1) S 1 1 1 0 -1 0 0 0 0 0 300 200 0 0 20 0 1 0",
            "102 (mark-background-8527-2) S 1 1 1 0 -1 0 0 0 0 0 100 0 0 0 20 0 1 0",
            "103 (Binder:1_2) S 1 1 1 0 -1 0 0 0 0 0 5 5 0 0 20 0 1 0",
        )
        val m = HeapLog8618.cpuByFamily8622(stats)
        assertEquals(6_000.0, m["mark-background-#"]!!, 1e-9)
        assertEquals(100.0, m["Binder:#"]!!, 1e-9)
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("candidateVersion8622 = candidateVersion8198,"))
        val pol = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        assertTrue(pol.contains("val version = candidateVersion8622.takeIf { it > 0L } ?: com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(mint)"))
    }
}

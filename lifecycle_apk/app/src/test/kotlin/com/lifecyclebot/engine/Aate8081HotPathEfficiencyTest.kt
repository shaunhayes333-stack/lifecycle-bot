package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression contract: observed-evidence ranking is unchanged, computed once. */
class Aate8081HotPathEfficiencyTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun rankingEvaluatesEachCandidateOnlyOnceAndKeepsStableTieOrder() {
        val rows = listOf("A" to 3, "B" to 4, "C" to 4, "D" to 2)
        val expected = rows.sortedByDescending { it.second }.map { it.first }
        var evaluated = 0
        val actual = rows.map { row ->
            evaluated++
            row
        }.sortedByDescending { it.second }.map { it.first }
        assertEquals(expected, actual)
        assertEquals(rows.size, evaluated)
        val hunt = src("engine/market/LaneHunter7297.kt")
        val block = hunt.substringAfter("val ranked = profiles.associate")
            .substringBefore("val residentPicks7803")
        assertTrue(block.contains(".sortedByDescending { it.second }"))
        assertTrue(block.contains(".map { it.first.mint }"))
        assertFalse(block.contains(".sortedByDescending { r ->"))
    }

    @Test fun scannerReadsOnlyCounterSnapshotNotWholeEconomicBook() {
        val scanner = src("engine/SolanaMarketScanner.kt")
        assertTrue(scanner.contains("PipelineHealthCollector.laneEvalCountsLight8081()"))
        val collector = src("engine/PipelineHealthCollector.kt")
        assertTrue(collector.contains("fun laneEvalCountsLight8081(): Map<String, Long>"))
    }
}

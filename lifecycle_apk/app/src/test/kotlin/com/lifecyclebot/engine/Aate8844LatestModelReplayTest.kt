package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EdgeModel8537
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 8844: after an unfilled-heavy export grows beyond 60k rows, the predictor must replay recent truth. */
class Aate8844LatestModelReplayTest {
    @Test fun replayKeepsLatestSixtyThousandRowsNotOldest() {
        val rows = File.createTempFile("aate-8844-replay-", ".jsonl")
        try {
            rows.bufferedWriter().use { out ->
                repeat(60_007) { out.appendLine("row-$it") }
            }
            var n = 0
            var first = ""
            var last = ""
            val total = EdgeModel8537.streamLatestDatasetRows8844(rows) { line ->
                if (n == 0) first = line
                last = line
                n++
            }
            assertEquals(60_007, total)
            assertEquals(60_000, n)
            assertEquals("row-7", first)
            assertEquals("row-60006", last)
        } finally {
            rows.delete()
        }
    }

    @Test fun exactAndShortDatasetsAreNotDiscarded() {
        val rows = File.createTempFile("aate-8844-small-", ".jsonl")
        try {
            rows.writeText("old\nrunner\nlatest\n")
            val kept = mutableListOf<String>()
            assertEquals(3, EdgeModel8537.streamLatestDatasetRows8844(rows, 3) { kept += it })
            assertEquals(listOf("old", "runner", "latest"), kept)
            kept.clear()
            assertEquals(3, EdgeModel8537.streamLatestDatasetRows8844(rows, 2) { kept += it })
            assertEquals(listOf("runner", "latest"), kept)

            val source = File("src/main/kotlin/com/lifecyclebot/engine/truth/EdgeModel8537.kt").readText()
            val replay = source.substringAfter("private fun replayDataset8642(file: File): Pair<Int, Int>")
                .substringBefore("private fun writeRidge")
            assertTrue(replay.contains("streamLatestDatasetRows8844(file)"))
            assertTrue(!replay.contains("seen++ >= DATASET_MAX_LINES"))
        } finally {
            rows.delete()
        }
    }
}

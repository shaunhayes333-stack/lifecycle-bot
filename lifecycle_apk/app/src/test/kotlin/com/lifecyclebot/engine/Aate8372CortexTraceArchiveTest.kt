package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexTraceArchive8372
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercise the actual compressed archive; no production trading services or copied archive logic. */
class Aate8372CortexTraceArchiveTest {
    private fun trace(atMs: Long, net: Double = -15.0): String = JSONObject()
        .put("at", atMs).put("net", net).put("bucket", "REFUSE")
        .put("raws", JSONArray(listOf(0.5, 12.25, -2.0)))
        .put("preds", JSONArray(listOf(11.0, -3.5, JSONObject.NULL)))
        .put("stamp", -0.0010005003335835344).toString()

    @Test fun exactOldEpisodeSurvives1600NewGradesAndRestartWithoutReplacingAnotherLane() {
        val archive = CortexTraceArchive8372()
        val oldId = "same-mint|MOONSHOT|1000"
        val newId = "same-mint|MOONSHOT|500000"
        val otherLane = "same-mint|QUALITY|1000"
        val original = trace(1_000L)
        assertTrue(archive.put(oldId, 1_000L, 20_000_000L, original, 600_000L))
        assertTrue(archive.put(newId, 500_000L, 20_000_000L, trace(500_000L, 8.0), 600_000L))
        assertTrue(archive.put(otherLane, 1_000L, 20_000_000L, trace(1_000L, -2.0), 600_000L))
        repeat(1_600) { i ->
            val at = 2_000L + i
            assertTrue(archive.put("token-$i|EXPRESS|$at", at, 20_000_000L, trace(at), 600_000L))
        }
        val saved = JSONObject(archive.snapshot(600_000L).toString())
        val restored = CortexTraceArchive8372().also { it.restore(saved, 900_000L) }
        assertEquals(1_603, restored.size())
        assertEquals(original, restored.get(oldId, 900_000L))
        assertEquals(trace(500_000L, 8.0), restored.get(newId, 900_000L))
        assertEquals(trace(1_000L, -2.0), restored.get(otherLane, 900_000L))
        assertTrue(restored.put(oldId, 1_000L, 20_000_000L, trace(1_000L, 75.0), 900_000L))
        assertEquals(75.0, JSONObject(restored.get(oldId, 900_000L)!!).getDouble("net"), 0.0)
        assertEquals("Frozen original verdict survives a revised outcome", "REFUSE",
            JSONObject(restored.get(oldId, 900_000L)!!).getString("bucket"))
        assertEquals(8.0, JSONObject(restored.get(newId, 900_000L)!!).getDouble("net"), 0.0)
        assertEquals(1_603, restored.size())
        assertEquals(0L, restored.counts().capacityEvicted)
    }

    @Test fun rowBudgetEvictsChronologicallyNotByResultOrInsertionOrder() {
        val archive = CortexTraceArchive8372(maxRecords = 2)
        assertTrue(archive.put("new-winner", 3_000L, 20_000L, trace(3_000L, 900.0), 5_000L))
        assertTrue(archive.put("old-loser", 1_000L, 20_000L, trace(1_000L, -15.0), 5_000L))
        assertTrue(archive.put("middle-loser", 2_000L, 20_000L, trace(2_000L, -20.0), 5_000L))
        assertNull(archive.get("old-loser", 5_000L))
        assertNotNull(archive.get("new-winner", 5_000L))
        assertNotNull(archive.get("middle-loser", 5_000L))
        assertEquals(1L, archive.counts().capacityEvicted)
        assertFalse("A late old spill must not displace a newer decision", archive.put("late-old-winner", 500L,
            20_000L, trace(500L, 1_000.0), 5_000L))
        assertNotNull(archive.get("new-winner", 5_000L))
        assertNotNull(archive.get("middle-loser", 5_000L))
        assertEquals(1L, archive.counts().capacityRejected)
    }

    @Test fun compressedByteBudgetAndReplacementNeverLeakAccountedBytes() {
        val single = CortexTraceArchive8372()
        single.put("one", 1_000L, 20_000L, trace(1_000L), 5_000L)
        val budget = single.bytes() + 4
        val archive = CortexTraceArchive8372(maxCompressedBytes = budget)
        assertTrue(archive.put("one", 1_000L, 20_000L, trace(1_000L), 5_000L))
        val firstBytes = archive.bytes()
        assertTrue(archive.put("one", 1_000L, 20_000L, trace(1_000L), 5_000L))
        assertEquals(firstBytes, archive.bytes())
        assertTrue(archive.put("two", 2_000L, 20_000L, trace(2_000L), 5_000L))
        assertEquals(1, archive.size())
        assertTrue(archive.bytes() <= budget)
        assertEquals(1L, archive.counts().capacityEvicted)
        assertTrue(archive.remove("two"))
        assertEquals(0L, archive.bytes())
    }

    @Test fun expiryPrunesBeforeQuotaAndAtBoundaryWithoutEvictingLiveTrace() {
        val archive = CortexTraceArchive8372(maxRecords = 2)
        archive.put("early", 1_000L, 4_000L, trace(1_000L), 3_000L)
        archive.put("late", 2_000L, 10_000L, trace(2_000L), 3_000L)
        assertTrue(archive.put("fresh", 4_000L, 20_000L, trace(4_000L), 4_000L))
        assertNull(archive.get("early", 4_000L))
        assertNotNull(archive.get("late", 4_000L))
        assertEquals(1L, archive.counts().expired)
        assertEquals(0L, archive.counts().capacityEvicted)
        assertEquals(1, archive.prune(10_000L))
        assertNotNull(archive.get("fresh", 10_000L))
    }

    @Test fun invalidUpdateAndCapacityRejectionLeaveOriginalTraceIntact() {
        val archive = CortexTraceArchive8372(maxCompressedBytes = 1_000L)
        val original = trace(1_000L)
        assertTrue(archive.put("episode", 1_000L, 20_000L, original, 5_000L))
        assertFalse(archive.put("episode", 2_000L, 20_000L, trace(2_000L), 5_000L))
        assertFalse(archive.put("episode", 1_000L, 20_000L, "x".repeat(CortexTraceArchive8372.MAX_EXPANDED_BYTES + 1), 5_000L))
        assertFalse(archive.put("future", 6_000L, 20_000L, "{}", 5_000L))
        val large = java.util.Random(42).let { r -> buildString { repeat(20_000) { append((33 + r.nextInt(90)).toChar()) } } }
        assertFalse(archive.put("too-large", 2_000L, 20_000L, large, 5_000L))
        assertEquals(original, archive.get("episode", 5_000L))
        assertEquals(3L, archive.counts().invalid)
        assertEquals(1L, archive.counts().capacityRejected)
    }

    @Test fun corruptBase64GzipAndExpansionBombCannotBecomePartialTrace() {
        val saved = CortexTraceArchive8372().also {
            it.put("good", 1_000L, 20_000L, trace(1_000L), 5_000L)
        }.snapshot(5_000L)
        val bomb = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(ByteArray(CortexTraceArchive8372.MAX_EXPANDED_BYTES + 1) { 65 }) }
        }.toByteArray()
        val rows = saved.getJSONArray("rows")
        for ((id, encoded) in listOf(
            "bad-base64" to "!!!not base64!!!",
            "not-gzip" to Base64.getEncoder().encodeToString("not gzip".toByteArray()),
            "bomb" to Base64.getEncoder().encodeToString(bomb),
        )) rows.put(JSONObject().put("id", id).put("at", 1_000L).put("until", 20_000L).put("gz", encoded))
        val restored = CortexTraceArchive8372().also { it.restore(saved, 5_000L) }
        assertEquals(1, restored.size())
        assertEquals(trace(1_000L), restored.get("good", 5_000L))
        assertEquals(3L, restored.counts().corrupt)
        assertNull(restored.get("bomb", 5_000L))
    }

    @Test fun countersAndExpirySurviveCheckpointWithoutReintroducingDeadEpisodes() {
        val archive = CortexTraceArchive8372(maxRecords = 1)
        archive.put("one", 1_000L, 6_000L, trace(1_000L), 5_000L)
        archive.put("two", 2_000L, 10_000L, trace(2_000L), 5_000L)
        val saved = archive.snapshot(5_000L)
        val restored = CortexTraceArchive8372().also { it.restore(saved, 10_000L) }
        assertEquals(0, restored.size())
        assertEquals(0L, restored.bytes())
        assertEquals(1L, restored.counts().capacityEvicted)
        assertEquals(1L, restored.counts().expired)
    }

    @Test fun replacingModelBooksClearsAllRevisionCoordinatesButKeepsDiagnosticCounters() {
        val archive = CortexTraceArchive8372(maxRecords = 2)
        archive.put("one", 1_000L, 10_000L, trace(1_000L), 5_000L)
        archive.put("two", 2_000L, 10_000L, trace(2_000L), 5_000L)
        archive.put("three", 3_000L, 10_000L, trace(3_000L), 5_000L)
        assertFalse(archive.put("invalid", 6_000L, 10_000L, "{}", 5_000L))
        val counts = archive.counts()
        assertEquals(1L, counts.capacityEvicted)
        assertEquals(1L, counts.invalid)
        assertTrue(archive.bytes() > 0L)
        assertEquals(2, archive.clearRevisionCoordinates8372())
        assertEquals(0, archive.size())
        assertEquals(0L, archive.bytes())
        assertEquals(0, archive.prune(10_000L))
        assertNull(archive.get("two", 5_000L))
        assertEquals(counts, archive.counts())
        assertEquals(0, archive.clearRevisionCoordinates8372())
        val restored = CortexTraceArchive8372()
        restored.restore(JSONObject(archive.snapshot(5_000L).toString()), 5_000L)
        assertEquals(0, restored.size())
        assertEquals(counts, restored.counts())
        // Reusing an id after replacement cannot leave an old expiry/order index attached to the new trace.
        assertTrue(archive.put("two", 5_000L, 30_000L, trace(5_000L), 5_000L))
        assertEquals(0, archive.prune(10_000L))
        assertEquals(trace(5_000L), archive.get("two", 10_000L))
        assertEquals(1, archive.snapshot(10_000L).getJSONArray("rows").length())
    }
}

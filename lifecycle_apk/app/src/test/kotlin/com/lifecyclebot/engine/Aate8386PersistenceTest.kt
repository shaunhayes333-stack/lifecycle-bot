package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.*
import com.lifecyclebot.engine.truth.ShadowWrites8386
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class Aate8386PersistenceTest {
    @Test fun detachedNumericalSnapshotCannotObserveLaterModelMutation() {
        val live = CortexLedger7885()
        live.lanes["CORE"] = CortexLedger7885.Stat().apply { n = 30.0; sum = 600.0; logScale = -1.23 }
        live.seats["voter|CORE"] = CortexLedger7885.Seat(3).apply { scored = 300; bins[0].sum = 456.0 }
        live.pairs["v1|v2"] = doubleArrayOf(1.0, 2.0, 3.0)
        val frozen = live.detached8386()
        val original = frozen.encode().toString()
        live.lanes["CORE"]!!.sum = 999.0; live.seats["voter|CORE"]!!.bins[0].sum = -10.0
        live.pairs["v1|v2"]!![0] = 100.0
        assertEquals(original, frozen.encode().toString())
        val restored = CortexLedger7885().also { it.decode(JSONObject(original)) }
        assertEquals(600.0, restored.lanes["CORE"]!!.sum, 0.0)
        assertEquals(456.0, restored.seats["voter|CORE"]!!.bins[0].sum, 0.0)
        assertEquals(1.0, restored.pairs["v1|v2"]!![0], 0.0)
    }
    @Test fun gradeRecordsAndFrozenPredictionsSurviveDetachedEncodeAndRestore() {
        val live = CortexGradeBook8144()
        val record = CortexGradeRecord8144('D', "mint|CORE|1000", "mint|CORE", 1000, "CORE", "PULLBACK", "NEUTRAL",
            "EARLY", "LOW", "SMALL", 2, 20.0, true, true, false)
        live.addDecision(record)
        live.label(record.id, 100_000.0, 100_001.0, 100_000.0, true, 2000)
        val frozen = live.detached8386()
        val encoded = frozen.encode()
        live.label(record.id, -15.0, -12.0, -15.0, true, 3000)
        assertEquals(encoded, frozen.encode())
        val restored = CortexGradeBook8144().also { it.decode(encoded) }
        assertEquals(20.0, restored.decision(record.id)!!.predictedEdgePct, 0.0)
        assertEquals(100_000.0, restored.decision(record.id)!!.executablePct, 0.0)
    }
    @Test fun coldTraceSnapshotSharesOnlyPrivateImmutableBytes() {
        val archive = CortexTraceArchive8372(10, 100_000)
        assertTrue(archive.put("mint|CORE|1", 1, 10_000, "{\"original\":true}", 2))
        val frozen = archive.detached8386(2)
        assertTrue(archive.put("mint|CORE|1", 1, 10_000, "{\"original\":false}", 3))
        val restored = CortexTraceArchive8372(10, 100_000)
        restored.restore(frozen.snapshot(3), 3)
        assertEquals("{\"original\":true}", restored.get("mint|CORE|1", 3))
    }
    @Test fun failedWritesRetryAndStaleDeleteCannotEraseReopenedEpisode() {
        val q = ShadowWrites8386(8)
        val store = HashMap<String, String>()
        fun commit(rows: Map<String, ShadowWrites8386.Write>): Boolean {
            rows.forEach { (k, w) -> if (w.value == null) store.remove(k) else store[k] = w.value }
            return true
        }
        assertTrue(q.stage("mint", 1, "old")); assertFalse(q.flush { false }); assertEquals(1, q.size())
        assertTrue(q.stage("mint", 1, null)); assertTrue(q.stage("mint", 2, "new"))
        assertFalse(q.stage("mint", 1, null)); assertTrue(q.flush(::commit))
        assertEquals("new", store["mint"]); assertEquals(0, q.size())
        assertTrue(q.stage("mint", 2, null)); assertFalse(q.stage("mint", 2, "late_old_write"))
    }
    @Test fun delayedFlushCannotClearNewerQueuedWriteAndWritersStayOrdered() {
        val q = ShadowWrites8386(8)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            q.stage("mint", 1, null)
            val writing = pool.submit<Boolean> { q.flush { entered.countDown(); release.await(5, TimeUnit.SECONDS); true } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(q.stage("mint", 2, "new"))
            release.countDown(); assertTrue(writing.get(5, TimeUnit.SECONDS)); assertEquals(1, q.size())
            assertTrue(q.flush { assertEquals("new", it["mint"]!!.value); true })
        } finally { release.countDown(); pool.shutdownNow() }
    }
    @Test fun writerQueueIsBoundedAndRepeatedUpdatesCoalesce() {
        val q = ShadowWrites8386(2)
        repeat(1000) { assertTrue(q.stage("mint", 1, "update$it")) }
        assertEquals(1, q.size()); assertTrue(q.stage("second", 1, "v")); assertFalse(q.stage("third", 1, "v"))
        assertTrue(q.flush { assertEquals("update999", it["mint"]!!.value); true })
    }
    @Test fun actualCortexCaptureProducesOffMonitorEncoderAndValidStreamingJson() {
        val method = Cortex7885::class.java.getDeclaredMethod("snapshotEncoder8386", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val encode = method.invoke(Cortex7885, false) as () -> MutableMap<String, String>
        assertFalse(Thread.holdsLock(Cortex7885))
        val blobs = encode()
        val pending = blobs.entries.first { it.key.startsWith("CORTEX_PENDING_") }.value
        val root = JSONObject(pending)
        assertTrue(root.has("dict")); assertTrue(root.has("p")); assertTrue(root.has("g8013")); assertTrue(root.has("bound8076"))
    }
}

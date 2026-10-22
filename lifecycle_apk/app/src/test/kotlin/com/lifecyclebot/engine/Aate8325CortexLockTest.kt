package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexGradeBook8144
import com.lifecyclebot.engine.cortex.CortexRank8325
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8325 §LOCK_ONLY_FOR_SMALL_STATE — 5.0.8323: assessLockWait8324 max 121 s, assess p50 1.5 s / p90 12.8 s,
 * ASYNC_DROPPED 548. The Cortex monitor was held through slow work: SQLite reads of a close receipt for every replayed close,
 * the periodic save (@Synchronized across the SQLite writes), the executable refresh's calls into other components, the
 * report's status lines and ranking sorts, and a failed load re-decoded on every call. Each is now outside the lock; the
 * assessment takes the lock once for the fusion, and the ranking / ponds are copied under it and computed outside, cached.
 */
class Aate8325CortexLockTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val cx = src("engine/cortex/Cortex7885.kt")

    /** Every `synchronized(this) { ... }` block body in [code] (brace-matched). */
    private fun lockedBlocks(code: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        while (true) {
            val i = code.indexOf("synchronized(this) {", from)
            if (i < 0) break
            var j = i + "synchronized(this) {".length
            var depth = 1
            while (j < code.length && depth > 0) {
                when (code[j]) { '{' -> depth++; '}' -> depth-- }
                j++
            }
            out.add(code.substring(i, j))
            from = j
        }
        return out
    }

    private fun fn(name: String, until: String): String = cx.substringAfter(name).substringBefore(until)

    private val slow = listOf("CortexVoters7885.readAll", "dynamicVotes", "refreshPonds8324", "inversionLine8324", "rankings8325",
        "LaneCheatSheet8323", "LanePlaybook7907", "LearningPersistence", "statusLine()", "rankRows", "pondRows", "persist",
        "refreshExecutable8187", "Thread.sleep")

    @Test fun assessTakesTheLockOnceAndDoesNoSlowWorkUnderIt() {
        val assess = fn("fun assess(ts: TokenState, laneRaw: String, nowMs: Long = System.currentTimeMillis()): Assessment? {",
            "// ── V5.0.8324 §TAIL_EV")
        val blocks = lockedBlocks(assess)
        assertEquals("one critical section per assessment", 1, blocks.size)
        val b = blocks.single()
        for (k in slow) assertFalse("assess holds the lock through $k", b.contains(k))
        // the voters are read before the lock, the ranking verdict is only peeked (never computed) on the hot path
        assertTrue(assess.indexOf("CortexVoters7885.readAll(") in 0 until assess.indexOf("synchronized(this) {"))
        assertTrue(assess.indexOf("CortexVoters7885.dynamicVotes(ts)") in 0 until assess.indexOf("synchronized(this) {"))
        val bucket = fn("private fun rankConsistentBucket8325(", "private fun runnerBucketNow8324(")
        assertTrue(bucket.contains("rankCache8325.peek()"))
        assertFalse(bucket.contains("rankings8325("))
        assertFalse(bucket.contains("synchronized(this)"))
    }

    @Test fun noStoreIoIsDoneWhileHoldingTheCortexLockOutsideTheBootLoad() {
        val boot = fn("    private fun ensureLoaded() {", "// ── V5.0.8324 §RESTORE_WITHOUT_RESET")
        val rest = cx.replace(boot, "")
        for (b in lockedBlocks(rest)) {
            assertFalse(b.take(160), b.contains("LearningPersistence."))
            assertFalse(b.take(160), b.contains("refreshExecutable8187("))
            assertFalse(b.take(160), b.contains("inversionLine8324("))
            assertFalse(b.take(160), b.contains("LanePlaybook7907.statusLine()"))
        }
        // the save is serialised on its own lock, not on the Cortex monitor
        assertFalse(cx.contains("@Synchronized private fun persistBlocking8094()"))
        assertTrue(cx.contains("private fun persistBlocking8094() { synchronized(persistLock8325) {"))
        assertTrue(cx.contains("private fun durableCloseCommit8198(receipt: String): Boolean = synchronized(persistLock8325) { durableCloseCommitLocked8325(receipt) }"))
        // the close receipt is read before the lock; the executable refresh runs after it
        val close = fn("fun onCanonicalClose(", "// ── persistence ──")
        assertTrue(close.indexOf("val storedReceipt8325 =") in 0 until close.indexOf("synchronized(this) {"))
        assertTrue(close.contains("if (execRefresh8325) try { refreshExecutable8187(System.currentTimeMillis()) } catch (_: Throwable) {}"))
        // a failed load waits before it is retried
        assertTrue(boot.contains("if (System.currentTimeMillis() < loadRetryAtMs8325) return"))
        // the report reads the other components and the ranking before its lock
        val status = fn("    fun statusLine(): String {", "return synchronized(this) {")
        assertTrue(status.contains("val ext8325 = statusExternals8325()"))
        assertTrue(status.contains("val inversion8325 = inversionLine8324()"))
    }

    @Test fun theExecutableRefreshSplitsTheExternalReadsFromTheBooks() {
        val r = fn("    private fun refreshExecutable8187(nowMs: Long) {", "/** V5.0.8187 — \"LANE offset")
        val blocks = lockedBlocks(r)
        assertEquals(2, blocks.size)
        for (b in blocks) {
            assertFalse(b.contains("CostLedger7962")); assertFalse(b.contains("NetEdge8030")); assertFalse(b.contains("EntryChase7961"))
            assertFalse(b.contains("refreshPonds8324")); assertFalse(b.contains("rankings8325"))
        }
        assertTrue(r.contains("if (!execRefreshing8325.compareAndSet(false, true)) return"))
        // onLabel refreshes after its lock
        val label = fn("    fun onLabel(mint: String, labelLane: String, horizonMin: Int", "// ── V5.0.8144 — grade records")
        for (b in lockedBlocks(label)) assertFalse(b.contains("refreshExecutable8187("))
        assertTrue(label.contains("refreshExecutable8187(System.currentTimeMillis())  // V5.0.8187 / V5.0.8325"))
    }

    @Test fun rankingAndPondsAreCachedAndComputedOnce() {
        val cache = CortexRank8325.TtlCache8325<String>(30_000L)
        var n = 0
        assertNull(cache.peek())
        assertEquals("v1", cache.get(1_000L) { n++; "v$n" })
        assertEquals("v1", cache.get(20_000L) { n++; "v$n" })
        assertEquals("v1", cache.peek())
        assertEquals(1, n)
        assertEquals("v2", cache.get(31_000L) { n++; "v$n" })
        assertEquals(2L, cache.computes.get())
        cache.invalidate()
        assertEquals("v3", cache.get(31_001L) { n++; "v$n" })
        // the Cortex reads both through the cache, copying rows under the lock in one pass
        assertTrue(cx.contains("rankCache8325.get(nowMs) {"))
        assertTrue(cx.contains("val rows = synchronized(this) { gradeBook8144.rankRowsByLane8325() }"))
        assertTrue(cx.contains("pondCache8325.get(System.currentTimeMillis()) {"))
        assertTrue(cx.contains("val rows = synchronized(this) { gradeBook8144.pondRowsByLane8325() }"))
        assertTrue(CortexRank8325.TTL_MS_8325 >= 30_000L)
    }

    @Test fun oneLaneRankCheckReadsTheOrderingItBucketsOn() {
        // 40 rows: the edge ranks backwards, the tail EV ranks forwards
        val rows = (0 until 40).map { i ->
            CortexGradeBook8144.RankRow8324(predicted = -i.toDouble(), tailEv = i.toDouble(), objective = i * 10.0, bucket = 1, admitted = false)
        }
        val runner = CortexRank8325.rankLane8325("SHITCOIN", rows, runner = true, pondCompact = null)
        assertNotNull(runner)
        assertEquals("tailEv", runner!!.orderedBy)
        assertEquals("RANKS", runner.verdict)
        val scalp = CortexRank8325.rankLane8325("TREASURY", rows, runner = false, pondCompact = null)!!
        assertEquals("edge", scalp.orderedBy)
        assertEquals("INVERTED", scalp.verdict)
        assertNull(CortexRank8325.rankLane8325("CYCLIC", rows.take(5), runner = false, pondCompact = null))
    }
}

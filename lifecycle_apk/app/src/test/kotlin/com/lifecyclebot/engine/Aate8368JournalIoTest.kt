package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** V5.0.8368 — durable journal writes get their own FIFO priority lane, ahead of stats/bulk work. */
class Aate8368JournalIoTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    private fun drain(q: PriorityBlockingQueue<JournalIo8368.Task8368>): List<JournalIo8368.Task8368> {
        val out = ArrayList<JournalIo8368.Task8368>()
        while (true) {
            val t = q.poll() ?: break
            out.add(t)
        }
        return out
    }

    @Test fun journal_inserts_are_never_queued_behind_bulk_jobs() {
        val q = PriorityBlockingQueue<JournalIo8368.Task8368>()
        val noop = Runnable { }
        val bulkA = JournalIo8368.task8368(JournalIo8368.LANE_BULK, 0L, noop)
        val bulkB = JournalIo8368.task8368(JournalIo8368.LANE_BULK, 0L, noop)
        val j1 = JournalIo8368.task8368(JournalIo8368.LANE_JOURNAL, 0L, noop)
        val bulkC = JournalIo8368.task8368(JournalIo8368.LANE_BULK, 0L, noop)
        val j2 = JournalIo8368.task8368(JournalIo8368.LANE_JOURNAL, 0L, noop)
        listOf(bulkA, bulkB, j1, bulkC, j2).forEach { q.add(it) }
        val order = drain(q)
        assertEquals(listOf(j1, j2, bulkA, bulkB, bulkC), order)
    }

    @Test fun journal_inserts_are_fifo_among_themselves() {
        val q = PriorityBlockingQueue<JournalIo8368.Task8368>()
        val tasks = (1..50).map { JournalIo8368.task8368(JournalIo8368.LANE_JOURNAL, it.toLong(), Runnable { }) }
        tasks.shuffled(java.util.Random(8368L)).forEach { q.add(it) }
        val order = drain(q)
        assertEquals(tasks, order)
        assertEquals((1..50).map { it.toLong() }, order.map { it.enqueuedMs })
    }

    @Test fun single_thread_priority_executor_runs_queued_journal_writes_first_in_order() {
        val ex = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, PriorityBlockingQueue<Runnable>())
        try {
            val gate = CountDownLatch(1)
            val done = CountDownLatch(7)
            val ran = Collections.synchronizedList(ArrayList<String>())
            ex.execute(JournalIo8368.task8368(JournalIo8368.LANE_BULK, 0L, Runnable { gate.await(5, TimeUnit.SECONDS) }))
            for (i in 1..3) ex.execute(JournalIo8368.task8368(JournalIo8368.LANE_BULK, 0L, Runnable { ran.add("bulk$i"); done.countDown() }))
            for (i in 1..4) ex.execute(JournalIo8368.task8368(JournalIo8368.LANE_JOURNAL, 0L, Runnable { ran.add("journal$i"); done.countDown() }))
            gate.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("journal1", "journal2", "journal3", "journal4", "bulk1", "bulk2", "bulk3"), ran.toList())
        } finally {
            ex.shutdownNow()
        }
    }

    @Test fun latency_stats_report_p50_p95_and_session_max() {
        val s = JournalIo8368.LatencyStats8368(capacity = 100)
        assertEquals(0L, s.percentile(0.5))
        for (ms in 1L..100L) s.record(ms)
        assertEquals(50L, s.percentile(0.50))
        assertEquals(95L, s.percentile(0.95))
        assertEquals(100L, s.max())
        assertEquals(100L, s.count())
        // Ring keeps the recent window for percentiles; max is session-wide.
        for (i in 1..100) s.record(10L)
        assertEquals(10L, s.percentile(0.95))
        assertEquals(100L, s.max())
        assertEquals(200L, s.count())
        s.record(-5L)
        assertEquals(0L, s.percentile(0.0))
    }

    @Test fun slow_label_fires_only_above_five_seconds() {
        assertFalse(JournalIo8368.isSlow8368(5_000L))
        assertTrue(JournalIo8368.isSlow8368(5_001L))
        assertTrue(JournalIo8368.isSlow8368(14_800L))
        assertEquals("JOURNAL_IO_SLOW_8368", JournalIo8368.SLOW_LABEL)
    }

    @Test fun status_line_carries_the_journal_io_block() {
        val st = JournalIo8368.status8368()
        assertTrue(st.startsWith("journalIO8368[p50="))
        assertTrue(st.contains(" p95=") && st.contains(" max=") && st.contains(" depth=") && st.contains(" statsJobs="))
        val store = src("engine/TradeHistoryStore.kt")
        val status = store.substringAfter("fun validRowsStatus7346").substringBefore("fun getAllValidTradesSnapshot")
        assertTrue(status.contains("JournalIo8368.status8368()"))
    }

    @Test fun insert_trade_async_uses_the_journal_executor_not_trade_history_io() {
        val store = src("engine/TradeHistoryStore.kt")
        val insert = store.substringAfter("private fun insertTradeAsync").substringBefore("fun awaitDurableJournalBoundary6669")
        assertTrue(insert.contains("postJournalWrite8368(Runnable {"))
        assertFalse(insert.contains("ioHandler"))
        assertTrue(insert.contains("noteJournalCommitLatency8368(enqueuedMs8368, trade)"))
        // Never mark journaled before the durable row: stamp follows the insert.
        assertTrue(insert.indexOf("insertWithOnConflict") < insert.indexOf("stampDurableJournalCommit6641(trade)"))
        assertTrue(insert.indexOf("stampDurableJournalCommit6641(trade)") < insert.indexOf("noteJournalCommitLatency8368("))
        // Writer: single thread, priority queue, normal priority, journal lane.
        val writer = store.substringAfter("private fun startJournalWriter8368").substringBefore("private fun noteJournalCommitLatency8368")
        assertTrue(writer.contains("ThreadPoolExecutor(\n            1, 1,"))
        assertTrue(writer.contains("PriorityBlockingQueue<Runnable>()"))
        assertTrue(writer.contains("Process.THREAD_PRIORITY_DEFAULT"))
        assertTrue(writer.contains("JournalIo8368.LANE_JOURNAL"))
        // The durable boundary waits on the same journal FIFO.
        val boundary = store.substringAfter("fun awaitDurableJournalBoundary6669").substringBefore("private fun stampDurableJournalCommit6641")
        assertTrue(boundary.contains("postJournalWrite8368(Runnable { reached.countDown() })"))
        assertFalse(boundary.contains("ioHandler"))
    }

    @Test fun stats_refresh_stays_on_background_trade_history_io() {
        val store = src("engine/TradeHistoryStore.kt")
        assertTrue(store.contains("HandlerThread(\"TradeHistoryIO\", Process.THREAD_PRIORITY_BACKGROUND)"))
        listOf(
            "scheduleStatsRefresh" to "TradeStatsRefresh",
            "scheduleRawClosedTradesRefresh" to "TradeRawClosedRefresh",
            "scheduleLatestBuyRefresh" to "TradeLatestBuyRefresh",
            "scheduleAllSellsRefresh" to "TradeAllSellsRefresh",
            "scheduleRollingSliceRefresh" to "TradeRollingSliceRefresh",
        ).forEach { (fn, threadName) ->
            val body = store.substringAfter("private fun $fn(").substringBefore("\n    }\n")
            assertTrue(fn, body.contains("JournalIo8368.statsJob8368(Runnable {"))
            assertTrue(fn, body.contains("ioHandler?.post(r) ?: Thread(r, \"$threadName\").start()"))
            assertFalse(fn, body.contains("postJournalWrite8368"))
        }
    }

    @Test fun other_sqlite_mutations_share_the_journal_fifo_so_order_is_unchanged() {
        val store = src("engine/TradeHistoryStore.kt")
        assertFalse(store.contains("ioHandler?.post {"))
        val backfill = store.substringAfter("fun backfillLastBuyEntryQty6311").substringBefore("fun getLastBuyQtyForMint")
        assertTrue(backfill.contains("postJournalWrite8368(Runnable {"))
        val clear = store.substringAfter("fun clearAllTrades").substringBefore("fun fullResetIncludingLifetime")
        assertTrue(clear.contains("postJournalWrite8368(Runnable {"))
    }
}

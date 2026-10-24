package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8368 — durable journal write lane for TradeHistoryStore.
 *
 * CI smokes 5.0.8299 / 8322 / 8360 failed on cross-asset paper BUY receipts
 * whose POSITION/LEDGER/TERMINAL_EXEC/FILL_LOT stores committed at once while
 * JOURNAL stayed false for 10-15 s: the SQLite insert (and the
 * stampDurableJournalCommit6641 that follows it) sat on the background-priority
 * TradeHistoryIO thread behind stats-refresh scans.
 *
 * Journal row writes now run on their own normal-priority single thread. Its
 * queue is a priority queue ordered by (lane, sequence): every journal write is
 * LANE_JOURNAL and runs strictly FIFO among themselves (sequence order), and
 * anything bulk that is ever placed on this thread is LANE_BULK and always runs
 * after every queued journal write. Stats refresh stays on TradeHistoryIO.
 *
 * Pure JVM — no Android types — so ordering and latency stats are unit-tested.
 */
object JournalIo8368 {
    const val LANE_JOURNAL = 0
    const val LANE_BULK = 1
    const val SLOW_MS = 5_000L
    const val SLOW_LABEL = "JOURNAL_IO_SLOW_8368"

    private val seq8368 = AtomicLong(0L)

    /** One unit of work on the journal write thread; ordered by lane then FIFO sequence. */
    class Task8368(
        val lane: Int,
        val seq: Long,
        val enqueuedMs: Long,
        private val body: Runnable,
    ) : Runnable, Comparable<Task8368> {
        override fun run() {
            body.run()
        }

        override fun compareTo(other: Task8368): Int {
            if (lane != other.lane) return if (lane < other.lane) -1 else 1
            return if (seq < other.seq) -1 else if (seq > other.seq) 1 else 0
        }
    }

    fun task8368(lane: Int, enqueuedMs: Long, body: Runnable): Task8368 =
        Task8368(lane, seq8368.incrementAndGet(), enqueuedMs, body)

    /** Enqueue-to-commit latency: percentiles over the most recent samples, max over the session. */
    class LatencyStats8368(private val capacity: Int = 4_096) {
        private val ring = LongArray(capacity.coerceAtLeast(1))
        private var size = 0
        private var next = 0
        private var maxMs = 0L
        private var total = 0L

        @Synchronized
        fun record(ms: Long) {
            val v = ms.coerceAtLeast(0L)
            ring[next] = v
            next = (next + 1) % ring.size
            if (size < ring.size) size++
            if (v > maxMs) maxMs = v
            total++
        }

        @Synchronized
        fun percentile(p: Double): Long {
            if (size == 0) return 0L
            val sorted = ring.copyOf(size)
            sorted.sort()
            val rank = Math.ceil(p.coerceIn(0.0, 1.0) * size).toInt().coerceIn(1, size)
            return sorted[rank - 1]
        }

        @Synchronized
        fun max(): Long = maxMs

        @Synchronized
        fun count(): Long = total
    }

    private val latency8368 = LatencyStats8368()
    private val depth8368 = AtomicInteger(0)
    private val statsPending8368 = AtomicInteger(0)
    private val statsTotal8368 = AtomicLong(0L)
    private val slowCount8368 = AtomicLong(0L)

    fun onEnqueued8368() { depth8368.incrementAndGet() }

    fun onDequeued8368() {
        if (depth8368.decrementAndGet() < 0) depth8368.set(0)
    }

    fun depth8368(): Int = depth8368.get()

    /** True when a single durable insert took longer than SLOW_MS from enqueue to commit. */
    fun isSlow8368(latencyMs: Long): Boolean = latencyMs > SLOW_MS

    /** Record one durable insert; returns true when it crossed the slow threshold. */
    fun onJournalCommitted8368(latencyMs: Long): Boolean {
        latency8368.record(latencyMs)
        val slow = isSlow8368(latencyMs)
        if (slow) slowCount8368.incrementAndGet()
        return slow
    }

    /** Wraps a stats-refresh job so the status line can show how many are pending. */
    fun statsJob8368(body: Runnable): Runnable {
        statsPending8368.incrementAndGet()
        statsTotal8368.incrementAndGet()
        return Runnable {
            try {
                body.run()
            } finally {
                if (statsPending8368.decrementAndGet() < 0) statsPending8368.set(0)
            }
        }
    }

    fun status8368(): String =
        "journalIO8368[p50=${latency8368.percentile(0.50)}ms p95=${latency8368.percentile(0.95)}ms " +
            "max=${latency8368.max()}ms depth=${depth8368.get()} statsJobs=${statsPending8368.get()}] " +
            "journalInserts8368=${latency8368.count()} journalSlow8368=${slowCount8368.get()} " +
            "statsJobsTotal8368=${statsTotal8368.get()}"
}

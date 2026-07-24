package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6347 — SCANNER/HYDRATION QUEUE SEPARATION (operator P1-1 spec).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Separate queues: LIVE_READY | HYDRATING | PROBATION | SHADOW |
 *    REJECTED_WITH_TTL. Hydration cannot stall the live loop.
 *    A hot pump.fun mint that hasn't fully hydrated must not park the
 *    entire scanner cursor for 180 seconds waiting on Birdeye / Helius
 *    metadata."
 *
 * WHY IT MATTERS
 *   V5.0.6341 already demoted SAFETY_NOT_READY_STALE from hard-block to
 *   soft-shape. That killed the 180s loop stall symptom, but did not
 *   solve the root cause: the scanner used ONE queue for both live-
 *   ready candidates and mid-hydration candidates. When the hydration
 *   queue was long the live queue starved.
 *
 *   6347 introduces a labelled multi-queue router. Every candidate the
 *   scanner emits is stamped with a lifecycle bucket. The Executor's
 *   live loop consumes from LIVE_READY only; the hydration workers
 *   consume from HYDRATING; PROBATION rows keep firing but only in
 *   shadow mode; REJECTED_WITH_TTL are held out of the queue for their
 *   TTL and then re-scored.
 *
 *   This module is the ROUTER — the plumbing. It does NOT touch the
 *   scanner's scoring, it does NOT touch the executor's entry contract,
 *   and it does NOT modify existing rejection code. It provides:
 *
 *     • [enqueue] — call from the scanner emit path with a bucket tag.
 *     • [drain]   — call from the consumer with the bucket it wants.
 *     • [rejectWithTtl] — call from a soft-shape rejector; the mint
 *                          is held out for the TTL and then eligible
 *                          again on re-scoring.
 *     • [snapshot] — health surface for FIRST-TRADE READINESS to read.
 */
object ScannerHydrationQueues6347 {

    enum class Bucket {
        /** Fully hydrated, quote-ready, past every static gate. Live loop only. */
        LIVE_READY,
        /** Metadata/quote hydration still resolving. Hydration workers only. */
        HYDRATING,
        /** Failed prior sample but must keep training on live outcomes at zero size. */
        PROBATION,
        /** Explicitly shadow-only via governor or LaneEntryContract veto. */
        SHADOW,
        /** Recently soft-rejected; blocked out until TTL elapses. */
        REJECTED_WITH_TTL,
    }

    private data class Row(
        val mint: String,
        val bucket: Bucket,
        val laneRequested: String,
        val enqueuedAtMs: Long,
        val ttlUntilMs: Long,
        val note: String,
    )

    private val rowsByMint = ConcurrentHashMap<String, Row>()
    private val counters = ConcurrentHashMap<Bucket, AtomicLong>().apply {
        Bucket.values().forEach { put(it, AtomicLong(0L)) }
    }

    /** Route a candidate mint into the labelled bucket. Idempotent per mint —
     *  the LATEST bucket wins so a HYDRATING row can be promoted to
     *  LIVE_READY when hydration completes. */
    fun enqueue(
        mint: String,
        bucket: Bucket,
        laneRequested: String = "STANDARD",
        note: String = "",
    ) {
        if (mint.isBlank()) return
        val nowMs = System.currentTimeMillis()
        // If we recently rejected this mint with a TTL that hasn't lapsed,
        // ignore the incoming enqueue unless it's an explicit REJECTED_WITH_TTL
        // refresh.
        val prev = rowsByMint[mint]
        if (prev != null && prev.bucket == Bucket.REJECTED_WITH_TTL &&
            nowMs < prev.ttlUntilMs && bucket != Bucket.REJECTED_WITH_TTL) {
            try {
                PipelineHealthCollector.labelInc("SCANNER_QUEUE_REJECT_TTL_HELD_6347")
            } catch (_: Throwable) {}
            return
        }
        rowsByMint[mint] = Row(
            mint = mint,
            bucket = bucket,
            laneRequested = laneRequested,
            enqueuedAtMs = nowMs,
            ttlUntilMs = 0L,
            note = note,
        )
        counters[bucket]?.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("SCANNER_QUEUE_ENQUEUE_${bucket.name}_6347")
        } catch (_: Throwable) {}
    }

    /**
     * Register a soft-rejection with a hold-out TTL. When the TTL elapses
     * the mint is eligible for re-scoring; enqueue() will accept the next
     * bucket assignment.
     */
    fun rejectWithTtl(mint: String, ttlMs: Long, note: String) {
        if (mint.isBlank() || ttlMs <= 0L) return
        val nowMs = System.currentTimeMillis()
        rowsByMint[mint] = Row(
            mint = mint,
            bucket = Bucket.REJECTED_WITH_TTL,
            laneRequested = "",
            enqueuedAtMs = nowMs,
            ttlUntilMs = nowMs + ttlMs,
            note = note,
        )
        counters[Bucket.REJECTED_WITH_TTL]?.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("SCANNER_QUEUE_REJECT_TTL_ENQUEUE_6347")
            ForensicLogger.lifecycle(
                "SCANNER_QUEUE_REJECT_TTL_6347",
                "mint=${mint.take(10)} ttlMs=$ttlMs note=$note",
            )
        } catch (_: Throwable) {}
    }

    /** Drain up to [max] rows from a bucket. Rows are consumed (removed) from
     *  the queue. Rejected-with-TTL rows are surfaced only after their TTL
     *  elapses so the caller can re-score them. */
    fun drain(bucket: Bucket, max: Int = 32): List<String> {
        val nowMs = System.currentTimeMillis()
        val out = mutableListOf<String>()
        val iter = rowsByMint.entries.iterator()
        while (iter.hasNext() && out.size < max) {
            val e = iter.next()
            val row = e.value
            if (row.bucket != bucket) continue
            if (row.bucket == Bucket.REJECTED_WITH_TTL && nowMs < row.ttlUntilMs) continue
            out += row.mint
            iter.remove()
        }
        if (out.isNotEmpty()) {
            try {
                PipelineHealthCollector.labelInc("SCANNER_QUEUE_DRAIN_${bucket.name}_6347")
            } catch (_: Throwable) {}
        }
        return out
    }

    /** Current bucket sizes for the FIRST-TRADE READINESS surface. */
    fun sizesByBucket(): Map<Bucket, Int> {
        val out = HashMap<Bucket, Int>()
        Bucket.values().forEach { out[it] = 0 }
        for (row in rowsByMint.values) out.merge(row.bucket, 1) { a, b -> a + b }
        return out
    }

    /** Human-readable snapshot for `AATE Pipeline Health` dumps. */
    fun snapshot(): String {
        val sizes = sizesByBucket()
        val totals = counters.mapValues { it.value.get() }
        return "SCANNER_HYD_QUEUES_6347 " +
            Bucket.values().joinToString(" ") {
                "${it.name}=${sizes[it] ?: 0}/${totals[it] ?: 0L}"
            }
    }

    /** Test-only helper. */
    internal fun resetForTest() {
        rowsByMint.clear()
        counters.values.forEach { it.set(0L) }
    }
}

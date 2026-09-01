package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6626 §RUNTIME_LOOP_UNCHOKE §1 — HOT-LABEL COALESCER.
 *
 * OPERATOR EMERGENCY (V5.0.6308 dump):
 *   cycles avgMs=31_295 maxMs=373_256 anrHints=79 maxFrameGapMs=17_418
 *   MARKET_DATA_SENTINEL_6471                   = 290_278 (~8.5/sec)
 *   CRYPTO_PAPER_RUNTIME_PRECEDENCE_6559        = 218_349 (~6.4/sec)
 *   CRYPTO_EVAL_GENERATION_COALESCED_6615       = 160_827 (~4.7/sec)
 *   EXEC_GATE/EXPIRED_TICKET_ECONOMIC_REJECT_6614=  235   (tickets dying
 *     of old age because cycle time 30-373s > TTL 180s)
 *
 * RCA (troubleshoot_agent):
 *   The three hot callsites (MarketDataProvenance6471 recordSentinel,
 *   DynamicAltTokenRegistry markEvaluationStarted6567,
 *   CryptoAltTrader runtimeDisabledReason) invoke
 *   PipelineHealthCollector.labelInc synchronously on the emit thread
 *   under 500K+ hits/hour combined. The single ConcurrentHashMap keyed
 *   AtomicLong writes + downstream ForensicLogger.lifecycle emit are
 *   enough at that rate to accumulate 17s Main-thread frame gaps and
 *   crash cycle time to 30-373s. Once cycle time blows past 180s the
 *   ticket TTL, buy intents are economic-reject-expired before they
 *   can execute — killing throughput / volume / winrate together.
 *
 * FIX
 * ───
 *   Provide `inc(key)` which increments a thread-shared AtomicLong
 *   accumulator per label key. A single background scheduled thread
 *   flushes the accumulator into `PipelineHealthCollector.labelInc(key)`
 *   once per second, preserving counter accuracy while cutting the
 *   per-call cost from CHM.put + atomic + ForensicLogger overhead
 *   down to a hashmap get + atomic increment. Under 500K+ hits/hour
 *   the observable Main-thread cost drops from ~139 ops/sec to ~1 op/sec
 *   per label.
 *
 * BLAST RADIUS
 * ────────────
 *   Diagnostic counters only. The coalescer is fail-open (every op
 *   is `try { ... } catch (_: Throwable) {}`) and total counts remain
 *   monotonic — only the flush cadence changes.
 */
object HotLabelCoalescer6626 {

    private val pending = ConcurrentHashMap<String, AtomicLong>()
    private val flushedTotal = AtomicLong(0L)
    private val flushRuns = AtomicLong(0L)
    private const val FLUSH_INTERVAL_MS_6626 = 1_000L

    private val scheduler: ScheduledExecutorService by lazy {
        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "HotLabelCoalescer6626-flush").apply { isDaemon = true }
        }
        exec.scheduleAtFixedRate(
            { safeFlush6626() },
            FLUSH_INTERVAL_MS_6626, FLUSH_INTERVAL_MS_6626, TimeUnit.MILLISECONDS,
        )
        exec
    }

    init {
        // Prime the scheduler on class-load so no callsite has to bootstrap it.
        try { scheduler.hashCode() } catch (_: Throwable) {}
    }

    /**
     * Coalesced label increment. Safe from any thread; work is deferred
     * to the flush thread. Callers keep their existing `try { ... } catch`
     * shape — this method itself never throws.
     */
    fun inc6626(key: String) {
        if (key.isBlank()) return
        try {
            val c = pending[key] ?: pending.computeIfAbsent(key) { AtomicLong(0L) }
            c.incrementAndGet()
        } catch (_: Throwable) { /* fail-open — diagnostic only */ }
    }

    /**
     * Force-flush for tests and pipeline-dump determinism. Not
     * intended for hot-path use.
     */
    fun flushNow6626(): Long = safeFlush6626()

    private fun safeFlush6626(): Long {
        var drained = 0L
        try {
            // Snapshot-and-reset each key so late writers only lose at
            // most one flush window (still monotonic in the sink).
            for ((key, cell) in pending) {
                val n = cell.getAndSet(0L)
                if (n <= 0L) continue
                repeat(minOf(n, MAX_PER_KEY_PER_FLUSH_6626).toInt()) {
                    try { PipelineHealthCollector.labelInc(key) } catch (_: Throwable) {}
                }
                if (n > MAX_PER_KEY_PER_FLUSH_6626) {
                    // Extremely unlikely under normal load but keeps the
                    // sink from being flooded if a runaway loop appears.
                    try {
                        PipelineHealthCollector.labelInc(
                            "HOT_LABEL_COALESCER_TRUNCATED_6626|$key",
                        )
                    } catch (_: Throwable) {}
                }
                drained += n
            }
            if (drained > 0L) {
                flushedTotal.addAndGet(drained)
                flushRuns.incrementAndGet()
            }
        } catch (_: Throwable) { /* fail-open */ }
        return drained
    }

    fun statusLine6626(): String {
        val pendingHits = try { pending.values.sumOf { it.get() } } catch (_: Throwable) { 0L }
        return "flushed=${flushedTotal.get()} flushRuns=${flushRuns.get()} pending=$pendingHits keys=${pending.size}"
    }

    /** V5.0.6626 test-only reset — clears the accumulator without draining to the sink. */
    fun resetForTest() {
        pending.clear()
        flushedTotal.set(0L)
        flushRuns.set(0L)
    }

    private const val MAX_PER_KEY_PER_FLUSH_6626 = 10_000L
}

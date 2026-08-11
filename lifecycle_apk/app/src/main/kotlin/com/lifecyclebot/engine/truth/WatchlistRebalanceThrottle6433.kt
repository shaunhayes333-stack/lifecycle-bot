package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6433 — WATCHLIST REBALANCE COALESCER.
 *
 * OPERATOR (PIPELINE CHOKE spec):
 *   'Current source rebalance/LRU logic runs per intake event and is
 *    creating tens of thousands of operations. Replace synchronous
 *    per-token rebalance with coalesced/batched rebalance every
 *    ~500-1000ms or when material state changes.'
 *
 * DESIGN
 * ──────
 * Callers wrap their rebalance work in maybeRun { ... } — the block
 * runs at most once per MIN_INTERVAL_MS unless materialChange=true is
 * passed, in which case it runs unconditionally on the next call. If
 * a caller misses the window, the coalesceSkipped counter is bumped
 * so the operator can measure the reduction (target: >90% skip rate
 * without loss of coverage).
 */
object WatchlistRebalanceThrottle6433 {

    private const val MIN_INTERVAL_MS = 750L

    private val lastRunMs = AtomicLong(0L)
    private val runs = AtomicLong(0L)
    private val coalesceSkipped = AtomicLong(0L)
    @Volatile private var pendingMaterial: Boolean = false

    /** Signal that a material state change happened; next call will run. */
    fun markMaterialChange() { pendingMaterial = true }

    /**
     * Run the block if enough time has passed OR a material change is
     * pending. Otherwise increment coalesceSkipped and return false.
     */
    fun maybeRun(materialChange: Boolean = false, block: () -> Unit): Boolean {
        if (materialChange) markMaterialChange()
        return maybeRunInternal(block)
    }

    fun maybeRunInternal(block: () -> Unit): Boolean {
        val now = System.currentTimeMillis()
        val last = lastRunMs.get()
        if (!pendingMaterial && (now - last) < MIN_INTERVAL_MS) {
            coalesceSkipped.incrementAndGet()
            try { PipelineHealthCollector.labelInc("WATCHLIST_REBALANCE_COALESCED_6433") } catch (_: Throwable) {}
            return false
        }
        if (!lastRunMs.compareAndSet(last, now)) {
            // Another thread beat us to it; count as coalesced.
            coalesceSkipped.incrementAndGet()
            return false
        }
        pendingMaterial = false
        try {
            block()
        } finally {
            runs.incrementAndGet()
            try { PipelineHealthCollector.labelInc("WATCHLIST_REBALANCE_RUN_6433") } catch (_: Throwable) {}
        }
        return true
    }

    fun statusLine(): String {
        val r = runs.get(); val c = coalesceSkipped.get()
        val total = r + c
        val skipPct = if (total > 0) 100.0 * c / total else 0.0
        return "runs=$r coalesced=$c skipPct=${"%.1f".format(skipPct)}%"
    }
}

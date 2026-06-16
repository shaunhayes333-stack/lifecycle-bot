package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.3801 — paper-only exit sweep budget.
 *
 * Paper learning was being starved by restored-position exit churn: every loop
 * re-evaluated dozens of paper positions, emitted EXIT/EXEC_TRACE_SELL noise,
 * and duplicate-suppressed after the expensive path. This helper caps paper exit
 * checks per loop-sized window while preserving live exits and paper entry
 * throughput. It is intentionally not a strategy gate: it only throttles repeated
 * simulator exit maintenance work.
 */
object PaperExitSweepBudget {
    private const val WINDOW_MS = 5_000L
    private val windowStartMs = AtomicLong(0L)
    private val used = AtomicInteger(0)
    private val seenMintWindow = ConcurrentHashMap<String, Long>()

    fun allow(mint: String, openPaperPositions: Int): Boolean {
        if (mint.isBlank()) return false
        val now = System.currentTimeMillis()
        val start = windowStartMs.get()
        if (start == 0L || now - start >= WINDOW_MS) {
            if (windowStartMs.compareAndSet(start, now)) {
                used.set(0)
                try { seenMintWindow.clear() } catch (_: Throwable) {}
            }
        }
        val currentWindow = windowStartMs.get().takeIf { it > 0L } ?: now
        if (seenMintWindow.putIfAbsent(mint, currentWindow) == currentWindow) return false
        val maxChecks = minOf(5, openPaperPositions.coerceAtLeast(0)).coerceAtLeast(1)
        val n = used.incrementAndGet()
        return if (n <= maxChecks) {
            true
        } else {
            try { PipelineHealthCollector.labelInc("PAPER_EXIT_SWEEP_BUDGET_SKIP") } catch (_: Throwable) {}
            false
        }
    }
}

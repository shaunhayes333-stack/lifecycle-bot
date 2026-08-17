package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6459 §P1 — RECONCILER AUTHORITY (explicit scope + cadence).
 *
 * Operator: forensic reconciler last pass 1228s ago while quick shows
 * HEALTHY 3s ago. Stale reconciler must NOT display as healthy.
 * This module publishes a typed CadenceReport that exposes overdueBy
 * for both quick and full so the operator dump can never confuse them.
 */
object ReconcilerCadenceAuthority6459 {
    private const val QUICK_EXPECTED_MS = 5_000L
    private const val FULL_EXPECTED_MS = 30_000L
    private val quickLastMs = AtomicLong(0L)
    private val fullLastMs = AtomicLong(0L)
    private val quickPasses = AtomicLong(0L)
    private val fullPasses = AtomicLong(0L)
    private val staleReports = AtomicLong(0L)

    fun noteQuickPass() { quickLastMs.set(System.currentTimeMillis()); quickPasses.incrementAndGet() }
    fun noteFullPass() { fullLastMs.set(System.currentTimeMillis()); fullPasses.incrementAndGet() }

    data class CadenceReport(val quickOverdueMs: Long, val fullOverdueMs: Long, val quickHealthy: Boolean, val fullHealthy: Boolean)

    fun report(): CadenceReport {
        val now = System.currentTimeMillis()
        val q = quickLastMs.get().let { if (it == 0L) Long.MAX_VALUE else (now - it) - QUICK_EXPECTED_MS }
        val f = fullLastMs.get().let { if (it == 0L) Long.MAX_VALUE else (now - it) - FULL_EXPECTED_MS }
        val quickHealthy = q < QUICK_EXPECTED_MS * 3
        val fullHealthy = f < FULL_EXPECTED_MS * 3
        if (!quickHealthy || !fullHealthy) staleReports.incrementAndGet()
        return CadenceReport(q.coerceAtLeast(0L), f.coerceAtLeast(0L), quickHealthy, fullHealthy)
    }

    fun statusLine(): String {
        val r = report()
        return "quickPasses=${quickPasses.get()} fullPasses=${fullPasses.get()} " +
            "quickOverdueMs=${r.quickOverdueMs}(healthy=${r.quickHealthy}) " +
            "fullOverdueMs=${r.fullOverdueMs}(healthy=${r.fullHealthy}) stale=${staleReports.get()}"
    }
}

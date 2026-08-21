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

    fun noteQuickPass() { quickLastMs.set(System.currentTimeMillis()); quickPasses.incrementAndGet(); ReconcilerHeartbeat6467.onQuickSuccess() }
    fun noteFullPass() { fullLastMs.set(System.currentTimeMillis()); fullPasses.incrementAndGet(); ReconcilerHeartbeat6467.onFullSuccess() }

    data class CadenceReport(val quickOverdueMs: Long, val fullOverdueMs: Long, val quickHealthy: Boolean, val fullHealthy: Boolean)

    fun report(): CadenceReport {
        val qAge = ReconcilerHeartbeat6467.quickAgeMs()
        val fAge = ReconcilerHeartbeat6467.fullAgeMs()
        val q = if (qAge < 0L) 0L else (qAge - QUICK_EXPECTED_MS).coerceAtLeast(0L)
        val f = if (fAge < 0L) 0L else (fAge - FULL_EXPECTED_MS).coerceAtLeast(0L)
        val quickHealthy = qAge < 0L || qAge < QUICK_EXPECTED_MS * 4
        val fullHealthy = fAge < 0L || fAge < FULL_EXPECTED_MS * 4
        if (!quickHealthy || !fullHealthy) staleReports.incrementAndGet()
        return CadenceReport(q.coerceAtLeast(0L), f.coerceAtLeast(0L), quickHealthy, fullHealthy)
    }

    fun statusLine(): String {
        val r = report()
        return "quickPasses=${ReconcilerHeartbeat6467.quickPasses()} fullPasses=${ReconcilerHeartbeat6467.fullPasses()} " +
            "quickOverdueMs=${r.quickOverdueMs}(healthy=${r.quickHealthy}) " +
            "fullOverdueMs=${r.fullOverdueMs}(healthy=${r.fullHealthy}) stale=${staleReports.get()}"
    }
}

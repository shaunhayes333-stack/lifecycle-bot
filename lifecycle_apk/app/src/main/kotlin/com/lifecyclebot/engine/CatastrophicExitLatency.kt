package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6324 — CATASTROPHIC EXIT LATENCY TRACE (operator hotfix §15).
 *
 * Measures the time between detection of a catastrophic move and each
 * downstream stage (broadcast, confirmation, finalisation). The trace
 * is emitted at the end of the exit so an operator can see whether the
 * exit was slow because of detection, broadcasting, finality wait, or
 * reconciliation.
 *
 * Does NOT fabricate profitability while finality is pending; that
 * rule is enforced by the accounting authority ladder (P1/P4).
 */
object CatastrophicExitLatency {

    data class Trace(
        val mint: String,
        val detectionSource: String,
        val confirmingSource: String,
        val detectedLossPct: Double,
        val priceAgeMs: Long,
        val detectionMs: Long,
        var routeReadyMs: Long = 0L,
        var broadcastMs: Long = 0L,
        var confirmationMs: Long = 0L,
        var finalisationMs: Long = 0L,
        var route: String = "",
        var result: String = "",
    )

    private val active = ConcurrentHashMap<String, Trace>()
    private val emittedTraces = AtomicLong(0L)

    fun onDetect(
        mint: String, detectionSource: String, confirmingSource: String,
        detectedLossPct: Double, priceAgeMs: Long,
    ): Trace {
        val t = Trace(
            mint = mint,
            detectionSource = detectionSource,
            confirmingSource = confirmingSource,
            detectedLossPct = detectedLossPct,
            priceAgeMs = priceAgeMs,
            detectionMs = System.currentTimeMillis(),
        )
        active[mint] = t
        return t
    }

    fun onRouteReady(mint: String) { active[mint]?.let { it.routeReadyMs = System.currentTimeMillis() } }
    fun onBroadcast(mint: String, route: String) {
        active[mint]?.let { it.broadcastMs = System.currentTimeMillis(); it.route = route }
    }
    fun onConfirmation(mint: String) { active[mint]?.let { it.confirmationMs = System.currentTimeMillis() } }
    fun onFinalisation(mint: String, result: String) {
        val t = active.remove(mint) ?: return
        t.finalisationMs = System.currentTimeMillis()
        t.result = result
        val detectionToBroadcast = if (t.broadcastMs > 0L) t.broadcastMs - t.detectionMs else -1L
        val detectionToFinalised = t.finalisationMs - t.detectionMs
        emittedTraces.incrementAndGet()
        try {
            ForensicLogger.lifecycle(
                "CATASTROPHIC_EXIT_LATENCY_6324",
                "mint=${t.mint.take(10)} detectionSource=${t.detectionSource} confirmingSource=${t.confirmingSource} detectedLossPct=${"%.1f".format(t.detectedLossPct)} priceAgeMs=${t.priceAgeMs} detectionMs=${t.detectionMs} routeReadyMs=${t.routeReadyMs} broadcastMs=${t.broadcastMs} confirmationMs=${t.confirmationMs} finalisationMs=${t.finalisationMs} totalDetectionToBroadcastMs=$detectionToBroadcast totalDetectionToFinalisedMs=$detectionToFinalised route=${t.route} result=${t.result}",
            )
            PipelineHealthCollector.labelInc("CATASTROPHIC_EXIT_LATENCY_6324")
        } catch (_: Throwable) {}
    }

    fun cancel(mint: String, reason: String) {
        active.remove(mint) ?: return
        try {
            ForensicLogger.lifecycle(
                "CATASTROPHIC_EXIT_LATENCY_CANCELLED_6324",
                "mint=${mint.take(10)} reason=$reason",
            )
        } catch (_: Throwable) {}
    }

    fun emittedTraceCount(): Long = emittedTraces.get()
    fun activeTraceCount(): Int = active.size
}

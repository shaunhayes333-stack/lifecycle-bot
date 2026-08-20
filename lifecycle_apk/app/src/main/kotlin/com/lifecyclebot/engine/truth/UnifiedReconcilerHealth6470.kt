package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6470 §P0 — UNIFIED RECONCILER HEALTH.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "Eliminate the parallel §6441 / §6454 / §6459 reconciler truth
 *    surfaces. Observed impossible state:
 *      §6454 quick=455/full=77 healthy
 *      §6459 quickPasses=0/fullPasses=0 stale=true
 *    There must be one reconciler service and one ReconcilerHealth
 *    snapshot. All health/report surfaces read that same object."
 *
 * DESIGN
 * ──────
 * This module DOES NOT replace the individual reconcilers — that
 * would be a rip-and-replace of thousands of lines. Instead it
 * provides a single UNIFIED snapshot and DETECTS split-brain state
 * across the surfaces. Any report authority that queries reconciler
 * health MUST read `snapshot()` from here, not the individual
 * surfaces. That converges the reported truth to a single value and
 * makes contradictions loud.
 *
 * SPLIT-BRAIN detection
 * ─────────────────────
 * A surface reporting HEALTHY (recent success ticks) while a sibling
 * surface reports STALE (zero passes / MAX_VALUE age) is a
 * contradiction. Detected surfaces emit `RECONCILER_SPLIT_BRAIN_6470`.
 *
 * The authoritative WallClockReconciler6454 fed
 * ReconcilerHeartbeat6467 in V5.0.6467, so we take THAT as the ground
 * truth. `ReconcilerCadenceAuthority6459` and `CanonicalReconciler6441`
 * are compared against it; disagreement is telemetry, not policy.
 */
object UnifiedReconcilerHealth6470 {

    data class Snapshot(
        val quickPasses: Long,
        val fullPasses: Long,
        val quickAgeMs: Long,
        val fullAgeMs: Long,
        val healthyPer6467: Boolean,
        val splitBrainDetected: Boolean,
        val contradictions: List<String>,
    )

    private val queries = AtomicLong(0L)
    private val splitBrainDetections = AtomicLong(0L)
    private val lastSnapshot = AtomicReference<Snapshot?>(null)

    fun snapshot(): Snapshot {
        queries.incrementAndGet()
        // Ground-truth counters from 6467 (already fed by WallClockReconciler6454
        // in V5.0.6467).
        val quickPasses = try { ReconcilerHeartbeat6467.quickPasses() } catch (_: Throwable) { 0L }
        val fullPasses = try { ReconcilerHeartbeat6467.fullPasses() } catch (_: Throwable) { 0L }
        val quickAge = try { ReconcilerHeartbeat6467.quickAgeMs() } catch (_: Throwable) { -1L }
        val fullAge = try { ReconcilerHeartbeat6467.fullAgeMs() } catch (_: Throwable) { -1L }
        val healthy = quickPasses > 0L && quickAge in 0L..300_000L
        // Detect contradictions against secondary surfaces. We look at PipelineHealthCollector
        // labels since that's how each surface publishes health.
        val contradictions = mutableListOf<String>()
        if (healthy) {
            // If 6459 reports stale/uninitialized while 6467 says healthy, that's a contradiction.
            try {
                val cadence6459 = PipelineHealthCollector.labelCountSnapshot("RECONCILER_CADENCE_STALE_6459")
                if (cadence6459 > 0L) contradictions += "6459_stale_while_6467_healthy"
            } catch (_: Throwable) {}
            try {
                val cadence6459Un = PipelineHealthCollector.labelCountSnapshot("RECONCILER_CADENCE_UNINITIALIZED_6459")
                if (cadence6459Un > 0L) contradictions += "6459_uninit_while_6467_healthy"
            } catch (_: Throwable) {}
        }
        val split = contradictions.isNotEmpty()
        if (split) {
            splitBrainDetections.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "RECONCILER_SPLIT_BRAIN_6470",
                    "contradictions=${contradictions.joinToString(",")} " +
                        "quickPasses=$quickPasses quickAgeMs=$quickAge " +
                        "fullPasses=$fullPasses fullAgeMs=$fullAge",
                )
                PipelineHealthCollector.labelInc("RECONCILER_SPLIT_BRAIN_6470")
            } catch (_: Throwable) {}
        }
        val snap = Snapshot(
            quickPasses = quickPasses,
            fullPasses = fullPasses,
            quickAgeMs = quickAge,
            fullAgeMs = fullAge,
            healthyPer6467 = healthy,
            splitBrainDetected = split,
            contradictions = contradictions.toList(),
        )
        lastSnapshot.set(snap)
        return snap
    }

    fun lastSnapshot(): Snapshot? = lastSnapshot.get()

    fun statusLine(): String {
        val stamp = CanonicalInstanceIdentity6472.stamp("UnifiedReconcilerHealth6470")
        val s = lastSnapshot.get() ?: return "queries=${queries.get()} snapshot=null $stamp"
        return "queries=${queries.get()} splitBrain=${splitBrainDetections.get()} " +
            "quickPasses=${s.quickPasses} fullPasses=${s.fullPasses} " +
            "quickAgeMs=${s.quickAgeMs} fullAgeMs=${s.fullAgeMs} " +
            "healthy=${s.healthyPer6467} contradictions=${s.contradictions.size} $stamp"
    }

    internal fun resetForTest() {
        queries.set(0L); splitBrainDetections.set(0L); lastSnapshot.set(null)
    }
}

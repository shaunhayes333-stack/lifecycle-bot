package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6496 §3 — ROOT CAUSE FRESHNESS AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6495 evidence):
 *
 *   "6495's root-cause banner is misleading. It says
 *    PAPER_EQUITY_CONSERVATION_VIOLATION_6467 (n=14). But the current
 *    state says Paper capital conservation: OK, conservationViolations=0.
 *    The 14 violations are historical/replay events.
 *
 *    That should be repaired. Root cause should use active state or
 *    counter delta since the previous health interval, not merely
 *    lifetimeCount > 0."
 *
 * DESIGN
 * ──────
 * Sits between `RootCauseClassifier6471` and
 * `PipelineHealthCollector.labelCountSnapshot`. Records the label
 * count at the top of every classify() cycle and exposes:
 *
 *   • `activeCount(label)`  → delta since last cycle (> 0 iff the
 *                              counter incremented within the freshness
 *                              window ⇒ this fault is genuinely ACTIVE)
 *   • `lifetimeCount(label)` → raw lifetime, for informational display
 *
 * Classifier consults `activeCount(label) > 0` instead of
 * `lifetimeCount > 0`. Historical (stable) counters are surfaced on a
 * separate "past faults" line, never as the top-priority root cause.
 *
 * The freshness window defaults to 60 s (approx one operator dump
 * interval). A previous checkpoint older than the window is treated
 * as "unknown last" — the first call returns lifetime (fail-open so
 * we do not mask a real fault at boot).
 */
object RootCauseFreshnessAuthority6496 {

    private val lastSampled = ConcurrentHashMap<String, Sample>()
    private val classifications = AtomicLong(0L)

    // 60s freshness window (matches the operator's dump cadence).
    private const val FRESHNESS_WINDOW_MS = 60_000L

    private data class Sample(val count: Long, val atMs: Long)

    /**
     * Returns the delta of `label`'s counter since the last call.
     * A positive value means the counter incremented within the
     * freshness window → this fault is currently ACTIVE.
     *
     * First-ever call on a label returns the lifetime value so we
     * never mask a fault present at boot.
     */
    fun activeCount(label: String): Long {
        classifications.incrementAndGet()
        val nowMs = System.currentTimeMillis()
        val current = try {
            PipelineHealthCollector.labelCountSnapshot(label)
        } catch (_: Throwable) { 0L }
        val prev = lastSampled[label]
        lastSampled[label] = Sample(current, nowMs)
        return when {
            prev == null -> current // fail-open on first sighting
            else -> (current - prev.count).coerceAtLeast(0L) // elapsed time never reactivates lifetime history
        }
    }

    /** Raw lifetime count. Historical (stable > 0 across windows) faults surface here only. */
    fun lifetimeCount(label: String): Long =
        try { PipelineHealthCollector.labelCountSnapshot(label) } catch (_: Throwable) { 0L }

    /** True iff the label incremented within the freshness window. */
    fun isActive(label: String): Boolean = activeCount(label) > 0L

    /** True iff lifetime > 0 but no delta within the freshness window. */
    fun isHistoricalOnly(label: String): Boolean {
        val life = lifetimeCount(label)
        val delta = activeCount(label)
        return life > 0L && delta == 0L
    }

    fun statusLine(): String =
        "classifications=${classifications.get()} tracked=${lastSampled.size} windowMs=$FRESHNESS_WINDOW_MS"

    internal fun resetForTest() {
        lastSampled.clear()
        classifications.set(0L)
    }
}

package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6398 — SCANNER HEAT PUBLISHER (wire-up).
 *
 * Feeds hydrated-candidates-per-second percentile into
 * AdaptiveFloorBrain6397.postScannerHeat so the floor responds to
 * market temperature in real time.
 *
 * Callers (scanner intake) invoke `onHydratedCandidate()` each time a
 * candidate passes hard-safety hydration. A rolling 30s window is used
 * as the sample base; we compute the current rate vs. its own p90 to
 * yield a 0..1 percentile.
 */
object ScannerHeatPublisher6398 {

    /** Rolling window (ms) for computing hydrated candidates per second. */
    const val WINDOW_MS: Long = 30_000L
    /** Reference max hydrated-per-second (calibrate to observed live volume). */
    const val REFERENCE_MAX_PER_SEC: Double = 3.0

    private val timestamps = ConcurrentLinkedDeque<Long>()
    val hydratedTotal = AtomicLong(0L)

    /**
     * Record one hydrated candidate. Recomputes the rolling rate and
     * publishes the percentile-normalised value to the brain.
     */
    fun onHydratedCandidate(nowMs: Long = System.currentTimeMillis()) {
        hydratedTotal.incrementAndGet()
        timestamps.addLast(nowMs)
        pruneOlderThan(nowMs - WINDOW_MS)
        val perSec = timestamps.size.toDouble() / (WINDOW_MS / 1_000.0)
        val pct01 = (perSec / REFERENCE_MAX_PER_SEC).coerceIn(0.0, 1.0)
        AdaptiveFloorBrain6397.postScannerHeat(pct01)
    }

    fun currentPct01(nowMs: Long = System.currentTimeMillis()): Double {
        pruneOlderThan(nowMs - WINDOW_MS)
        val perSec = timestamps.size.toDouble() / (WINDOW_MS / 1_000.0)
        return (perSec / REFERENCE_MAX_PER_SEC).coerceIn(0.0, 1.0)
    }

    private fun pruneOlderThan(threshold: Long) {
        while (timestamps.peekFirst()?.let { it < threshold } == true) timestamps.pollFirst()
    }

    internal fun clearAllForTest() { timestamps.clear(); hydratedTotal.set(0L) }
}

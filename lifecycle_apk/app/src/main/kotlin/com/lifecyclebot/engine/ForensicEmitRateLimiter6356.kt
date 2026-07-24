package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6356 — FORENSIC EMIT RATE LIMITER.
 *
 * OPERATOR SYMPTOM
 *   The V5.0.6308-era emergency report showed LIVE_PROBABILITY_RAPID_PIVOT_SHAPED_4572
 *   / LIVE_PROBABILITY_SIZE_SHAPE_5999 / LIVE_PROBABILITY_RAW_REALITY_CLAMP_6000
 *   firing 30-50 times per single ms tick. Each call writes a
 *   ForensicLogger.lifecycle row to disk, so the events bombing at
 *   30/ms is a direct ANR source (max frame gap 46723ms in the same
 *   report; loop cycles blew up to 123435ms max).
 *
 * FIX PHILOSOPHY
 *   The three labels above fire per LANE_EVAL, which runs thousands of
 *   times per session (V5.0.6308 report: 5711 lane evals in 3432s
 *   uptime). Same message repeated per eval carries no new information
 *   — we already have the label counter for per-eval frequency data.
 *   The ForensicLogger row's value is showing the current shape state
 *   AT INTERVALS, not on every eval.
 *
 *   [shouldEmit] returns true if we haven't emitted for the (label, key)
 *   pair inside the last [cooldownMs] and false otherwise. Callers keep
 *   the PipelineHealthCollector.labelInc call (cheap in-memory) and
 *   wrap only the ForensicLogger.lifecycle call in the shouldEmit gate.
 *
 * DEFAULT COOLDOWN
 *   30 seconds. Matches operator's cadence for reading pipeline dumps
 *   — anything more frequent is disk churn without information gain.
 */
object ForensicEmitRateLimiter6356 {

    private const val DEFAULT_COOLDOWN_MS: Long = 30_000L

    /** Composite key = "LABEL|scope". Scope is normally the lane / mint /
     *  bucket so a hot lane doesn't starve out cool lanes. */
    private val lastEmitAtMs = ConcurrentHashMap<String, Long>()

    /**
     * Returns true if the caller should emit; also stamps the emission
     * timestamp atomically so a race between two threads produces exactly
     * one emit. Never throws — fail-open on any exception so an
     * observability rate-limiter cannot break the caller's hot path.
     */
    fun shouldEmit(label: String, scope: String = "", cooldownMs: Long = DEFAULT_COOLDOWN_MS): Boolean {
        return try {
            val key = "$label|$scope"
            val now = System.currentTimeMillis()
            val prev = lastEmitAtMs[key]
            if (prev != null && now - prev < cooldownMs) {
                try { PipelineHealthCollector.labelInc("FORENSIC_EMIT_SUPPRESSED_6356") } catch (_: Throwable) {}
                return false
            }
            // Optimistic swap. If a concurrent thread beats us, we still return
            // true — extra emit on rare races is preferable to the map churn
            // of a full compareAndSet loop.
            lastEmitAtMs[key] = now
            true
        } catch (_: Throwable) {
            true
        }
    }

    internal fun resetForTest() { lastEmitAtMs.clear() }
}

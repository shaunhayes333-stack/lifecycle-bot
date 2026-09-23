package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7264 §HOW_LONG_DOES_A_SWEEP_ACTUALLY_TAKE.
 *
 * Operator 5.0.7263, paper, 36 open positions in 17 minutes:
 *
 *   Exit sweep start/done:      5 / 5  (reset=5)
 *   ExitCoordinator stale resets: 5    (should be ~0)
 *   EXIT_COORDINATOR_STALE_HEARTBEAT_REPLACED_7057  40
 *   EXIT_COORDINATOR_REPLACEMENT_AWAITING_DISPATCH_7180  30
 *   PRICE_FALLBACK_ALL_LIVE_SOURCES_FAILED_6914  10809
 *
 * Five full sweeps in seventeen minutes against a 30-second cadence. This
 * subsystem has been repaired eight times (6647, 6897, 7057, 7067, 7121,
 * 7179, 7180, 7213) and every repair reasoned about liveness, relaunching
 * and lease age — never about the one number that would settle it: how long
 * the sweep body takes per position when the price providers are refusing.
 * UNIVERSAL_SL_SWEEP_SUMMARY_6402 writes elapsedMs to the forensic log, which
 * is not on the report. This object holds the last, worst and mean sweep
 * duration, the positions it covered, and the slow-position count, so the
 * next snapshot says whether the coordinator is dead or merely doing 36
 * synchronous provider round-trips on one thread.
 *
 * Read-only telemetry. No gate reads it.
 */
object ExitSweepTiming7264 {
    private val sweeps = AtomicLong(0L)
    private val lastElapsedMs = AtomicLong(0L)
    private val maxElapsedMs = AtomicLong(0L)
    private val totalElapsedMs = AtomicLong(0L)
    private val lastSeen = AtomicLong(0L)
    private val lastEvaluated = AtomicLong(0L)
    private val lastDeferred = AtomicLong(0L)
    private val lastEndedAtMs = AtomicLong(0L)
    private val slowPositions = AtomicLong(0L)
    private val maxPositionMs = AtomicLong(0L)

    fun onSweep(elapsedMs: Long, seen: Int, evaluated: Int, deferred: Int) {
        sweeps.incrementAndGet()
        lastElapsedMs.set(elapsedMs)
        totalElapsedMs.addAndGet(elapsedMs.coerceAtLeast(0L))
        maxElapsedMs.accumulateAndGet(elapsedMs) { a, b -> maxOf(a, b) }
        lastSeen.set(seen.toLong()); lastEvaluated.set(evaluated.toLong()); lastDeferred.set(deferred.toLong())
        lastEndedAtMs.set(System.currentTimeMillis())
    }

    fun onSlowPosition(elapsedMs: Long) {
        slowPositions.incrementAndGet()
        maxPositionMs.accumulateAndGet(elapsedMs) { a, b -> maxOf(a, b) }
    }

    fun statusLine(): String {
        val n = sweeps.get()
        val mean = if (n == 0L) 0L else totalElapsedMs.get() / n
        val ago = if (lastEndedAtMs.get() == 0L) -1L else System.currentTimeMillis() - lastEndedAtMs.get()
        val perPos = if (lastEvaluated.get() == 0L) 0L else lastElapsedMs.get() / lastEvaluated.get()
        return "sweeps=$n lastMs=${lastElapsedMs.get()} meanMs=$mean maxMs=${maxElapsedMs.get()} " +
            "lastSeen=${lastSeen.get()} lastEvaluated=${lastEvaluated.get()} lastDeferred=${lastDeferred.get()} " +
            "perPositionMs=$perPos slowPositions=${slowPositions.get()} worstPositionMs=${maxPositionMs.get()} lastEndedAgoMs=$ago"
    }
}

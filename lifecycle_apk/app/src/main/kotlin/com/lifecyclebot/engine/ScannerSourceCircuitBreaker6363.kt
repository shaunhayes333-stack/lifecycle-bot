package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6363 — SCANNER SOURCE CIRCUIT BREAKER.
 *
 * OPERATOR DIRECTIVE (from V5.0.6362 emergency snapshot):
 *   "scanPumpFunDirect:169 timeouts" — a single scanner source burned 169 ×
 *   5s = ~14 minutes of scan-batch time while returning nothing. Cycles
 *   ballooned to 87-136s in the tail as a direct consequence.
 *
 * DESIGN
 *   Per-source consecutive-timeout counter. When a source hits
 *   [TRIP_THRESHOLD] consecutive timeouts, it is placed in a
 *   [COOLDOWN_MS]-long cooldown during which [shouldRun] returns `false`.
 *   Any successful scan in the meantime clears the counter (see
 *   [onSuccess]) and re-arms the source immediately.
 *
 *   This is FUNDAMENTALLY DIFFERENT from the earlier V5.9.1497
 *   deprioritization (removed by V5.0.3686 as "wrong failure mode"): that
 *   one skipped 2/3 cycles PERMANENTLY once tripped, silently disabling
 *   the source. This circuit breaker
 *     - trips only on CONSECUTIVE failure (spike-tolerant)
 *     - cools for a bounded window (60s) — never a permanent ban
 *     - self-heals the instant a scan succeeds
 *     - fully bypassable by [reset]
 *   so the intake never permanently loses a source and healthy sources
 *   are never suppressed.
 *
 * SCOPE
 *   Only guards the withTimeout wrapper around a single scan block.
 *   PumpPortal WS firehose is on a separate path and untouched. Batch
 *   budget ([SolanaMarketScanner.SCAN_BATCH_BUDGET_MS] = 8s) still
 *   applies on top.
 */
object ScannerSourceCircuitBreaker6363 {

    /** Trip after this many CONSECUTIVE timeouts. */
    const val TRIP_THRESHOLD: Int = 3

    /** How long a tripped source stays in cooldown. */
    const val COOLDOWN_MS: Long = 60_000L

    private data class State(
        val consecutiveTimeouts: Int,
        val trippedUntilMs: Long,
    )

    private val state = ConcurrentHashMap<String, State>()
    private val trips = AtomicLong(0L)
    private val skips = AtomicLong(0L)

    /** Whether the source may run this cycle. */
    fun shouldRun(name: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = state[name] ?: return true
        if (nowMs >= s.trippedUntilMs) return true
        skips.incrementAndGet()
        return false
    }

    /** Call after a successful scan. Clears the streak and any active cooldown. */
    fun onSuccess(name: String) {
        state.remove(name)
    }

    /** Call after a scan times out. May trip the breaker if streak crosses the threshold. */
    fun onTimeout(name: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        var tripped = false
        state.compute(name) { _, prev ->
            val streak = (prev?.consecutiveTimeouts ?: 0) + 1
            val until = if (streak >= TRIP_THRESHOLD) {
                tripped = true
                nowMs + COOLDOWN_MS
            } else {
                prev?.trippedUntilMs ?: 0L
            }
            State(consecutiveTimeouts = streak, trippedUntilMs = until)
        }
        if (tripped) trips.incrementAndGet()
        return tripped
    }

    /** Non-timeout errors (network etc.) don't count toward the streak but do reset it since
     *  the request completed with a definitive answer — the source is reachable, just noisy. */
    fun onError(name: String) {
        state.remove(name)
    }

    /** For tests / operator debug. */
    fun snapshot(): Map<String, Pair<Int, Long>> =
        state.mapValues { (_, s) -> s.consecutiveTimeouts to s.trippedUntilMs }

    fun tripsTotal(): Long = trips.get()
    fun skipsTotal(): Long = skips.get()

    /** Test-only. Never wire to runtime. */
    fun reset() {
        state.clear()
        trips.set(0L)
        skips.set(0L)
    }
}

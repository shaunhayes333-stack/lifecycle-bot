package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6473 §P1.1-Watchlist — HARD-CAP INVARIANT (audit-only).
 *
 * OPERATOR MANDATE (6472 deferred):
 *   "Hard invariant: watchlist physical size <= configured cap at all
 *    times. Do not insert above cap and then rebalance thousands of
 *    times."
 *
 * DESIGN
 * ──────
 * Audit-only surface. Callers who mutate a watchlist / candidate
 * queue call `.assertSize(current, cap, watchlistName)` after every
 * insert. On overrun, emits `WATCHLIST_HARDCAP_OVERRUN_6473` with
 * the overrun magnitude so the operator can see EXACTLY which
 * watchlist is breaching the invariant.
 *
 * The module does NOT mutate the underlying watchlist — that would
 * be an unbounded side-effect from an audit surface. Instead the
 * emitted telemetry is picked up by the operator report and drives
 * a fix on the insert side.
 */
object WatchlistHardCapInvariant6473 {

    private val checks = AtomicLong(0L)
    private val overruns = AtomicLong(0L)
    private val lastOverrunMagnitude = AtomicLong(0L)

    /**
     * Assert `current <= cap`. Returns true when the invariant holds.
     * On violation, emits telemetry + forensic entry.
     */
    fun assertSize(current: Int, cap: Int, watchlistName: String): Boolean {
        checks.incrementAndGet()
        if (cap <= 0 || current <= cap) return true
        val magnitude = current - cap
        overruns.incrementAndGet()
        lastOverrunMagnitude.set(magnitude.toLong())
        try {
            ForensicLogger.lifecycle(
                "WATCHLIST_HARDCAP_OVERRUN_6473",
                "watchlist=$watchlistName current=$current cap=$cap overrun=+$magnitude",
            )
            PipelineHealthCollector.labelInc("WATCHLIST_HARDCAP_OVERRUN_6473_$watchlistName".take(60))
        } catch (_: Throwable) {}
        return false
    }

    fun statusLine(): String {
        val stamp = CanonicalInstanceIdentity6472.stamp("WatchlistHardCapInvariant6473")
        return "checks=${checks.get()} overruns=${overruns.get()} " +
            "lastOverrun=${lastOverrunMagnitude.get()} $stamp"
    }

    internal fun resetForTest() {
        checks.set(0L); overruns.set(0L); lastOverrunMagnitude.set(0L)
    }
}

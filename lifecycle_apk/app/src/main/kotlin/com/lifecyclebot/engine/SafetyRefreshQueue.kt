package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1496 — SAFETY REFRESH QUEUE (spec 5.0.3501 §1 SAFETY_NOT_READY_STALE).
 *
 * When a LIVE candidate is deferred because its safety report is stale/missing,
 * the finality layer (ExecutableOpenGate) requests an out-of-band refresh for
 * that mint via [request]. BotService.processTokenCycle drains this set at the
 * top of each token pass and forces a fresh safety check BEFORE FDG runs, so
 * the candidate is re-evaluated with current safety on the very next tick —
 * deferred and refreshed, never silently discarded.
 *
 * Tiny, lock-free, idempotent. A mint stays queued (de-duplicated) until the
 * cycle that refreshes it clears it via [consume]. Bounded by natural watchlist
 * size; entries are one-shot.
 */
object SafetyRefreshQueue {
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Request an immediate safety refresh for [mint]. Idempotent. */
    fun request(mint: String) {
        if (mint.isBlank()) return
        if (pending.add(mint)) {
            try {
                ForensicLogger.lifecycle(
                    "SAFETY_REFRESH_REQUESTED",
                    "mint=${mint.take(10)} queued=true reason=stale_finality_defer",
                )
            } catch (_: Throwable) {}
        }
    }

    /** True if this mint is awaiting a forced refresh. */
    fun isPending(mint: String): Boolean = mint.isNotBlank() && pending.contains(mint)

    /** Atomically claim a refresh request (returns true once, then clears it). */
    fun consume(mint: String): Boolean = mint.isNotBlank() && pending.remove(mint)

    /** Snapshot of currently-queued mints (diagnostics). */
    fun snapshot(): Set<String> = pending.toSet()

    fun size(): Int = pending.size

    fun clear() = pending.clear()
}

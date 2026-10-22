package com.lifecyclebot.network

import java.util.concurrent.ConcurrentHashMap

/**
 * 8268: one outstanding SPL wallet snapshot per wallet owner.
 *
 * Wallet balances for admission, reconciliation and exits all request the same expensive
 * getTokenAccountsByOwner snapshot. On an RPC outage those callers previously each walked
 * the entire provider ladder, amplifying 429 responses into a device-wide request storm.
 *
 * IN_FLIGHT and BACKOFF are indeterminate outcomes, NEVER evidence of an empty wallet.
 * A successful strict snapshot (which publishes WalletAccountCache) clears backoff.
 * Transaction cache invalidation is separate: it cannot be satisfied by an old snapshot.
 */
object WalletTokenReadGate8268 {
    private data class State(
        var inFlight: Boolean = false,
        var failures: Int = 0,
        var retryAtMs: Long = 0L,
        var inFlightSinceMs: Long = 0L,   // V5.0.8307 — when the outstanding read started (0 = none)
    )

    private val owners = ConcurrentHashMap<String, State>()
    private val fallbackOwners = ConcurrentHashMap<String, State>()

    /** Returns null only after taking the single-flight lease. */
    fun begin(owner: String, nowMs: Long = System.currentTimeMillis()): String? =
        beginFrom(owners, owner, nowMs)

    /** The bounded known-mint rescue is itself a 6-way RPC fanout. Gate it separately. */
    fun beginFallback(owner: String, nowMs: Long = System.currentTimeMillis()): String? =
        beginFrom(fallbackOwners, owner, nowMs)

    private fun beginFrom(states: ConcurrentHashMap<String, State>, owner: String, nowMs: Long): String? {
        val state = states.computeIfAbsent(owner) { State() }
        return synchronized(state) {
            when {
                state.inFlight -> "IN_FLIGHT"
                nowMs < state.retryAtMs -> "BACKOFF"
                else -> {
                    state.inFlight = true
                    state.inFlightSinceMs = nowMs
                    null
                }
            }
        }
    }

    /** Release the lease on every path, including exceptions and superseded observations. */
    fun finish(
        owner: String,
        success: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        providerFailure: Boolean = true,
    ) = finishIn(owners, owner, success, nowMs, providerFailure, successCooldownMs = 0L)

    /** Even a successful partial known-mint observation is not a full-wallet proof. */
    fun finishFallback(owner: String, success: Boolean, nowMs: Long = System.currentTimeMillis()) =
        finishIn(fallbackOwners, owner, success, nowMs, providerFailure = true, successCooldownMs = 5_000L)

    private fun finishIn(
        states: ConcurrentHashMap<String, State>,
        owner: String,
        success: Boolean,
        nowMs: Long,
        providerFailure: Boolean,
        successCooldownMs: Long,
    ) {
        val state = states.computeIfAbsent(owner) { State() }
        synchronized(state) {
            if (success) {
                state.failures = 0
                state.retryAtMs = nowMs + successCooldownMs
            } else if (providerFailure) {
                state.failures = (state.failures + 1).coerceAtMost(10)
                val delayMs = (2_000L shl (state.failures - 1).coerceAtMost(4)).coerceAtMost(30_000L)
                state.retryAtMs = nowMs + delayMs
            } else {
                // A confirmed transaction invalidated the in-progress snapshot.
                // Retry promptly rather than treating valid evidence invalidation as RPC outage.
                state.retryAtMs = 0L
            }
            state.inFlight = false
            state.inFlightSinceMs = 0L
        }
    }

    // ── V5.0.8307 §EVERY_CALLER_JOINS_THE_ONE_READ ──
    //
    // 5.0.8306: getTokenAccountsByOwner was 38.5k of 65.5k Helius credits and 1,416 of 1,871 reads were duplicates within
    // 10 s. A caller that met IN_FLIGHT threw and walked its own fallback (the six-way known-mint fan-out, a retry, a
    // per-mint read) instead of using the snapshot one second away. Callers now wait, bounded, for the read in flight and
    // take its published snapshot; a caller needing a snapshot newer than a moment (a sell after its own broadcast) joins
    // only a read that STARTED after that moment, so concurrent forced reads coalesce into one as well.

    /** When the read in flight for [owner] started (0 = none in flight). */
    fun inFlightSince8307(owner: String): Long = owners[owner]?.let { synchronized(it) { if (it.inFlight) it.inFlightSinceMs else 0L } } ?: 0L

    /** Pure: may a caller needing a snapshot observed at or after [notBeforeMs] wait for a read that started at [startedAtMs]? */
    fun joinable8307(startedAtMs: Long, notBeforeMs: Long): Boolean = startedAtMs > 0L && startedAtMs >= notBeforeMs

    /** Pure: may a published snapshot observed at [observedAtMs] serve a caller at [nowMs] that needs one at/after [notBeforeMs]? */
    fun serves8307(observedAtMs: Long, nowMs: Long, notBeforeMs: Long): Boolean =
        observedAtMs > 0L && observedAtMs <= nowMs && observedAtMs >= notBeforeMs

    /** Wait (bounded) until no read is in flight for [owner]. True when idle before [maxWaitMs] ran out. */
    fun awaitIdle8307(owner: String, maxWaitMs: Long): Boolean {
        val until = System.currentTimeMillis() + maxWaitMs.coerceAtLeast(0L)
        while (inFlightSince8307(owner) > 0L) {
            if (System.currentTimeMillis() >= until) return false
            try { Thread.sleep(40L) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
        }
        return true
    }
}

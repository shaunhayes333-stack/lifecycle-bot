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
    )

    private val owners = ConcurrentHashMap<String, State>()

    /** Returns null only after taking the single-flight lease. */
    fun begin(owner: String, nowMs: Long = System.currentTimeMillis()): String? {
        val state = owners.computeIfAbsent(owner) { State() }
        return synchronized(state) {
            when {
                state.inFlight -> "IN_FLIGHT"
                nowMs < state.retryAtMs -> "BACKOFF"
                else -> {
                    state.inFlight = true
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
    ) {
        val state = owners.computeIfAbsent(owner) { State() }
        synchronized(state) {
            if (success) {
                state.failures = 0
                state.retryAtMs = 0L
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
        }
    }
}

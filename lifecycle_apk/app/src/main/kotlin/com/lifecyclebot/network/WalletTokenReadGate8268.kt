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
        }
    }
}

package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6121 — WalletStateBridge
 *
 * Cheap, thread-safe cache of the most recent observed SOL wallet
 * balance. Written by BotService's wallet-poll loop, read by hot-path
 * consumers (DailyCompoundTargeter, ConvictionStackBooster, sizing
 * multipliers) that can't afford a synchronous RPC call.
 *
 * Fail-open: reads return 0.0 if nothing has ever been written; callers
 * are expected to treat 0 as "unknown, don't apply the shape".
 */
object WalletStateBridge {

    private val encodedBalance = AtomicLong(0L)          // Double bits
    private val lastUpdateMs = AtomicLong(0L)

    /** Write from any wallet-poll callsite. */
    fun publishSolBalance(sol: Double) {
        try {
            val bits = java.lang.Double.doubleToRawLongBits(sol.coerceAtLeast(0.0))
            encodedBalance.set(bits)
            lastUpdateMs.set(System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    /** Read the last known SOL balance. Zero == unknown. */
    fun latestSolBalance(): Double {
        return try {
            java.lang.Double.longBitsToDouble(encodedBalance.get())
        } catch (_: Throwable) { 0.0 }
    }

    fun lastUpdateMs(): Long = lastUpdateMs.get()
}

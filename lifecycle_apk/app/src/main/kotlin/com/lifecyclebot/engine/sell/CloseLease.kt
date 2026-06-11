package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1527 — SELL SOURCE FIX (spec item 3).
 *
 * ONE canonical close lease per mint. Single gate that prevents duplicate sell
 * jobs / repeated SELL_START spam for the same mint.
 *
 * MARS forensics: SELL_START x11 over ~138s with recursive
 * "PENDING_RETRY_1: PENDING_RETRY_1: ..." pollution and no quote/submit/confirm
 * phase. Each retry re-entered the sell path because nothing held a lease
 * across the request->retry boundary.
 *
 * Contract:
 *  - acquire(mint) succeeds for exactly one caller; later callers are rejected
 *    and increment duplicateCloseAttemptsSuppressed until the lease releases.
 *  - lease carries: closeAttemptCount, lastCloseFailureCode, originalExitReason.
 *  - originalExitReason set ONCE on first acquire, NEVER mutated.
 *  - hard TTL auto-clears a leaked lease so a mint is never permanently blocked.
 *  - release only on CLOSED_CONFIRMED or terminal retry failure.
 *
 * Sits ABOVE SellExecutionLocks (a short tx-in-flight mutex): the lease spans
 * the WHOLE close lifecycle incl. retry waits; SellExecutionLocks spans one
 * quote->confirm attempt.
 */
object CloseLease {

    const val LEASE_TTL_MS: Long = 600_000L

    data class Lease(
        val mint: String,
        val originalExitReason: String,
        @Volatile var acquiredMs: Long,
        @Volatile var lastTouchMs: Long,
        @Volatile var closeAttemptCount: Int,
        @Volatile var lastCloseFailureCode: String?,
    )

    private val leases = ConcurrentHashMap<String, Lease>()
    private val _dupSuppressed = AtomicLong(0L)

    val duplicateCloseAttemptsSuppressed: Long get() = _dupSuppressed.get()
    fun activeLeaseCount(): Int = leases.size
    fun isLeased(mint: String): Boolean = current(mint) != null

    private fun current(mint: String): Lease? {
        if (mint.isBlank()) return null
        val l = leases[mint] ?: return null
        if (System.currentTimeMillis() - l.acquiredMs >= LEASE_TTL_MS) {
            leases.remove(mint)
            try {
                ForensicLogger.lifecycle("SELL_LEASE_STALE_CLEARED",
                    "mint=${mint.take(10)} ageMs=${System.currentTimeMillis() - l.acquiredMs} attempts=${l.closeAttemptCount}")
            } catch (_: Throwable) {}
            return null
        }
        return l
    }

    fun acquire(mint: String, symbol: String, rawReason: String): Lease? {
        if (mint.isBlank()) return null
        val existing = current(mint)
        if (existing != null) {
            _dupSuppressed.incrementAndGet()
            try {
                ForensicLogger.lifecycle("SELL_DUPLICATE_SUPPRESSED",
                    "mint=${mint.take(10)} symbol=$symbol heldAttempts=${existing.closeAttemptCount} " +
                    "originalExitReason=${existing.originalExitReason} totalSuppressed=${_dupSuppressed.get()}")
            } catch (_: Throwable) {}
            return null
        }
        val now = System.currentTimeMillis()
        val canonical = canonicalReason(rawReason)
        val lease = Lease(mint, canonical, now, now, 1, null)
        val raced = leases.putIfAbsent(mint, lease)
        if (raced != null) {
            _dupSuppressed.incrementAndGet()
            return null
        }
        try {
            ForensicLogger.lifecycle("SELL_LEASE_ACQUIRED",
                "mint=${mint.take(10)} symbol=$symbol originalExitReason=$canonical")
        } catch (_: Throwable) {}
        return lease
    }

    fun recordRetry(mint: String, failureCode: String): Int {
        val l = current(mint) ?: return 0
        l.closeAttemptCount += 1
        l.lastCloseFailureCode = failureCode
        l.lastTouchMs = System.currentTimeMillis()
        try {
            ForensicLogger.lifecycle("SELL_RETRY_SCHEDULED",
                "mint=${mint.take(10)} attempt=${l.closeAttemptCount} reason=$failureCode " +
                "originalExitReason=${l.originalExitReason}")
        } catch (_: Throwable) {}
        return l.closeAttemptCount
    }

    fun release(mint: String, terminal: String) {
        val l = leases.remove(mint) ?: return
        try {
            ForensicLogger.lifecycle("SELL_LEASE_RELEASED",
                "mint=${mint.take(10)} terminal=$terminal attempts=${l.closeAttemptCount} " +
                "originalExitReason=${l.originalExitReason}")
        } catch (_: Throwable) {}
    }

    fun attemptCount(mint: String): Int = current(mint)?.closeAttemptCount ?: 0

    fun canonicalReason(raw: String): String =
        raw.replace(Regex("^(PENDING_RETRY_\\d+:\\s*)+"), "").trim().ifBlank { "exit" }
}

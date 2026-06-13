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
        // V5.9.1533 — single-flight backoff (spec item 3). A released-or-retrying
        // lease must NOT instantly re-enter the same failed route. nextEligibleMs is
        // an absolute wallclock gate per mint; exit callers skip cheaply until then.
        @Volatile var nextEligibleMs: Long = 0L,
        @Volatile var lastErrorClass: String = "",
        // Intent priority: a more urgent exit reason may RAISE priority without
        // spawning a second worker. Higher = more urgent (RUG=100, SL=80, TP=40…).
        @Volatile var intentPriority: Int = 0,
        // V5.9.1568 — true only while a doSell attempt is actively running.
        // Retryable failures keep the lease but flip this false so a later tick
        // can reuse the lease after backoff instead of waiting for 10m TTL.
        @Volatile var inFlight: Boolean = true,
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
        val now = System.currentTimeMillis()
        if (existing != null) {
            if (existing.inFlight || now < existing.nextEligibleMs) {
                _dupSuppressed.incrementAndGet()
                try {
                    ForensicLogger.lifecycle("SELL_DUPLICATE_SUPPRESSED",
                        "mint=${mint.take(10)} symbol=$symbol heldAttempts=${existing.closeAttemptCount} " +
                        "inFlight=${existing.inFlight} waitMs=${(existing.nextEligibleMs - now).coerceAtLeast(0L)} " +
                        "originalExitReason=${existing.originalExitReason} totalSuppressed=${_dupSuppressed.get()}")
                } catch (_: Throwable) {}
                return null
            }
            // Retryable attempt completed and backoff has elapsed: reuse the
            // existing lease, preserving originalExitReason/attempt history.
            existing.inFlight = true
            existing.lastTouchMs = now
            try {
                ForensicLogger.lifecycle("SELL_LEASE_RETRY_REENTERED",
                    "mint=${mint.take(10)} symbol=$symbol attempt=${existing.closeAttemptCount + 1} originalExitReason=${existing.originalExitReason}")
            } catch (_: Throwable) {}
            return existing
        }
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
        val now = System.currentTimeMillis()
        l.lastTouchMs = now
        l.inFlight = false
        // If the route did not set provider-specific backoff, still prevent an
        // immediate same-tick retry storm while allowing the next cycle through.
        if (l.nextEligibleMs <= now) l.nextEligibleMs = now + 2_000L
        try {
            ForensicLogger.lifecycle("SELL_RETRY_SCHEDULED",
                "mint=${mint.take(10)} attempt=${l.closeAttemptCount} reason=$failureCode " +
                "nextEligibleMs=${l.nextEligibleMs} originalExitReason=${l.originalExitReason}")
        } catch (_: Throwable) {}
        return l.closeAttemptCount
    }

    /**
     * V5.9.1533 — per-mint exponential backoff keyed by error class (spec item 3 + 7).
     * Jupiter 503/429, Pump 0x1787, etc. each get their own escalating cooldown so a
     * release never re-enters the same dead route on the next tick. Caps at 60s.
     */
    fun scheduleBackoff(mint: String, errorClass: String): Long {
        val l = current(mint) ?: return 0L
        l.lastErrorClass = errorClass
        val attempt = l.closeAttemptCount.coerceAtLeast(1)
        // 2s, 4s, 8s, 16s, 32s, 60s cap — provider/error aware.
        val base = when {
            errorClass.contains("503") || errorClass.contains("429") || errorClass.contains("BACKOFF") -> 4_000L
            errorClass.contains("0x1787") || errorClass.contains("PUMP_ROUTE_INVALID") -> 6_000L
            errorClass.contains("JITO") -> 1_500L
            else -> 2_000L
        }
        val delay = (base * (1L shl (attempt - 1).coerceIn(0, 5))).coerceAtMost(60_000L)
        l.nextEligibleMs = System.currentTimeMillis() + delay
        l.lastTouchMs = System.currentTimeMillis()
        // Do NOT flip inFlight here: scheduleBackoff can be called from inside
        // doSell while the stack is still active. recordRetry() marks the attempt
        // reusable after doSell returns FAILED_RETRYABLE.
        try {
            ForensicLogger.lifecycle("SELL_BACKOFF_SCHEDULED",
                "mint=${mint.take(10)} errorClass=$errorClass attempt=$attempt delayMs=$delay nextEligibleMs=${l.nextEligibleMs}")
        } catch (_: Throwable) {}
        return delay
    }

    /** True if this mint is currently in a backoff cooldown (caller should skip cheaply). */
    fun inBackoff(mint: String): Boolean {
        val l = current(mint) ?: return false
        return System.currentTimeMillis() < l.nextEligibleMs
    }

    fun msUntilEligible(mint: String): Long {
        val l = current(mint) ?: return 0L
        return (l.nextEligibleMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /**
     * Update intent priority WITHOUT spawning a second worker (spec item 3). A new,
     * more-urgent exit reason raises priority on the existing lease; returns true if
     * raised. The active worker reads intentPriority to escalate slippage/route.
     */
    fun raiseIntent(mint: String, reason: String, priority: Int): Boolean {
        val l = current(mint) ?: return false
        if (priority > l.intentPriority) {
            l.intentPriority = priority
            l.lastTouchMs = System.currentTimeMillis()
            try {
                ForensicLogger.lifecycle("SELL_INTENT_RAISED",
                    "mint=${mint.take(10)} newReason=$reason priority=$priority (no new worker)")
            } catch (_: Throwable) {}
            return true
        }
        return false
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

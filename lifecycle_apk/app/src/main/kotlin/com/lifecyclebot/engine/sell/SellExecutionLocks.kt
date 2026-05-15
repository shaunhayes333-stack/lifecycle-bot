package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.775 — EMERGENT MEME surgical fix.
 *
 * Operator forensics_20260516_001259.json showed HODL/GPT positions
 * permanently blocked with `SELL_BLOCKED_ALREADY_IN_PROGRESS lock=true`
 * even though no real sell tx was in flight (no SELL_TX_BUILT, no
 * SELL_BROADCAST, no SELL_CONFIRMED, last_sell_signature empty).
 *
 * Root cause: this object was previously a plain `AtomicBoolean` with
 * no TTL. If ANY code path acquired the lock and failed to release
 * (e.g. a long-running profit-lock verifier coroutine, a thrown
 * exception that bypassed the finally, an OS-killed process restart
 * with an in-memory leftover) the lock stayed `true` forever and
 * every future sell attempt was rejected at `blockIfSellInFlight`.
 *
 * Per operator spec V5.9.775:
 *  - sell lock must have a hard TTL
 *  - must always be released on inconclusive balance checks, RPC empty,
 *    quote failures, tx build failures, retry scheduling
 *  - SELL_BLOCKED_ALREADY_IN_PROGRESS must only fire while an actual
 *    sell tx is being built/broadcast/confirmed, not while waiting for
 *    a retry
 *
 * Implementation:
 *  - timestamp-keyed `ConcurrentHashMap<String, Long>` (millis acquired)
 *  - `tryAcquire(mint)` evicts a stale entry older than [DEFAULT_TTL_MS]
 *    before testing — so a leaked lock auto-clears on the next attempt
 *  - `isLocked(mint)` returns false for stale entries (lazy eviction)
 *  - `acquiredAtMs(mint)` exposed for forensics (SELL_LOCK_STALE_CLEARED)
 *  - `forceRelease(mint)` for explicit auto-recovery from blockIfSellInFlight
 */
object SellExecutionLocks {
    /**
     * Hard TTL. A live sell from quote to verify normally takes 5–30 s.
     * 60 s gives ample headroom for a slow Jupiter/PumpPortal roundtrip
     * while still auto-clearing genuine leaks within a single retry cycle.
     */
    const val DEFAULT_TTL_MS: Long = 60_000L

    private val locks = ConcurrentHashMap<String, Long>()

    fun tryAcquire(mint: String): Boolean {
        if (mint.isBlank()) return false
        val now = System.currentTimeMillis()
        // Evict stale entry first so a previously leaked lock cannot
        // block a fresh sell attempt forever.
        val existing = locks[mint]
        if (existing != null && (now - existing) >= DEFAULT_TTL_MS) {
            locks.remove(mint)
        }
        // putIfAbsent returns null when the key was not present — i.e.
        // we successfully acquired. Any non-null return means another
        // (fresh) lock holder beat us to it.
        return locks.putIfAbsent(mint, now) == null
    }

    fun release(mint: String) {
        if (mint.isNotBlank()) locks.remove(mint)
    }

    fun isLocked(mint: String): Boolean {
        if (mint.isBlank()) return false
        val ts = locks[mint] ?: return false
        val now = System.currentTimeMillis()
        if ((now - ts) >= DEFAULT_TTL_MS) {
            // Lazy eviction — never report a stale lock as held.
            locks.remove(mint)
            return false
        }
        return true
    }

    /** Diagnostics — millis since the lock was acquired, or null if not held. */
    fun ageMs(mint: String): Long? {
        if (mint.isBlank()) return null
        val ts = locks[mint] ?: return null
        return System.currentTimeMillis() - ts
    }

    /** Explicit recovery handle — used by Executor.blockIfSellInFlight stale-clear path. */
    fun forceRelease(mint: String): Long? {
        if (mint.isBlank()) return null
        val removed = locks.remove(mint) ?: return null
        return System.currentTimeMillis() - removed
    }

    /** Diagnostics. */
    fun heldCount(): Int = locks.size
}

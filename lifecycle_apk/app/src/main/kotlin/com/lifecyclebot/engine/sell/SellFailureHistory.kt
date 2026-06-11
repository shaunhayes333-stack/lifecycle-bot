package com.lifecyclebot.engine.sell

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.9.495z43 — operator spec item F.
 *
 * Operator: "STATE_DOWNGRADE_BLOCKED should not hide actual sell failures.
 *  Keep canonical state plus failure history. If a route fails after a
 *  successful parse, do not keep retrying the same sell. If TX_PARSE_OK
 *  proves tokens consumed, immediately update remaining quantity before
 *  any further sell attempt."
 *
 * This module is the failure-history side of the spec. The state machine
 * itself lives in TokenLifecycleTracker; this map holds a per-mint
 * structured failure ring buffer that callers consult before launching
 * another retry. If the latest entry is TX_PARSE_OK_BUT_ROUTE_FAILED
 * the caller MUST refuse to retry until operator manually clears it
 * (or the next reconciler pass shows the wallet balance moved).
 */
object SellFailureHistory {

    enum class Kind {
        ROUTE_FAILED_NO_SIGNATURE,
        ROUTE_FAILED_AFTER_BROADCAST,
        TX_PARSE_OK_BUT_ROUTE_FAILED,    // critical — do NOT retry blindly
        AMOUNT_VIOLATION,
        TIMEOUT,
        UNKNOWN,
    }

    data class Entry(
        val mint: String,
        val symbol: String,
        val kind: Kind,
        val reason: String,
        val sigOrNull: String?,
        val atMs: Long,
    )

    private const val MAX_PER_MINT = 10
    private val history = ConcurrentHashMap<String, ArrayDeque<Entry>>()
    private val totalRecorded = AtomicInteger(0)

    @Synchronized
    fun record(
        mint: String,
        symbol: String,
        kind: Kind,
        reason: String,
        sig: String? = null,
    ) {
        if (mint.isBlank()) return
        val q = history.getOrPut(mint) { ArrayDeque() }
        q.addLast(Entry(mint, symbol, kind, reason, sig, System.currentTimeMillis()))
        while (q.size > MAX_PER_MINT) q.removeFirst()
        totalRecorded.incrementAndGet()
    }

    fun latest(mint: String): Entry? = history[mint]?.lastOrNull()

    /**
     * Operator spec: "If a route fails AFTER a successful parse, do not keep
     * retrying the same sell." Returns true when the caller should refuse
     * the next sell attempt and surface the issue to the operator instead.
     */
    fun shouldBlockNextRetry(mint: String): Boolean {
        val last = latest(mint) ?: return false
        return last.kind == Kind.TX_PARSE_OK_BUT_ROUTE_FAILED
    }

    /** Forensics export hook. */
    fun snapshot(): Map<String, List<Entry>> =
        history.mapValues { it.value.toList() }

    fun totalRecorded(): Int = totalRecorded.get()

    fun clear(mint: String) { history.remove(mint) }

    /**
     * V5.9.1522 — regression guard: returns true if ANY mint had a recent
     * NO_SIGNATURE route failure while its sell-execution lock is STILL held.
     * That is the exact illegal state the operator forbids: a route that ended
     * in NO_SIGNATURE must clear the in-progress lock unless a retry job is
     * actually scheduled. If we find a leaked lock, opportunistically clear it
     * so the system self-heals on the next stop attempt (and the guard flips
     * back to green once telemetry refreshes).
     */
    fun hasLeakedNoSignatureLock(): Boolean {
        var leaked = false
        for ((mint, q) in history) {
            val last = q.lastOrNull() ?: continue
            if (last.kind != Kind.ROUTE_FAILED_NO_SIGNATURE) continue
            // only consider recent failures (last 5 min) — old history is noise
            if (System.currentTimeMillis() - last.atMs > 5 * 60_000L) continue
            val locked = try { SellExecutionLocks.isLocked(mint) } catch (_: Throwable) { false }
            if (locked) {
                leaked = true
                // self-heal: a NO_SIGNATURE with no scheduled retry must not hold the lock
                try { SellExecutionLocks.release(mint) } catch (_: Throwable) {}
                try {
                    com.lifecyclebot.engine.ErrorLogger.warn(
                        "SellFailureHistory",
                        "♻️ [NO_SIG_LOCK_LEAK] ${last.symbol} — released leaked lock after NO_SIGNATURE")
                } catch (_: Throwable) {}
            }
        }
        return leaked
    }
}

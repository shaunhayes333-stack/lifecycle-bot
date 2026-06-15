package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ForensicLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.3746 — BALANCE_UNKNOWN REQUEUE LOOP FIX (operator spec, items 1, 4, 5, 7).
 *
 * Per-mint registry of positions currently "waiting for a balance proof". A mint
 * in this state has NO active sell — no close lease, no sell lock, no in-flight
 * route, no PendingSellQueue ACTIVE_SELL_READY job. Only BalanceProofPoller is
 * allowed to act on it until proof arrives or two-provider zero confirmation.
 *
 * Spec contract:
 *   - markWaiting(mint, symbol, reason, priority) is idempotent. A second call
 *     for the same mint merges the exit reason (highest priority wins) and
 *     increments balance_wait_merge_count. It does NOT spawn a second worker
 *     and MUST NOT count as SELL_DUPLICATE_SUPPRESSED.
 *   - clear(mint) is the only way out: called when proof is found
 *     (BALANCE_PROOF_READY → re-enqueue active sell) or when two independent
 *     reads confirm zero (ZERO_BALANCE_CONFIRMED → CLOSED_VERIFIED).
 *   - getWaiting(mint) returns the wait record so the poller can read the
 *     desired exit reason and schedule the next poll.
 *
 * Exit reason priority used by mergeIntent():
 *   100 = UNIVERSAL_HARD_FLOOR / RUG
 *    80 = STRICT_SL
 *    60 = ORPHAN_RESCUE
 *    40 = NORMAL_EXIT / take-profit
 */
object BalanceProofWaitState {
    private const val TAG = "BalanceProofWaitState"

    data class Wait(
        val mint: String,
        val symbol: String,
        @Volatile var desiredExitReason: String,
        @Volatile var intentPriority: Int,
        @Volatile var firstQueuedAtMs: Long,
        @Volatile var lastTouchedMs: Long,
        @Volatile var pollAttempt: Int = 0,
        @Volatile var consecutiveZeroReads: Int = 0,
        @Volatile var balanceWaitMergeCount: Int = 0,
        @Volatile var nextPollAtMs: Long = 0L,
        @Volatile var runtimeGeneration: Long = 0L,
    )

    private val waits = ConcurrentHashMap<String, Wait>()
    private val _mergeCounter = AtomicLong(0L)

    /** Backoff schedule per spec: 2s, 5s, 10s, 20s, 30s, then 30s cap. */
    fun backoffFor(attempt: Int): Long {
        val table = longArrayOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L)
        return if (attempt < table.size) table[attempt] else 30_000L
    }

    fun priorityFor(reason: String): Int {
        val r = reason.uppercase()
        return when {
            r.contains("RUG") || r.contains("UNIVERSAL_HARD_FLOOR") || r.contains("CATASTROPHE") -> 100
            r.contains("STRICT_SL") || r.contains("HARD_FLOOR") || r.contains("STOP_LOSS") -> 80
            r.contains("ORPHAN") -> 60
            else -> 40
        }
    }

    /**
     * Idempotent mark-waiting. Returns true if a NEW wait record was created
     * (caller should schedule a poll); false if an existing record was merged
     * (caller must NOT spawn a second poller / lease).
     */
    fun markWaiting(mint: String, symbol: String, reason: String, runtimeGeneration: Long = 0L): Boolean {
        if (mint.isBlank()) return false
        val now = System.currentTimeMillis()
        val newPriority = priorityFor(reason)
        val existing = waits[mint]
        if (existing != null) {
            // Merge: keep first-queue time, raise priority only if higher, count merge.
            if (newPriority > existing.intentPriority) {
                existing.desiredExitReason = reason
                existing.intentPriority = newPriority
            }
            existing.lastTouchedMs = now
            existing.balanceWaitMergeCount += 1
            _mergeCounter.incrementAndGet()
            try {
                ForensicLogger.lifecycle("BALANCE_WAIT_MERGE",
                    "mint=${mint.take(10)} symbol=$symbol reason=$reason " +
                    "priority=$newPriority existingPriority=${existing.intentPriority} " +
                    "mergeCount=${existing.balanceWaitMergeCount} (no new lease / no duplicate suppressed)")
            } catch (_: Throwable) {}
            return false
        }
        val w = Wait(
            mint = mint,
            symbol = symbol,
            desiredExitReason = reason,
            intentPriority = newPriority,
            firstQueuedAtMs = now,
            lastTouchedMs = now,
            pollAttempt = 0,
            consecutiveZeroReads = 0,
            balanceWaitMergeCount = 0,
            nextPollAtMs = now + backoffFor(0),
            runtimeGeneration = runtimeGeneration,
        )
        val raced = waits.putIfAbsent(mint, w)
        if (raced != null) {
            // Race lost — treat as merge of the survivor.
            raced.balanceWaitMergeCount += 1
            _mergeCounter.incrementAndGet()
            return false
        }
        try {
            ForensicLogger.lifecycle("SELL_WAITING_BALANCE_PROOF",
                "mint=${mint.take(10)} symbol=$symbol reason=$reason priority=$newPriority " +
                "close_lease_active=false close_lease_blocking=false sell_in_flight=false")
            ForensicLogger.lifecycle("BALANCE_PROOF_POLL_SCHEDULED",
                "mint=${mint.take(10)} symbol=$symbol nextPollMs=${backoffFor(0)} attempt=1")
        } catch (_: Throwable) {}
        // Forensic counter for visibility (separate from SELL_RETRY_TEMPORARY_ONLY).
        try {
            SellForensics.inc(SellForensics.SELL_WAITING_BALANCE_PROOF,
                "mint=${mint.take(10)} symbol=$symbol reason=$reason")
            SellForensics.inc(SellForensics.BALANCE_PROOF_POLL_SCHEDULED,
                "mint=${mint.take(10)} symbol=$symbol attempt=1")
            SellForensics.inc(SellForensics.EXEC_LIVE_SELL_WAITING_BALANCE_PROOF,
                "mint=${mint.take(10)} symbol=$symbol")
        } catch (_: Throwable) {}
        return true
    }

    fun isWaiting(mint: String): Boolean = waits.containsKey(mint)
    fun getWaiting(mint: String): Wait? = waits[mint]
    fun all(): List<Wait> = waits.values.toList()
    fun size(): Int = waits.size
    fun totalMergeCount(): Long = _mergeCounter.get()

    /** Mark the next poll backoff and increment attempt. */
    fun scheduleNextPoll(mint: String) {
        val w = waits[mint] ?: return
        w.pollAttempt += 1
        w.lastTouchedMs = System.currentTimeMillis()
        w.nextPollAtMs = System.currentTimeMillis() + backoffFor(w.pollAttempt)
        try {
            ForensicLogger.lifecycle("BALANCE_PROOF_STILL_UNKNOWN",
                "mint=${mint.take(10)} symbol=${w.symbol} attempt=${w.pollAttempt} " +
                "nextPollMs=${backoffFor(w.pollAttempt)}")
            SellForensics.inc(SellForensics.BALANCE_PROOF_STILL_UNKNOWN,
                "mint=${mint.take(10)} attempt=${w.pollAttempt}")
        } catch (_: Throwable) {}
    }

    /** Eligible to poll right now? */
    fun dueForPoll(mint: String, now: Long = System.currentTimeMillis()): Boolean {
        val w = waits[mint] ?: return false
        return now >= w.nextPollAtMs
    }

    fun recordZeroRead(mint: String): Int {
        val w = waits[mint] ?: return 0
        w.consecutiveZeroReads += 1
        w.lastTouchedMs = System.currentTimeMillis()
        return w.consecutiveZeroReads
    }

    fun resetZeroReads(mint: String) {
        val w = waits[mint] ?: return
        w.consecutiveZeroReads = 0
    }

    fun clear(mint: String, terminal: String) {
        val removed = waits.remove(mint) ?: return
        try {
            ForensicLogger.lifecycle("BALANCE_WAIT_CLEARED",
                "mint=${mint.take(10)} symbol=${removed.symbol} terminal=$terminal " +
                "attempts=${removed.pollAttempt} merges=${removed.balanceWaitMergeCount} " +
                "ageMs=${System.currentTimeMillis() - removed.firstQueuedAtMs}")
        } catch (_: Throwable) {}
    }

    fun summary(): String {
        if (waits.isEmpty()) return "no_waits"
        return waits.values.joinToString(",") {
            "${it.symbol}(attempt=${it.pollAttempt},priority=${it.intentPriority},merges=${it.balanceWaitMergeCount})"
        }
    }

    fun resetForTest() {
        waits.clear()
        _mergeCounter.set(0L)
    }
}

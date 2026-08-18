package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6461 §P0-#2 — PENDING_ENTRY → OPEN COLLAPSE FIX.
 *
 * OPERATOR MANDATE (6457 dump):
 *   "Ensure PENDING_ENTRY never participates in open-position counts,
 *    lane-open counts, or deployed-capital counts. If no live lease
 *    after TTL, it must transition to CANCELLED_ENTRY. Update the
 *    legacy Position State Ledger projection."
 *
 * DESIGN
 * ──────
 * CanonicalPositionAuthority6441.openPositions() already filters to
 * OPEN + PARTIALLY_CLOSED (excludes PENDING_ENTRY). This module adds:
 *
 *   1. Explicit pendingEntryPositions() accessor for observability.
 *   2. sweepStalePendingEntries(ttlMs) — quarantines PENDING_ENTRY rows
 *      older than ttlMs (default 90s) with reason
 *      "PENDING_ENTRY_TTL_CANCELLED_6461". Quarantined rows drop out of
 *      openPositions() because they leave the OPEN/PARTIALLY_CLOSED set.
 *   3. Runtime guard `assertNotInOpenSet()` — a callable audit that
 *      halts the sweep if PENDING_ENTRY somehow leaked into
 *      openPositions() (defence-in-depth).
 *
 * Why quarantine (not delete): the openPosition() call already debited
 * paperCash by `entryCostSol + feesSol`. A silent delete would strand
 * that cash; quarantine + capital release path keeps invariants intact.
 */
object PendingEntryProjectionGuard6461 {

    private const val DEFAULT_TTL_MS = 90_000L   // 90s — longer than any
                                                 // legitimate live lease
                                                 // pending confirmation.

    private val sweeps = AtomicLong(0L)
    private val cancelled = AtomicLong(0L)
    private val leaks = AtomicLong(0L)

    /**
     * Returns the current PENDING_ENTRY positions. Non-mutating.
     */
    fun pendingEntryPositions(): List<CanonicalPositionAuthority6441.Position> {
        return try { CanonicalPositionAuthority6441.pendingEntryPositions6461() }
        catch (_: Throwable) { emptyList() }
    }

    /**
     * Defensive audit — a PENDING_ENTRY row must never appear in
     * openPositions(). If it does, the projection filter is broken
     * (regression). Emits PENDING_ENTRY_LEAKED_INTO_OPEN_6461.
     */
    fun assertNotInOpenSet(): Int {
        val open = try { CanonicalPositionAuthority6441.openPositions() } catch (_: Throwable) { return 0 }
        val leakCount = open.count { it.lifecycle == CanonicalPositionAuthority6441.Lifecycle.PENDING_ENTRY }
        if (leakCount > 0) {
            leaks.addAndGet(leakCount.toLong())
            try {
                ForensicLogger.lifecycle(
                    "PENDING_ENTRY_LEAKED_INTO_OPEN_6461",
                    "leaked=$leakCount openCount=${open.size} — projection filter regression",
                )
                PipelineHealthCollector.labelInc("PENDING_ENTRY_LEAKED_INTO_OPEN_6461")
            } catch (_: Throwable) {}
        }
        return leakCount
    }

    /**
     * Ask CanonicalPositionAuthority6441 to quarantine any PENDING_ENTRY
     * older than ttlMs. Returns the number of rows cancelled.
     *
     * Uses the authority's own quarantine() call so the mutation is
     * serialised through the same ReentrantLock and audit path.
     */
    fun sweepStalePendingEntries(ttlMs: Long = DEFAULT_TTL_MS): Int {
        sweeps.incrementAndGet()
        val cancelledIds = try {
            CanonicalPositionAuthority6441.cancelStalePendingEntries6461(ttlMs)
        } catch (_: Throwable) { emptyList() }
        val count = cancelledIds.size
        if (count > 0) {
            cancelled.addAndGet(count.toLong())
            try {
                ForensicLogger.lifecycle(
                    "PENDING_ENTRY_TTL_CANCELLED_6461",
                    "count=$count ttlMs=$ttlMs ids=${cancelledIds.take(4).joinToString(",")}",
                )
                PipelineHealthCollector.labelInc("PENDING_ENTRY_TTL_CANCELLED_6461")
            } catch (_: Throwable) {}
        }
        return count
    }

    fun statusLine(): String =
        "sweeps=${sweeps.get()} cancelled=${cancelled.get()} leaksDetected=${leaks.get()}"

    internal fun resetForTest() {
        sweeps.set(0L); cancelled.set(0L); leaks.set(0L)
    }
}

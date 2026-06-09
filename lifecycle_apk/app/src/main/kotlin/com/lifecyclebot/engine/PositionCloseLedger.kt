package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.1470 — POSITION CLOSE LEDGER (IDLE/STUCK TRADING FIX, spec items 1/2/8/9).
 *
 * THE PROBLEM (operator snapshot 5.0.3472): the bot idles while still scanning
 * because stale/ghost paper positions and supervisor leases occupy open slots and
 * forcedOpen priority. The exit coordinator repeatedly tries to resolve the SAME
 * mints (HOT_EXIT_STALE_RESET storm, stale resets=10, SUPERVISOR_LEASE_FORCE_RELEASED
 * =5620) because close finalization is NOT atomically removing a mint from every
 * registry — and the same mints get SOLD multiple times (Fsnx8Y, 7AUvsp).
 *
 * ROOT CAUSE: Position.isOpen is a COMPUTED getter (qtyToken>0 && !pendingVerify).
 * There is no durable "this mint is CLOSED" stamp that survives after the per-mint
 * sell lock releases, so a later exit pass can re-see the mint as sellable before
 * all derived sets (forcedOpen, memeOpen, hotExit queue) drop it.
 *
 * THE FIX: one authoritative, thread-safe close ledger keyed by mint. paperSell
 * stamps a closeId + closedAt the instant a position finalizes; every place that
 * decides "is this mint open / should I sell it / should I keep its slot" consults
 * isClosed(mint) first. This is the single source of truth the spec demands.
 *
 * SAFETY: this ledger ONLY records close-state metadata. It NEVER touches position
 * P&L, entry data, qtyToken, or wallet balances. Held (still-open) positions are
 * never recorded here. A reopen() path clears the stamp so a legitimately re-bought
 * mint can open again cleanly.
 */
object PositionCloseLedger {

    data class CloseRecord(
        val mint: String,
        val closeId: String,
        val closedAtMs: Long,
        val reason: String,
        val pnlPct: Int,
    )

    private val closed = ConcurrentHashMap<String, CloseRecord>()

    /** TTL after which a close record is pruned so the mint can be freshly re-bought
     *  without carrying stale close metadata forever. 10 min is comfortably longer
     *  than any exit-coordinator / supervisor lease lifecycle. */
    private const val CLOSE_TTL_MS = 10 * 60_000L

    /**
     * Stamp a mint CLOSED. Returns the closeId. Idempotent: if already closed within
     * TTL, returns the EXISTING closeId (so a duplicate finalize attempt is detectable
     * by the caller comparing the returned id to a freshly-minted one).
     */
    fun markClosed(mint: String, reason: String, pnlPct: Int): String {
        if (mint.isBlank()) return ""
        val now = System.currentTimeMillis()
        val existing = closed[mint]
        if (existing != null && (now - existing.closedAtMs) < CLOSE_TTL_MS) {
            return existing.closeId
        }
        val id = "C${now}_${mint.take(6)}"
        closed[mint] = CloseRecord(mint, id, now, reason.take(40), pnlPct)
        return id
    }

    /** True if this mint has a live (within-TTL) close stamp. */
    fun isClosed(mint: String): Boolean {
        if (mint.isBlank()) return false
        val rec = closed[mint] ?: return false
        if (System.currentTimeMillis() - rec.closedAtMs >= CLOSE_TTL_MS) {
            closed.remove(mint)
            return false
        }
        return true
    }

    /** The existing close id for a mint, or null. */
    fun closeIdOf(mint: String): String? = closed[mint]?.closeId

    fun recordOf(mint: String): CloseRecord? = closed[mint]

    /**
     * Clear the close stamp — call ONLY when a mint is legitimately re-opened
     * (fresh BUY confirmed). Lets the same mint trade again after its cooldown.
     */
    fun reopen(mint: String) {
        if (mint.isBlank()) return
        closed.remove(mint)
    }

    /** Prune expired records. Cheap; safe to call each cycle. */
    fun prune() {
        if (closed.isEmpty()) return
        val now = System.currentTimeMillis()
        val it = closed.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.closedAtMs >= CLOSE_TTL_MS) it.remove()
        }
    }

    fun size(): Int = closed.size

    /** Diagnostic snapshot for the health dump. */
    fun snapshot(): List<CloseRecord> = closed.values.sortedByDescending { it.closedAtMs }
}

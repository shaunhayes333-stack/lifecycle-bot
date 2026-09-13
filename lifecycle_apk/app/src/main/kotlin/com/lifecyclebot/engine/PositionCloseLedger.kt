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
        val sellSig: String = "",
        val soldQtyRaw: Long = 0L,
        val remainingQtyRaw: Long = 0L,
        val dustAmount: Double = 0.0,
        val realizedSol: Double = 0.0,
        val realizedPnl: Double = 0.0,
        val source: String = "",
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
        if (isRejectedCloseReason(reason)) {
            // V5.0.6727 §CLOSE_LEDGER_REJECTED_REASON_BREAKDOWN — 6726
            // dump: "slot-health close ledger says 0 mints stamped CLOSED,
            // despite 181 completed sells". Root: this early-return
            // silently rejects any reason matching the deny-list, which
            // includes STARTUP_GHOST_RECONCILE / CLOSED_UNVERIFIED /
            // BALANCE_UNKNOWN and several other reap-shaped tags that
            // paper sells sometimes propagate. Emit a per-reason-tag
            // counter so the operator dump reveals which specific tag
            // is preventing the stamp, so the caller in the next push
            // can be repaired to pass a canonical stamp-eligible reason.
            val rejTag6727 = try {
                val r = reason.uppercase()
                when {
                    r.contains("BALANCE_UNKNOWN") -> "BALANCE_UNKNOWN"
                    r.contains("RPC_EMPTY_MAP") -> "RPC_EMPTY_MAP"
                    r.contains("SELL_ROUTE_FAILED_NO_SIGNATURE") -> "SELL_ROUTE_FAILED_NO_SIGNATURE"
                    r.contains("NO_SIGNATURE_UNLOCKED") -> "NO_SIGNATURE_UNLOCKED"
                    r.contains("CLOSED_UNVERIFIED") -> "CLOSED_UNVERIFIED"
                    r.contains("STARTUP_GHOST_RECONCILE") -> "STARTUP_GHOST_RECONCILE"
                    r.contains("GHOST_REAP_ZERO_BALANCE") -> "GHOST_REAP_ZERO_BALANCE"
                    r.contains("UNKNOWN_RECONCILE_STALE_REAP") -> "UNKNOWN_RECONCILE_STALE_REAP"
                    r.contains("RECONCILER_REAP_NOSIG") -> "RECONCILER_REAP_NOSIG"
                    else -> "OTHER"
                }
            } catch (_: Throwable) { "OTHER" }
            try {
                PipelineHealthCollector.labelInc("POSITION_CLOSE_LEDGER_REJECTED_6727_$rejTag6727")
                ForensicLogger.lifecycle("POSITION_CLOSE_LEDGER_REJECTED", "mint=${mint.take(10)} reason=$reason tag6727=$rejTag6727")
            } catch (_: Throwable) {}
            return ""
        }
        val now = System.currentTimeMillis()
        val existing = closed[mint]
        if (existing != null && (now - existing.closedAtMs) < CLOSE_TTL_MS) {
            return existing.closeId
        }
        val id = "C${now}_${mint.take(6)}"
        closed[mint] = CloseRecord(mint, id, now, reason.take(40), pnlPct)
        try { com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markClosed("paper", mint) } catch (_: Throwable) {}
        // V5.0.6454 §P0 — ONE SETTLEMENT, ONE REWARD EVENT. Deleted the
        // compact 0.05-SOL realizedPnL PROXY (operator: "PositionCloseLedger
        // may not invent financial values"). The compact markClosed is
        // now a pure METADATA STAMP — no reward publish. Only
        // markClosedFull (which carries authoritative realizedSol +
        // realizedPnl + fees) may publish to the finalized bus.
        try { PipelineHealthCollector.labelInc("POSITION_CLOSE_LEDGER_METADATA_ONLY_6454") } catch (_: Throwable) {}
        return id
    }

    /** V5.9.1530 — atomic FULL close stamp from the SELL_FINALIZE path. Carries the
     *  entire canonical close payload so the ledger is the single source of truth.
     *  Idempotent within TTL. */
    fun markClosedFull(
        mint: String, reason: String, pnlPct: Int, sellSig: String,
        soldQtyRaw: Long, remainingQtyRaw: Long, dustAmount: Double,
        realizedSol: Double, realizedPnl: Double, source: String,
    ): String {
        if (mint.isBlank()) return ""
        if (sellSig.isBlank() || isRejectedCloseReason(reason)) {
            try { ForensicLogger.lifecycle("POSITION_CLOSE_LEDGER_REJECTED", "mint=${mint.take(10)} reason=$reason sigBlank=${sellSig.isBlank()}") } catch (_: Throwable) {}
            return ""
        }
        val now = System.currentTimeMillis()
        val existing = closed[mint]
        if (existing != null && (now - existing.closedAtMs) < CLOSE_TTL_MS) return existing.closeId
        val id = "C${now}_${mint.take(6)}"
        closed[mint] = CloseRecord(
            mint = mint, closeId = id, closedAtMs = now, reason = reason.take(40), pnlPct = pnlPct,
            sellSig = sellSig.take(96), soldQtyRaw = soldQtyRaw, remainingQtyRaw = remainingQtyRaw,
            dustAmount = dustAmount, realizedSol = realizedSol, realizedPnl = realizedPnl,
            source = source.take(24),
        )
        try { com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markClosed("paper", mint) } catch (_: Throwable) {}
        // V5.0.6453 §P0-#6 — the direct GrowthAlignedRewardShaper6439.shape
        // call has been DELETED from this path (obsolete writer). Reward
        // shaping now fires from the CanonicalTradeFinalizedBus6450
        // subscriber installed by CanonicalRewardBootstrap6453 — a SINGLE
        // owner receives the terminal event and fans out to shaper +
        // RewardPurityGate. Prior redundant call created a parallel W/L
        // count that disagreed with the bus subscribers.
        try { com.lifecyclebot.engine.truth.CanonicalRewardBootstrap6453.ensureBootstrapped() } catch (_: Throwable) {}
        // V5.0.6448 — PositionCloseLedger is a close metadata ledger only.
        // Canonical lifecycle/reward transitions are now performed at executor
        // confirmation sites with full qty/proceeds/cost data. Do not infer or
        // replay canonical SELL/RewardPurity here from a journal/close row.
        try { PipelineHealthCollector.labelInc("POSITION_CLOSE_LEDGER_METADATA_ONLY_6448") } catch (_: Throwable) {}
        // V5.0.6485 — metadata-only projection. Canonical terminal reducers publish.
        return id
    }

    /** Spec rehydration rule: hard-closed = CLOSED in ledger AND fresh wallet balance
     *  is dust. null balance = unknown → NOT hard-closed (never hide a genuinely-held
     *  bag on an RPC blip). */
    fun isHardClosed(mint: String, walletBalanceUi: Double?, dustUi: Double = 1.0): Boolean {
        if (!isClosed(mint)) return false
        if (walletBalanceUi == null) return false
        return walletBalanceUi <= dustUi
    }

    /**
     * V5.0.6699 — a fresh canonical BUY outranks stale close metadata. The old
     * implementation required every buy path to remember to call reopen(); no
     * production caller did, so a legitimately re-entered mint could remain
     * CLOSED for the full 10-minute TTL and be suppressed by paper/live exit
     * guards. Self-heal only when a canonical open position is newer than the
     * close stamp; an older held position can never erase a genuine close.
     */
    private fun clearIfCanonicallyReopened6699(mint: String, rec: CloseRecord): Boolean {
        val freshOpen = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.openPositions().any { p ->
                p.mint == mint && p.openedAtMs > rec.closedAtMs && p.remainingQtyRaw > java.math.BigInteger.ZERO
            }
        } catch (_: Throwable) { false }
        if (!freshOpen) return false
        if (!closed.remove(mint, rec)) return false
        try { PaperPositionCloseAuthority.reopen("PAPER", mint) } catch (_: Throwable) {}
        try { PaperPositionCloseAuthority.reopen("LIVE", mint) } catch (_: Throwable) {}
        try {
            PipelineHealthCollector.labelInc("POSITION_CLOSE_LEDGER_CANONICAL_REOPEN_6699")
            ForensicLogger.lifecycle(
                "POSITION_CLOSE_LEDGER_CANONICAL_REOPEN_6699",
                "mint=${mint.take(10)} priorCloseId=${rec.closeId} closedAt=${rec.closedAtMs} action=clear_stale_close_for_new_canonical_open",
            )
        } catch (_: Throwable) {}
        return true
    }

    /** True if this mint has a live (within-TTL) close stamp. */
    fun isClosed(mint: String): Boolean {
        if (mint.isBlank()) return false
        val rec = closed[mint] ?: return false
        if (clearIfCanonicallyReopened6699(mint, rec)) return false
        if (System.currentTimeMillis() - rec.closedAtMs >= CLOSE_TTL_MS) {
            closed.remove(mint, rec)
            return false
        }
        return true
    }

    /** The existing close id for a mint, or null. */
    fun closeIdOf(mint: String): String? = if (isClosed(mint)) closed[mint]?.closeId else null

    fun recordOf(mint: String): CloseRecord? = if (isClosed(mint)) closed[mint] else null

    /**
     * Clear the close stamp — call ONLY when a mint is legitimately re-opened
     * (fresh BUY confirmed). Lets the same mint trade again after its cooldown.
     */
    fun reopen(mint: String) {
        if (mint.isBlank()) return
        closed.remove(mint)
        try { PaperPositionCloseAuthority.reopen("PAPER", mint) } catch (_: Throwable) {}
        try { PaperPositionCloseAuthority.reopen("LIVE", mint) } catch (_: Throwable) {}
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

    /**
     * V5.0.6743 §CLOSE_LEDGER_RECONSTRUCT_FROM_CANONICAL — operator
     * directive Feb 2026:
     *   > "Rebuild CLOSED stamps from canonical terminal state."
     *
     * The 6742 dump surfaced 42 canonical Lifecycle.CLOSED positions
     * for which this ledger held ZERO stamps, which in turn kept
     * slot-health at forced=100/open=100 and drove 460 CASH_STARVED_EXIT
     * plus 111 POSITION_HARD_CAP_EXIT throttles. Root cause: the paper
     * mirror stamps CLOSED lifecycle via `ExecutorCanonicalMirror6442`,
     * but the projection convergence sites are per-Executor sell — a
     * canonical close reached via a different path (recovery replay,
     * OwnerLane restore, terminal reducer) leaves this ledger unstamped.
     *
     * Reconstructor pass: walk canonical `closedPositions()` for the
     * given mode; for each mint whose `lastMutationMs` is inside the
     * ledger TTL and which is NOT already stamped here, stamp it
     * synthetically with a canonical-derived reason. NEVER mutates
     * P&L, wallet, or lot ledgers — pure metadata reconstruction.
     * Returns the number of newly-stamped mints so the caller can
     * observe the reconstruction rate.
     */
    fun reconstructFromCanonical6743(mode: String = "paper"): Int {
        val now = System.currentTimeMillis()
        val recent = try {
            com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.closedPositions()
                .filter { it.mode.equals(mode, ignoreCase = true) }
                .filter { (now - it.lastMutationMs) < CLOSE_TTL_MS }
        } catch (_: Throwable) { emptyList() }
        if (recent.isEmpty()) return 0
        var stamped = 0
        for (p in recent) {
            val mint = p.mint
            if (mint.isBlank()) continue
            if (closed.containsKey(mint)) continue
            // Synthesize a stamp-eligible reason so the reject deny-list
            // does not swallow it. Carries the canonical positionId so
            // downstream forensic tools can trace the reconstruction.
            val reason = "CANONICAL_TERMINAL_RECONSTRUCT_6743:${p.positionId.take(12)}"
            val id = "R${p.lastMutationMs}_${mint.take(6)}"
            closed[mint] = CloseRecord(
                mint = mint, closeId = id, closedAtMs = p.lastMutationMs,
                reason = reason.take(40), pnlPct = 0,
                source = "CANONICAL_RECONSTRUCT_6743",
            )
            try { com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464.markClosed(mode.lowercase(), mint) } catch (_: Throwable) {}
            stamped++
        }
        if (stamped > 0) try {
            PipelineHealthCollector.labelInc("POSITION_CLOSE_LEDGER_RECONSTRUCTED_FROM_CANONICAL_6743")
            repeat(stamped) { PipelineHealthCollector.labelInc("POSITION_CLOSE_LEDGER_RECONSTRUCTED_FROM_CANONICAL_STAMPS_6743") }
            ForensicLogger.lifecycle(
                "POSITION_CLOSE_LEDGER_RECONSTRUCTED_FROM_CANONICAL_6743",
                "mode=${mode.lowercase()} newlyStamped=$stamped ttlWindowMs=$CLOSE_TTL_MS action=fill_missing_ledger_stamps_from_canonical_terminal",
            )
        } catch (_: Throwable) {}
        return stamped
    }

    fun size(): Int = closed.size

    /** Diagnostic snapshot for the health dump. */
    fun snapshot(): List<CloseRecord> = closed.values.sortedByDescending { it.closedAtMs }
    private fun isRejectedCloseReason(reason: String): Boolean {
        val r = reason.uppercase()
        return r.contains("BALANCE_UNKNOWN") ||
            r.contains("RPC_EMPTY_MAP") ||
            r.contains("SELL_ROUTE_FAILED_NO_SIGNATURE") ||
            r.contains("NO_SIGNATURE_UNLOCKED") ||
            r.contains("CLOSED_UNVERIFIED") ||
            r.contains("STARTUP_GHOST_RECONCILE") ||
            r.contains("GHOST_REAP_ZERO_BALANCE") ||
            r.contains("UNKNOWN_RECONCILE_STALE_REAP") ||
            r.contains("RECONCILER_REAP_NOSIG")
    }

}

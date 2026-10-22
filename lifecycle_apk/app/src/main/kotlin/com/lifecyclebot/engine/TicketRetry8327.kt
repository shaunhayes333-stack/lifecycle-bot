package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8327 §A_TRANSIENT_DEFER_IS_RETRIED_NOT_DROPPED.
 *
 * 5.0.8323 ticketExec8304: 13 tickets -> 4 executions. Three of the drops were the executor's own NON-terminal deferrals —
 * `liveBuy` said "deferring, not failing" (TOKEN_MAP_ROUTE_PENDING_7371, MUTEX_BUSY_DEFERRED,
 * SAFETY_PROOF_PENDING_DEFERRED_8270: lease and claim released non-terminally, no buy failure) — and the lane wrapper then
 * ended the sealed ticket anyway (`unexecutedTicket7948` -> "LIVE_BUY_NOT_OPENED_7948:<stop>", the pending intent marked
 * FAILED, the SOL reservation released). The retry those deferrals promise could never land on the ticket.
 *
 * Now a ticket whose stop is one of those transient causes is HELD, not dropped ([retryOrTerminal8327]):
 *  - the ticket, its pending intent and its SOL reservation stay; the executor's own released claim lets the next tick's
 *    FRESH FDG BUY re-drive the same sealed intent (SpecialistPreauthSeal7834 reuses the version's live intent);
 *  - every retry re-runs liveBuy's own checks: the route lookup, the wallet mutex, PreTradeHardGate's safety proof.
 *    A safety defer resumes ONLY when the proof lands; nothing here buys, sizes or bypasses a gate;
 *  - bounded: at most [MAX_HOLDS_8327] holds per ticket and [HOLD_TTL_MS_8327] (the SOL reservation's own TTL,
 *    WalletCapacitySeal7868.RESERVATION_TTL_MS) from the first hold. The executor's own budgets still end a cause
 *    by name (DEFERRED_DATA_TOKEN_MAP_8080 / DEFERRED_DATA_SAFETY_PROOF_8270 after three in five minutes);
 *  - a held ticket ends as an execution (EXEC stage: `RETRY_EXECUTED_8327_<cause>`), as another named terminal (any
 *    non-transient stop, a reseal), or by the sweep: `<cause>_RETRY_EXPIRED_8327` / `<cause>_RETRY_BUDGET_8327`.
 * Counted per lane in the ticketExec8304 line: `retry[held= exec= expired= budget=]`.
 */
object TicketRetry8327 {
    const val MAX_HOLDS_8327 = 4
    const val HOLD_TTL_MS_8327 = 90_000L
    private const val SWEEP_EVERY_MS_8327 = 5_000L

    /** The executor's transient, non-terminal deferral stops (lastLiveBuyStop8252 values). */
    private val TRANSIENT_CAUSES_8327 = listOf(
        "TOKEN_MAP_ROUTE_PENDING_7371",
        "TOKEN_MAP_PENDING_DEFERRED_7371",
        "MUTEX_BUSY_DEFERRED",
        "SAFETY_PROOF_PENDING_DEFERRED_8270",
    )

    class Hold8327(val lane: String, val attemptId: String, val cause: String, val firstMs: Long, val holds: Int)

    private val held8327 = ConcurrentHashMap<String, Hold8327>()
    private val counts8327 = ConcurrentHashMap<String, AtomicLong>()   // "LANE|held|exec|expired|budget"
    private val sweeping8327 = AtomicBoolean(false)
    @Volatile private var lastSweepMs8327 = 0L

    /** Pure: the transient cause named in a wrapper reason ("LIVE_BUY_NOT_OPENED_7948:<stop>"), else null. */
    fun transientCause8327(reason: String): String? {
        val inner = reason.substringAfter(':', "").trim().uppercase()
        if (inner.isBlank()) return null
        return TRANSIENT_CAUSES_8327.firstOrNull { inner == it || inner.startsWith("$it:") || inner.startsWith("${it}_") }
    }

    /** Pure: hold again? [prior] is this ticket's earlier hold (null = first). */
    fun mayHold8327(prior: Hold8327?, nowMs: Long): Boolean =
        prior == null || (prior.holds < MAX_HOLDS_8327 && nowMs - prior.firstMs in 0L until HOLD_TTL_MS_8327)

    /** Pure: the named terminal for a held ticket at [nowMs], or null while it may still retry. */
    fun expiryReason8327(h: Hold8327, nowMs: Long): String? =
        if (nowMs - h.firstMs >= HOLD_TTL_MS_8327) "${h.cause}_RETRY_EXPIRED_8327" else null

    private fun count8327(lane: String, k: String) {
        counts8327.computeIfAbsent("$lane|$k") { AtomicLong(0L) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TICKET_RETRY_8327_${k.uppercase()}_$lane") } catch (_: Throwable) {}
    }

    /**
     * Executor.unexecutedTicket7948: null = this ticket is HELD for a retry (the caller must not terminalize it, mark its
     * intent failed or release its reservation); else the terminal reason to end it with — [reason] itself when the stop
     * is not transient, "<cause>_RETRY_BUDGET_8327" when the retry budget is spent.
     */
    fun retryOrTerminal8327(lane: String, attemptId: String, reason: String, nowMs: Long = System.currentTimeMillis()): String? {
        sweepAsync8327(nowMs)
        val cause = transientCause8327(reason) ?: return reason
        if (attemptId.isBlank()) return reason
        val prior = held8327[attemptId]
        if (!mayHold8327(prior, nowMs)) {
            held8327.remove(attemptId)
            count8327(lane, "budget")
            return "${cause}_RETRY_BUDGET_8327"
        }
        if (held8327.size > 2_000) held8327.entries.removeIf { nowMs - it.value.firstMs > 10 * HOLD_TTL_MS_8327 }
        val holds = (prior?.holds ?: 0) + 1
        held8327[attemptId] = Hold8327(lane, attemptId, cause, prior?.firstMs ?: nowMs, holds)
        count8327(lane, "held")
        try {
            ForensicLogger.lifecycle("TICKET_RETRY_HELD_8327",
                "attemptId=${attemptId.take(28)} lane=$lane cause=$cause holds=$holds/$MAX_HOLDS_8327 " +
                    "ageMs=${nowMs - (prior?.firstMs ?: nowMs)} ttlMs=$HOLD_TTL_MS_8327 action=keep_ticket_retry_next_fresh_buy")
        } catch (_: Throwable) {}
        return null
    }

    /** TicketExec8304.onStage8304 EXEC: a held ticket executed. */
    fun onExec8327(attemptId: String) {
        val h = held8327.remove(attemptId) ?: return
        count8327(h.lane, "exec")
        try { PipelineHealthCollector.labelInc("RETRY_EXECUTED_8327_${h.cause}") } catch (_: Throwable) {}
    }

    /** TicketExec8304.onDrop8304: a held ticket ended by another name (reseal, a non-transient stop, the sweep). */
    fun onTerminal8327(attemptId: String) { held8327.remove(attemptId) }

    fun heldCount8327(): Int = held8327.size

    /** Expired holds end by name, off the caller's thread (the terminal takes the sheet / seal / entry locks). */
    fun sweepAsync8327(nowMs: Long = System.currentTimeMillis()) {
        if (held8327.isEmpty() || nowMs - lastSweepMs8327 < SWEEP_EVERY_MS_8327) return
        if (held8327.values.none { expiryReason8327(it, nowMs) != null }) return
        if (!sweeping8327.compareAndSet(false, true)) return
        lastSweepMs8327 = nowMs
        try {
            Thread({
                try { sweep8327(System.currentTimeMillis()) } catch (_: Throwable) {} finally { sweeping8327.set(false) }
            }, "AATE-ticket-retry-8327").apply { isDaemon = true; start() }
        } catch (_: Throwable) { sweeping8327.set(false) }
    }

    private fun sweep8327(nowMs: Long) {
        for (h in held8327.values.toList()) {
            val why = expiryReason8327(h, nowMs) ?: continue
            if (held8327.remove(h.attemptId) == null) continue
            // A signed swap still unresolved is owned by the signed ledger / reconciler, not ended here (Executor 8198).
            if ((try { com.lifecyclebot.network.SignedTxLedger8085.unresolvedAttempt8198(h.attemptId) } catch (_: Throwable) { null }) != null) continue
            count8327(h.lane, "expired")
            terminal8327(h.lane, h.attemptId, why)
        }
    }

    /** The same terminal Executor.unexecutedTicket7948 applies: named drop, reservation released, pending intent FAILED. */
    private fun terminal8327(lane: String, attemptId: String, reason: String) {
        val exact = try { ExecutableOpenGate.ticketForAttempt(attemptId) } catch (_: Throwable) { null }
        try { ToolkitSignalSheet.terminalizeUnexecutedTicket7948(lane, attemptId, reason) } catch (_: Throwable) {}
        if (exact != null && exact.mode == "LIVE") try {
            WalletCapacitySeal7868.release(exact.mint, attemptId = exact.attemptId)
            val pending = com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.findPending(exact.mint, "LIVE", exact.candidateVersion)
            if (pending?.attemptId == exact.attemptId) com.lifecyclebot.engine.truth.CanonicalEntryAuthority6551.markFailed(pending, reason)
        } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("TICKET_RETRY_ENDED_8327", "attemptId=${attemptId.take(28)} lane=$lane reason=$reason") } catch (_: Throwable) {}
    }

    /** Pure: the retry suffix of the ticketExec8304 line ("" when this lane never held a ticket). */
    fun formatSuffix8327(held: Long, exec: Long, expired: Long, budget: Long, holdingNow: Int): String =
        if (held + exec + expired + budget <= 0L && holdingNow <= 0) ""
        else " retry8327[held=$held exec=$exec expired=$expired budget=$budget holdingNow=$holdingNow]"

    fun suffix8327(lane: String): String {
        sweepAsync8327()
        fun c(k: String) = counts8327["$lane|$k"]?.get() ?: 0L
        return formatSuffix8327(c("held"), c("exec"), c("expired"), c("budget"), held8327.values.count { it.lane == lane })
    }

    // ── MINT_REENTRY_COOLDOWN_6312 before the seal ──

    /**
     * TradeAuthorizer, before the seal (live only): the executor's mint re-entry cooldown verdict (MintReEntryCooldown,
     * the same call liveBuy makes). 5.0.8323 QUALITY sealed three tickets that liveBuy then refused as
     * MINT_REENTRY_COOLDOWN_6312. The executor check stays as the backstop.
     */
    fun preSealCooldownRefusal8327(mint: String, paper: Boolean): String? {
        if (paper || mint.isBlank()) return null
        val why = try { MintReEntryCooldown.shouldBlockReEntry(mint) } catch (_: Throwable) { null } ?: return null
        try {
            PipelineHealthCollector.labelInc("PRESEAL_MINT_REENTRY_COOLDOWN_8327")
            ForensicLogger.lifecycle("PRESEAL_MINT_REENTRY_COOLDOWN_8327", "mint=${mint.take(10)} detail=${why.take(120)} action=refuse_before_ticket")
        } catch (_: Throwable) {}
        return "PRESEAL_MINT_REENTRY_COOLDOWN_6312_8327"
    }

    internal fun resetForTests8327() { held8327.clear(); counts8327.clear(); lastSweepMs8327 = 0L }
}

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
        com.lifecyclebot.engine.truth.OnChainDepth8038.reserveCause8385(reason)?.let { return it }
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
        // V5.0.8418 — an attempt whose buy is signed-unresolved or chain-confirmed is never re-sent: its receipt decides.
        if ((try { com.lifecyclebot.network.SignedTxLedger8085.attemptSpent8418(attemptId) } catch (_: Throwable) { null }) != null) {
            held8327.remove(attemptId)
            count8327(lane, "spent8418")
            return "${reason}_ATTEMPT_SPENT_NO_RETRY_8418"
        }
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
        if (prior == null) try { pool8330().schedule({ sweep8327(System.currentTimeMillis()) }, HOLD_TTL_MS_8327, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
        arm8330(attemptId, holds)  // V5.0.8330 — this hold resumes once when its cause clears (or on the late-event guard)
        try {
            ForensicLogger.lifecycle("TICKET_RETRY_HELD_8327",
                "attemptId=$attemptId lane=$lane cause=$cause mint=${reserveWaits8385[attemptId]?.mint} mode=${reserveWaits8385[attemptId]?.mode} candidateVersion=${reserveWaits8385[attemptId]?.version} requiredObservedAfter=${reserveWaits8385[attemptId]?.afterMs} holds=$holds/$MAX_HOLDS_8327 " +
                    "ageMs=${nowMs - (prior?.firstMs ?: nowMs)} ttlMs=$HOLD_TTL_MS_8327 action=keep_ticket_retry_next_fresh_buy")
        } catch (_: Throwable) {}
        return null
    }

    /** TicketExec8304.onStage8304 EXEC: a held ticket executed. */
    fun onExec8327(attemptId: String) {
        reserveWaits8385.remove(attemptId)
        resumes8330.remove(attemptId); armed8330.remove(attemptId)  // V5.0.8330
        val h = held8327.remove(attemptId) ?: return
        count8327(h.lane, "exec")
        try { PipelineHealthCollector.labelInc("RETRY_EXECUTED_8327_${h.cause}") } catch (_: Throwable) {}
    }

    /** TicketExec8304.onDrop8304: a held ticket ended by another name (reseal, a non-transient stop, the sweep). */
    fun onTerminal8327(attemptId: String) { reserveWaits8385.remove(attemptId); held8327.remove(attemptId); resumes8330.remove(attemptId); armed8330.remove(attemptId) }

    // ── V5.0.8330 — event-driven dispatcher: a held ticket resumes the moment its cause clears ──
    //
    // 5.0.8326 live: held 7, retried-then-executed 0, expired 4. A hold waited for a LATER fresh FDG BUY to reach the
    // same ticket; none did inside 90 s. Now the three causes have events: the wallet-spend mutex release
    // (Executor.releaseBuyMutex8330), a safety proof landing (TokenSafetyChecker cache write) and a route resolving
    // (TokenMapAuthority commit). Each resumes the held ticket through the SAME lane wrapper call with the SAME
    // attemptId (registered by the wrapper before its liveBuy, [registerResume8330]), so the sealed ticket, pending
    // intent and SOL reservation are reused — never a new seal, never a new reservation. Before the wrapper runs,
    // [resumeRefusal8330] re-checks: the mint is not held and no buy is in flight (never a second purchase), the sealed
    // ticket is still live and still the version's live intent, hard safety is not blocked, and the current price has
    // not run past the decision mark; the wrapper's liveBuy then re-runs its own route / wallet / mutex / PreTradeHardGate
    // checks. A resume that does not open goes back through unexecutedTicket7948 -> retryOrTerminal8327: the same
    // 4-hold / 90 s budget and the same named terminals apply. One resume per hold per event (armed per hold).

    const val TRIGGER_MUTEX_8330 = "mutex"
    const val TRIGGER_PROOF_8330 = "proof"
    const val TRIGGER_ROUTE_8330 = "route"
    const val TRIGGER_GUARD_8330 = "guard"
    /** The current price may stand at most this far above the sealed decision mark for a resume. */
    const val MAX_RESUME_PRICE_DRIFT_8330 = 0.05
    private const val LATE_EVENT_GUARD_MS_8330 = 2_500L
    private const val RESUME_MEMORY_MS_8330 = 10L * 60_000L

    class Resume8330(val lane: String, val attemptId: String, val mint: String, val registeredMs: Long, val run: () -> Boolean)

    private val resumes8330 = ConcurrentHashMap<String, Resume8330>()
    private val armed8330 = ConcurrentHashMap<String, Int>()
    private val resuming8330: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val resumeCounts8330 = ConcurrentHashMap<String, AtomicLong>()   // "LANE|resumed|<trigger>" / "LANE|executed" / "LANE|refused|<why>"
    @Volatile private var poolRef8330: java.util.concurrent.ScheduledExecutorService? = null

    private fun pool8330(): java.util.concurrent.ScheduledExecutorService {
        poolRef8330?.let { return it }
        synchronized(this) {
            poolRef8330?.let { return it }
            val p = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "AATE-ticket-resume-8330").apply { isDaemon = true } }
            poolRef8330 = p
            return p
        }
    }

    /** Pure: does [trigger] clear a hold of [cause]? The late-event guard re-checks any cause. */
    fun triggerMatches8330(cause: String, trigger: String): Boolean = when (trigger) {
        TRIGGER_GUARD_8330 -> com.lifecyclebot.engine.truth.OnChainDepth8038.reserveCause8385(cause) == null
        "reserves8385" -> com.lifecyclebot.engine.truth.OnChainDepth8038.reserveCause8385(cause) != null
        TRIGGER_MUTEX_8330 -> cause == "MUTEX_BUSY_DEFERRED"
        TRIGGER_PROOF_8330 -> cause == "SAFETY_PROOF_PENDING_DEFERRED_8270"
        TRIGGER_ROUTE_8330 -> cause == "TOKEN_MAP_ROUTE_PENDING_7371" || cause == "TOKEN_MAP_PENDING_DEFERRED_7371"
        else -> false
    }

    /** Pure: a token-map commit with this route status has RESOLVED (blank / PENDING are still resolving). */
    fun routeResolved8330(routeStatus: String): Boolean =
        routeStatus.isNotBlank() && !routeStatus.contains("PENDING", ignoreCase = true)

    /**
     * Pure: why the held ticket may NOT resume now (null = it may). Order matters: a held / in-flight mint is the
     * one-purchase rule, then the seal, then safety, then the price.
     */
    fun resumeRefusal8330(
        mintHeld: Boolean, buyInFlight: Boolean, ticketLive: Boolean, versionIntentIsTicket: Boolean,
        safetyBlocked: Boolean, decisionPriceUsd: Double, priceNowUsd: Double,
    ): String? = when {
        mintHeld -> "ALREADY_HELD"
        buyInFlight -> "BUY_IN_FLIGHT"
        !ticketLive -> "TICKET_GONE"
        !versionIntentIsTicket -> "VERSION_RESEALED"
        safetyBlocked -> "SAFETY_BLOCKED"
        decisionPriceUsd > 0.0 && priceNowUsd > 0.0 && priceNowUsd > decisionPriceUsd * (1.0 + MAX_RESUME_PRICE_DRIFT_8330) -> "PRICE_ABOVE_DECISION"
        else -> null
    }

    /** Pure: a refusal that ends the ticket (the hold cannot become executable); PRICE_ABOVE_DECISION keeps the hold. */
    fun refusalIsTerminal8330(why: String): Boolean = why != "PRICE_ABOVE_DECISION" && why != "TOKEN_STATE_MISSING"

    /** Pure: may this hold be dispatched for [trigger]? Consumes nothing. */
    fun shouldDispatch8330(hold: Hold8327?, resume: Resume8330?, armedHolds: Int?, trigger: String, mint: String?): Boolean =
        hold != null && resume != null && armedHolds == hold.holds && triggerMatches8330(hold.cause, trigger) &&
            (mint == null || resume.mint == mint)

    /** The lane wrapper, before its liveBuy: how to re-run THIS attempt (same wrapper, same attemptId). */
    fun registerResume8330(lane: String, attemptId: String, mint: String, run: () -> Boolean) {
        if (attemptId.isBlank() || mint.isBlank()) return
        val now = System.currentTimeMillis()
        if (resumes8330.size > 256) resumes8330.entries.removeIf { now - it.value.registeredMs > RESUME_MEMORY_MS_8330 && !held8327.containsKey(it.key) }
        resumes8330[attemptId] = Resume8330(lane, attemptId, mint, now, run)
    }

    private fun arm8330(attemptId: String, holds: Int) {
        armed8330[attemptId] = holds
        reserveWaits8385[attemptId]?.let { w ->
            com.lifecyclebot.engine.truth.CurveReserves7951.acceptedObservation8385(w.mint)?.let { onReserveObserved8385(w.mint, it.atMs, it.source, it.version) }
        }
        if (!resumes8330.containsKey(attemptId)) return
        try { pool8330().schedule(Runnable { dispatchOne8330(attemptId, TRIGGER_GUARD_8330) }, LATE_EVENT_GUARD_MS_8330, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
    }

    internal data class ReserveWait8385(val mint: String, val mode: String, val attemptId: String, val version: Long,
        val afterMs: Long, val baselineVersion: Long, val accountRequired: Boolean)
    private val reserveWaits8385 = ConcurrentHashMap<String, ReserveWait8385>()
    fun noteReserveWait8385(attemptId: String, requirement: com.lifecyclebot.engine.truth.OnChainDepth8038.Preflight8385.DataDefer) {
        val ticket = ExecutableOpenGate.ticketForAttempt(attemptId) ?: return
        val observation = com.lifecyclebot.engine.truth.CurveReserves7951.acceptedObservation8385(requirement.mint)
        reserveWaits8385.putIfAbsent(attemptId, ReserveWait8385(requirement.mint, ticket.mode, attemptId, ticket.candidateVersion,
            requirement.observedAfterMs, observation?.let { if (it.atMs >= requirement.observedAfterMs) it.version - 1L else it.version } ?: 0L, requirement.accountRequired))
    }
    internal fun reserveMatches8385(w: ReserveWait8385, mint: String, atMs: Long, source: String, version: Long, nowMs: Long): Boolean =
        w.mint == mint && atMs >= w.afterMs && version > w.baselineVersion &&
            (!w.accountRequired || source == "ACCOUNT") && com.lifecyclebot.engine.truth.LaunchCohort8173.reserveFresh8174(atMs, nowMs)
    fun onReserveObserved8385(mint: String, atMs: Long, source: String, version: Long) {
        val now = System.currentTimeMillis()
        reserveWaits8385.values.filter { reserveMatches8385(it, mint, atMs, source, version, now) }.forEach { w ->
            try { ForensicLogger.lifecycle("DATA_DEFER_RESUMED_8385", "mint=$mint mode=${w.mode} attemptId=${w.attemptId} candidateVersion=${w.version} observedAt=$atMs source=$source observationVersion=$version") } catch (_: Throwable) {}
            dispatchOne8330(w.attemptId, "reserves8385")
        }
    }

    fun onVerifiedMigration8385(mint: String, observedAtMs: Long) {
        val now = System.currentTimeMillis()
        if (!com.lifecyclebot.engine.truth.LaunchCohort8173.reserveFresh8174(observedAtMs, now)) return
        reserveWaits8385.values.filter { it.mint == mint && observedAtMs >= it.afterMs }.forEach {
            dispatchOne8330(it.attemptId, "reserves8385")
        }
    }

    fun onReserveTimeout8385(mint: String, cause: String) {
        reserveWaits8385.values.filter { it.mint == mint }.forEach { w ->
            val h = held8327[w.attemptId] ?: return@forEach
            if (held8327.remove(w.attemptId, h)) terminal8327(h.lane, h.attemptId, "${h.cause}:$cause")
        }
    }

    /** Executor.releaseBuyMutex8330: the wallet-spend mutex is free. */
    fun onMutexReleased8330() = dispatch8330(TRIGGER_MUTEX_8330, null)

    /** TokenSafetyChecker: a safety report for [mint] landed in the cache. */
    fun onProofLanded8330(mint: String) = dispatch8330(TRIGGER_PROOF_8330, mint)

    /** TokenMapAuthority: the token map for [mint] committed with [routeStatus]. */
    fun onRouteResolved8330(mint: String, routeStatus: String) { if (routeResolved8330(routeStatus)) dispatch8330(TRIGGER_ROUTE_8330, mint) }

    private fun dispatch8330(trigger: String, mint: String?) {
        if (held8327.isEmpty() || resumes8330.isEmpty()) return
        for (h in held8327.values.toList()) {
            if (mint != null && resumes8330[h.attemptId]?.mint != mint) continue
            dispatchOne8330(h.attemptId, trigger)
        }
    }

    private fun dispatchOne8330(attemptId: String, trigger: String) {
        val h = held8327[attemptId] ?: return
        val r = resumes8330[attemptId] ?: return
        if (!shouldDispatch8330(h, r, armed8330[attemptId], trigger, null)) return
        if (!armed8330.remove(attemptId, h.holds)) return
        try { pool8330().execute(Runnable { resume8330(h, r, trigger) }) } catch (_: Throwable) { armed8330[attemptId] = h.holds }
    }

    private fun count8330(lane: String, k: String) { resumeCounts8330.computeIfAbsent("$lane|$k") { AtomicLong(0L) }.incrementAndGet() }

    private fun runtimeRefusal8330(h: Hold8327, r: Resume8330): String? {
        val ticket = try { ExecutableOpenGate.ticketForAttempt(h.attemptId) } catch (_: Throwable) { null }
        val ts = try { HandoffRoute8180.tokenFor8180(r.mint) } catch (_: Throwable) { null } ?: return "TOKEN_STATE_MISSING"
        val held = (try { com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.hasOpenMint(r.mint) } catch (_: Throwable) { true }) ||
            ts.position.isOpen || ts.position.pendingVerify || ts.position.qtyToken > 0.0
        val inFlight = (try { com.lifecyclebot.network.SignedTxLedger8085.unresolvedFor(r.mint, "BUY") } catch (_: Throwable) { null }) != null ||
            (try { com.lifecyclebot.network.SignedTxLedger8085.attemptSpent8418(h.attemptId) } catch (_: Throwable) { null }) != null  // V5.0.8418 — confirmed too
        if (ticket != null && ticket.expiresAtMs6613 > 0L && System.currentTimeMillis() >= ticket.expiresAtMs6613) return "AUTHORIZATION_EXPIRED_8385"
        val wait8385 = reserveWaits8385[h.attemptId]
        if (wait8385 != null && (ticket?.mint != wait8385.mint || ticket.mode != wait8385.mode || ticket.candidateVersion != wait8385.version)) return "RESERVE_WAIT_SUPERSEDED_8385"
        val versionIntent = if (ticket == null) false else try {
            ExecutableOpenGate.activeExecutionIntent6519(ticket.mode, ticket.mint, ticket.candidateVersion)?.attemptId == ticket.attemptId
        } catch (_: Throwable) { false }
        val safetyBlocked = try { ts.safety.isBlocked || ts.safety.hardBlockReasons.isNotEmpty() } catch (_: Throwable) { false }
        return resumeRefusal8330(held, inFlight, ticket != null && ticket.mode == "LIVE", versionIntent, safetyBlocked,
            ticket?.executableMarkPriceUsd6613 ?: 0.0, ts.lastPrice)
    }

    private fun resume8330(h: Hold8327, r: Resume8330, trigger: String) {
        if (!held8327.containsKey(h.attemptId)) return
        if (!resuming8330.add(h.attemptId)) return
        try {
            count8330(r.lane, "resumed|$trigger")
            try { PipelineHealthCollector.labelInc("TICKET_RESUMED_8330_${trigger.uppercase()}_${r.lane}") } catch (_: Throwable) {}
            val why = runtimeRefusal8330(h, r)
            if (why != null) {
                count8330(r.lane, "refused|$why")
                try { ForensicLogger.lifecycle("TICKET_RESUME_REFUSED_8330", "attemptId=${h.attemptId.take(28)} lane=${r.lane} mint=${r.mint.take(10)} cause=${h.cause} trigger=$trigger why=$why terminal=${refusalIsTerminal8330(why)}") } catch (_: Throwable) {}
                if (refusalIsTerminal8330(why)) {
                    if (held8327.remove(h.attemptId) != null) terminal8327(r.lane, h.attemptId, "${h.cause}_RESUME_REFUSED_${why}_8330")
                } else armed8330[h.attemptId] = h.holds   // re-armed: the next event re-checks
                return
            }
            try { ForensicLogger.lifecycle("TICKET_RESUMED_8330", "attemptId=${h.attemptId.take(28)} lane=${r.lane} mint=${r.mint.take(10)} cause=${h.cause} trigger=$trigger holds=${h.holds}/$MAX_HOLDS_8327 action=rerun_same_wrapper_same_attempt") } catch (_: Throwable) {}
            val opened = try { r.run() } catch (_: Throwable) { false }
            if (opened) count8330(r.lane, "executed")
            // not opened: the wrapper's unexecutedTicket7948 re-held (re-armed) or ended the ticket by name.
        } finally { resuming8330.remove(h.attemptId) }
    }

    /** Pure: " resumed=N(mutex=a/proof=b/route=c/guard=d) resumedExecuted=E resumedRefused=[WHY=n,...]" ("" when nothing resumed). */
    fun formatResumeSuffix8330(byTrigger: Map<String, Long>, executed: Long, refused: Map<String, Long>): String {
        val total = byTrigger.values.sum()
        if (total <= 0L && executed <= 0L && refused.isEmpty()) return ""
        val t = listOf(TRIGGER_MUTEX_8330, TRIGGER_PROOF_8330, TRIGGER_ROUTE_8330, TRIGGER_GUARD_8330).joinToString("/") { "$it=${byTrigger[it] ?: 0L}" }
        val r = refused.entries.sortedByDescending { it.value }.take(6).joinToString(",") { "${it.key}=${it.value}" }.ifBlank { "NONE" }
        return " resumed=$total($t) resumedExecuted=$executed resumedRefused=[$r]"
    }

    private fun resumeSuffix8330(lane: String): String {
        val prefix = "$lane|"
        val byTrigger = HashMap<String, Long>(); val refused = HashMap<String, Long>(); var executed = 0L
        for ((k, v) in resumeCounts8330) {
            if (!k.startsWith(prefix)) continue
            val rest = k.removePrefix(prefix)
            when {
                rest.startsWith("resumed|") -> byTrigger[rest.removePrefix("resumed|")] = v.get()
                rest.startsWith("refused|") -> refused[rest.removePrefix("refused|")] = v.get()
                rest == "executed" -> executed = v.get()
            }
        }
        return formatResumeSuffix8330(byTrigger, executed, refused)
    }

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
        return formatSuffix8327(c("held"), c("exec"), c("expired"), c("budget"), held8327.values.count { it.lane == lane }) +
            resumeSuffix8330(lane)  // V5.0.8330 — event-driven resumes
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

    internal fun resetForTests8327() { reserveWaits8385.clear(); held8327.clear(); counts8327.clear(); lastSweepMs8327 = 0L; resumes8330.clear(); armed8330.clear(); resumeCounts8330.clear() }

    /** Tests (V5.0.8330): the hold's armed count and whether a resume closure is registered. */
    internal fun armedHolds8330(attemptId: String): Int? = armed8330[attemptId]
    internal fun hasResume8330(attemptId: String): Boolean = resumes8330.containsKey(attemptId)
}

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
        // V5.0.8087 — an emergency that arrived while this attempt was running. The attempt ends and the
        // emergency is handed straight to a worker (no redispatch wait); before the attempt has signed
        // anything it is asked to stand down (preemptForEmergency8087).
        @Volatile var emergencyWaiting8087: String? = null,
        @Volatile var emergencyWaitingSinceMs8087: Long = 0L,
        // V5.0.3996 — atomic sell-finality contract. Pending finality is NOT an
        // active buy-blocking lease, but it must keep retry metadata alive until
        // proof arrives or the hard TTL clears it. Generic residue reaping may
        // prune route-failure leases, never unresolved finality proof leases.
        @Volatile var finalityPending: Boolean = false,
        // V5.0.7807 — B1/B3: an emergency reason that arrived on a lease opened for
        // a softer exit. The sell runs under it (ProtectiveExitClass7807.effectiveReason).
        @Volatile var emergencyReason7807: String? = null,
        // V5.0.8288 — pre-route waits (an attempt that ended before any balance / quote stage and signed nothing): how
        // many, and when the emergency may re-enter (StopFast8288). They are not route attempts.
        @Volatile var preRouteWaits8288: Int = 0,
        @Volatile var preRouteEligibleAtMs8288: Long = 0L,
    )

    private val leases = ConcurrentHashMap<String, Lease>()
    private val _dupSuppressed = AtomicLong(0L)

    val duplicateCloseAttemptsSuppressed: Long get() = _dupSuppressed.get()
    // V5.0.3685 — P0: raw leases.size counted expired entries, inflating the
    // pendingSellQueue signal fed to SellOnlySafeMode → SELL_ONLY_SAFE_MODE stayed
    // active even after all sells landed. Count only TTL-live leases.
    fun activeLeaseCount(): Int = leases.entries.count { (_, l) ->
        System.currentTimeMillis() - l.acquiredMs < LEASE_TTL_MS
    }

    // V5.0.3915 — operator dump 06-19 19:28 root cause.
    // The bot stopped LIVE BUYs completely (0/482) while LIVE SELLs kept working
    // (81/81), because ADMISSION_GATE:SELL_ONLY_SAFE_MODE = 337 — SellOnlySafeMode
    // was permanently armed off the back of a phantom pendingSellQueue signal.
    // Reason: the previous version of activeBlockingLeaseCount() counted any
    // lease as "blocking" when EITHER inFlight=true OR now >= nextEligibleMs.
    // Once a sell route failed without signature, the lease was recorded with
    // inFlight=false and nextEligibleMs=now+backoff. The instant that backoff
    // elapsed (seconds, not minutes) the lease started satisfying the
    // (now >= nextEligibleMs) branch forever, until the 10-min TTL pruned it.
    // With zero open positions and zero host wallet tokens, this is residue,
    // not pressure — but SellOnlySafeMode read it as live pending sell pressure
    // and hard-blocked every buy.
    //
    // Correct semantics: a lease is "actively blocking" admission ONLY while a
    // close route is genuinely in-flight (inFlight=true). A non-in-flight lease
    // past its backoff is a retryable residue — the next exit/reconciler tick
    // either picks it up (after which inFlight flips true again and it
    // legitimately blocks) or it ages out via TTL. Either way it must not
    // gate live buys in the meantime.
    fun activeBlockingLeaseCount(): Int {
        reapResidue()
        val now = System.currentTimeMillis()
        return leases.entries.count { (_, l) ->
            (now - l.acquiredMs < LEASE_TTL_MS) && l.inFlight
        }
    }

    // V5.0.3915 — proactive residue reaper. Any lease that has been
    // !inFlight for longer than RESIDUE_REAP_MS gets pruned even before TTL.
    // The 10-min TTL is correct for in-flight protection, but for a long-idle
    // retryable record it's pure latency on the SellOnlySafeMode signal. Called
    // by activeBlockingLeaseCount() on every read so the reap happens lazily
    // wherever the signal is consumed (RuntimeStateSnapshot, doctor, admission).
    private const val RESIDUE_REAP_MS: Long = 60_000L
    @Synchronized
    private fun reapResidue() {
        val now = System.currentTimeMillis()
        val it = leases.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val l = e.value
            val idleMs = now - l.acquiredMs
            val pastBackoff = now >= l.nextEligibleMs
            if (!l.inFlight && !l.finalityPending && pastBackoff && idleMs >= RESIDUE_REAP_MS) {
                it.remove()
                try {
                    ForensicLogger.lifecycle(
                        "SELL_LEASE_RESIDUE_REAPED",
                        "mint=${e.key.take(10)} idleMs=$idleMs attempts=${l.closeAttemptCount} " +
                                "reason=non_in_flight_past_backoff (frees SellOnlySafeMode signal)",
                    )
                } catch (_: Throwable) {}
            }
        }
    }
    fun isLeased(mint: String): Boolean = current(mint) != null

    /** V5.0.7948 — true while a doSell attempt holds this mint's lease (an attempt is running). */
    fun sellInFlight7948(mint: String): Boolean = current(mint)?.inFlight == true

    @Synchronized
    private fun current(mint: String): Lease? {
        if (mint.isBlank()) return null
        val l = leases[mint] ?: return null
        if (System.currentTimeMillis() - l.acquiredMs >= LEASE_TTL_MS) {
            leases.remove(mint, l)
            l.finalityPending = false
            try {
                ForensicLogger.lifecycle("SELL_LEASE_STALE_CLEARED",
                    "mint=${mint.take(10)} ageMs=${System.currentTimeMillis() - l.acquiredMs} attempts=${l.closeAttemptCount}")
            } catch (_: Throwable) {}
            return null
        }
        return l
    }

    @Synchronized
    fun acquire(mint: String, symbol: String, rawReason: String): Lease? {
        if (mint.isBlank()) return null
        // V5.0.3746 — operator spec items 1, 4, 7: WAITING_BALANCE_PROOF must never
        // hold a close lease, and a NEW close lease must NEVER be acquired while
        // the mint is in proof-wait. The poller is the only worker permitted to
        // act until proof arrives or zero is confirmed (ZERO_BALANCE_CONFIRMED).
        if (BalanceProofWaitState.isWaiting(mint)) {
            // Idempotent merge: raise the exit-reason priority on the wait, but
            // do not spawn a second worker / lease / suppressed-duplicate metric.
            BalanceProofWaitState.markWaiting(mint, symbol, rawReason)
            queueCause8087(mint, rawReason, "PROOF_WAIT")
            try {
                ForensicLogger.lifecycle("SELL_LEASE_DEFERRED_PROOF_WAIT",
                    "mint=${mint.take(10)} symbol=$symbol reason=$rawReason " +
                    "action=no_lease_no_duplicate_suppressed (poller owns this mint)")
            } catch (_: Throwable) {}
            return null
        }
        val existing = current(mint)
        val now = System.currentTimeMillis()
        if (existing != null) {
            // V5.0.7807 — B1: an emergency never waits out a backoff scheduled for a
            // softer exit (up to 60s before 7807). It is gated only by its own
            // 2/3/5/8/10s cadence from the last attempt; an in-flight attempt is
            // still never doubled (Field Manual L248).
            // V5.0.7965 — a profit capture on a spike is as time-critical as a stop: 5.0.7964 live,
            // Frank at +532% (peak +691%) had its spike-tier and runner-lock sells suppressed behind a
            // failed hard-floor lease's backoff, and the tier re-arms ran out. Never doubled in flight.
            val emergency7807 = ProtectiveExitClass7807.isEmergency(rawReason) || urgentProfitCapture7965(rawReason)
            val emergencyEligible7807 = emergency7807 && !existing.inFlight &&
                (now - existing.lastTouchMs >= ProtectiveExitClass7807.emergencyRetryDelayMs(existing.closeAttemptCount - 1) ||
                    StopFast8288.preRouteEligible8288(existing.preRouteEligibleAtMs8288, now))  // V5.0.8288 — a pre-route wait re-enters on its short clock
            if (emergency7807) {
                val prior7807 = existing.emergencyReason7807 ?: existing.originalExitReason
                existing.emergencyReason7807 = ProtectiveExitClass7807.effectiveReason(prior7807, canonicalReason(rawReason))
            }
            if (existing.inFlight || (now < existing.nextEligibleMs && !emergencyEligible7807)) {
                _dupSuppressed.incrementAndGet()
                if (emergency7807 && existing.inFlight) {
                    // Coalesce: one owner per mint; the stronger reason rides the existing lease and is
                    // dispatched the instant the running attempt ends (or stands down before signing).
                    existing.emergencyReason7807?.let { existing.emergencyWaiting8087 = it }
                    if (existing.emergencyWaitingSinceMs8087 == 0L) existing.emergencyWaitingSinceMs8087 = now
                }
                queueCause8087(mint, rawReason, if (existing.inFlight) "LEASE_IN_FLIGHT" else "BACKOFF")
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
            if (emergency7807) ProtectiveExitClass7807.label("EMERGENCY_EXIT_RETRY_7807")
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

    @Synchronized
    fun recordRetry(mint: String, failureCode: String): Int {
        val l = current(mint) ?: return 0
        l.closeAttemptCount += 1
        l.lastCloseFailureCode = failureCode
        if (failureCode.contains("SELL_FINALITY_PENDING_RETRY", ignoreCase = true)) l.finalityPending = true
        val now = System.currentTimeMillis()
        l.lastTouchMs = now
        l.inFlight = false
        // V5.0.8288 — a real route attempt keeps the 7807 cadence; a pre-route short clock still standing is not a backoff.
        val preRouteClock8299 = l.preRouteEligibleAtMs8288 > 0L && l.nextEligibleMs == l.preRouteEligibleAtMs8288
        l.preRouteEligibleAtMs8288 = 0L
        // If the route did not set provider-specific backoff, still prevent an
        // immediate same-tick retry storm while allowing the next cycle through.
        if (preRouteClock8299 || l.nextEligibleMs <= now) l.nextEligibleMs = now + 2_000L
        // V5.0.8087 — an emergency queued behind this attempt does not inherit its backoff.
        val waiting8094 = l.emergencyWaiting8087
        waiting8094?.let { r -> l.nextEligibleMs = now; handoff8087(mint, r, l) }
        // V5.0.8094 — an emergency lease re-dispatches ITSELF when its backoff ends. 8093: nothing re-asked after a
        // failed emergency attempt until a tick / the risk clock came round (redispatchGap avg 24.6 s, trigger ->
        // broadcast max 43 s). acquire() still single-flights the sell; an unresolved signed sell still blocks a resend.
        val leaseReason8094 = l.emergencyReason7807 ?: l.originalExitReason
        if (waiting8094 == null && ProtectiveExitClass7807.isEmergency(leaseReason8094)) scheduleEmergencyRetry8094(mint, leaseReason8094, l.nextEligibleMs)
        try {
            ForensicLogger.lifecycle("SELL_RETRY_SCHEDULED",
                "mint=${mint.take(10)} attempt=${l.closeAttemptCount} reason=$failureCode " +
                "nextEligibleMs=${l.nextEligibleMs} originalExitReason=${l.originalExitReason}")
        } catch (_: Throwable) {}
        return l.closeAttemptCount
    }

    /**
     * V5.0.8288 — the attempt ended before any balance / quote stage ran and signed nothing (a running slice held the
     * sell lock, a stale in-flight marker): not a route attempt — no attempt count (no slippage / route escalation), and
     * the emergency re-dispatches itself on StopFast8288's short clock instead of 2 / 3 / 5 / 8 s. Single-flight is
     * unchanged: acquire() still refuses while an attempt is in flight.
     */
    @Synchronized
    fun recordPreRouteWait8288(mint: String): Long {
        val l = current(mint) ?: return 0L
        val now = System.currentTimeMillis()
        l.preRouteWaits8288 += 1
        val delay = StopFast8288.preRouteDelayMs8288(l.preRouteWaits8288)
        l.inFlight = false
        l.lastTouchMs = now
        l.nextEligibleMs = now + delay
        l.preRouteEligibleAtMs8288 = now + delay
        val waiting = l.emergencyWaiting8087
        if (waiting != null) {
            l.nextEligibleMs = now
            l.preRouteEligibleAtMs8288 = now
            handoff8087(mint, waiting, l)
        } else {
            val reason = l.emergencyReason7807 ?: l.originalExitReason
            if (ProtectiveExitClass7807.isEmergency(reason)) scheduleEmergencyRetry8094(mint, reason, l.nextEligibleMs)
        }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SELL_PRE_ROUTE_WAIT_8288")
            ForensicLogger.lifecycle("SELL_PRE_ROUTE_WAIT_8288",
                "mint=${mint.take(10)} waits=${l.preRouteWaits8288} delayMs=$delay attempts=${l.closeAttemptCount} originalExitReason=${l.originalExitReason} " +
                    "cause8435=${SellRouteSpine8144.lastCause8435(mint)}")
        } catch (_: Throwable) {}
        return delay
    }

    /**
     * V5.9.1533 — per-mint exponential backoff keyed by error class (spec item 3 + 7).
     * Jupiter 503/429, Pump 0x1787, etc. each get their own escalating cooldown so a
     * release never re-enters the same dead route on the next tick. Caps at 60s.
     */
    @Synchronized
    fun scheduleBackoff(mint: String, errorClass: String): Long {
        val l = current(mint) ?: return 0L
        l.lastErrorClass = errorClass
        val attempt = l.closeAttemptCount.coerceAtLeast(1)
        // 2s, 4s, 8s, 15s cap (V5.0.7807) — provider/error aware.
        val base = when {
            errorClass.contains("503") || errorClass.contains("429") || errorClass.contains("BACKOFF") -> 4_000L
            errorClass.contains("0x1787") || errorClass.contains("PUMP_ROUTE_INVALID") -> 6_000L
            errorClass.contains("JITO") -> 1_500L
            else -> 2_000L
        }
        // V5.0.7807 — B1: an emergency lease retries on 2/3/5/8/10s; every other
        // backoff is capped at 15s (was 60s).
        val leaseReason7807 = l.emergencyReason7807 ?: l.originalExitReason
        val delay = ProtectiveExitClass7807.retryDelayMs(
            leaseReason7807, attempt,
            (base * (1L shl (attempt - 1).coerceIn(0, 5))).coerceAtMost(60_000L),
        )
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
    @Synchronized
    fun raiseIntent(mint: String, reason: String, priority: Int): Boolean {
        val l = current(mint) ?: return false
        if (ProtectiveExitClass7807.isEmergency(reason)) {
            l.emergencyReason7807 = ProtectiveExitClass7807.effectiveReason(
                l.emergencyReason7807 ?: l.originalExitReason, canonicalReason(reason))
        }
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

    @Synchronized
    fun release(mint: String, terminal: String) {
        val l = leases.remove(mint) ?: return
        l.emergencyWaiting8087?.let { r -> handoff8087(mint, r, l) }
        try {
            ForensicLogger.lifecycle("SELL_LEASE_RELEASED",
                "mint=${mint.take(10)} terminal=$terminal attempts=${l.closeAttemptCount} " +
                "originalExitReason=${l.originalExitReason}")
        } catch (_: Throwable) {}
    }

    fun attemptCount(mint: String): Int = current(mint)?.closeAttemptCount ?: 0

    // ── V5.0.8087 — catastrophic exits do not queue behind a running attempt ──
    //
    // 5.0.8086 live: a catastrophic exit spent 58 s between trigger and its first worker. The lease was held
    // by an attempt already running for the mint; the emergency was suppressed as a duplicate and waited for
    // the protective redispatch to come round again. Now it is coalesced onto the lease, the running attempt
    // stands down between tiers if it has not signed anything, and the emergency is handed to a worker the
    // moment the attempt ends. A signed-but-unresolved sell is never doubled: its own confirmation and
    // same-bytes rebroadcast continue, and the hand-off answers from that outcome.

    /** Set by BotService: dispatch [reason] for [mint] on the emergency pool. */
    @Volatile var emergencyHandoff8087: ((mint: String, reason: String) -> Unit)? = null

    private val selfRetryAt8094 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val retryTimer8094 by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "emergency-retry-8094").apply { isDaemon = true } }
    }

    /** Pure (V5.0.8094): the self-retry delay for an emergency whose lease is eligible again at [eligibleAtMs]. */
    internal fun selfRetryDelayMs8094(eligibleAtMs: Long, nowMs: Long): Long = (eligibleAtMs - nowMs + 50L).coerceIn(250L, 15_000L)

    private fun scheduleEmergencyRetry8094(mint: String, reason: String, eligibleAtMs: Long) {
        if (emergencyHandoff8087 == null) return
        val now = System.currentTimeMillis()
        val pending = selfRetryAt8094[mint]
        if (pending != null && pending >= now) return            // one self-retry per mint at a time
        val delay = selfRetryDelayMs8094(eligibleAtMs, now)
        selfRetryAt8094[mint] = now + delay
        try {
            retryTimer8094.schedule({
                selfRetryAt8094.remove(mint)
                if (current(mint) != null) {
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_EMERGENCY_SELF_RETRY_8094") } catch (_: Throwable) {}
                    try { emergencyHandoff8087?.invoke(mint, reason) } catch (_: Throwable) {}
                }
            }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { selfRetryAt8094.remove(mint) }
    }

    private fun handoff8087(mint: String, reason: String, l: Lease) {
        val waitedMs = if (l.emergencyWaitingSinceMs8087 > 0L) System.currentTimeMillis() - l.emergencyWaitingSinceMs8087 else 0L
        l.emergencyWaiting8087 = null
        l.emergencyWaitingSinceMs8087 = 0L
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_EMERGENCY_HANDOFF_8087")
            ForensicLogger.lifecycle("EXIT_EMERGENCY_HANDOFF_8087", "mint=${mint.take(10)} reason=${reason.take(60)} waitedMs=$waitedMs")
        } catch (_: Throwable) {}
        val h = emergencyHandoff8087 ?: run {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_EMERGENCY_HANDOFF_NO_DISPATCHER_8087") } catch (_: Throwable) {}
            return
        }
        try { h(mint, reason) } catch (_: Throwable) {}
    }

    /**
     * True when a running, non-emergency attempt should stand down: an emergency is waiting on its lease and
     * no signed sell for the mint is unresolved (nothing on the wire can be doubled by the hand-off).
     */
    fun preemptForEmergency8087(mint: String, runningReason: String): Boolean {
        val l = current(mint) ?: return false
        if (l.emergencyWaiting8087 == null) return false
        if (ProtectiveExitClass7807.isEmergency(runningReason)) return false
        if (try { com.lifecyclebot.network.SignedTxLedger8085.unresolvedFor(mint, "SELL") != null } catch (_: Throwable) { true }) return false
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_ATTEMPT_PREEMPTED_BY_EMERGENCY_8087") } catch (_: Throwable) {}
        return true
    }

    private fun queueCause8087(mint: String, reason: String, cause: String) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("EXIT_QUEUE_CAUSE_${cause}_8087")
            com.lifecyclebot.engine.truth.ExitStageTiming7876.noteQueueCause8087(mint, cause)
        } catch (_: Throwable) {}
    }

    fun canonicalReason(raw: String): String =
        raw.replace(Regex("^(PENDING_RETRY_\\d+:\\s*)+"), "").trim().ifBlank { "exit" }

    /** V5.0.7965 — pure: a sell into a spike / a runner lock that must not wait out a backoff. */
    fun urgentProfitCapture7965(reason: String?): Boolean {
        val r = reason.orEmpty().uppercase()
        return r.startsWith("SPIKE_CAPTURE_7943") || r.contains("RAPID_DRAWDOWN_FROM_PEAK") || r.contains("MONSTER_LOCK") ||
            r.startsWith("CHART_CAPTURE_TOP_7950") || r.contains("PEAK_CAPTURE") ||
            // V5.0.8048 — a profit lock is protecting a gain on a falling print: it lands at the fast-land tip and cadence.
            r.startsWith("TICK_PROFIT_LOCK") || r.startsWith("LIFECYCLE_PROFIT_")
    }
}

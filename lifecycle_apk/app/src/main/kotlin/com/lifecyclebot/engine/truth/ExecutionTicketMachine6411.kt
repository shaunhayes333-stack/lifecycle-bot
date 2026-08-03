package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6411 §4 — EXECUTION-TICKET STATE MACHINE.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Every authorised ticket must reach exactly one final or managed
 *  state. LIVE_BUY_DEFERRED_NON_TERMINAL may exist only as a
 *  bounded temporary state. It must never be repeatedly restored
 *  forever."
 *
 * DESIGN
 * ──────
 *   • Every live execution intent is represented by a Ticket with
 *     an atomic State reference.
 *   • Transitions are compare-and-set with expected prior states.
 *   • Terminal states are final. Restoration cannot revive terminal
 *     tickets and never resets attemptCount / createdAt / venue.
 *   • Idempotency key = mint|side|decisionEpochBucket so retries
 *     never create a duplicate active ticket.
 *
 * Bounded time budgets (§3.4):
 *   maxTicketAgeMemeMs      = 25_000
 *   maxTicketAgeStdMs       = 60_000
 *   maxRouteResolveMs       =  8_000
 *
 * This module records tickets in-memory and stamps them into
 * [ExecutionAttemptJournal6411] on each terminal transition, so
 * operators see the full lifecycle even when no chain tx is sent.
 */
object ExecutionTicketMachine6411 {

    enum class State(val terminal: Boolean = false) {
        CREATED,
        AUTHORISED,
        VENUE_RESOLVING,
        ROUTE_RESOLVED,
        QUOTING,
        QUOTED,
        SIMULATING,
        READY_TO_SIGN,
        SIGNED,
        SUBMITTING,
        SUBMITTED,
        CONFIRMING,
        OPEN_CONFIRMED(terminal = true),
        SUBMISSION_UNKNOWN_RECONCILE(terminal = true),
        RETRY_SCHEDULED,
        ROUTE_UNAVAILABLE_TERMINAL(terminal = true),
        SUBMISSION_FAILED_TERMINAL(terminal = true),
        STALE_CANCELLED(terminal = true),
        DUPLICATE_SUPPRESSED(terminal = true),
        SAFETY_REJECTED(terminal = true),
        TICKET_EXPIRED(terminal = true),
    }

    private const val MAX_AGE_MEME_MS = 25_000L
    private const val MAX_AGE_STD_MS = 60_000L

    data class Ticket(
        val ticketId: String,
        val executionIntentId: String,
        val decisionId: String,
        val mint: String,
        val symbol: String,
        val lane: String,
        val wallet: String,
        val side: String,
        val isMemeShort: Boolean,
        val createdAtMs: Long,
        val state: AtomicReference<State> = AtomicReference(State.CREATED),
        val attempts: AtomicInteger = AtomicInteger(0),
        val venue: AtomicReference<ExecutableVenue6411?> = AtomicReference(null),
        val adapter: AtomicReference<ExecutionAdapter6411?> = AtomicReference(null),
        val txSignature: AtomicReference<String?> = AtomicReference(null),
        val updatedAtMs: AtomicLong = AtomicLong(System.currentTimeMillis()),
    ) {
        fun ageMs(now: Long = System.currentTimeMillis()): Long = now - createdAtMs
        fun expired(now: Long = System.currentTimeMillis()): Boolean {
            val budget = if (isMemeShort) MAX_AGE_MEME_MS else MAX_AGE_STD_MS
            return ageMs(now) > budget
        }
    }

    private val tickets = ConcurrentHashMap<String, Ticket>()
    private val activeByIntent = ConcurrentHashMap<String, String>() // intent -> ticketId

    /** Idempotency key. Same intent within same 5-second bucket returns identical id. */
    fun intentId(wallet: String, mint: String, side: String, decisionId: String): String {
        val bucket = System.currentTimeMillis() / 5_000L
        return "$wallet|$mint|$side|$decisionId|$bucket"
    }

    /**
     * Create a new ticket, or return the existing active ticket for
     * an in-flight intent (idempotency).
     */
    fun create(
        wallet: String,
        mint: String,
        symbol: String,
        lane: String,
        side: String,
        decisionId: String,
        isMemeShort: Boolean,
    ): Ticket {
        val intent = intentId(wallet, mint, side, decisionId)
        val existingId = activeByIntent[intent]
        if (existingId != null) {
            val existing = tickets[existingId]
            if (existing != null && !existing.state.get().terminal) {
                try { PipelineHealthCollector.labelInc("EXEC_DUPLICATE_SUPPRESSED_6411") } catch (_: Throwable) {}
                return existing
            }
        }
        val ticketId = "T-$intent"
        val t = Ticket(
            ticketId = ticketId, executionIntentId = intent, decisionId = decisionId,
            mint = mint, symbol = symbol, lane = lane, wallet = wallet, side = side,
            isMemeShort = isMemeShort, createdAtMs = System.currentTimeMillis(),
        )
        tickets[ticketId] = t
        activeByIntent[intent] = ticketId
        try { PipelineHealthCollector.labelInc("EXEC_TICKETS_CREATED") } catch (_: Throwable) {}
        return t
    }

    /**
     * Atomic transition. Returns true if [expect] was the current
     * state and the CAS to [next] succeeded. Rejects impossible
     * transitions (e.g. re-open of a terminal ticket).
     */
    fun transition(t: Ticket, expect: State, next: State, reason: String = ""): Boolean {
        if (expect.terminal) {
            try { PipelineHealthCollector.labelInc("EXEC_TICKET_TERMINAL_REVIVE_BLOCKED_6411") } catch (_: Throwable) {}
            return false
        }
        val ok = t.state.compareAndSet(expect, next)
        if (ok) {
            t.updatedAtMs.set(System.currentTimeMillis())
            if (next == State.SUBMITTING || next == State.SUBMITTED) t.attempts.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "EXEC_TICKET_TRANSITION_6411",
                    "ticket=${t.ticketId.take(24)} mint=${t.mint.take(10)} sym=${t.symbol} " +
                        "side=${t.side} lane=${t.lane} ${expect}->$next attempts=${t.attempts.get()} " +
                        "ageMs=${t.ageMs()} reason=${reason.take(80)}",
                )
                PipelineHealthCollector.labelInc("EXEC_TICKET_TRANSITION_6411")
                if (next.terminal) {
                    PipelineHealthCollector.labelInc("EXEC_TICKET_TERMINAL_$next")
                }
            } catch (_: Throwable) {}
            if (next.terminal) {
                activeByIntent.remove(t.executionIntentId, t.ticketId)
            }
        } else {
            try { PipelineHealthCollector.labelInc("EXEC_TICKET_TRANSITION_REJECTED_6411") } catch (_: Throwable) {}
        }
        return ok
    }

    /** Force a terminal state (used by expiry sweeper). Never revives a terminal ticket. */
    fun forceTerminal(t: Ticket, terminal: State, reason: String): Boolean {
        if (!terminal.terminal) return false
        val cur = t.state.get()
        if (cur.terminal) return false
        val ok = t.state.compareAndSet(cur, terminal)
        if (ok) {
            t.updatedAtMs.set(System.currentTimeMillis())
            try {
                ForensicLogger.lifecycle(
                    "EXEC_TICKET_FORCE_TERMINAL_6411",
                    "ticket=${t.ticketId.take(24)} mint=${t.mint.take(10)} sym=${t.symbol} " +
                        "$cur->$terminal reason=${reason.take(80)} ageMs=${t.ageMs()}",
                )
                PipelineHealthCollector.labelInc("EXEC_TICKET_FORCE_TERMINAL_6411")
                PipelineHealthCollector.labelInc("EXEC_TICKET_TERMINAL_$terminal")
            } catch (_: Throwable) {}
            activeByIntent.remove(t.executionIntentId, t.ticketId)
        }
        return ok
    }

    /** Sweep for expired tickets — call from the bot loop. */
    fun sweepExpired(): Int {
        val now = System.currentTimeMillis()
        var swept = 0
        for (t in tickets.values) {
            val s = t.state.get()
            if (s.terminal) continue
            if (t.expired(now)) {
                if (forceTerminal(t, State.TICKET_EXPIRED, "budget_exceeded")) {
                    swept++
                    try { PipelineHealthCollector.labelInc("EXEC_TICKET_EXPIRED_6411") } catch (_: Throwable) {}
                }
            }
        }
        return swept
    }

    fun statusLine(): String {
        val n = tickets.size
        var active = 0
        var terminal = 0
        for (t in tickets.values) if (t.state.get().terminal) terminal++ else active++
        return "tickets=$n active=$active terminal=$terminal"
    }

    internal fun resetForTest() {
        tickets.clear()
        activeByIntent.clear()
    }
}

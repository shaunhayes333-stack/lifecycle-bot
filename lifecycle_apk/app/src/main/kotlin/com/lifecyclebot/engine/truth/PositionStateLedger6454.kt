package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6454 §P0 — TERMINAL SELL CAS AT SIDE-EFFECT DOOR.
 *
 * OPERATOR MANDATE:
 *   "Wire PositionStateLedger.reserveTerminalSell() BEFORE every
 *    paper/live terminal SELL. PositionId mandatory; remove blank-ID
 *    fail-open. Do NOT use mint as PositionId.
 *
 *    Only: OPEN/PARTIAL -> CLOSING via CAS
 *          CLOSING -> CLOSED via CAS after settlement.
 *
 *    confirmTerminalSell must:
 *      - CAS CLOSING -> CLOSED
 *      - return false if already CLOSED
 *      - NEVER increment terminal count twice
 *      - NEVER journal/account/reward a rejected duplicate
 *
 *    DsXR94/2cxRDE/2JLR9u repeated SELL pattern must become impossible."
 *
 * DESIGN
 * ──────
 * Compact state machine keyed by positionId. reserveTerminalSell() does
 * CAS OPEN/PARTIAL -> CLOSING. confirmTerminalSell() does CAS CLOSING ->
 * CLOSED. Any other transition is REJECTED and returns false. Blank
 * positionId is refused unconditionally.
 */
object PositionStateLedger6454 {

    enum class Lifecycle { UNKNOWN, OPEN, PARTIAL, CLOSING, CLOSED }

    enum class ReserveResult { RESERVED, REJECTED_BLANK_ID, REJECTED_ALREADY_CLOSING, REJECTED_ALREADY_CLOSED, REJECTED_UNKNOWN }
    enum class ConfirmResult { CONFIRMED, REJECTED_NOT_CLOSING, REJECTED_ALREADY_CLOSED, REJECTED_BLANK_ID }

    private val states = ConcurrentHashMap<String, Lifecycle>()
    private val terminalCount = ConcurrentHashMap<String, AtomicLong>() // positionId -> increments; expected == 1
    private val reservations = AtomicLong(0L)
    private val reservationRejects = AtomicLong(0L)
    private val confirms = AtomicLong(0L)
    private val confirmRejects = AtomicLong(0L)
    private val blankIdRejects = AtomicLong(0L)

    /** V5.0.6519 — projection rebuild from CanonicalPositionAuthority only. */
    fun syncFromCanonical6519(openPositions: List<CanonicalPositionAuthority6441.Position>) {
        states.clear()
        openPositions.forEach { p ->
            if (p.positionId.isNotBlank() && p.remainingQtyRaw > java.math.BigInteger.ZERO) {
                states[p.positionId] = when (p.lifecycle) {
                    CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED -> Lifecycle.PARTIAL
                    else -> Lifecycle.OPEN
                }
            }
        }
        try { PipelineHealthCollector.labelInc("POSITION_STATE_PROJECTED_FROM_CANONICAL_6519") } catch (_: Throwable) {}
    }

    fun openOrPartialCount6519(): Int = states.values.count { it == Lifecycle.OPEN || it == Lifecycle.PARTIAL }

    /** Called at position creation to seed lifecycle=OPEN. */
    fun onEntry(positionId: String) {
        if (positionId.isBlank()) return
        states.putIfAbsent(positionId, Lifecycle.OPEN)
        // V5.0.6617 §POSITION_LIFECYCLE_FORMALIZATION — mirror DISCOVERED
        //   into the formalized lifecycle. The formalization module
        //   backfills mint/symbol/lane later via markDiscovered when the
        //   trader calls it explicitly; this call only stamps the
        //   discoveredAtMs so the sequence is preserved.
        try {
            PositionLifecycleFormalization6617.markDiscovered(positionId, mint = "", symbol = "", lane = "")
        } catch (_: Throwable) {}
    }

    /** Called on any partial sell that leaves >0 remaining. */
    fun onPartial(positionId: String) {
        if (positionId.isBlank()) return
        val prior = states[positionId]
        if (prior == Lifecycle.OPEN || prior == Lifecycle.PARTIAL) {
            states[positionId] = Lifecycle.PARTIAL
        }
    }

    /**
     * CAS OPEN/PARTIAL -> CLOSING. Must be called BEFORE any side effect
     * of a terminal sell (cash mutation, journal write, reward). Returns
     * REJECTED_* if the position cannot legitimately transition.
     */
    fun reserveTerminalSell(positionId: String, reason: String): ReserveResult {
        if (positionId.isBlank()) {
            blankIdRejects.incrementAndGet()
            reservationRejects.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_BLANK_POSITION_ID_6454",
                    "reason=${reason.take(40)}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_SELL_BLANK_POSITION_ID_6454")
            } catch (_: Throwable) {}
            return ReserveResult.REJECTED_BLANK_ID
        }
        val prior = states.putIfAbsent(positionId, Lifecycle.CLOSING)
        if (prior == null) {
            // Never seen this positionId — treat as UNKNOWN (fail-closed).
            states.remove(positionId, Lifecycle.CLOSING)
            reservationRejects.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_UNKNOWN_POSITION_6454",
                    "positionId=${positionId.take(12)} reason=${reason.take(40)}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_SELL_UNKNOWN_POSITION_6454")
            } catch (_: Throwable) {}
            return ReserveResult.REJECTED_UNKNOWN
        }
        return when (prior) {
            Lifecycle.OPEN, Lifecycle.PARTIAL -> {
                if (states.replace(positionId, prior, Lifecycle.CLOSING)) {
                    reservations.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("TERMINAL_SELL_RESERVED_6454") } catch (_: Throwable) {}
                    ReserveResult.RESERVED
                } else {
                    // Someone else beat us — inspect current state.
                    reservationRejects.incrementAndGet()
                    when (states[positionId]) {
                        Lifecycle.CLOSING -> ReserveResult.REJECTED_ALREADY_CLOSING
                        Lifecycle.CLOSED -> ReserveResult.REJECTED_ALREADY_CLOSED
                        else -> ReserveResult.REJECTED_ALREADY_CLOSING
                    }
                }
            }
            Lifecycle.CLOSING -> {
                reservationRejects.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "TERMINAL_SELL_DUPLICATE_CLOSING_REJECTED_6454",
                        "positionId=${positionId.take(12)} reason=${reason.take(40)}",
                    )
                    PipelineHealthCollector.labelInc("TERMINAL_SELL_DUPLICATE_CLOSING_REJECTED_6454")
                    // V5.0.6578 §P1-4 — duplicate close loop invariant.
                    // Operator directive: "duplicate close loops = 0". Every
                    // duplicate attempt is now recorded under the canonical
                    // DUPLICATE_TERMINAL_MUTATION_6578 counter so a runaway
                    // retry pattern is visible without inspecting per-reason
                    // labels.
                    PipelineHealthCollector.labelInc("DUPLICATE_TERMINAL_MUTATION_6578")
                } catch (_: Throwable) {}
                ReserveResult.REJECTED_ALREADY_CLOSING
            }
            Lifecycle.CLOSED -> {
                reservationRejects.incrementAndGet()
                try {
                    ForensicLogger.lifecycle(
                        "TERMINAL_SELL_DUPLICATE_CLOSED_REJECTED_6454",
                        "positionId=${positionId.take(12)} reason=${reason.take(40)}",
                    )
                    PipelineHealthCollector.labelInc("TERMINAL_SELL_DUPLICATE_CLOSED_REJECTED_6454")
                    // V5.0.6578 §P1-4 — same invariant.
                    PipelineHealthCollector.labelInc("DUPLICATE_TERMINAL_MUTATION_6578")
                } catch (_: Throwable) {}
                ReserveResult.REJECTED_ALREADY_CLOSED
            }
            Lifecycle.UNKNOWN -> ReserveResult.REJECTED_UNKNOWN
        }
    }

    /**
     * CAS CLOSING -> CLOSED. Must be called AFTER settlement side effects
     * (journal + accounting + reward publish) succeed. Returns false and
     * refuses to mutate if not in CLOSING (never double-counts terminal).
     */
    fun confirmTerminalSell(positionId: String): ConfirmResult {
        if (positionId.isBlank()) {
            blankIdRejects.incrementAndGet()
            confirmRejects.incrementAndGet()
            return ConfirmResult.REJECTED_BLANK_ID
        }
        val cur = states[positionId]
        if (cur == Lifecycle.CLOSED) {
            confirmRejects.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TERMINAL_SELL_CONFIRM_ALREADY_CLOSED_6454") } catch (_: Throwable) {}
            return ConfirmResult.REJECTED_ALREADY_CLOSED
        }
        if (cur != Lifecycle.CLOSING) {
            confirmRejects.incrementAndGet()
            try { PipelineHealthCollector.labelInc("TERMINAL_SELL_CONFIRM_NOT_CLOSING_6454") } catch (_: Throwable) {}
            return ConfirmResult.REJECTED_NOT_CLOSING
        }
        if (!states.replace(positionId, Lifecycle.CLOSING, Lifecycle.CLOSED)) {
            confirmRejects.incrementAndGet()
            return ConfirmResult.REJECTED_ALREADY_CLOSED
        }
        // Count exactly once.
        terminalCount.getOrPut(positionId) { AtomicLong(0L) }.incrementAndGet()
        confirms.incrementAndGet()
        try { PipelineHealthCollector.labelInc("TERMINAL_SELL_CONFIRMED_6454") } catch (_: Throwable) {}
        // V5.0.6617 §POSITION_LIFECYCLE_FORMALIZATION — mirror the
        //   confirmed terminal into the formalized lifecycle so the
        //   closureDelta reconciler sees CLOSED at the same causal
        //   moment PSL6454 does. Idempotent — markClosed no-ops on
        //   duplicate calls per positionId.
        try { PositionLifecycleFormalization6617.markClosed(positionId) } catch (_: Throwable) {}
        return ConfirmResult.CONFIRMED
    }

    fun lifecycle(positionId: String): Lifecycle = states[positionId] ?: Lifecycle.UNKNOWN

    /**
     * Revert CLOSING -> OPEN when a reserved terminal SELL fails to
     * settle (e.g. live route error, provider outage). Callers MUST use
     * this on any FAILED_* / WAITING_* return path from a sell function
     * that had a successful reserveTerminalSell. Idempotent — no-op if
     * lifecycle is not CLOSING (in particular, does NOT undo a
     * confirmed CLOSED position).
     */
    fun abandonTerminalSell(positionId: String, reason: String): Boolean {
        if (positionId.isBlank()) return false
        val ok = states.replace(positionId, Lifecycle.CLOSING, Lifecycle.OPEN)
        if (ok) {
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_ABANDONED_6454",
                    "positionId=${positionId.take(12)} reason=${reason.take(40)}",
                )
                PipelineHealthCollector.labelInc("TERMINAL_SELL_ABANDONED_6454")
            } catch (_: Throwable) {}
        }
        return ok
    }

    fun terminalCount(positionId: String): Long = terminalCount[positionId]?.get() ?: 0L

    fun statusLine(): String = "positions=${states.size} reserved=${reservations.get()}/rej=${reservationRejects.get()} " +
        "confirmed=${confirms.get()}/rej=${confirmRejects.get()} blankIdRejects=${blankIdRejects.get()}"

    // Test-only helper.
    internal fun resetForTest() {
        states.clear(); terminalCount.clear()
        reservations.set(0); reservationRejects.set(0)
        confirms.set(0); confirmRejects.set(0); blankIdRejects.set(0)
    }
}

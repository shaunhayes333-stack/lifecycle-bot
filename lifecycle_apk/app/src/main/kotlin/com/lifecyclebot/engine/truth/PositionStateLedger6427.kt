package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6427 §H — POSITION STATE MACHINE + TERMINAL-SELL IDEMPOTENCY.
 *
 * OPERATOR (V5.0.6424 spec):
 * "The report contains indisputable duplicate terminal exits.
 *  AENK1Y receives repeated terminal SELLs at 16:58 / 17:03 / 17:05 / 17:20.
 *  Hn6Kdx receives repeated terminal SELLs at 16:30 / 16:51 / 17:39.
 *  Rows report rem=0 and then sell the same position again.
 *  THIS MUST BECOME IMPOSSIBLE BY CONSTRUCTION."
 *
 * DESIGN
 * ──────
 * Atomic per-positionId state machine + consumed-idempotency-key set.
 * The finalizeIfEligible(...) helper is CAS: only the FIRST call for a
 * given positionId can transition OPEN/PARTIALLY_CLOSED -> CLOSING and
 * only the FIRST call for a given (positionId, executionGeneration,
 * operationType) key can execute. All subsequent calls return
 * ALREADY_CLOSED / DUPLICATE_INTENT with the reason recorded.
 *
 * Wired at the paper/live sell boundary in Executor.kt as a GUARD that
 * returns a Decision, not as a full state replacement. This lets us
 * ship the invariant without refactoring the whole executor.
 */
object PositionStateLedger6427 {

    enum class State { OPEN, PARTIALLY_CLOSED, CLOSE_REQUESTED, CLOSING, CLOSED }

    data class Decision(val allow: Boolean, val reason: String)

    private data class Slot(
        val state: AtomicReference<State> = AtomicReference(State.OPEN),
        val consumedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet(),
        val terminalCount: AtomicLong = AtomicLong(0L),
        val lastTransitionMs: AtomicLong = AtomicLong(0L),
    )

    private val ledger = ConcurrentHashMap<String, Slot>()
    private val stats = ConcurrentHashMap<String, AtomicLong>()

    private fun bump(k: String) {
        stats.computeIfAbsent(k) { AtomicLong(0L) }.incrementAndGet()
    }

    /** Register a new position at open. Idempotent. */
    fun registerOpen(positionId: String) {
        if (positionId.isBlank()) return
        val slot = ledger.computeIfAbsent(positionId) { Slot() }
        slot.state.compareAndSet(State.CLOSED, State.OPEN)  // recycle if reopened
        slot.lastTransitionMs.set(System.currentTimeMillis())
    }

    /** After a partial sell that leaves qty > 0. */
    fun markPartial(positionId: String) {
        val slot = ledger[positionId] ?: return
        val current = slot.state.get()
        if (current == State.OPEN || current == State.PARTIALLY_CLOSED) {
            slot.state.compareAndSet(current, State.PARTIALLY_CLOSED)
            slot.lastTransitionMs.set(System.currentTimeMillis())
        }
    }

    /**
     * Attempt to reserve a terminal-sell intent. Only ONE reservation per
     * position may succeed. Idempotency key adds a second layer of
     * protection against retry storms.
     */
    fun reserveTerminalSell(
        positionId: String,
        executionGeneration: Long,
        operationType: String,
        mint: String,
        symbol: String,
    ): Decision {
        if (positionId.isBlank()) {
            // Fail-open for legacy code paths that don't yet supply a
            // positionId. This guard is additive; it must not stop
            // trades that predate the state machine.
            bump("TERMINAL_SELL_NO_POSITION_ID_6427")
            return Decision(true, "no_position_id_fail_open")
        }
        val slot = ledger.computeIfAbsent(positionId) { Slot() }
        val idemKey = "$positionId|$executionGeneration|$operationType"
        if (!slot.consumedKeys.add(idemKey)) {
            bump("TERMINAL_SELL_DUPLICATE_INTENT_6427")
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_DUPLICATE_INTENT_6427",
                    "positionId=$positionId key=$idemKey mint=${mint.take(10)} sym=$symbol",
                )
            } catch (_: Throwable) {}
            return Decision(false, "DUPLICATE_INTENT $idemKey")
        }
        while (true) {
            val current = slot.state.get()
            when (current) {
                State.CLOSED -> {
                    bump("TERMINAL_SELL_ALREADY_CLOSED_6427")
                    try {
                        ForensicLogger.lifecycle(
                            "TERMINAL_SELL_ALREADY_CLOSED_6427",
                            "positionId=$positionId prior=${slot.terminalCount.get()} mint=${mint.take(10)} sym=$symbol op=$operationType",
                        )
                    } catch (_: Throwable) {}
                    return Decision(false, "ALREADY_CLOSED terminalCount=${slot.terminalCount.get()}")
                }
                State.CLOSING -> {
                    bump("TERMINAL_SELL_CLOSING_IN_FLIGHT_6427")
                    return Decision(false, "CLOSING_IN_FLIGHT")
                }
                State.CLOSE_REQUESTED, State.OPEN, State.PARTIALLY_CLOSED -> {
                    if (slot.state.compareAndSet(current, State.CLOSING)) {
                        slot.lastTransitionMs.set(System.currentTimeMillis())
                        bump("TERMINAL_SELL_RESERVED_6427")
                        return Decision(true, "reserved prior=${current.name}")
                    }
                    // lost the CAS race; retry
                }
            }
        }
    }

    /**
     * Confirm a terminal sell actually landed (post-fill / post-journal).
     * Transitions CLOSING -> CLOSED. Increments terminalCount for
     * observability; if this ever exceeds 1 for a positionId we have a
     * ledger corruption event.
     */
    fun confirmTerminalSell(positionId: String) {
        if (positionId.isBlank()) return
        val slot = ledger[positionId] ?: return
        slot.state.set(State.CLOSED)
        slot.lastTransitionMs.set(System.currentTimeMillis())
        val n = slot.terminalCount.incrementAndGet()
        if (n > 1) {
            try {
                ForensicLogger.lifecycle(
                    "TERMINAL_SELL_DOUBLE_CONFIRM_6427",
                    "positionId=$positionId terminalCount=$n",
                )
                PipelineHealthCollector.labelInc("TERMINAL_SELL_DOUBLE_CONFIRM_6427")
            } catch (_: Throwable) {}
        }
    }

    /** Release a reservation if the sell aborts before landing. */
    fun releaseReservation(positionId: String, priorStateWasOpen: Boolean) {
        val slot = ledger[positionId] ?: return
        val target = if (priorStateWasOpen) State.OPEN else State.PARTIALLY_CLOSED
        slot.state.compareAndSet(State.CLOSING, target)
    }

    fun stateOf(positionId: String): State =
        ledger[positionId]?.state?.get() ?: State.OPEN

    fun statusLine(): String {
        val byState = ledger.values.groupingBy { it.state.get() }.eachCount()
        val doubleConfirms = ledger.values.count { it.terminalCount.get() > 1 }
        return "positions=${ledger.size} states=$byState doubleConfirm=$doubleConfirms"
    }

    internal fun resetForTest() {
        ledger.clear(); stats.clear()
    }
}

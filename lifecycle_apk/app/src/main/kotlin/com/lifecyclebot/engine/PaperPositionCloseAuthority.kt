package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.3800 — Paper-position close authority.
 *
 * Mirrors the live close-authority principle for the simulator without touching
 * live routing: once a PAPER close has been requested/started/finalized for a
 * mode+mint, later paper exit ticks return before DO_SELL_ENTRY / EXEC_TRACE_SELL
 * / sell-lock churn / cooldown re-arm / journal writes. This keeps paper exit
 * cleanup from starving intake → lane-eval learning.
 */
object PaperPositionCloseAuthority {
    enum class State { OPEN, CLOSE_REQUESTED, CLOSING, CLOSED, REJECTED, FAILED }

    data class CloseState(
        val key: String,
        val mint: String,
        val mode: String,
        @Volatile var symbol: String = "",
        @Volatile var state: State = State.OPEN,
        @Volatile var closeId: String = "",
        @Volatile var reason: String = "",
        @Volatile var updatedAtMs: Long = System.currentTimeMillis(),
        @Volatile var lastAlreadyPendingLogMs: Long = 0L,
    )

    data class Guard(val blocked: Boolean, val state: State?, val reason: String, val closeId: String = "")

    private val states = ConcurrentHashMap<String, CloseState>()
    private const val ALREADY_PENDING_LOG_MS = 30_000L
    private const val FAILED_RETRY_TTL_MS = 20_000L

    private fun normMode(mode: String): String = mode.trim().uppercase().ifBlank { "PAPER" }
    private fun key(mode: String, mint: String): String = "${normMode(mode)}|$mint"

    fun reopen(mode: String = "PAPER", mint: String) {
        if (mint.isBlank()) return
        states.remove(key(mode, mint))
    }

    fun stateOf(mode: String = "PAPER", mint: String): State? {
        if (mint.isBlank()) return null
        syncLedger(mode, mint, "")
        return states[key(mode, mint)]?.state
    }

    fun preSellGuard(mode: String = "PAPER", mint: String, symbol: String = "", reason: String = ""): Guard {
        if (mint.isBlank()) return Guard(false, null, "blank")
        val k = key(mode, mint)
        syncLedger(mode, mint, symbol)
        val now = System.currentTimeMillis()
        val st = states[k]
        if (st != null) {
            if (st.state == State.FAILED || st.state == State.REJECTED) {
                if (now - st.updatedAtMs >= FAILED_RETRY_TTL_MS) return Guard(false, st.state, "retryable_after_failed", st.closeId)
            }
            if (st.state == State.CLOSE_REQUESTED || st.state == State.CLOSING || st.state == State.CLOSED) {
                maybeLogAlreadyPending(st, reason, now)
                return Guard(true, st.state, st.state.name, st.closeId)
            }
        }
        return Guard(false, st?.state ?: State.OPEN, "OPEN")
    }

    fun markCloseRequested(mode: String = "PAPER", mint: String, symbol: String = "", reason: String = ""): String {
        if (mint.isBlank()) return ""
        val k = key(mode, mint)
        val now = System.currentTimeMillis()
        val st = states.compute(k) { _, old ->
            val s = old ?: CloseState(k, mint, normMode(mode), symbol = symbol)
            if (s.state != State.CLOSED && s.state != State.CLOSING) {
                s.state = State.CLOSE_REQUESTED
            }
            s.symbol = symbol.ifBlank { s.symbol }
            s.reason = reason
            s.updatedAtMs = now
            if (s.closeId.isBlank()) s.closeId = "PAPER_${mint.take(10)}_${now}"
            s
        }
        emit("PAPER_CLOSE_REQUESTED", mint, symbol, "state=${st?.state} closeId=${st?.closeId ?: ""} reason=$reason")
        return st?.closeId ?: ""
    }

    fun markClosing(mode: String = "PAPER", mint: String, symbol: String = "", reason: String = ""): String {
        if (mint.isBlank()) return ""
        val k = key(mode, mint)
        val now = System.currentTimeMillis()
        val st = states.compute(k) { _, old ->
            val s = old ?: CloseState(k, mint, normMode(mode), symbol = symbol)
            if (s.state != State.CLOSED) s.state = State.CLOSING
            s.symbol = symbol.ifBlank { s.symbol }
            s.reason = reason
            s.updatedAtMs = now
            if (s.closeId.isBlank()) s.closeId = "PAPER_${mint.take(10)}_${now}"
            s
        }
        return st?.closeId ?: ""
    }

    fun markClosed(mode: String = "PAPER", mint: String, symbol: String = "", reason: String = "", closeId: String = "") {
        if (mint.isBlank()) return
        val k = key(mode, mint)
        val now = System.currentTimeMillis()
        val cid = closeId.ifBlank { runCatching { PositionCloseLedger.closeIdOf(mint) }.getOrNull() ?: "PAPER_${mint.take(10)}_${now}" }
        states.compute(k) { _, old ->
            val s = old ?: CloseState(k, mint, normMode(mode), symbol = symbol)
            s.state = State.CLOSED
            s.symbol = symbol.ifBlank { s.symbol }
            s.reason = reason.ifBlank { s.reason }
            s.closeId = cid
            s.updatedAtMs = now
            s
        }
        emit("PAPER_CLOSE_CLOSED", mint, symbol, "closeId=$cid reason=$reason")
        if (normMode(mode) == "PAPER") emit("PAPER_CLOSE_CONFIRMED_LEDGER_ONLY", mint, symbol, "closeId=$cid reason=$reason")
    }

    fun markFailed(mode: String = "PAPER", mint: String, symbol: String = "", reason: String = "") {
        if (mint.isBlank()) return
        val k = key(mode, mint)
        val now = System.currentTimeMillis()
        states.compute(k) { _, old ->
            val s = old ?: CloseState(k, mint, normMode(mode), symbol = symbol)
            s.state = State.FAILED
            s.symbol = symbol.ifBlank { s.symbol }
            s.reason = reason
            s.updatedAtMs = now
            s
        }
        emit("PAPER_CLOSE_FAILED", mint, symbol, "reason=$reason")
    }

    private fun syncLedger(mode: String, mint: String, symbol: String) {
        val cid = runCatching { PositionCloseLedger.closeIdOf(mint) }.getOrNull() ?: return
        val k = key(mode, mint)
        val now = System.currentTimeMillis()
        states.compute(k) { _, old ->
            val s = old ?: CloseState(k, mint, normMode(mode), symbol = symbol)
            s.state = State.CLOSED
            s.closeId = cid
            s.symbol = symbol.ifBlank { s.symbol }
            s.reason = s.reason.ifBlank { "LEDGER_CLOSED" }
            s.updatedAtMs = now
            s
        }
    }

    private fun maybeLogAlreadyPending(st: CloseState, reason: String, now: Long) {
        if (now - st.lastAlreadyPendingLogMs < ALREADY_PENDING_LOG_MS) return
        st.lastAlreadyPendingLogMs = now
        emit("PAPER_CLOSE_ALREADY_PENDING", st.mint, st.symbol, "state=${st.state} closeId=${st.closeId} oldReason=${st.reason} newReason=$reason")
    }

    private fun emit(label: String, mint: String, symbol: String, detail: String) {
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle(label, "mint=${mint.take(10)} symbol=$symbol $detail") } catch (_: Throwable) {}
    }
}

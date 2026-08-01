package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6405 §4 — TERMINAL FINALITY & DUPLICATE EXIT PREVENTION.
 *
 * A position lifetime has EXACTLY ONE terminal outcome:
 *   • CLOSED_FULL_EXIT   — sum(sold raw) == entry raw
 *   • CLOSED_STOP        — hard stop / catastrophic exit finalised
 *   • CLOSED_LIQUIDATED  — orphan sweep / manual liquidate
 *
 * Once terminal, no further exit intents may broadcast. Duplicate
 * exit attempts are rejected before they reach the transaction
 * builder — no more '18 duplicate rows for the same mint' the
 * V5.0.6402 reconciler flagged.
 *
 * Keyed by (mint, positionGeneration) — the same key the raw ledger
 * uses in [DecimalIntegrityAuthority6405].
 */
object TerminalFinalityAuthority6405 {

    enum class Terminal { CLOSED_FULL_EXIT, CLOSED_STOP, CLOSED_LIQUIDATED }

    private data class Record(val terminal: Terminal, val atMs: Long, val reason: String)

    private val terminals = ConcurrentHashMap<String, Record>()

    private fun key(mint: String, gen: Long) = "$mint|$gen"

    /** Idempotent — once terminal, later calls with a different reason are ignored. */
    fun markTerminal(mint: String, positionGeneration: Long, terminal: Terminal, reason: String) {
        if (mint.isBlank()) return
        val k = key(mint, positionGeneration)
        val prior = terminals.putIfAbsent(k, Record(terminal, System.currentTimeMillis(), reason))
        if (prior == null) {
            try {
                ForensicLogger.lifecycle(
                    "POSITION_TERMINAL_6405",
                    "mint=${mint.take(10)} gen=$positionGeneration terminal=${terminal.name} reason=$reason",
                )
                PipelineHealthCollector.labelInc("POSITION_TERMINAL_6405")
            } catch (_: Throwable) {}
        }
    }

    fun isTerminal(mint: String, positionGeneration: Long): Boolean =
        terminals.containsKey(key(mint, positionGeneration))

    fun terminalOf(mint: String, positionGeneration: Long): Terminal? =
        terminals[key(mint, positionGeneration)]?.terminal

    /**
     * Gate an exit intent. Returns true when the caller may proceed;
     * false when the position is already terminal (and emits a
     * DUPLICATE_EXIT_BLOCKED_6405 counter).
     */
    fun allowExit(mint: String, positionGeneration: Long, exitReason: String): Boolean {
        val k = key(mint, positionGeneration)
        val prior = terminals[k]
        if (prior == null) return true
        try {
            ForensicLogger.lifecycle(
                "DUPLICATE_EXIT_BLOCKED_6405",
                "mint=${mint.take(10)} gen=$positionGeneration exitReason=$exitReason " +
                    "priorTerminal=${prior.terminal.name} priorAtMs=${prior.atMs} priorReason=${prior.reason.take(60)}",
            )
            PipelineHealthCollector.labelInc("DUPLICATE_EXIT_BLOCKED_6405")
        } catch (_: Throwable) {}
        return false
    }

    /** Diagnostics — count of terminal positions currently tracked. */
    fun terminalCount(): Int = terminals.size

    /** For fresh re-buy of the same mint at a new generation. */
    fun clearGeneration(mint: String, positionGeneration: Long) {
        terminals.remove(key(mint, positionGeneration))
    }

    internal fun clearForTest() { terminals.clear() }
}

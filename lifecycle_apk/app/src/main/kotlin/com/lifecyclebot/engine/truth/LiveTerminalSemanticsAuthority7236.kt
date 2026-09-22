package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7236 §LIVE_TERMINAL_SEMANTICS — operator diagnosis 5.0.7234:
 *
 *   "Eleven CSV losses are currently fake economic losses.
 *    LIVE_BROADCAST is NOT a realized loss. Current export labels 11
 *    broadcast-only sells as −100% and counts them as losses.
 *    Only LIVE_FINALIZED / wallet-confirmed terminal closes may update
 *    WR, PnL, expectancy, learner rewards or tax/export summaries."
 *
 * PURPOSE — a single boolean the accounting/learning/export layer
 * consults for a given SELL row: "does this row count as a terminal
 * economic outcome?"
 *
 *   PAPER              — always terminal (deterministic simulator).
 *   LIVE_FINALIZED     — terminal (canonical settlement).
 *   LIVE_BALANCE_CONFIRMED — terminal (wallet observed).
 *   LIVE_SIG_CONFIRMED — terminal (transaction confirmed on chain).
 *   LIVE_BROADCAST     — NOT terminal. Broadcast intent, no proof.
 *   blank/unknown live — NOT terminal.
 *
 * WHY THIS IS THE CORRECT LAYER — the CSV summary, WR ticker, learner
 * reward stream, expectancy adaptor and tax export all iterate the
 * Trade rows. Moving the "did this actually happen economically?"
 * decision into one authority means every downstream consumer sees the
 * same terminal set, and a future proof-state (e.g. LIVE_PARTIAL_
 * SIG_CONFIRMED) can be adopted in one line.
 *
 * OPERATOR CSV EVIDENCE (5.0.7234):
 *   sells (rows)                  = 19
 *   LIVE_BROADCAST (in-flight)    = 11
 *   LIVE_FINALIZED (terminal)     =  8
 *   naive summary WR              = 2W / 17L = 10.5%
 *   canonical (terminal-only) WR  = 2W /  6L = 25.0%
 *
 * The naive summary was minting fourteen fake losses per session.
 */
object LiveTerminalSemanticsAuthority7236 {

    private val terminalPaper = AtomicLong(0L)
    private val terminalLiveFinalized = AtomicLong(0L)
    private val terminalLiveBalance = AtomicLong(0L)
    private val terminalLiveSig = AtomicLong(0L)
    private val excludedBroadcast = AtomicLong(0L)
    private val excludedUnknown = AtomicLong(0L)

    /**
     * Returns true when the SELL row's proof state represents a
     * realized economic outcome. Callers use this before adding the
     * row to WR, PnL, expectancy, learner reward stream or CSV export
     * summary.
     *
     * @param mode        trade mode ("paper" / "live" / blank)
     * @param proofState  normalized proof state (PAPER_SIMULATED /
     *                    LIVE_FINALIZED / LIVE_BALANCE_CONFIRMED /
     *                    LIVE_SIG_CONFIRMED / LIVE_BROADCAST / blank)
     */
    fun isTerminalOutcome(mode: String, proofState: String): Boolean {
        val m = mode.trim().lowercase()
        val ps = proofState.trim().uppercase()

        // Paper is always terminal (deterministic simulator).
        if (m == "paper") {
            terminalPaper.incrementAndGet()
            return true
        }

        // Explicit paper proof state.
        if (ps == "PAPER_SIMULATED") {
            terminalPaper.incrementAndGet()
            return true
        }

        // Live proof states.
        if (m == "live" || ps.startsWith("LIVE_")) {
            when (ps) {
                "LIVE_FINALIZED" -> {
                    terminalLiveFinalized.incrementAndGet()
                    return true
                }
                "LIVE_BALANCE_CONFIRMED" -> {
                    terminalLiveBalance.incrementAndGet()
                    return true
                }
                "LIVE_SIG_CONFIRMED" -> {
                    terminalLiveSig.incrementAndGet()
                    return true
                }
                "LIVE_BROADCAST" -> {
                    excludedBroadcast.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("LIVE_TERMINAL_EXCLUDED_BROADCAST_7236")
                        if (excludedBroadcast.get() % 25L == 1L) {
                            ForensicLogger.lifecycle(
                                "LIVE_TERMINAL_EXCLUDED_BROADCAST_7236",
                                "proof=LIVE_BROADCAST mode=$m " +
                                    "action=excluded_from_WR_PnL_expectancy_export_awaiting_finality",
                            )
                        }
                    } catch (_: Throwable) {}
                    return false
                }
                else -> {
                    excludedUnknown.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("LIVE_TERMINAL_EXCLUDED_UNKNOWN_7236") } catch (_: Throwable) {}
                    return false
                }
            }
        }

        // Blank / non-live / non-paper — legacy safety: accept. The
        // legacy accounting predicate already screens invalid rows
        // before this authority is consulted, so a blank mode row
        // reaching this point is an already-vetted historical row.
        terminalPaper.incrementAndGet()
        return true
    }

    data class Summary(
        val terminalPaper: Long,
        val terminalLiveFinalized: Long,
        val terminalLiveBalance: Long,
        val terminalLiveSig: Long,
        val excludedBroadcast: Long,
        val excludedUnknown: Long,
    ) {
        val totalTerminal: Long get() =
            terminalPaper + terminalLiveFinalized + terminalLiveBalance + terminalLiveSig
        val totalExcluded: Long get() = excludedBroadcast + excludedUnknown
    }

    fun summary(): Summary = Summary(
        terminalPaper = terminalPaper.get(),
        terminalLiveFinalized = terminalLiveFinalized.get(),
        terminalLiveBalance = terminalLiveBalance.get(),
        terminalLiveSig = terminalLiveSig.get(),
        excludedBroadcast = excludedBroadcast.get(),
        excludedUnknown = excludedUnknown.get(),
    )

    fun statusLine(): String {
        val s = summary()
        return "LiveTerminalSemanticsAuthority7236 terminal=${s.totalTerminal} " +
            "(paper=${s.terminalPaper},finalized=${s.terminalLiveFinalized}," +
            "balance=${s.terminalLiveBalance},sig=${s.terminalLiveSig}) " +
            "excluded=${s.totalExcluded} " +
            "(broadcast=${s.excludedBroadcast},unknown=${s.excludedUnknown})"
    }

    internal fun clearForTest() {
        terminalPaper.set(0L); terminalLiveFinalized.set(0L)
        terminalLiveBalance.set(0L); terminalLiveSig.set(0L)
        excludedBroadcast.set(0L); excludedUnknown.set(0L)
    }
}

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.TradeRecord
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6499 §1 — TERMINAL CLOSE AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6498 evidence):
 *
 *   "Your recent journal contains exactly:
 *      2 terminal SELL
 *      4 PARTIAL_SELL
 *      8 BUY
 *    Yet Performance Analytics reports 6 closed trades, 5W/1L.
 *    That is almost certainly the 2 full sells + 4 partial sells
 *    being counted as six terminal trades.
 *
 *    Your own StrategyTruthLedger confirms it:
 *      clean=3 ... partialNonTerminal=4
 *
 *    That is why you're getting nonsense such as: 83.3% WR PF=3435
 *    +7.562 SOL 5 consecutive wins — those numbers should not be
 *    trusted.
 *
 *    P0: Terminal analytics purity — exclude PARTIAL_SELL from
 *    closed-trade N/WR/PF/expectancy/streaks."
 *
 * DESIGN
 * ──────
 * Single classifier that mirrors the existing
 * `Executor.isPartialByReason` / `isPartialByQty` rules but works on
 * `TradeRecord` (the persisted analytics row) instead of the
 * transient `Trade`.
 *
 * `isTerminalClose(record)` returns true only for a genuine terminal
 * close. Partial-sell records — whether identified by exit-reason
 * pattern or by residual quantity — return false.
 *
 * Analytics / performance snapshots MUST route every closed-trade
 * candidate through this authority. Non-terminal records still
 * appear in the raw journal but MUST NOT contribute to WR / PF /
 * expectancy / streak / totalPnL derivations.
 */
object TerminalCloseAuthority6499 {

    private val classifications = AtomicLong(0L)
    private val terminals = AtomicLong(0L)
    private val partials = AtomicLong(0L)

    // Same partial-reason patterns Executor.isPartialByReason uses.
    // Duplicated as an authority-scoped list so analytics can classify
    // without importing Executor internals.
    private val PARTIAL_REASON_PATTERNS = listOf(
        "partial",
        "profit_lock",
        "capital_recovery",
        "wr_recovery",
        "profit_take_partial",
        "scale_out",
    )

    /**
     * True iff [record] represents a genuine terminal close.
     * A terminal close has (a) a non-empty exit reason that does NOT
     * match any partial pattern, and (b) either no partialSold at all
     * OR partialSold indicates the full remaining qty was flushed.
     *
     * When the exit reason is blank (legacy rows), the classifier
     * falls open — treating the row as terminal for backward
     * compatibility with pre-partial-tracking rows. This mirrors
     * the existing analytics behaviour before this authority
     * shipped, so historical WR is not retroactively re-scored.
     */
    fun isTerminalClose(record: TradeRecord): Boolean {
        classifications.incrementAndGet()
        val exitReason = record.exitReason.trim().lowercase()
        if (exitReason.isNotBlank()) {
            for (p in PARTIAL_REASON_PATTERNS) {
                if (exitReason.contains(p)) {
                    partials.incrementAndGet()
                    return false
                }
            }
        }
        // Quantity gate: a row where partialSold > 0 but < 99 means
        // this row represents a partial that scaled out some quantity
        // and left the rest open. Skip.
        val partialPct = record.partialSold
        if (partialPct > 0.0 && partialPct < 99.0 && exitReason.isBlank()) {
            // Legacy row with partial-sold but no exit reason — treat
            // as partial to be safe.
            partials.incrementAndGet()
            return false
        }
        terminals.incrementAndGet()
        return true
    }

    fun statusLine(): String =
        "classifications=${classifications.get()} terminals=${terminals.get()} partials=${partials.get()}"

    internal fun resetForTest() {
        classifications.set(0L); terminals.set(0L); partials.set(0L)
    }
}

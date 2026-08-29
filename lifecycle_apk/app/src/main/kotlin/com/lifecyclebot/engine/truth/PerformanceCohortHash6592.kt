package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.TradeRecord
import java.security.MessageDigest

/**
 * V5.0.6592 §PERFORMANCE_COHORT_TRUTH — stable, deterministic hash of a
 * terminal-trade cohort used to bind every metric on a performance card
 * (WR / PF / expectancy / totalPnl) to the SAME trade ID set.
 *
 * Operator directive Feb 2026:
 *   > "Expose: performanceCohort, terminalIdsHash, terminalN, journalRows,
 *   >  buyRows, sellRows, partialRows. Trades on a WR card == terminal
 *   >  closes, not raw journal rows."
 *
 * A UI that displays two figures allegedly describing the same 16 trades
 * must publish the cohort hash next to each; a mismatch means the numbers
 * come from different data sources (the impossible splice that produced
 * 2W/14L PF 0.81 with Total P&L +2.6492 SOL).
 */
object PerformanceCohortHash6592 {

    fun hash(trades: List<TradeRecord>): String {
        if (trades.isEmpty()) return "empty"
        // Stable ordering: sort by id then tsExit; a cohort is identity-
        // defined by its member set, not the caller's iteration order.
        val stable = trades.asSequence()
            .map { "${it.id}|${it.tsExit}|${it.symbol}" }
            .sorted()
            .joinToString(";")
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(stable.toByteArray())
            digest.take(8).joinToString("") { "%02x".format(it) }
        } catch (_: Throwable) {
            // Non-crypto fallback — deterministic and sufficient for
            // cohort-equality checks even without a JCE provider.
            stable.hashCode().toString(16)
        }
    }
}

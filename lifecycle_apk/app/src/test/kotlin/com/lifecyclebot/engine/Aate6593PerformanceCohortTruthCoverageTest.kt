package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

/**
 * V5.0.6593 — performance-cohort truth: WR / PF / expectancy / totalPnl
 * on the SAME analytics card MUST come from the same terminal ID set.
 *
 * Operator directive Feb 2026:
 *   > "For any card, ALL of: N, wins, losses, WR, grossWin, grossLoss,
 *   >  PF, expectancy, realizedPnL must come from the exact same
 *   >  terminal trade ID set. Never combine AccountLedger.realizedPnl
 *   >  with a filtered journal WR."
 *
 * Pre-6593 the code overrode totalPnlSol with the account-wide
 * PaperAccountLedger6430.realizedPnlSol() while leaving WR/PF/expectancy
 * on the journal decisive-trade cohort — producing impossible cards
 * (16 trades / 2W-14L / PF 0.81 / expectancy -0.0011 SOL with Total P&L
 * +2.6492 SOL). Fix: totalPnlSol is now the journal-cohort PnL;
 * accountLedgerRealizedPnlSol is a separate field the UI may show as a
 * distinct wallet card, never spliced onto the WR card.
 */
class Aate6593PerformanceCohortTruthCoverageTest {

    @Test
    fun aate6593_analytics_no_longer_splices_ledger_pnl_over_journal_wr() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/PerformanceAnalytics.kt"
        ).readText()
        // totalPnl must be assigned from journalTotalPnl (the same cohort
        // that produced WR/PF/expectancy). Not the ledger.
        assertTrue(
            "V5.0.6593: totalPnl must equal journalTotalPnl (same-cohort invariant)",
            src.contains("val totalPnl = journalTotalPnl")
        )
        // The ledger override must be gone from the WR card.
        assertFalse(
            "V5.0.6593: the ledger override that produced impossible cards must be removed",
            src.contains("val totalPnl = if (canonicalRealizedPnl.isFinite()) {") &&
                Regex("""val\s+totalPnl\s*=\s*if\s*\(canonicalRealizedPnl\.isFinite\(\)\)\s*\{[^}]*canonicalRealizedPnl\s*\}\s*else\s+journalTotalPnl""").containsMatchIn(src)
        )
        // Account-ledger PnL must be exposed as a separate field.
        assertTrue(
            "V5.0.6593: accountLedgerRealizedPnlSol must be a separate exposed field",
            src.contains("val accountLedgerRealizedPnlSol: Double = 0.0") &&
                src.contains("accountLedgerRealizedPnlSol = sanitizeDouble(accountLedgerRealizedPnl6592)")
        )
        // Cohort identity must be exposed for UI-side verification.
        assertTrue(
            "V5.0.6593: cohortTerminalIdsHash, cohortTerminalN, cohort row counters exposed",
            src.contains("val cohortTerminalIdsHash: String = \"\"") &&
                src.contains("val cohortTerminalN: Int = 0") &&
                src.contains("val cohortJournalRows: Int = 0") &&
                src.contains("cohortTerminalIdsHash = com.lifecyclebot.engine.truth.PerformanceCohortHash6592.hash(decisiveTrades)")
        )
    }

    @Test
    fun aate6593_cohort_hash_is_stable_and_deterministic() {
        // Empty cohort must return a stable sentinel.
        assertEquals(
            "V5.0.6593: empty cohort must produce a stable sentinel",
            "empty",
            com.lifecyclebot.engine.truth.PerformanceCohortHash6592.hash(emptyList())
        )
        // Same cohort in different orders must produce the SAME hash.
        val t1 = TradeRecord(id = 1, tsExit = 1000L, symbol = "AAA")
        val t2 = TradeRecord(id = 2, tsExit = 2000L, symbol = "BBB")
        val t3 = TradeRecord(id = 3, tsExit = 3000L, symbol = "CCC")
        val hashOrderA = com.lifecyclebot.engine.truth.PerformanceCohortHash6592.hash(listOf(t1, t2, t3))
        val hashOrderB = com.lifecyclebot.engine.truth.PerformanceCohortHash6592.hash(listOf(t3, t1, t2))
        assertEquals(
            "V5.0.6593: cohort hash must be order-independent (set-identity)",
            hashOrderA,
            hashOrderB,
        )
        // Different cohorts must produce different hashes.
        val hashDifferent = com.lifecyclebot.engine.truth.PerformanceCohortHash6592.hash(listOf(t1, t2))
        assertNotEquals(
            "V5.0.6593: distinct cohorts must produce distinct hashes",
            hashOrderA,
            hashDifferent,
        )
        assertTrue(
            "V5.0.6593: cohort hash must be non-blank",
            hashOrderA.isNotBlank() && hashOrderA != "empty",
        )
    }
}

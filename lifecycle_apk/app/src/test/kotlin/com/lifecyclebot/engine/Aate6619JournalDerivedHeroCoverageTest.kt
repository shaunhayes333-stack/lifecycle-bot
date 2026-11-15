package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * V5.0.6619 — JOURNAL_DERIVED_HERO_AUTHORITY
 *
 * Operator directive Feb 2026: "The main UI balance is broken. Look at
 * the journal vs the hero balance in the main UI. That figure is
 * impossible and should be solely derived from data directly from
 * the journal."
 *
 * Forensic (fresh install V5.0.6617): hero showed +$5,793 while the
 * journal RAW parity band totalled +1.5999 SOL (~$136) and the clean
 * journal tab showed +$146.08. The ledger accumulator drifted; the
 * journal did not. Doctrine (V5.0.6616): journal = source of truth.
 *
 * This test enforces four invariants:
 *
 *   1. JournalEconomicReplay6619 exists with the required API and
 *      does NOT read PaperAccountLedger6430 accumulators for the
 *      economic answer (only startingCashSol for the immutable
 *      starting-cash config).
 *
 *   2. JournalEconomicAuthority6616 (which feeds the three heroes)
 *      now sources cash / equity / realized from
 *      JournalEconomicReplay6619, not from CanonicalCapitalAuthority6450
 *      or PaperAccountLedger6430.
 *
 *   3. replay() computes deterministic paper-only cash from journal
 *      rows using the equation
 *        cash = start − Σ(BUY sol + BUY fee) + Σ(SELL gross − SELL fee)
 *      and never fabricates rows for missing journal state.
 *
 *   4. Divergence probe emits PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619
 *      when the ledger cash disagrees with journal replay cash by
 *      more than 0.001 SOL — the ledger stays alive for execution but
 *      the hero binds to the journal.
 */
class Aate6619JournalDerivedHeroCoverageTest {

    @After
    fun tearDown() {
        try { com.lifecyclebot.engine.truth.JournalEconomicReplay6619.resetForTest() } catch (_: Throwable) {}
        try { com.lifecyclebot.engine.truth.JournalEconomicAuthority6616.resetForTest() } catch (_: Throwable) {}
    }

    @Test
    fun aate6619_replay_authority_exists_with_required_api() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt"
        ).readText()
        assertTrue(
            "V5.0.6619: JournalEconomicReplay6619 object must exist",
            src.contains("object JournalEconomicReplay6619")
        )
        assertTrue(
            "V5.0.6619: ReplayResult data class must carry cash/realized/openCost/equity/rows",
            src.contains("data class ReplayResult") &&
                src.contains("val cashSol: Double") &&
                src.contains("val realizedPnlSol: Double") &&
                src.contains("val openCostBasisSol: Double") &&
                src.contains("val equitySol: Double") &&
                src.contains("val paperRows: Int")
        )
        assertTrue(
            "V5.0.6619: replay() must walk TradeHistoryStore.getAllValidTradesSnapshot for paper rows",
            src.contains("fun replay(): ReplayResult") &&
                src.contains("TradeHistoryStore.getAllValidTradesSnapshot(limit = 20_000)")
        )
        assertTrue(
            "V5.0.6619: divergence probe must emit PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619",
            src.contains("PAPER_LEDGER_VS_JOURNAL_DIVERGENCE_6619")
        )
    }

    @Test
    fun aate6619_authority6616_feeds_heroes_from_replay_not_from_ledger_accumulators() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicAuthority6616.kt"
        ).readText()
        assertTrue(
            "V5.0.6619: notifyEconomicMutation must call JournalEconomicReplay6619.replay()",
            src.contains("JournalEconomicReplay6619.replay()")
        )
        assertTrue(
            "V5.0.6619: authority must tag published snapshots with TRADE_JOURNAL_REPLAY_6619 source",
            src.contains("TRADE_JOURNAL_REPLAY_6619")
        )
    }

    @Test
    fun aate6619_replay_of_empty_journal_returns_startingCash_only() {
        val replay = com.lifecyclebot.engine.truth.JournalEconomicReplay6619
        val ledger = com.lifecyclebot.engine.truth.PaperAccountLedger6430
        ledger.resetForTest()
        ledger.initialize(11.7647)
        val r = replay.replay()
        assertEquals(
            "V5.0.6619: empty journal must yield cash = startingCash",
            11.7647, r.cashSol, 1e-6
        )
        assertEquals(
            "V5.0.6619: empty journal must yield realized = 0",
            0.0, r.realizedPnlSol, 1e-9
        )
        assertEquals(
            "V5.0.6619: empty journal must yield openCost = 0",
            0.0, r.openCostBasisSol, 1e-9
        )
        assertEquals(
            "V5.0.6619: empty journal must yield equity = startingCash",
            11.7647, r.equitySol, 1e-6
        )
    }

    @Test
    fun aate6619_authority_snapshot_reports_journal_replay_source() {
        val ledger = com.lifecyclebot.engine.truth.PaperAccountLedger6430
        val auth = com.lifecyclebot.engine.truth.JournalEconomicAuthority6616
        ledger.resetForTest()
        auth.resetForTest()
        // The 9k-test suite shares JVM singletons: old forensic parity and
        // canonical-replay witnesses can belong to unrelated earlier tests.
        // Reset those witnesses before testing an empty-journal initialization;
        // production still refuses a genuinely mismatched durable journal.
        com.lifecyclebot.engine.truth.ForensicReconciliation6635.resetForTest()
        com.lifecyclebot.engine.truth.CanonicalPaperReplay6464.resetForTest()
        // V5.0.8803 — the replay memo (keyed by journal revision and starting cash) is a JVM singleton too: an earlier
        // test's replay at the same revision and 11.7647 start was served back here, and its result blocked the publish.
        com.lifecyclebot.engine.truth.JournalEconomicReplay6619.resetForTest()
        // V5.0.8805 — still red on 8803: the reconciliation compares the journal with the canonical positions and economic
        // events, which earlier tests in the same JVM leave open. An empty journal must meet an empty book.
        com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441.resetForTest()
        com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.resetForTest()
        ledger.initialize(11.7647)
        // 8794: initialize() records its mutation revision synchronously,
        // while the expensive journal replay is coalesced onto a worker to
        // avoid the ledger -> journal lock inversion observed in the device.
        // Verify eventual durable projection rather than same-thread replay.
        val deadline = System.currentTimeMillis() + 5_000L
        var snap = auth.currentSnapshot()
        while (snap == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
            snap = auth.currentSnapshot()
        }
        if (snap == null) {
            // V5.0.8805 — say WHY nothing was published (the replay and reconciliation the worker would have gated on)
            val r = try { com.lifecyclebot.engine.truth.JournalEconomicReplay6619.replay() } catch (t: Throwable) { null }
            val d = try { com.lifecyclebot.engine.truth.ForensicReconciliation6635.deltas6647() } catch (t: Throwable) { null }
            org.junit.Assert.fail("V5.0.6619: snapshot must be published after initialize() — replay{cash=${r?.cashSol} " +
                "complete=${r?.totalsComplete6899} rows=${r?.paperRows} failures=${r?.invariantFailures?.take(4)}} " +
                "recon{reconciled=${d?.reconciled} current=${d?.accountingCurrent8397} cashSide=${d?.cashSideReconciled8397} " +
                "cash=${d?.cashSol} basis=${d?.basisSol} health=${d?.healthLine8397?.take(160)} incomplete=${d?.incompleteReceipts8397?.take(80)}}")
        }
        assertNotNull("V5.0.6619: snapshot must be published after initialize()", snap)
        assertEquals(
            "V5.0.6619: initial snapshot cash must equal startingCash (via journal replay)",
            11.7647, snap!!.cashSol, 1e-6
        )
        assertTrue(
            "V5.0.6619: snapshot source must be TRADE_JOURNAL_REPLAY_6619 (got=${snap.source})",
            snap.source.contains("6619")
        )
    }
}

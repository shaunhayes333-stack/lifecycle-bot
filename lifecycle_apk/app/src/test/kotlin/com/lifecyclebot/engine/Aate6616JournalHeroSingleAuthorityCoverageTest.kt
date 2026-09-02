package com.lifecyclebot.engine

import org.junit.Test
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * V5.0.6616 — JOURNAL → BALANCE → HERO SINGLE-AUTHORITY REPAIR
 *
 * OPERATOR DIRECTIVE (verbatim, Feb 2026):
 *   "The durable trade journal is the economic source of truth for
 *    paper mode. There must be exactly one derivation chain:
 *      Durable Trade Journal → Journal economic replay →
 *      Canonical Paper Economic Snapshot → HeroSnapshotAuthority →
 *      Main/Meme, Markets, Crypto Universe screens.
 *
 *    All three heroes must satisfy:
 *      MEME.cash == MARKETS.cash == CRYPTO.cash
 *      MEME.rev  == MARKETS.rev  == CRYPTO.rev
 *    within the same canonical snapshot revision."
 *
 * This test enforces four invariants at the source-code + runtime level:
 *
 *   1. JournalEconomicAuthority6616 exists with the required API
 *      (currentSnapshot / notifyEconomicMutation / probeHeroBinding /
 *      recordHeroRender / revision) and CanonicalEconomicSnapshot
 *      carries revision + cashSol + equitySol + source.
 *
 *   2. PaperAccountLedger6430 notifies the authority on every economic
 *      mutation (BUY / SELL / ROLLBACK_BUY / PURGE / INITIALIZE) so the
 *      revision is monotonic across the ledger's causal chain.
 *
 *   3. TokenizedStockTrader no longer bypasses the journal via
 *      PaperAccountLedger6430.onSell on the double-refusal fallback —
 *      the PAPER_CLOSE_UNJOURNALED_LEAK_6581 mutation site was replaced
 *      by PAPER_CLOSE_JOURNAL_REFUSED_NO_LEDGER_MUTATION_6616.
 *
 *   4. All three hero surfaces (MainActivity meme hero,
 *      MultiAssetActivity markets hero, CryptoAltActivity crypto hero)
 *      call JournalEconomicAuthority6616.recordHeroRender +
 *      probeHeroBinding so parity is measured at render time.
 */
class Aate6616JournalHeroSingleAuthorityCoverageTest {

    @After
    fun tearDown() {
        com.lifecyclebot.engine.truth.JournalEconomicAuthority6616.resetForTest()
    }

    @Test
    fun aate6616_authority_exists_with_required_api_surface() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicAuthority6616.kt"
        ).readText()
        assertTrue(
            "V5.0.6616: JournalEconomicAuthority6616 object must exist",
            src.contains("object JournalEconomicAuthority6616")
        )
        assertTrue(
            "V5.0.6616: CanonicalEconomicSnapshot must carry revision + cash + equity + source",
            src.contains("data class CanonicalEconomicSnapshot") &&
                src.contains("val revision: Long") &&
                src.contains("val cashSol: Double") &&
                src.contains("val equitySol: Double") &&
                src.contains("val source: String")
        )
        assertTrue(
            "V5.0.6616: authority must expose notifyEconomicMutation / currentSnapshot / probeHeroBinding / recordHeroRender / revision",
            src.contains("fun notifyEconomicMutation(kind: String)") &&
                src.contains("fun currentSnapshot(): CanonicalEconomicSnapshot?") &&
                src.contains("fun probeHeroBinding(") &&
                src.contains("fun recordHeroRender(") &&
                src.contains("fun revision(): Long")
        )
        assertTrue(
            "V5.0.6616: parity probe must emit HERO_JOURNAL_PARITY_OK_6616 / FAIL_6616",
            src.contains("HERO_JOURNAL_PARITY_OK_6616") &&
                src.contains("HERO_JOURNAL_PARITY_FAIL_6616")
        )
        assertTrue(
            "V5.0.6616: hero render must emit HERO_BALANCE_RENDER_6616",
            src.contains("HERO_BALANCE_RENDER_6616")
        )
    }

    @Test
    fun aate6616_notify_mutation_increments_revision_monotonically() {
        val auth = com.lifecyclebot.engine.truth.JournalEconomicAuthority6616
        assertNull("V5.0.6616: cold state must not fabricate a snapshot", auth.currentSnapshot())
        auth.notifyEconomicMutation("BUY")
        val r1 = auth.revision()
        assertTrue("V5.0.6616: revision must advance after first mutation (got $r1)", r1 >= 1L)
        auth.notifyEconomicMutation("SELL")
        val r2 = auth.revision()
        assertTrue("V5.0.6616: revision must be monotonic (r1=$r1 r2=$r2)", r2 > r1)
        assertNotNull("V5.0.6616: currentSnapshot must be published after mutation", auth.currentSnapshot())
    }

    @Test
    fun aate6616_probe_hero_binding_flags_divergence() {
        val auth = com.lifecyclebot.engine.truth.JournalEconomicAuthority6616
        auth.notifyEconomicMutation("SELL")
        val snap = auth.currentSnapshot()
        assertNotNull(snap)
        // Matching cash+equity => OK.
        val ok = auth.probeHeroBinding("MEME", snap!!.cashSol, snap.equitySol)
        assertTrue("V5.0.6616: matched cash+equity must be accepted", ok)
        // Diverged cash => FAIL.
        val bad = auth.probeHeroBinding("MARKETS", snap.cashSol + 1.0, snap.equitySol)
        assertTrue("V5.0.6616: cash divergence must be flagged (got $bad)", !bad)
    }

    @Test
    fun aate6616_paper_ledger_notifies_authority_on_every_mutation() {
        val ledger = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt"
        ).readText()
        assertTrue(
            "V5.0.6616: PaperAccountLedger6430.onBuy must notify BUY mutation",
            ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"BUY\")")
        )
        assertTrue(
            "V5.0.6616: PaperAccountLedger6430.onSell must notify SELL mutation",
            ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"SELL\")")
        )
        assertTrue(
            "V5.0.6616: PaperAccountLedger6430.rollbackBuy must notify ROLLBACK_BUY mutation",
            ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"ROLLBACK_BUY\")")
        )
        assertTrue(
            "V5.0.6616: PaperAccountLedger6430.onPositionPurged must notify PURGE mutation",
            ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"PURGE\")")
        )
        assertTrue(
            "V5.0.6616: PaperAccountLedger6430.initialize must notify INITIALIZE mutation",
            ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\"INITIALIZE\")")
        )
        assertTrue(
            "V5.0.6616: durable restore path must forcePublish to attach UI to real balance on cold start",
            ledger.contains("JournalEconomicAuthority6616.forcePublish(\"TRADE_JOURNAL_REPLAY_RESTORE_6487\")")
        )
    }

    @Test
    fun aate6616_tokenized_stock_trader_no_longer_bypasses_journal() {
        val trader = java.io.File(
            "src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt"
        ).readText()
        // The double-refusal fallback MUST NOT contain a live
        // PaperAccountLedger6430.onSell call anymore — that was the
        // 30-leak source. Comments referencing it (for historical
        // context) are allowed only when preceded by "//".
        val liveOnSell = trader
            .lineSequence()
            .filter { !it.trimStart().startsWith("//") }
            .any { it.contains("PaperAccountLedger6430.onSell(") }
        assertTrue(
            "V5.0.6616: TokenizedStockTrader must not execute PaperAccountLedger6430.onSell(...) as a fallback (journal is authority)",
            !liveOnSell
        )
        assertTrue(
            "V5.0.6616: TokenizedStockTrader must emit PAPER_CLOSE_JOURNAL_REFUSED_NO_LEDGER_MUTATION_6616 on double refusal",
            trader.contains("PAPER_CLOSE_JOURNAL_REFUSED_NO_LEDGER_MUTATION_6616")
        )
    }

    @Test
    fun aate6616_all_three_heroes_probe_journal_parity_and_render() {
        val meme = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt"
        ).readText()
        val markets = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/MultiAssetActivity.kt"
        ).readText()
        val crypto = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/CryptoAltActivity.kt"
        ).readText()
        assertTrue(
            "V5.0.6616: Meme hero (MainActivity) must recordHeroRender + probeHeroBinding on MEME surface",
            meme.contains("JournalEconomicAuthority6616") &&
                meme.contains(".recordHeroRender(\"MEME\"") &&
                meme.contains(".probeHeroBinding(\"MEME\"")
        )
        assertTrue(
            "V5.0.6616: Markets hero (MultiAssetActivity) must recordHeroRender + probeHeroBinding on MARKETS surface + bind to CASH",
            markets.contains("UnifiedAccountSnapshot6635.read(\"MARKETS\")") &&
                markets.contains(".recordHeroRender(\"MARKETS\"") &&
                markets.contains(".probeHeroBinding(\"MARKETS\"") &&
                markets.contains("unified6635?.cashSol")
        )
        assertTrue(
            "V5.0.6616: Crypto Universe hero (CryptoAltActivity) must recordHeroRender + probeHeroBinding on CRYPTO surface",
            crypto.contains("JournalEconomicAuthority6616") &&
                crypto.contains(".recordHeroRender(\"CRYPTO\"") &&
                crypto.contains(".probeHeroBinding(\"CRYPTO\"")
        )
    }

    @Test
    fun aate6616_hero_snapshot_authority_carries_journal_revision() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/HeroSnapshotAuthority6503.kt"
        ).readText()
        assertTrue(
            "V5.0.6616: Hero data class must expose journalRevision + source",
            src.contains("val journalRevision: Long") &&
                src.contains("val source: String")
        )
        assertTrue(
            "V5.0.6616: HeroSnapshotAuthority must prefer JournalEconomicAuthority6616.currentSnapshot() for cash",
            src.contains("JournalEconomicAuthority6616.currentSnapshot()?.cashSol")
        )
    }

    @Test
    fun aate6616_main_hero_binds_to_cash_not_equity() {
        // Operator directive: "If all three hero cards are intended to
        // mean spendable wallet balance, all three must display the SAME
        // cashSol." The MainActivity headline subtitle explicitly says
        // "PAPER · CASH", so the big number MUST bind to journal cashSol.
        val meme = java.io.File(
            "src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt"
        ).readText()
        assertTrue(
            "V5.0.6643: MainActivity hero must bind to reconciled unified cash",
            meme.contains("UnifiedAccountSnapshot6635.read(\"MEME\")") &&
                meme.contains("unifiedSnap6635?.cashSol") && meme.contains("ACCOUNTING ERROR")
        )
    }

    @Test
    fun aate6616_bumped_wakelock_and_exit_lane_land_on_same_patch() {
        val bot = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/BotService.kt"
        ).readText()
        val shit = java.io.File(
            "src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt"
        ).readText()
        assertTrue(
            "V5.0.6616: WakeLock/Doze diagnostic must be tagged V5.0.6616",
            bot.contains("V5.0.6616 §BACKGROUND_DOZE_EXEMPTION_AUDIT") &&
                bot.contains("BACKGROUND_DOZE_RISK_NOT_WHITELISTED_6616")
        )
        assertTrue(
            "V5.0.6616: ShitCoin immutable exit-lane repair must be tagged V5.0.6616",
            shit.contains("V5.0.6616 §IMMUTABLE_ENTRY_LANE_EXIT_REASON")
        )
        assertTrue(
            "V5.0.6616: BotService mirror exit-lane repair must be tagged V5.0.6616",
            bot.contains("V5.0.6616 §IMMUTABLE_ENTRY_LANE_EXIT_REASON")
        )
    }
}

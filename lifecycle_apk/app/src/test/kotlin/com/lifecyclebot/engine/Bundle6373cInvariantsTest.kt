package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6373c — Ghost Paper Purge Neutralized (regression fix vs V5.0.6366 F3).
 *
 * Operator directive (verbatim):
 *   "I went back and installed 6364. thats displaying everything fine,
 *    volume is there and winrate and ev is good. address every build
 *    between there and now identify the regressions."
 *
 *   Follow-up:
 *   "the bot seems to be losing track of held tokens? trade volume has
 *    slowed dramatically"
 *
 * Root cause identified: V5.0.6366 F3 ghost purge whitelisted only 5 V3
 * sub-traders (ShitCoin/Moonshot/BlueChip/Quality/CashGen). Paper buys
 * routed as WHALE_FOLLOW / COPYTRADE / PRESALE_SNIPE / MICRO_CAP /
 * TREASURY / CYCLIC / MOMENTUM_SWING / LAB were mis-classified as ghosts
 * and got `ts.position = Position()` reset + PositionPersistence removal +
 * PositionCloseLedger.markClosed. Downstream: held tokens vanish from UI,
 * subsequent sells read a phantom/default `ts.position` and journal
 * `cost=0.0100 qty=4750` rows against real positions ("money is
 * disappearing").
 *
 * Fix: positive-existence predicate. If TradeHistoryStore has a valid
 * recent BUY row for this mint, the position is REAL — never purge.
 * Real orphaned rows (crashed session with no journal entry) still get
 * cleaned. Universal 2x–5x compound target (V5.0.6372) is kept as-is
 * per operator: "thats aate policy! daily wallet growth of 2x - 5x
 * minimum."
 */
class Bundle6373cInvariantsTest {

    @Test
    fun ghost_purge_uses_positive_existence_from_tradehistorystore() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6373c: ghost predicate must consult TradeHistoryStore.getLatestBuyByMintSnapshot for the mint set",
            txt.contains("val recentBuyMintsForGhost6373c") &&
                txt.contains("TradeHistoryStore.getLatestBuyByMintSnapshot().keys"),
        )
        assertTrue(
            "V5.0.6373c: only rows with NO recent BUY across the whole store are treated as orphaned ghosts",
            txt.contains("recentBuyMintsForGhost6373c.isNotEmpty() && ts.mint !in recentBuyMintsForGhost6373c"),
        )
        assertTrue(
            "V5.0.6373c: new ghost label emitted with clear reason",
            txt.contains("PAPER_GHOST_PURGED_6373C_NO_BUY_ROW"),
        )
    }

    @Test
    fun old_v5_0_6366_whitelist_predicate_removed() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // The whitelist-of-5 predicate is the regression. It must be gone.
        assertFalse(
            "V5.0.6373c: V5.0.6366 whitelist-of-5-sub-traders ghost predicate must be removed",
            txt.contains("canonicalOwnedMints != null && ts.mint !in canonicalOwnedMints"),
        )
        assertFalse(
            "V5.0.6373c: PAPER_GHOST_PURGED_6366 label must be gone (use 6373C_NO_BUY_ROW instead)",
            txt.contains("PAPER_GHOST_PURGED_6366"),
        )
    }

    @Test
    fun universal_compound_target_scope_preserved() {
        // Operator: "no thats aate policy! daily wallet growth of 2x - 5x minimum"
        // The V5.0.6372 universal-scope size advisory must NOT be reverted back
        // to the meme-family filter, because 2x–5x daily growth is universal AATE
        // doctrine, not a meme-only optimization.
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/MemeCompoundTarget6256.kt").readText()
        assertFalse(
            "V5.0.6373c: universal compound target scope must remain intact — no meme-only lane filter",
            txt.contains("memeFamily6373c") || txt.contains("if (!memeFamily"),
        )
    }
}

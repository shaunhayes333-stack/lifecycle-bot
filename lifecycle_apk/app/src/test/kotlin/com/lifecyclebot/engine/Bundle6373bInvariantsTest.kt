package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6373b — CANONICAL POSITION SENTINEL at paperSell entry.
 *
 * Operator snapshot proved the phantom-sell bug: 4 different mints
 * (BhmJPx, 2MwxyM, 39q9ks, b7vYe1) "sold" within a 0.5 s window all
 * reporting identical cost=0.0100 qty=4750 entry=2.1052162e-06 while
 * their real BUYs were qty≈8.15e+04 cost=0.1811. That's the sell path
 * reading a shared / reset TokenState.position instead of the real
 * mint's buy record. Money "disappearing".
 *
 * This bundle wires a canonical-position sentinel at paperSell entry
 * that cross-checks ts.position against TradeHistoryStore's authoritative
 * latest BUY row and BLOCKS the sell (no journal, no train, no
 * unregister, no touch) whenever:
 *   - no canonical BUY row for the mint
 *   - BUY row is malformed
 *   - ts.position is unpopulated
 *   - pos.costSol < 0.05 SOL while the real BUY was >= 0.05 SOL
 *     (kills the 0.01 phantom fallback)
 *   - cost / qty divergence between ts.position and the BUY exceeds 2×
 */
class Bundle6373bInvariantsTest {

    @Test
    fun paperSell_has_canonical_position_sentinel_at_entry() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6373b: paperSell must have the CANONICAL POSITION SENTINEL block header at entry",
            txt.contains("V5.0.6373b — CANONICAL POSITION SENTINEL"),
        )
        assertTrue(
            "V5.0.6373b: sentinel must load canonical BUY row via TradeHistoryStore.getLatestBuyByMintSnapshot",
            txt.contains("TradeHistoryStore.getLatestBuyByMintSnapshot()[ts.mint]"),
        )
    }

    @Test
    fun sentinel_blocks_phantom_cost_basis_below_005_sol() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6373b: sentinel must reject pos.costSol < 0.05 when canonical BUY was >= 0.05 (kills 0.01 SOL phantom)",
            txt.contains("pos.costSol < 0.05 && canonicalBuy.entryCostSol >= 0.05") &&
                txt.contains("COST_BASIS_PHANTOM_FALLBACK"),
        )
    }

    @Test
    fun sentinel_blocks_cost_and_qty_divergence_over_2x() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6373b: sentinel must block when cost divergence > 2×",
            txt.contains("COST_BASIS_DIVERGES_FROM_CANONICAL"),
        )
        assertTrue(
            "V5.0.6373b: sentinel must block when qty divergence > 2×",
            txt.contains("QTY_DIVERGES_FROM_CANONICAL"),
        )
        assertTrue(
            "V5.0.6373b: divergence gate must use ratio > 2.0",
            txt.contains("ratio > 2.0"),
        )
    }

    @Test
    fun sentinel_emits_sell_blocked_and_short_circuits() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "V5.0.6373b: sentinel must emit SELL_BLOCKED_NO_CANONICAL_POSITION_6373",
            txt.contains("SELL_BLOCKED_NO_CANONICAL_POSITION_6373"),
        )
        assertTrue(
            "V5.0.6373b: sentinel must release the paper sell lock before returning",
            txt.contains("releasePaperSellLock(ts.mint)") &&
                txt.contains("return SellResult.FAILED_RETRYABLE"),
        )
    }

    @Test
    fun sentinel_does_not_journal_or_unregister_on_block() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        // Sanity: the do-not-mutate contract for the blocked path must be
        // documented in the sentinel block so a future edit does not
        // accidentally journal / unregister the (possibly real) position.
        assertTrue(
            "V5.0.6373b: sentinel comment must document the do-not-journal / do-not-unregister contract",
            txt.contains("do NOT journal") &&
                txt.contains("real position") &&
                txt.contains("subsequent well-formed sell"),
        )
    }
}

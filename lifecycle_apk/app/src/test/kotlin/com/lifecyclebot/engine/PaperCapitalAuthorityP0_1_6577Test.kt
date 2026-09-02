package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.PaperCapitalAuthority6577
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6577 §P0-1 — ONE canonical paper capital authority.
 *
 * Operator directive:
 *   "MEME can stop opening positions from low available funds after
 *    Markets/Crypto consume capital, while Meme UI still displays funds."
 *   "PAPER_UI_CASH_DIVERGENCE  target = 0"
 *
 * Regressions pinned:
 *   1. PaperCapitalAuthority6577 exists (facade over PaperAccountLedger6430).
 *   2. probeUiCash emits the divergence counter when uiCashSol drifts.
 *   3. ShitCoinTraderAI.getBalance routes paper-mode reads through the
 *      canonical authority (source-string).
 *   4. CryptoAltTrader.getEffectiveBalance routes paper-mode reads through
 *      the canonical authority (source-string).
 *   5. MultiAssetActivity displays paper equity from the authority snapshot,
 *      not from a lane-specific PnL sum (source-string).
 */
class PaperCapitalAuthorityP0_1_6577Test {

    private val shitSrc = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ShitCoinTraderAI.kt").readText()
    private val cryptoAltSrc = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
    private val multiAssetSrc = File("src/main/kotlin/com/lifecyclebot/ui/MultiAssetActivity.kt").readText()

    @Before
    fun reset() {
        PaperCapitalAuthority6577.resetInvariantsForTest()
    }

    @Test
    fun snapshot_exposes_shared_account() {
        val s = PaperCapitalAuthority6577.snapshot()
        assertEquals("aate.paper.default", s.accountId)
        assertTrue("equity = cash + open", kotlin.math.abs(s.totalEquitySol - (s.availableCashSol + s.openMarketValueSol)) < 1e-9)
    }

    @Test
    fun probe_ui_cash_flags_divergence() {
        // Any UI that computes a cash figure diverging from the ledger by
        // more than the tolerance must increment PAPER_UI_CASH_DIVERGENCE.
        val (uiBefore, _, _) = PaperCapitalAuthority6577.invariantCounts()
        val agree = PaperCapitalAuthority6577.probeUiCash(
            surface = "TEST_AGREE",
            uiCashSol = PaperCapitalAuthority6577.availableCashSol(),
        )
        assertTrue("Matching UI cash should NOT flag divergence", agree)
        val diverge = PaperCapitalAuthority6577.probeUiCash(
            surface = "TEST_DIVERGE",
            uiCashSol = PaperCapitalAuthority6577.availableCashSol() + 5.0,
        )
        assertTrue("Non-matching UI cash MUST flag divergence", !diverge)
        val (uiAfter, _, _) = PaperCapitalAuthority6577.invariantCounts()
        assertEquals(uiBefore + 1L, uiAfter)
    }

    @Test
    fun probe_debit_without_reservation_counts() {
        val (_, before, _) = PaperCapitalAuthority6577.invariantCounts()
        PaperCapitalAuthority6577.probeDebitReservation("pid-1", reserved = false)
        val (_, after, _) = PaperCapitalAuthority6577.invariantCounts()
        assertEquals(before + 1L, after)
    }

    @Test
    fun shitcoin_lane_reads_shared_authority() {
        assertTrue(
            "ShitCoinTraderAI.getBalance must route paper mode through PaperCapitalAuthority6577",
            shitSrc.contains("PaperCapitalAuthority6577.availableCashSol()")
        )
        // Ensure the pre-6577 lane-local paperBalanceBps fallback is only a
        // catch-block fallback, not the primary read.
        assertTrue(
            "ShitCoinTraderAI.getBalance must fall through catch-only to the legacy accumulator",
            shitSrc.contains("catch (_: Throwable) { paperBalanceBps.get() / 100.0 }")
        )
    }

    @Test
    fun crypto_alt_lane_reads_shared_authority() {
        assertTrue(
            "CryptoAltTrader.getEffectiveBalance must route paper mode through PaperCapitalAuthority6577",
            cryptoAltSrc.contains("PaperCapitalAuthority6577.availableCashSol()")
        )
    }

    @Test
    fun multi_asset_ui_reads_shared_snapshot_and_probes_divergence() {
        assertTrue(
            "MultiAssetActivity must derive displayed paper equity from the unified atomic snapshot",
            multiAssetSrc.contains("UnifiedAccountSnapshot6635.read(\"MARKETS\")")
        )
        assertTrue(
            "MultiAssetActivity must withhold money when unified accounting is not reconciled",
            multiAssetSrc.contains("UnifiedAccountSnapshot6635.Status.RECONCILED") &&
                multiAssetSrc.contains("ACCOUNTING ERROR")
        )
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6344 — RealizedPnlConduit tests. The conduit is the single funnel
 * every writer of realized SOL PnL must use. These tests verify:
 *  (1) ledger-backed cost basis wins over caller-supplied fallback,
 *  (2) Cupsey partial allocation math routes through the authority,
 *  (3) LIVE_BROADCAST proof is quarantined and never authoritative,
 *  (4) divergence flag fires when classic inline pnl disagrees.
 */
class RealizedPnlConduit6344Test {

    @Before
    fun setup() {
        FillLotLedger6344.resetForTest()
    }

    @Test
    fun ledger_backed_full_close_returns_canonical_realized() {
        FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            entryCostSol = SolAmount.of(0.010),
            entryQty = TokenQuantity.of(100.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.0001),
            entryPriceUsdPerToken = PriceUsdPerToken.of(0.02),
            decimals = 6, laneCanonical = "STANDARD", entryTsMs = 0L,
        )
        val r = RealizedPnlConduit6344.finalize(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            sellTxSig = "S1",
            soldQty = TokenQuantity.of(100.0),
            proceedsSol = SolAmount.of(0.015),
            feeSol = SolAmount.of(0.0001),
            proofSource = "LIVE_FINALIZED",
        )
        assertTrue("canonical", r.canonical)
        assertEquals(0.010, r.allocatedEntryCostSol, 1e-9)
        assertEquals(0.015 - 0.010 - 0.0001, r.realizedSol, 1e-9)
        assertTrue("terminal", r.terminal)
    }

    @Test
    fun partial_close_allocates_proportional_cost_basis() {
        FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            entryCostSol = SolAmount.of(0.010),
            entryQty = TokenQuantity.of(100.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.0001),
            entryPriceUsdPerToken = PriceUsdPerToken.of(0.02),
            decimals = 6, laneCanonical = "STANDARD", entryTsMs = 0L,
        )
        val r = RealizedPnlConduit6344.finalize(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            sellTxSig = "S_PARTIAL",
            soldQty = TokenQuantity.of(40.0),
            proceedsSol = SolAmount.of(0.006),
            feeSol = SolAmount.ZERO,
            proofSource = "LIVE_FINALIZED",
        )
        assertTrue(r.canonical)
        assertEquals(0.004, r.allocatedEntryCostSol, 1e-9)
        assertEquals(0.002, r.realizedSol, 1e-9)
        assertFalse(r.terminal)
        assertEquals(60.0, r.remainingQty, 1e-9)
        // Classic path with full-basis subtraction: 0.006 - 0 - 0.010 = -0.004.
        // Canonical (correct): 0.002. Divergence must fire.
        assertTrue("partial-basis divergence must fire", r.diverged)
    }

    @Test
    fun live_broadcast_proof_is_quarantined() {
        FillLotLedger6344.appendBuy(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            entryCostSol = SolAmount.of(0.010),
            entryQty = TokenQuantity.of(100.0),
            entryPriceSolPerToken = PriceSolPerToken.of(0.0001),
            entryPriceUsdPerToken = PriceUsdPerToken.of(0.02),
            decimals = 6, laneCanonical = "STANDARD", entryTsMs = 0L,
        )
        val r = RealizedPnlConduit6344.finalize(
            walletAddress = "W", mintAddress = "M", buyTxSig = "B",
            sellTxSig = "S", soldQty = TokenQuantity.of(100.0),
            proceedsSol = SolAmount.of(0.015), feeSol = SolAmount.ZERO,
            proofSource = "LIVE_BROADCAST",
        )
        assertFalse(r.canonical)
        assertEquals(0.0, r.realizedSol, 1e-12)
        assertTrue(r.quarantineReasons.any { it.contains("BROADCAST") })
    }

    @Test
    fun fallback_basis_is_used_when_no_ledger_lot_exists() {
        val r = RealizedPnlConduit6344.finalize(
            walletAddress = "W", mintAddress = "M", buyTxSig = "",
            sellTxSig = "S", soldQty = TokenQuantity.of(100.0),
            proceedsSol = SolAmount.of(0.015), feeSol = SolAmount.ZERO,
            proofSource = "LIVE_FINALIZED",
            fallbackEntryCostSol = SolAmount.of(0.010),
            fallbackEntryQty = TokenQuantity.of(100.0),
            fallbackEntryPriceSol = PriceSolPerToken.of(0.0001),
            fallbackTokenDecimals = 6,
        )
        assertTrue(r.canonical)
        assertEquals(0.010, r.allocatedEntryCostSol, 1e-9)
        assertEquals(0.005, r.realizedSol, 1e-9)
    }
}

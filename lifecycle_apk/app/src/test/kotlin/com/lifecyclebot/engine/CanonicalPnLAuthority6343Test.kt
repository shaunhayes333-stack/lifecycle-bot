package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.6343 — Cupsey PnL invariant test suite.
 * Every clause from the operator's Cupsey partial-lot correction directive
 * has a dedicated assertion. If any of these regresses, the CI build fails.
 */
class CanonicalPnLAuthority6343Test {

    @Test
    fun clause3_realized_sol_is_proceeds_minus_allocated_minus_fee() {
        // Buy 100 tokens for 0.010 SOL. Sell all 100 for 0.015 SOL, fee 0.0001.
        val r = CanonicalPnLAuthority6343.computeRealizedSol(
            originalEntryCostSol = 0.010,
            originalEntryQty = 100.0,
            entryPriceSolPerToken = 0.0001,
            soldQty = 100.0,
            proceedsReceivedSol = 0.015,
            sellFeeSol = 0.0001,
            cumulativeSoldQty = 100.0,
            tokenDecimals = 6,
            proofSource = "LIVE_FINALIZED",
        )
        assertTrue("must be canonical: ${r.quarantineReasons}", r.isCanonical)
        assertEquals(0.010, r.allocatedEntryCostSol, 1e-9)
        assertEquals(0.015 - 0.010 - 0.0001, r.realizedSol, 1e-9)
        assertTrue("must be terminal", r.terminal)
    }

    @Test
    fun clause4_and_5_partial_sell_allocates_proportional_cost_not_full_basis() {
        // Buy 100 tokens for 0.010 SOL. Sell 40 tokens for 0.006 SOL.
        // Allocated cost = 0.010 × 40/100 = 0.004. Realized = 0.006 - 0.004 = 0.002.
        val r = CanonicalPnLAuthority6343.computeRealizedSol(
            originalEntryCostSol = 0.010,
            originalEntryQty = 100.0,
            entryPriceSolPerToken = 0.0001,
            soldQty = 40.0,
            proceedsReceivedSol = 0.006,
            sellFeeSol = 0.0,
            cumulativeSoldQty = 40.0,
            tokenDecimals = 6,
            proofSource = "LIVE_FINALIZED",
        )
        assertTrue(r.isCanonical)
        assertEquals(0.004, r.allocatedEntryCostSol, 1e-9)
        assertEquals(0.002, r.realizedSol, 1e-9)
        assertFalse("partial must NOT be terminal", r.terminal)
        assertEquals(60.0, r.remainingQty, 1e-9)
    }

    @Test
    fun clause6_terminal_only_when_sum_sold_equals_original() {
        val partial = CanonicalPnLAuthority6343.computeRealizedSol(
            originalEntryCostSol = 0.010, originalEntryQty = 100.0,
            entryPriceSolPerToken = 0.0001, soldQty = 40.0,
            proceedsReceivedSol = 0.005, sellFeeSol = 0.0,
            cumulativeSoldQty = 40.0, tokenDecimals = 6, proofSource = "LIVE_FINALIZED",
        )
        assertFalse(partial.terminal)
        val terminal = CanonicalPnLAuthority6343.computeRealizedSol(
            originalEntryCostSol = 0.010, originalEntryQty = 100.0,
            entryPriceSolPerToken = 0.0001, soldQty = 60.0,
            proceedsReceivedSol = 0.009, sellFeeSol = 0.0,
            cumulativeSoldQty = 100.0, tokenDecimals = 6, proofSource = "LIVE_FINALIZED",
        )
        assertTrue(terminal.terminal)
    }

    @Test
    fun clause7_live_broadcast_is_never_canonical() {
        val r = CanonicalPnLAuthority6343.computeRealizedSol(
            originalEntryCostSol = 0.010, originalEntryQty = 100.0,
            entryPriceSolPerToken = 0.0001, soldQty = 100.0,
            proceedsReceivedSol = 0.015, sellFeeSol = 0.0001,
            cumulativeSoldQty = 100.0, tokenDecimals = 6,
            proofSource = "LIVE_BROADCAST",
        )
        assertFalse("LIVE_BROADCAST must never be canonical", r.isCanonical)
        assertEquals(0.0, r.realizedSol, 1e-12)
        assertTrue(r.quarantineReasons.any { it.contains("BROADCAST") })
    }

    @Test
    fun clause10_cupsey_price_invariant_rejects_76x_mismatch() {
        // Real Cupsey case: 0.0264 SOL / 490.091 tokens = 5.387e-5 SOL/token.
        // A stored value of 4.088e-3 (76× off) MUST be rejected.
        val diag = CanonicalPnLAuthority6343.assertPriceCostQtyParity(
            entryCostSol = 0.0264,
            entryQty = 490.091,
            entryPriceSolPerToken = 4.088e-3,
        )
        assertNotNull("Cupsey 76× mismatch must trip the invariant", diag)
        assertTrue(diag!!.contains("PARITY_FAIL"))

        val r = CanonicalPnLAuthority6343.computeRealizedSol(
            originalEntryCostSol = 0.0264,
            originalEntryQty = 490.091,
            entryPriceSolPerToken = 4.088e-3,       // corrupt stored price
            soldQty = 490.091,
            proceedsReceivedSol = 0.030,
            sellFeeSol = 0.0,
            cumulativeSoldQty = 490.091,
            tokenDecimals = 6,
            proofSource = "LIVE_FINALIZED",
        )
        assertFalse("Cupsey row must be quarantined", r.isCanonical)
        assertTrue(r.quarantineReasons.any { it.contains("PRICE_COST_QTY_INVARIANT_FAILURE_6343") })
    }

    @Test
    fun clause9_correct_price_invariant_passes() {
        val diag = CanonicalPnLAuthority6343.assertPriceCostQtyParity(
            entryCostSol = 0.0264,
            entryQty = 490.091,
            entryPriceSolPerToken = 5.387e-5,   // correct derived value
        )
        assertNull("Correctly-derived price must pass invariant", diag)
    }
}

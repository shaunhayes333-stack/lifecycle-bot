package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.6346 — Canonical Learning Contract invariant test suite.
 * Verifies the eligibility invariants operator P0-4 spec requires
 * before a row can enter tactic switcher / personality tune / governor
 * stats / concentrator sample / expectancy classifier.
 */
class CanonicalLearningContract6346Test {

    private fun sellRow(
        entryCost: Double, entryQty: Double, entryPx: Double,
        soldQty: Double = entryQty,
    ) = Trade(
        side = "SELL", mode = "live",
        sol = 0.010, price = 0.00012, ts = 0L,
        pnlSol = 0.002, pnlPct = 20.0, reason = "TP",
        entryPriceSnapshot = entryPx,
        entryQtyToken = entryQty,
        entryCostSol = entryCost,
        soldQtyToken = soldQty,
    )

    @Test
    fun canonical_when_finalized_and_all_invariants_hold() {
        val t = sellRow(entryCost = 0.010, entryQty = 100.0, entryPx = 0.0001)
        val v = CanonicalLearningContract6346.assess(
            trade = t, proofSource = "LIVE_FINALIZED", tokenDecimals = 6,
        )
        assertTrue("must be canonical: ${v.reasons}", v.isCanonical)
    }

    @Test
    fun live_broadcast_is_never_canonical() {
        val t = sellRow(entryCost = 0.010, entryQty = 100.0, entryPx = 0.0001)
        val v = CanonicalLearningContract6346.assess(
            trade = t, proofSource = "LIVE_BROADCAST", tokenDecimals = 6,
        )
        assertFalse(v.isCanonical)
        assertTrue(v.reasons.any { it.contains("BROADCAST") })
    }

    @Test
    fun unknown_proof_source_is_never_canonical() {
        val t = sellRow(entryCost = 0.010, entryQty = 100.0, entryPx = 0.0001)
        val v = CanonicalLearningContract6346.assess(
            trade = t, proofSource = "", tokenDecimals = 6,
        )
        assertFalse(v.isCanonical)
        assertTrue(v.reasons.any { it.contains("PROOF_SOURCE_UNKNOWN") })
    }

    @Test
    fun cost_basis_parity_failure_quarantines() {
        // Cupsey case: cost / qty ≈ 5.387e-5 but stored price 4.088e-3 (76×).
        val t = sellRow(entryCost = 0.0264, entryQty = 490.091, entryPx = 4.088e-3)
        val v = CanonicalLearningContract6346.assess(
            trade = t, proofSource = "LIVE_FINALIZED", tokenDecimals = 6,
        )
        assertFalse(v.isCanonical)
        assertTrue(v.reasons.any { it.contains("COST_BASIS_PARITY_FAIL") })
    }

    @Test
    fun missing_cost_basis_on_sell_quarantines() {
        val t = Trade(
            side = "SELL", mode = "live", sol = 0.010, price = 0.00012, ts = 0L,
            pnlSol = 0.001, pnlPct = 10.0,
            entryPriceSnapshot = 0.0, entryCostSol = 0.0, entryQtyToken = 0.0,
        )
        val v = CanonicalLearningContract6346.assess(
            trade = t, proofSource = "LIVE_FINALIZED", tokenDecimals = 6,
        )
        assertFalse(v.isCanonical)
        assertTrue(v.reasons.any { it.contains("MISSING_ENTRY_COST_BASIS") })
    }

    @Test
    fun paper_proof_is_admitted_when_all_invariants_hold() {
        val t = sellRow(entryCost = 0.010, entryQty = 100.0, entryPx = 0.0001)
        val v = CanonicalLearningContract6346.assess(
            trade = t, proofSource = "PAPER", tokenDecimals = 6,
        )
        assertTrue(v.isCanonical)
    }
}

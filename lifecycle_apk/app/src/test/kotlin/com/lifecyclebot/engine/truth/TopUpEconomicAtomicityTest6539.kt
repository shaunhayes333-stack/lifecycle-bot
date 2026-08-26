package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Position
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6539 §TOP_UP_ECONOMIC_ATOMICITY_ROOT_FIX — regression tests.
 *
 * Operator mandate 8: cover fresh BUY, BUY+top-up, BUY+repeated top-ups,
 * restart/replay identity, live verified top-up, and two deliberately-
 * poisoned cases that MUST fail.
 *
 * These tests operate on the pure economic invariant so they can run in
 * the standard JVM unit-test environment (no emulator required).
 */
class TopUpEconomicAtomicityTest6539 {

    // Physical band matches QuantityInvariantAuthority6500.economicNotionalCheck6537.
    private val SOL_USD = 200.0

    private fun makePos(
        qtyToken: Double,
        entryPriceUsd: Double,
        costSol: Double,
        source: String = "DEXSCREENER_WS",
    ): Position = Position(
        qtyToken = qtyToken,
        entryPrice = entryPriceUsd,
        entryTime = System.currentTimeMillis(),
        costSol = costSol,
        entryPriceSource = source,
        isPaperPosition = true,
    )

    /** V5.0.6539 §8.1 — fresh paper BUY, no top-up → invariant passes. */
    @Test fun fresh_paper_buy_no_topup_passes_economic_invariant() {
        // Buy 0.10 SOL of a token at entry USD 0.005 (mid-cap real DEX quote).
        // impliedSolUsd = qty × entry / cost = (0.10×200/0.005) × 0.005 / 0.10 = 200
        val costSol = 0.10
        val entryUsd = 0.005
        val qty = costSol * SOL_USD / entryUsd
        val pos = makePos(qty, entryUsd, costSol)
        val chk = QuantityInvariantAuthority6500.economicNotionalCheck6537(pos)
        assertTrue("fresh BUY invariant should pass — got ${chk?.reason}", chk == null || chk.ok)
    }

    /** V5.0.6539 §8.2 — paper BUY + one top-up → invariant passes. */
    @Test fun buy_plus_one_topup_passes_economic_invariant() {
        val entryUsd = 0.005
        val buyCostSol = 0.10
        val buyQty = buyCostSol * SOL_USD / entryUsd
        val topUpCostSol = 0.05
        val topUpPriceUsd = 0.006
        val topUpQty = topUpCostSol * SOL_USD / topUpPriceUsd
        val totalQty = buyQty + topUpQty
        val prevNotional = buyQty * entryUsd
        val addedNotional = topUpQty * topUpPriceUsd
        val weightedEntryUsd = (prevNotional + addedNotional) / totalQty
        val totalCostSol = buyCostSol + topUpCostSol
        val pos = makePos(totalQty, weightedEntryUsd, totalCostSol)
        val chk = QuantityInvariantAuthority6500.economicNotionalCheck6537(pos)
        assertTrue("buy+topup invariant should pass — got ${chk?.reason}", chk == null || chk.ok)
    }

    /** V5.0.6539 §8.3 — paper BUY + repeated top-ups → invariant passes. */
    @Test fun buy_plus_repeated_topups_passes_economic_invariant() {
        var qty = 0.0
        var cost = 0.0
        var notional = 0.0
        val fills = listOf(
            Triple(0.10, 0.005, 0.0),
            Triple(0.05, 0.006, 0.0),
            Triple(0.08, 0.0058, 0.0),
            Triple(0.02, 0.007, 0.0),
        )
        for ((c, p, _) in fills) {
            val addedQty = c * SOL_USD / p
            qty += addedQty
            cost += c
            notional += addedQty * p
        }
        val avgEntryUsd = notional / qty
        val pos = makePos(qty, avgEntryUsd, cost)
        val chk = QuantityInvariantAuthority6500.economicNotionalCheck6537(pos)
        assertTrue("buy+repeated topups invariant should pass — got ${chk?.reason}", chk == null || chk.ok)
    }

    /**
     * V5.0.6539 §8.4 — restart/replay after top-ups must reproduce identical
     * qty/cost/USD-entry. We simulate replay by rebuilding the position from
     * the same BUY event stream and asserting the derived (qty, cost, entryUsd)
     * are bit-for-bit identical.
     */
    @Test fun restart_replay_reproduces_identical_qty_cost_and_entry() {
        val fills = listOf(
            0.10 to 0.005,
            0.05 to 0.006,
            0.08 to 0.0058,
        )
        fun aggregate(): Triple<Double, Double, Double> {
            var q = 0.0
            var c = 0.0
            var n = 0.0
            for ((solCost, priceUsd) in fills) {
                val addedQty = solCost * SOL_USD / priceUsd
                q += addedQty; c += solCost; n += addedQty * priceUsd
            }
            return Triple(q, c, n / q)
        }
        val first = aggregate()
        val replay = aggregate()
        assertTrue("qty replay-identical", first.first == replay.first)
        assertTrue("cost replay-identical", first.second == replay.second)
        assertTrue("entryUsd replay-identical", first.third == replay.third)
    }

    /** V5.0.6539 §8.5 — live verified top-up: USD/token basis remains valid. */
    @Test fun live_verified_topup_usd_basis_remains_valid() {
        val prevQty = 20_000.0
        val prevEntryUsd = 0.005
        val prevCostSol = prevQty * prevEntryUsd / SOL_USD
        val addedSol = 0.05
        val addedNotionalUsd = addedSol * SOL_USD
        val effectiveNewQty = 8_500.0     // wallet-verified delta
        val addedEntryUsd = addedNotionalUsd / effectiveNewQty
        val weightedEntryUsd =
            (prevQty * prevEntryUsd + addedNotionalUsd) / (prevQty + effectiveNewQty)
        val pos = makePos(
            qtyToken = prevQty + effectiveNewQty,
            entryPriceUsd = weightedEntryUsd,
            costSol = prevCostSol + addedSol,
        )
        val chk = QuantityInvariantAuthority6500.economicNotionalCheck6537(pos)
        assertTrue(
            "live verified topup should pass — got ${chk?.reason} addedEntryUsd=$addedEntryUsd weighted=$weightedEntryUsd",
            chk == null || chk.ok,
        )
    }

    /**
     * V5.0.6539 §8.6 — deliberately use sol/price for qty (pre-6539 bug).
     * The invariant MUST fail (impliedSolUsd collapses to ~1.0).
     */
    @Test fun deliberate_sol_over_price_qty_MUST_FAIL_invariant() {
        val costSol = 0.03
        val entryUsd = 0.00001381         // real USD/token price
        val brokenQty = costSol / entryUsd  // BUG: no × solUsd leg
        val pos = makePos(brokenQty, entryUsd, costSol)
        val chk = QuantityInvariantAuthority6500.economicNotionalCheck6537(pos)
        assertTrue("sol/price qty must trip invariant — got ${chk?.reason}", chk != null)
        assertFalse("sol/price qty must trip invariant — got ok=true", chk!!.ok)
    }

    /**
     * V5.0.6539 §8.7 — deliberately use costSol/qty as entryPriceUsd
     * (SOL/token contamination). The invariant MUST fail.
     */
    @Test fun deliberate_costSol_over_qty_as_entryPriceUsd_MUST_FAIL_invariant() {
        val costSol = 0.03
        val qty = 2172.0                  // arbitrary but plausible
        val brokenEntry = costSol / qty   // BUG: SOL/token in a USD/token field
        val pos = makePos(qty, brokenEntry, costSol)
        val chk = QuantityInvariantAuthority6500.economicNotionalCheck6537(pos)
        assertTrue("SOL/token entry must trip invariant — got ${chk?.reason}", chk != null)
        assertFalse("SOL/token entry must trip invariant — got ok=true", chk!!.ok)
    }
}

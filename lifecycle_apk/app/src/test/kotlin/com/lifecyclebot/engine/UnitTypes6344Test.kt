package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.6344 — Strong unit type invariant test suite.
 * Verifies nominal typing plus explicit-conversion arithmetic per operator P0-3 spec.
 */
class UnitTypes6344Test {

    @Test
    fun sol_plus_and_minus_stay_in_sol_space() {
        val a = SolAmount.of(1.5)
        val b = SolAmount.of(0.5)
        assertEquals(2.0, (a + b).unwrap(), 1e-12)
        assertEquals(1.0, (a - b).unwrap(), 1e-12)
    }

    @Test
    fun non_finite_sol_amount_is_sanitised_to_zero() {
        assertEquals(0.0, SolAmount.of(Double.NaN).unwrap(), 1e-12)
        assertEquals(0.0, SolAmount.of(Double.POSITIVE_INFINITY).unwrap(), 1e-12)
        assertEquals(0.0, SolAmount.of(Double.NEGATIVE_INFINITY).unwrap(), 1e-12)
    }

    @Test
    fun explicit_conversion_sol_to_usd_uses_supplied_price() {
        val sol = SolAmount.of(2.0)
        val usd = sol.toUsd(solPriceUsd = 150.0)
        assertEquals(300.0, usd.unwrap(), 1e-12)
    }

    @Test
    fun explicit_conversion_usd_to_sol_uses_supplied_price() {
        val usd = UsdAmount.of(300.0)
        val sol = usd.toSol(solPriceUsd = 150.0)
        assertEquals(2.0, sol.unwrap(), 1e-12)
    }

    @Test
    fun invalid_conversion_price_returns_zero_not_infinity() {
        val sol = SolAmount.of(1.0)
        assertEquals(0.0, sol.toUsd(solPriceUsd = 0.0).unwrap(), 1e-12)
        assertEquals(0.0, sol.toUsd(solPriceUsd = Double.NaN).unwrap(), 1e-12)
        val usd = UsdAmount.of(1.0)
        assertEquals(0.0, usd.toSol(solPriceUsd = 0.0).unwrap(), 1e-12)
    }

    @Test
    fun price_sol_per_token_derives_from_cost_over_qty() {
        val cost = SolAmount.of(0.0264)
        val qty = TokenQuantity.of(490.091)
        val derived = PriceSolPerToken.derive(cost, qty).unwrap()
        assertEquals(0.0264 / 490.091, derived, 1e-12)
    }

    @Test
    fun price_sol_per_token_cost_of_gives_back_original_cost() {
        val px = PriceSolPerToken.of(0.0001)
        val qty = TokenQuantity.of(100.0)
        assertEquals(0.010, px.costOf(qty).unwrap(), 1e-12)
    }

    @Test
    fun price_conversion_between_sol_and_usd_is_symmetric() {
        val pxSol = PriceSolPerToken.of(5.387e-5)
        val pxUsd = pxSol.toUsdPrice(solPriceUsd = 150.0)
        val roundTrip = pxUsd.toSolPrice(solPriceUsd = 150.0)
        assertEquals(pxSol.unwrap(), roundTrip.unwrap(), 1e-18)
    }

    @Test
    fun token_quantity_plus_minus_stay_in_token_space() {
        val a = TokenQuantity.of(100.0)
        val b = TokenQuantity.of(40.0)
        assertEquals(140.0, (a + b).unwrap(), 1e-12)
        assertEquals(60.0, (a - b).unwrap(), 1e-12)
    }
}

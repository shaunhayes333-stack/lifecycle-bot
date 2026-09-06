package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalMarkPurpose6570
import com.lifecyclebot.engine.truth.CanonicalPriceMarkRegistry6522
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6681 — non-regression locks for the price-truth lift.
 *
 * Contract:
 *  1. synthetic Pump.fun mcap/supply prices remain available as observations,
 *     so discovery/scoring volume is not choked;
 *  2. those synthetic units can never become executable entry truth;
 *  3. a real route-priced DEX observation still promotes normally;
 *  4. legacy synthetic->real extreme basis flips are held instead of being
 *     trained/exited as fake -90% losses;
 *  5. genuine same-source DEX collapses still pass through as real losses;
 *  6. canonical basis rebase explicitly restores comparability.
 */
class Aate6681PriceTruthLiftRegressionTest {

    @Test
    fun `synthetic pump observation remains scoreable but is not executable`() {
        CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "Mint6681Synthetic111111111111111111111111111"
        val now = System.currentTimeMillis()

        val result = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            mint = mint,
            observedBaseMint = mint,
            pairOrPool = "",
            quoteMint = "USD",
            source = "PUMP_FUN_BC_SYNTHETIC",
            priceUsd = 0.000055,
            liquidityUsd = 5_500.0,
            evidenceTimestampMs = now,
            nowMs = now,
        )

        assertFalse(result.promoted)
        assertEquals("SYNTHETIC_PRICE_UNIT_NOT_EXECUTABLE_6681", result.reason)
        assertNotNull(CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.OBSERVATION_SCORING))
        assertNull(CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE))
    }

    @Test
    fun `real dexscreener route price still promotes executable`() {
        CanonicalPriceMarkRegistry6522.resetForTest()
        val mint = "Mint6681Dex1111111111111111111111111111111"
        val now = System.currentTimeMillis()

        val result = CanonicalPriceMarkRegistry6522.resolveExecutableFromSourceEvidence6616(
            mint = mint,
            observedBaseMint = mint,
            pairOrPool = "",
            quoteMint = "USD",
            source = "DEXSCREENER_PAIR_POLL",
            priceUsd = 0.0000035,
            liquidityUsd = 5_500.0,
            evidenceTimestampMs = now,
            nowMs = now,
        )

        assertTrue(result.promoted)
        assertNotNull(CanonicalPriceMarkRegistry6522.get(mint, CanonicalMarkPurpose6570.EXECUTABLE_ENTRY_QUOTE))
    }

    @Test
    fun `synthetic to dex extreme downward basis flip is rejected`() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000056640836,
            currentPrice = 0.000003495,
            entrySource = "PUMP_FUN_BC_SYNTHETIC",
            currentSource = "DEXSCREENER_PAIR_POLL",
            entryPool = "MINT_ROUTE:Mint6681",
            currentPool = "MINT_ROUTE:Mint6681",
            priceBasisRescaled = false,
            emit = false,
        )
        assertFalse(verdict.ok)
        assertEquals("PRICE_BASIS_UNTRUSTED_SYNTHETIC_TRANSITION_6681", verdict.reason)
    }

    @Test
    fun `real same-source dex catastrophic loss is not hidden`() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000056640836,
            currentPrice = 0.000003495,
            entrySource = "DEXSCREENER_PAIR_POLL",
            currentSource = "DEXSCREENER_PAIR_POLL",
            entryPool = "raydium-real-pair",
            currentPool = "raydium-real-pair",
            priceBasisRescaled = false,
            emit = false,
        )
        assertTrue(verdict.ok)
        assertTrue(verdict.pnlPct < -90.0)
    }

    @Test
    fun `ordinary synthetic source transition is not overblocked`() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000010,
            currentPrice = 0.000008,
            entrySource = "PUMP_FUN_BC_SYNTHETIC",
            currentSource = "DEXSCREENER_PAIR_POLL",
            entryPool = "MINT_ROUTE:Mint6681",
            currentPool = "MINT_ROUTE:Mint6681",
            priceBasisRescaled = false,
            emit = false,
        )
        assertTrue(verdict.ok)
    }

    @Test
    fun `canonical rebase restores comparability even after large transition`() {
        val verdict = OpenPnlSanity.inspect(
            entryPrice = 0.000056640836,
            currentPrice = 0.000003495,
            entrySource = "PUMP_FUN_BC_SYNTHETIC",
            currentSource = "DEXSCREENER_PAIR_POLL",
            entryPool = "MINT_ROUTE:Mint6681",
            currentPool = "MINT_ROUTE:Mint6681",
            priceBasisRescaled = true,
            emit = false,
        )
        assertTrue(verdict.ok)
    }
}

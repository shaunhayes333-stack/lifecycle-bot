package com.lifecyclebot.engine

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.7110 — the standard pump.fun mint must resolve to 1e9 whole tokens.
 *
 * V5.0.7017 fixed a 1e6 unit error by trying decimals most-scaled first and
 * returning the first plausible reading. For raw total_supply = 1e15 that
 * returns 1e6 at d=9 and never reaches the correct 1e9 at d=6 — a fresh error
 * of exactly 1e3, which is the ratio=1000.00 the device reported.
 */
class Aate7110PumpFunSupplyUnitsTest {

    private fun payload(rawSupply: Double, mcap: Double, decimals: Int? = null) =
        JSONObject().apply {
            put("total_supply", rawSupply)
            put("usd_market_cap", mcap)
            if (decimals != null) put("decimals", decimals)
        }

    @Test
    fun theStandardMintResolvesToOneBillionWholeTokens() {
        // 1e9 tokens at 6 decimals => 1e15 raw. Both 1e15/1e9=1e6 and
        // 1e15/1e6=1e9 are inside the plausibility band; only one is right.
        val whole = PumpFunPriceUnits7017.wholeSupply(payload(1e15, 20_919.0))
        assertEquals(1e9, whole, 1e3)
    }

    @Test
    fun theDevicesOwnNumbersComeOutRight() {
        // HECKER, 5.0.7106: mcap 20919, chain supply 1e9, and the price the
        // bot reported was 0.020919707 — exactly 1000x the truth.
        val price = PumpFunPriceUnits7017.priceUsd(payload(1e15, 20_919.0))
        assertEquals(2.0919e-5, price, 1e-8)
        assertTrue("must not be the 1000x reading", price < 1e-3)
    }

    @Test
    fun aPayloadThatCarriesItsOwnDecimalsIsStillTrustedFirst() {
        // Branch 1 is the only non-inference. It must keep precedence.
        val whole = PumpFunPriceUnits7017.wholeSupply(payload(1e15, 1.0, decimals = 6))
        assertEquals(1e9, whole, 1e3)
    }

    @Test
    fun aGenuinelyDifferentSupplyStillWinsWhenItIsTheOnlyPlausibleReading() {
        // 5e6 whole tokens at 6 decimals => 5e12 raw. d=6 gives 5e6 (plausible);
        // d=9 gives 5e3 (also plausible). The standard-distance rule must pick
        // 5e6, which is nearer 1e9 in log space than 5e3 is.
        val whole = PumpFunPriceUnits7017.wholeSupply(payload(5e12, 1.0))
        assertEquals(5e6, whole, 1.0)
    }

    @Test
    fun anAlreadyWholeSupplyIsNotDividedAgain() {
        // Some payloads report whole tokens. 1e9 raw has no plausible division
        // other than itself at the top of the band.
        val whole = PumpFunPriceUnits7017.wholeSupply(payload(1e9, 1.0))
        assertEquals(1e9, whole, 1.0)
    }

    @Test
    fun anImpossiblePayloadReturnsNoQuoteRatherThanAGuess() {
        assertEquals(0.0, PumpFunPriceUnits7017.priceUsd(payload(1e15, 0.0)), 0.0)
        assertEquals(0.0, PumpFunPriceUnits7017.priceUsd(payload(0.0, 0.0)), 0.0)
    }
}

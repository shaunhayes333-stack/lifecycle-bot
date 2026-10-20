package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.*
import com.lifecyclebot.network.SwapQuote
import com.lifecyclebot.network.JupiterApi
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.math.BigInteger

class Aate8253ExitEvidenceTest {
    private val mint = "9o3iDgNPnZTMF8GcXKxzFTxRqme9mJw5WF7RnXtMpump"
    @After fun clear() { CanonicalPriceMarkRegistry6522.resetForTest() }

    @Test fun spotObservationCannotAcquireExecutableProfitAuthority() {
        val registry = CanonicalPriceMarkRegistry6522
        registry.resetForTest()
        val at = System.currentTimeMillis() - 100L
        assertTrue(registry.publishRepairedExitEconomic7418(mint, 0.00004283,
            "LOCKED_VENUE_POOL", true, at))
        assertNull(registry.get(mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC))
        assertEquals(CanonicalMarkPurpose6570.HELD_INDICATIVE, registry.heldRiskMark(mint)!!.purpose)
        assertEquals(at, registry.heldRiskMark(mint)!!.timestampMs)
        assertFalse(registry.publishRepairedExitEconomic7418(mint, 0.00004283,
            "LOCKED_VENUE_POOL", true))
        val raw = BigInteger("63331990605")
        assertTrue(registry.publishRepairedExitEconomic7418(mint, 0.000019,
            "HELD_HOT_EXECUTABLE_SELL_QUOTE_7876", true, at, raw, "pump:13017:2"))
        val exit = registry.get(mint, CanonicalMarkPurpose6570.EXIT_ECONOMIC)!!
        assertEquals(raw, exit.exitQuantityRaw)
        assertEquals("pump:13017:2", exit.exitPositionId)
    }

    @Test fun routeIdentityAndAmountMustMatchTheHolding() {
        val qty = BigInteger("63331990605")
        val q = SwapQuote(JSONObject(), 11_573_133L, 0.1, inputMint = mint,
            outputMint = JupiterApi.SOL_MINT, inAmount = qty.toLong())
        assertTrue(HeldHotMarkAuthority7419.quoteMatchesHolding(q, mint, qty))
        assertFalse(HeldHotMarkAuthority7419.quoteMatchesHolding(q.copy(inputMint = "other"), mint, qty))
        assertFalse(HeldHotMarkAuthority7419.quoteMatchesHolding(q.copy(outputMint = "other"), mint, qty))
        assertFalse(HeldHotMarkAuthority7419.quoteMatchesHolding(q.copy(inAmount = 1L), mint, qty))
        assertFalse(HeldHotMarkAuthority7419.quoteMatchesHolding(q.copy(outAmount = 0L), mint, qty))
    }

    @Test fun curveEstimateRetainsObservationTimeAndCannotSpendVirtualSol() {
        val at = System.currentTimeMillis() - 500L
        CurveReserves7951.noteAccount7951(mint, 40.0, 1_000_000.0, 0.000001, at)
        assertNull(HeldHotMarkAuthority7419.venueReserveSellEstimate(mint, BigInteger("1000000000"), 6, 150.0))
        CurveReserves7951.noteAccount7951(mint, 40.0, 1_000_000.0, 10.0, at + 1L)
        val estimate = HeldHotMarkAuthority7419.venueReserveSellEstimate(mint, BigInteger("1000000000"), 6, 150.0)!!
        assertEquals(at + 1L, estimate.observedAtMs)
        assertEquals("VENUE_RESERVE_CURVE", estimate.source)
        assertTrue(estimate.proceedsSol > 0.0 && estimate.proceedsSol <= 10.0)
    }
}

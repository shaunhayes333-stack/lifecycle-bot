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
    @After fun clear() { CanonicalPriceMarkRegistry6522.resetForTest(); CurveReserves7951.resetForTest7951() }

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

    @Test fun unavailableAggregatorUsesObservedVenueButNeverAnInvalidQuote() {
        val raw = 94_997_985_906L
        val estimate = HeldHotMarkAuthority7419.SellEstimate(0.000019, 0.017368608, 1L, "VENUE_RESERVE_POOL")
        assertEquals(17_368_608L, ProfitExitTruth8163.preflightProceeds(null, mint, raw) { estimate })
        assertFalse(ProfitExitTruth8163.clears(0.017925, 17_368_608L, 100))
        assertTrue(ProfitExitTruth8163.clears(0.017925, 21_000_000L, 100))
        assertNull(ProfitExitTruth8163.preflightProceeds(null, mint, raw) { null })
        val wrong = SwapQuote(JSONObject(), 99_000_000L, 0.0, inputMint = "another-mint",
            outputMint = JupiterApi.SOL_MINT, inAmount = raw)
        assertNull(ProfitExitTruth8163.preflightProceeds(wrong, mint, raw) {
            fail("An identity mismatch must not silently fall back")
            estimate
        })
        assertFalse(ProfitExitTruth8163.profitTaking("MANUAL_SELL"))
        assertFalse(ProfitExitTruth8163.profitTaking("STOP_LOSS"))
    }

    @Test fun inferredCreateFrameCannotBecomeAnObservedEmptyCurveVerdict() {
        val at = System.currentTimeMillis()
        CurveReserves7951.noteFrame7951(mint, 30.1, 1_000_000.0, at)
        assertEquals(0.1, CurveReserves7951.realSolIfActive8046(mint, at)!!, 0.000001)
        assertNull(CurveReserves7951.accountRealSol8253(mint, at))
        CurveReserves7951.noteAccount7951(mint, 40.0, 1_000_000.0, 10.0, at + 1)
        assertEquals(10.0, CurveReserves7951.accountRealSol8253(mint, at + 1)!!, 0.0)
        // Older create data cannot replace a newer account observation.
        CurveReserves7951.noteFrame7951(mint, 30.1, 1_000_000.0, at)
        assertEquals(10.0, CurveReserves7951.accountRealSol8253(mint, at + 1)!!, 0.0)
        // Invalid account values never acquire account-observed authority by arithmetic fallback.
        CurveReserves7951.noteAccount7951(mint, 30.1, 1_000_000.0, Double.NaN, at + 2)
        assertEquals(10.0, CurveReserves7951.accountRealSol8253(mint, at + 2)!!, 0.0)
        assertNull(CurveReserves7951.accountRealSol8253(mint, at + 3_600_000))
        CurveReserves7951.noteAccount7951(mint, 30.0, 1_000_000.0, 0.0, at + 3)
        assertEquals(0.0, CurveReserves7951.accountRealSol8253(mint, at + 3)!!, 0.0)
        assertTrue(OnChainDepth8038.curveNobodyBought8046(0.0))
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

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EntryEconomics8329
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8521ExecutableEntryTest {
    private val e = ExecutableEntryFloor8521

    @Test fun minimumOutputUsesTheBuiltToleranceNotTheIndicativeOutput() {
        assertEquals(950_000L, e.minimumOut8521(1_000_000L, 980_000L, 200, 500)!!)
        assertEquals(950_000L, e.minimumOut8521(1_000_000L, 940_000L, 200, 500)!!)
        assertEquals("bps arithmetic uses the supplied builder tolerance; dynamicBound covers output uncertainty", 990_000L,
            e.minimumOut8521(1_000_000L, 940_000L, 600, 100)!!)
        assertEquals(1_000_000L, e.minimumOut8521(1_000_000L, null, 0, null)!!)
        assertEquals(900_000L, e.minimumOut8521(1_000_000L, 900_000L, null, null)!!)
        assertNull(e.minimumOut8521(1_000_000L, null, null, null))
        assertNull(e.minimumOut8521(1_000_000L, 1_000_001L, 500, null))
        assertNull(e.minimumOut8521(1_000_000L, 0L, 500, null))
        assertNull(e.minimumOut8521(1_000_000L, null, 10_000, null))
        assertNull(e.minimumOut8521(0L, null, 500, null))
    }

    @Test fun rawAmountFloorDoesNotOverflowOrRoundUp() {
        assertEquals(8_762_203_435_012_037_016L, e.minimumOut8521(Long.MAX_VALUE, null, 500, null)!!)
        assertEquals(1L, e.minimumOut8521(3L, null, 5_000, null)!!)
        assertNull(e.minimumOut8521(1L, null, 5_000, null))
    }

    @Test fun dynamicSimulationOutputCannotBeReplacedByTheOlderBetterQuote() {
        assertEquals(594_000L, e.dynamicBound8521(1_000_000L, 980_000L, 200, 100, 600_000L)!!)
        assertEquals("do not improve the original floor on a bps report without a decoded bound", 940_000L,
            e.dynamicBound8521(1_000_000L, 940_000L, 600, 100, 1_010_000L)!!)
        assertEquals(950_000L, e.dynamicBound8521(1_000_000L, 980_000L, 200, 500, null)!!)
        assertNull(e.dynamicBound8521(1_000_000L, 980_000L, 200, 500, 0L))
    }

    @Test fun replayIndicativePassCanBeAnExecutableNetLoser() {
        // Same candidate/size/target: old 8329 admits a 3% chase at a 6% modelled trip.
        assertNull(EntryEconomics8329.consumed8329(3.0, 5.0, 13.0, 6.0))
        val indicative = e.netPct8521(1.0, 1.03, 13.0, 0.03, 2.0, 0.0003, 0.5, 0.0003)!!
        val permittedFill = e.netPct8521(1.0, 1.03 / 0.95, 13.0, 0.03, 2.0, 0.0003, 0.5, 0.0003)!!
        assertTrue("headline quote looks profitable", indicative > 0.0)
        assertTrue("the same transaction permits a net losing fill", permittedFill < 0.0)
    }

    @Test fun supportedRunnerStillClearsAtTheSameWorstFill() {
        assertTrue(e.netPct8521(1.0, 1.03 / 0.95, 30.0, 0.03, 2.0, 0.0003, 0.5, 0.0003)!! > 10.0)
        assertTrue(e.netPct8521(1.0, 1.0, 13.0, 0.03, 2.0, 0.0003, 0.5, 0.0003)!! > 0.0)
    }

    @Test fun embeddedBuyImpactAndSlippageAreNotChargedTwice() {
        // Four percent embedded buy drag is in the executable price. Only the 2% sale remains.
        val net = e.netPct8521(1.0, 1.04, 8.0, 1.0, 2.0, 0.0, 0.0, 0.0)!!
        assertEquals((1.08 / 1.04 * 0.98 - 1.0) * 100.0, net, 1e-10)
        assertTrue("a second charge of the 4% buy drag would incorrectly veto this trade", net > 0.0)
    }

    @Test fun finalTipIsCostedAtTheActualTicketAndChargedOnlyOnce() {
        val lowTip = e.netPct8521(1.0, 1.04, 10.0, 0.02, 1.0, 0.0001, 0.5, 0.0001)!!
        val highTip = e.netPct8521(1.0, 1.04, 10.0, 0.02, 1.0, 0.0001, 0.5, 0.002)!!
        assertTrue(lowTip > 0.0)
        assertTrue("large Jito/Sender tip consumes a small ticket's entire edge", highTip < 0.0)
        assertEquals(((0.02 * (1.1 / 1.04) * 0.99 - 0.0001) / (0.02 * 1.005 + 0.0001) - 1.0) * 100.0,
            lowTip, 1e-10)
    }

    @Test fun finalPriceMustBeFreshEvenWhenTheDecisionHasNotExpired() {
        val now = 1_800_000_000_000L
        assertTrue(e.fresh8521(now - 20_000L, now))
        assertFalse(e.fresh8521(now - 20_001L, now))
        assertTrue(e.fresh8521(now + 10L, now)) // a stream writer can run after the reader's clock
        assertFalse(e.fresh8521(now + 6_000L, now))
        assertFalse(e.fresh8521(0L, now))
    }

    @Test fun expensiveBuiltBuyDoesNotReplaceTheProtectiveSellWithAnAverageLeg() {
        // Observed policy: cheap ordinary entry, more expensive protective exit. A higher built
        // buy tip must be added to that full exit, not to the old round-trip average.
        val oldAverage = e.netPct8521(1.0, 1.0, 6.8, 0.01, 0.5, 0.000125, 0.5, 0.0004)!!
        val exactLegs = e.netWithFixedLegs8521(1.0, 1.0, 6.8, 0.01, 0.5,
            0.00025, 0.0002125, 0.5, 0.0004)!!
        assertTrue("half the round trip understated the protective exit", oldAverage > 0.0)
        assertTrue("the same executable ticket cannot pay both real legs", exactLegs < 0.0)
        assertTrue("a supported runner retains its positive edge", e.netWithFixedLegs8521(
            1.0, 1.0, 30.0, 0.01, 0.5, 0.00025, 0.0002125, 0.5, 0.0004)!! > 20.0)
    }

    @Test fun fixedLegSplitPreservesTheModelAndRejectsUnobservedOrInconsistentCosts() {
        val split = e.netWithFixedLegs8521(1.0, 1.04, 15.0, 0.03, 2.0,
            0.00025, 0.0002125, 0.5, 0.00001)!!
        assertEquals(e.netPct8521(1.0, 1.04, 15.0, 0.03, 2.0, 0.0002125, 0.5, 0.0000375)!!,
            split, 1e-10)
        assertNull(e.netWithFixedLegs8521(1.0, 1.0, 30.0, 0.03, 1.0,
            Double.NaN, 0.0002, 0.5, 0.0001))
        assertNull(e.netWithFixedLegs8521(1.0, 1.0, 30.0, 0.03, 1.0,
            0.0001, 0.0002, 0.5, 0.0001))
    }

    @Test fun fixedCostsStayCoherentWhenProtectiveTipsChangeBetweenEvaluations() {
        CostMeter8151.resetForTest8151()
        try {
            CostMeter8151.noteLeg8151(12_500L, 0L, 0L)
            CostMeter8151.noteTip8151(25_000L, 25_000L, entry = true)
            CostMeter8151.noteSellTip8378(200_000L, protective = true)
            val before = CostMeter8151.fixedLegs8521()
            assertEquals(0.00025, before.roundTripFixedSol, 1e-12)
            assertEquals(0.0000375, before.buyFixedSol, 1e-12)
            assertEquals(0.0002125, before.sellFixedSol, 1e-12)
            com.lifecyclebot.engine.truth.NetEdge8030.preTradeCostPct8329("EXPRESS", 0.03, 20_000.0)
            val modelBefore = com.lifecyclebot.engine.truth.NetEdge8030.fixedRoundTripForCurrentCost8521()!!

            // A protective send can update the meter after the entry model has been priced.
            CostMeter8151.noteSellTip8378(1_000_000L, protective = true)
            val after = CostMeter8151.fixedLegs8521()
            assertTrue("independent old/new reads would have formed an impossible pair",
                after.sellFixedSol > before.roundTripFixedSol)
            assertEquals(0.00025, before.roundTripFixedSol, 1e-12)
            assertEquals(modelBefore, com.lifecyclebot.engine.truth.NetEdge8030.fixedRoundTripForCurrentCost8521()!!, 0.0)
            for (fixed in listOf(before, after)) {
                assertTrue(fixed.sellFixedSol <= fixed.roundTripFixedSol)
                assertNotNull(e.netWithFixedLegs8521(1.0, 1.0, 30.0, 0.03, 0.5,
                    fixed.roundTripFixedSol, fixed.sellFixedSol, 0.5, 0.0004))
            }
            // The next ticket, including default-sized evaluation, replaces that thread's model component.
            com.lifecyclebot.engine.truth.NetEdge8030.preTradeCostPct8329("QUALITY", Double.NaN, 20_000.0)
            assertEquals(after.roundTripFixedSol,
                com.lifecyclebot.engine.truth.NetEdge8030.fixedRoundTripForCurrentCost8521()!!, 1e-12)
        } finally { CostMeter8151.resetForTest8151() }
    }

    @Test fun rebuildingDoesNotRetimestampAnOldQuoteAndCarriesTheActualOrder() {
        val indicative = com.lifecyclebot.network.SwapQuote(org.json.JSONObject(), 1_000_000L, 0.1,
            receivedAtMs8521 = 1_000L)
        assertEquals(1_000L, indicative.copy(router = "metis").receivedAtMs8521)
        val binding = indicative.copy(outAmount = 900_000L, receivedAtMs8521 = 2_000L)
        val built = com.lifecyclebot.network.SwapTxResult("tx", executionQuote8521 = binding)
        assertEquals(900_000L, built.executionQuote8521!!.outAmount)
        val source = File("src/main/kotlin/com/lifecyclebot/network/JupiterApi.kt").readText()
        assertTrue(source.contains("executionQuote8521 = freshOrder"))
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(executor.contains("executionQuote7688 = txResultLocal.executionQuote8521 ?: executionQuote7688"))
    }

    @Test fun curveAveragePriceUsesSizedVirtualReservesNotSpotOrRealReserve() {
        assertEquals(0.033, e.curvePrice8521(30.0, 1_000.0, 3.0, 1.0, 0.0, 0.0)!!, 1e-12)
        val sized = e.curvePrice8521(30.0, 1_000.0, 3.0, 1.0, 1.75, 10.0)!!
        assertTrue(sized > 0.033)
        assertTrue(e.curvePrice8521(30.0, 1_000.0, 6.0, 1.0, 1.75, 10.0)!! > sized)
        assertNull(e.curvePrice8521(0.0, 1_000.0, 3.0, 1.0, 0.0, 0.0))
        assertNull(e.curvePrice8521(30.0, Double.NaN, 3.0, 1.0, 0.0, 0.0))
        assertNull(e.curvePrice8521(30.0, 1_000.0, 3.0, 1.0, 0.0, 100.0))
    }

    @Test fun unknownOrInvalidEconomicsNeverBecomeZeroCost() {
        assertNull(e.netPct8521(1.0, Double.NaN, 30.0, 0.03, 2.0, 0.0003, 0.5, 0.0003))
        assertNull(e.netPct8521(1.0, 1.0, Double.NaN, 0.03, 2.0, 0.0003, 0.5, 0.0003))
        assertNull(e.netPct8521(1.0, 1.0, 30.0, 0.03, Double.NaN, 0.0003, 0.5, 0.0003))
        assertNull(e.netPct8521(1.0, 1.0, 30.0, 0.0, 2.0, 0.0003, 0.5, 0.0003))
    }

    @Test fun allLiveBuyRoutesValidateTheFillFloorBeforeSubmission() {
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val jupiter = executor.indexOf("EntryEconomics8329.jupiterFloorRefusal8521(")
        val jupiterReady = executor.indexOf("return JupiterBuyPreparation8090(quote, txResult, raydiumBuy7325, useJito, jitoTip)")
        assertTrue(jupiter > 0 && jupiter < jupiterReady)
        val raydium = executor.indexOf("floorPrice8521, fixed8521, \"RAYDIUM_MIN_OUT\"")
        val raydiumSend = executor.indexOf("com.lifecyclebot.network.RaydiumSellRoute7311.sendBuilt(", raydium)
        assertTrue(raydium > 0 && raydiumSend > raydium)
        val pumpStart = executor.indexOf("private fun tryPumpPortalBuy(")
        val preserve = executor.indexOf("val pumpApproved8521", pumpStart)
        val build = executor.indexOf("com.lifecyclebot.network.PumpFunDirectApi.buildBuyTx(", pumpStart)
        val envelope = executor.indexOf("val pumpEnv7311", build)
        val pumpFinal = executor.indexOf("EntryEconomics8329.pumpFinalRefusal8521(", envelope)
        val pumpSign = executor.indexOf("wallet.signSendAndConfirm(", pumpFinal)
        assertTrue(preserve in (pumpStart + 1) until build)
        assertTrue(pumpFinal > envelope && pumpSign > pumpFinal)
        assertTrue(executor.substring(envelope, pumpSign).contains("EntryContinuity8418.refusal8418(pumpApproved8521, finalIntent8521"))
        assertTrue("unpriced Pump falls through before any signed spend", executor.substring(pumpFinal, pumpSign).contains("return null"))
    }
}

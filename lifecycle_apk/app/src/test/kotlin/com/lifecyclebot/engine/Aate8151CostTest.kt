package com.lifecyclebot.engine

import com.lifecyclebot.engine.CostTrim8151.Urgency8151
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8151 — live round-trip cost: urgency-tiered tips and priority (ordinary entries no longer pay emergency fees),
 * impact-sized ordinary PumpPortal slippage, simulation before an ordinary PumpPortal buy, the cheapest executable
 * route for a confirmed-graduated pump mint, the round trip built from measured components for NetEdge8030, the
 * uneconomic-partial rule and the rent close after a confirmed full exit. Pure functions, explicit clocks.
 */
class Aate8151CostTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()
    private val c = CostTrim8151

    // ── urgency ──

    @Test fun urgencyKeepsEmergenciesAndUrgentExitsOnTheOldFees() {
        assertEquals(Urgency8151.EMERGENCY, c.urgencyOf8151(1, drain = false, fastLand = false))
        assertEquals(Urgency8151.EMERGENCY, c.urgencyOf8151(8, drain = true, fastLand = false))
        assertEquals(Urgency8151.URGENT, c.urgencyOf8151(2, drain = false, fastLand = false))
        assertEquals(Urgency8151.URGENT, c.urgencyOf8151(3, drain = false, fastLand = false))
        assertEquals(Urgency8151.URGENT, c.urgencyOf8151(5, drain = false, fastLand = true))
        assertEquals(Urgency8151.ORDINARY, c.urgencyOf8151(5, drain = false, fastLand = false))
        assertEquals(Urgency8151.ORDINARY, c.urgencyOf8151(8, drain = false, fastLand = false))
    }

    // ── tips and priority ──

    @Test fun ordinarySenderTipDropsToTheSwqosMinimumWithHeadroomUrgentKeepsLegacy() {
        // superseded by V5.0.8418 — the ordinary tip is 25,000 (5x the SWQoS minimum), was 50,000.
        assertEquals(25_000L, c.senderTipLamports8151(Urgency8151.ORDINARY, 200_000L))
        assertEquals(25_000L, c.senderTipLamports8151(Urgency8151.ORDINARY, 400_000L))
        assertEquals(5_000L, c.senderTipLamports8151(Urgency8151.ORDINARY, 1_000L))   // never below the Sender minimum
        assertEquals(400_000L, c.senderTipLamports8151(Urgency8151.URGENT, 400_000L))
        assertEquals(200_000L, c.senderTipLamports8151(Urgency8151.EMERGENCY, 200_000L))
        // The ordinary tip is only safe because Sender is the SWQOS-only endpoint (5,000-lamport minimum).
        assertTrue(src("network/HeliusSender.kt").contains("sender.helius-rpc.com/fast?swqos_only=true"))
        assertEquals(5_000L, com.lifecyclebot.network.HeliusSenderEnvelope7250.MIN_SWQOS_TIP_LAMPORTS)
    }

    @Test fun ordinaryCuPriceIsMediumCappedBySizeAndHighAndFloored() {
        // 0.029 SOL: 0.3% = 87,000 lamports over 300k CU = 290,000 micro-lamports per CU.
        assertEquals(100_000L, c.ordinaryMicroLamports8151(100_000L, 1_500_000L, 29_000_000L, 25_000L))
        assertEquals(290_000L, c.ordinaryMicroLamports8151(800_000L, 1_500_000L, 29_000_000L, 25_000L))
        assertEquals(25_000L, c.ordinaryMicroLamports8151(0L, 0L, 29_000_000L, 25_000L))       // no reading: the floor
        assertEquals(60_000L, c.ordinaryMicroLamports8151(0L, 60_000L, 0L, 25_000L))           // no medium: high, uncapped size
        assertEquals(200_000L, c.ordinaryMicroLamports8151(500_000L, 200_000L, 29_000_000L, 25_000L))  // never above high
        assertEquals(25_000L, c.ordinaryMicroLamports8151(100_000L, 1_500_000L, 1_000_000L, 25_000L))  // tiny trade: floor wins
    }

    @Test fun ordinaryPumpPriorityIsMediumCappedAgainstTheTrade() {
        assertEquals(0.00002, c.ordinaryPumpSol8151(0.00002, 0.0002, 0.029), 1e-12)
        assertEquals(0.00005, c.ordinaryPumpSol8151(0.0, 0.0002, 0.029), 1e-12)       // no reading: fallback
        assertEquals(0.000087, c.ordinaryPumpSol8151(0.0004, 0.0002, 0.029), 1e-12)   // 0.3% of 0.029 SOL
        assertEquals(0.0001, c.ordinaryPumpSol8151(0.0004, 0.0002, 0.0), 1e-12)       // unknown size: ordinary cap
        assertEquals(0.00002, c.ordinaryPumpSol8151(0.000001, 0.0002, 0.029), 1e-12)  // floor
        assertEquals(0.00002, c.ordinaryPumpSol8151(0.0, 0.0002, 0.001), 1e-12)       // tiny trade: floor
    }

    // ── slippage ──

    @Test fun ordinaryPumpBuySlippageFollowsImpactNeverAboveLegacy() {
        assertEquals(7, c.ordinaryPumpBuySlipPct8151(0.097, 10))   // 0.029 SOL into a 30 SOL curve
        assertEquals(6, c.ordinaryPumpBuySlipPct8151(0.0, 10))
        assertEquals(9, c.ordinaryPumpBuySlipPct8151(1.0, 10))
        assertEquals(10, c.ordinaryPumpBuySlipPct8151(2.0, 10))    // unknown-depth impact (2%) keeps the legacy 10%
        assertEquals(10, c.ordinaryPumpBuySlipPct8151(Double.NaN, 10))
        assertEquals(5, c.ordinaryPumpBuySlipPct8151(0.0, 5))
    }

    // ── simulation before send ──

    @Test fun onlyADefiniteSimulationFailureRefusesAnOrdinaryPumpBuy() {
        assertFalse(c.simDefiniteReject8151(null))
        assertFalse(c.simDefiniteReject8151("RPC error: 429 Too Many Requests"))
        assertFalse(c.simDefiniteReject8151("Simulate failed: null"))
        assertTrue(c.simDefiniteReject8151("Simulate failed: {InstructionError=[2, {Custom=6002}]} | Program log: Error: TooMuchSolRequired"))
    }

    // ── route cost term ──

    @Test fun aggregatorFirstOnlyForAnOrdinaryBuyOfAConfirmedGraduatedPool() {
        assertEquals(0.5, c.routeFeePctPerSide8151("PUMPPORTAL"), 1e-12)
        // V8391: documented general-pair aggregator fee is 0.1 per side; Costs8369 routeFee8391 uses live quote-specific fees.
        assertEquals(0.1, c.routeFeePctPerSide8151("jupiter"), 1e-12)
        assertEquals(0.0, c.routeFeePctPerSide8151("RAYDIUM"), 1e-12)
        assertTrue(c.aggregatorFirstForCost8151(Urgency8151.ORDINARY, "PUMPSWAP", pumpEligible = true, recentlyTried = false))
        assertTrue(c.aggregatorFirstForCost8151(Urgency8151.ORDINARY, "RAYDIUM", pumpEligible = true, recentlyTried = false))
        assertFalse(c.aggregatorFirstForCost8151(Urgency8151.ORDINARY, "PUMP_CURVE", pumpEligible = true, recentlyTried = false))
        assertFalse(c.aggregatorFirstForCost8151(Urgency8151.ORDINARY, "UNKNOWN", pumpEligible = true, recentlyTried = false))
        assertFalse(c.aggregatorFirstForCost8151(Urgency8151.ORDINARY, "PUMPSWAP", pumpEligible = true, recentlyTried = true))
        assertFalse(c.aggregatorFirstForCost8151(Urgency8151.URGENT, "PUMPSWAP", pumpEligible = true, recentlyTried = false))
        assertFalse(c.aggregatorFirstForCost8151(Urgency8151.ORDINARY, "PUMPSWAP", pumpEligible = false, recentlyTried = false))
    }

    // ── round-trip economics ──

    @Test fun roundTripIsBuiltFromItsComponents() {
        // Curve 1.25% + PumpPortal 0.5% + app 0.5% a side, two 0.0003 SOL legs, at 0.029 SOL.
        assertEquals(6.568965517241379, c.roundTripPct8151(1.25, 0.5, 0.5, 0.0003, 0.0, 0.029), 1e-9)
        assertTrue(c.roundTripPct8151(1.25, 0.5, 0.5, 0.0003, 0.0, 0.0).isNaN())
        assertEquals(2.0689655172413794, c.fixedCostSharePct8151(0.0003, 0.0, 0.029), 1e-9)
        // An account never closed adds its whole rent: ~9.1% of the trade.
        assertEquals(9.100965517241379, c.fixedCostSharePct8151(0.0003, c.ATA_RENT_SOL_8151, 0.029), 1e-9)
        assertEquals(6.185567010309279, c.breakEvenMovePct8151(6.0), 1e-9)
        assertEquals(0.0, c.breakEvenMovePct8151(0.0), 1e-12)
        assertTrue(c.breakEvenMovePct8151(-1.0).isNaN())
    }

    @Test fun theAppFeeConstantMirrorsTheExecutorsLiveFee() {
        // superseded by V5.0.8418 — owner re-added the 0.5% BUY fee (sell 0): the per-side constant is the mean, 0.25 (was 0.0).
        assertEquals(0.25, CostTrim8151.APP_FEE_PCT_PER_SIDE_8151, 1e-12)
        assertEquals(0.5, CostTrim8151.BUY_APP_FEE_PCT_8418, 0.0)
        assertEquals(0.0, CostTrim8151.SELL_APP_FEE_PCT_8418, 0.0)
        assertTrue(src("engine/Executor.kt").contains("@Volatile private var MEME_TRADING_FEE_PERCENT = 0.0"))   // V5.0.8392b — volatile, never a foldable const
    }

    // ── partial slices ──

    @Test fun aSliceWhoseFixedFeesExceedTheShareIsHeldExceptTiersOneToThree() {
        assertTrue(c.partialFixedFeeRefused8151(0.000255, 0.00145, 0.10, 5))    // 17.6% of proceeds
        assertFalse(c.partialFixedFeeRefused8151(0.000255, 0.00725, 0.10, 5))   // 3.5%
        assertFalse(c.partialFixedFeeRefused8151(0.000255, 0.00145, 0.10, 1))
        assertFalse(c.partialFixedFeeRefused8151(0.000255, 0.00145, 0.10, 2))
        assertFalse(c.partialFixedFeeRefused8151(0.000255, 0.00145, 0.10, 3))
        assertTrue(c.partialFixedFeeRefused8151(0.000255, 0.00145, 0.10, 8))
        assertFalse(c.partialFixedFeeRefused8151(0.000255, Double.NaN, 0.10, 5))
        assertFalse(c.partialFixedFeeRefused8151(0.000255, 0.0, 0.10, 5))
        assertFalse(c.partialFixedFeeRefused8151(0.000255, 0.00145, 0.0, 5))
    }

    // ── rent ──

    @Test fun targetedCloseNeedsAnExactZeroInitializedTerminalIdleAccount() {
        assertTrue(c.targetedCloseEligible8151("0", "initialized", false, false, 9, false, 1_000L, 2_000L))
        assertFalse(c.targetedCloseEligible8151("1", "initialized", false, false, 9, false, 1_000L, 2_000L))
        assertFalse(c.targetedCloseEligible8151("0", "frozen", false, false, 9, false, 1_000L, 2_000L))
        assertFalse(c.targetedCloseEligible8151("0", "initialized", true, false, 9, false, 1_000L, 2_000L))
        assertFalse(c.targetedCloseEligible8151("0", "initialized", false, true, 9, false, 1_000L, 2_000L))
        assertFalse(c.targetedCloseEligible8151("0", "initialized", false, false, null, false, 1_000L, 2_000L))
        assertFalse(c.targetedCloseEligible8151("0", "initialized", false, false, 7, false, 1_000L, 2_000L))
        assertFalse(c.targetedCloseEligible8151("0", "initialized", false, false, 9, true, 1_000L, 2_000L))
        assertFalse(c.targetedCloseEligible8151("0", "initialized", false, false, 9, false, 3_000L, 2_000L))
        // SELL_VERIFYING (7) is not terminal, SOLD_CONFIRMED (8) is.
        assertEquals(7, HostWalletTokenTracker.PositionStatus.SELL_VERIFYING.priority)
        assertEquals(8, HostWalletTokenTracker.PositionStatus.SOLD_CONFIRMED.priority)
    }

    @Test fun ewmaSeedsThenMoves() {
        assertEquals(10.0, c.ewma8151(Double.NaN, 10.0, 0.2), 1e-12)
        assertEquals(12.0, c.ewma8151(10.0, 20.0, 0.2), 1e-12)
        assertEquals(10.0, c.ewma8151(10.0, Double.NaN, 0.2), 1e-12)
    }

    // ── live meter ──

    @Test fun meterUsesMeasuredFeesAndTipsAndFallsBackBeforeAnyLeg() {
        val m = CostMeter8151
        m.resetForTest8151()
        // superseded by V5.0.8418 — before any leg: the PLANNED legs (base 5,000 + medium/floor 25,000 uL x 300k CU = 12,500
        // lamports each, ordinary entry tip 25,000, legacy sell tip 200,000), was the flat 0.0006 / 0.0003 SOL fallback.
        assertEquals(0.00025, m.roundTripFixedSol8151(), 1e-12)
        assertEquals(0.0002125, m.sellLegFixedSol8151(), 1e-12)
        m.noteLeg8151(80_000L, 2_039_280L, 0L)
        m.noteTip8151(50_000L, 200_000L, entry = true)
        assertEquals(0.00041, m.roundTripFixedSol8151(), 1e-12)   // 2 fees + ordinary entry tip + legacy sell tip
        assertEquals(0.00028, m.sellLegFixedSol8151(), 1e-12)
        m.noteTicket8151(0.029)
        assertEquals(0.029, m.ticketSol8151(0.0273), 1e-12)
        assertTrue(m.statusLine8151().contains("fixedShare="))
        assertTrue(m.statusLine8151().contains("tipSavedSol=0.000150"))
        val t = 1_800_000_000_000L
        assertFalse(m.aggregatorTriedRecently8151("MINT", t))
        m.noteAggregatorFirst8151("MINT", t)
        assertTrue(m.aggregatorTriedRecently8151("MINT", t + 1_000L))
        assertFalse(m.aggregatorTriedRecently8151("MINT", t + 11L * 60_000L))
        m.resetForTest8151()
    }

    @Test fun boundedRunAnswersInTimeOrProvesNothing() {
        assertEquals("x", CostMeter8151.runBounded8151(2_000L) { "x" })
        assertNull(CostMeter8151.runBounded8151(50L) { Thread.sleep(600L); "late" })
    }

    // ── wiring ──

    @Test fun costCutsAreWiredWhereTheTradesAreBuilt() {
        val e = src("engine/Executor.kt")
        assertTrue(e.contains("val freshPumpRoute = pumpPortalAutoEligible4559 && !costAggregatorFirst8151(ts, wallet)"))
        // superseded by V5.0.8337 — the preflight simulation is skipped at the call site when the entry is not ordinary (every entry pays to land)
        assertTrue(e.contains("if (entryOrdinary8151(ts.mint) && costPumpBuySimRefused8151(ts, built.txBase64, wallet, tradeKey)) return null"))
        assertTrue(e.contains("if (costPartialFixedRefused8151(ts, proceeds, reason, f)) return true"))
        assertTrue(e.contains("slipPct = costPumpBuySlip8151(ts, pumpBuyPlan.solAmount)"))
        assertTrue(e.contains("ordinary8151    = entryOrdinary8151(ts.mint)"))
        assertTrue(e.contains("ordinary8151    = ordinarySliceLabel8151(labelTag)"))
        assertTrue(src("network/JupiterApi.kt").contains("PriorityFee8009.ordinaryJupiterMicroLamports8151(solSideLamports8151(quote))"))
        assertTrue(src("network/PumpFunDirectApi.kt").contains("PriorityFee8009.ordinaryPumpPortalSol8151("))
        assertTrue(src("network/PriorityFee8009.kt").contains("includeAllPriorityFeeLevels"))
        assertTrue(src("engine/sell/LivePositionCloseAuthority.kt").contains("RentReclaimer7927.noteFullExit8151(mint)"))
        assertTrue(src("engine/RentReclaimer7927.kt").contains("maybeRunTargeted8151(nowMs)"))
        assertTrue(src("engine/truth/NetEdge8030.kt").contains("componentsPct8151(size, mint,"))
        assertTrue(src("engine/TradeVerifier.kt").contains("CostMeter8151.noteLeg8151(parsed.feeLamports"))
        assertTrue(src("engine/TradeVerifier.kt").contains("CostMeter8151.noteFailedLeg8151(parsed.feeLamports)"))
    }
}

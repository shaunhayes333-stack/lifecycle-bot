package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.FieldManual7715
import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * V5.0.8418 — the round trip as it is paid on chain: planned (not flat) fixed legs, the executed route's own fee, realised
 * slippage vs the executed quote from receipts, the mark-based lift retired once receipts measure the trip, rent queued
 * for reclaim on every LIVE close, and the stop / entry bar that follow. Behaviour tests on the functions' outputs.
 */
class Aate8418RoundTripTest {
    private val sol = "So11111111111111111111111111111111111111112"

    // ── fixed SOL ──

    @Test fun fixedLegsBeforeAnyMeasurementAreThePlannedSpendNotAFlatGuess() {
        val m = CostMeter8151
        m.resetForTest8151()
        // base 5,000 + floor 25,000 uL x 300,000 CU = 12,500 a leg; ordinary entry tip 25,000; protective sell tip 200,000
        assertEquals(12_500L, RoundTrip8418.plannedLegFeeLamports8418(0L, 25_000L, 300_000L))
        assertEquals(8_000L, RoundTrip8418.plannedLegFeeLamports8418(10_000L, 25_000L, 300_000L))   // a medium reading wins
        assertEquals(0.00025, m.roundTripFixedSol8151(), 1e-12)
        val share = CostTrim8151.fixedCostSharePct8151(m.roundTripFixedSol8151() / 2.0, 0.0, 0.031)
        assertEquals(0.806, share, 0.001)   // was 1.94 % (0.0006 SOL) at a 0.031 SOL ticket
        // measured values replace each part: 2 x 21,280 (5.0.8416 receipt: entryFee 0.07 % of 0.0304) + tips
        m.noteLeg8151(21_280L, 2_039_280L, 0L)
        m.noteTip8151(25_000L, 200_000L, entry = true)
        m.noteSellTip8378(25_000L, protective = false)
        // a cheap ordinary sell tip never lowers the modelled sell leg: the protective class (legacy floor until measured)
        assertEquals((2 * 21_280L + 25_000L + 200_000L) / 1e9, m.roundTripFixedSol8151(), 1e-12)
        assertEquals(25_000L, m.lastSellTipLamports8418())
        m.noteSellTip8378(400_000L, protective = true)   // the exits work raises the protective tip: the model follows it
        assertEquals(400_000.0, m.modelSellTipLamports8418(), 1e-9)
        assertEquals((2 * 21_280L + 25_000L + 400_000L) / 1e9, m.roundTripFixedSol8151(), 1e-12)
        assertEquals((21_280L + 400_000L) / 1e9, m.sellLegFixedSol8151(), 1e-12)
        m.resetForTest8151()
    }

    @Test fun ordinaryTipIsFiveTimesTheSwqosMinimumAndProtectiveSendsKeepTheLegacyTip() {
        assertEquals(25_000L, CostTrim8151.senderTipLamports8151(CostTrim8151.Urgency8151.ORDINARY, 200_000L))
        assertEquals(5_000L, CostTrim8151.senderTipLamports8151(CostTrim8151.Urgency8151.ORDINARY, 1_000L))
        assertEquals(200_000L, CostTrim8151.senderTipLamports8151(CostTrim8151.Urgency8151.URGENT, 200_000L))
        assertEquals(200_000L, CostTrim8151.senderTipLamports8151(CostTrim8151.Urgency8151.EMERGENCY, 200_000L))
        assertTrue(CostTrim8151.sellProtective8378("STOP_LOSS", urgent = false, previousSellUnlanded = false))
        assertTrue(CostTrim8151.sellProtective8378("TAKE_PROFIT", urgent = false, previousSellUnlanded = true))
        // the entry cap at a 0.031 SOL ticket (0.5 % = 155,000 lamports) is not what limits the tip any more
        assertEquals(25_000L, Costs8369.entryTipLamports8369(25_000L, 31_000_000L, 7_500L, 5_000L, capFailing = false))
        assertEquals(200_000L, Costs8369.entryTipLamports8369(200_000L, 31_000_000L, 7_500L, 5_000L, capFailing = true))
    }

    // ── route fee ──

    @Test fun jupiterRouteFeeIsTheExecutedRoutesOwnWeightedByTheLandedShare() {
        // 5.0.8416 landings: BUY Ultra 1 / Metis 22 -> 0.5 % x 1/23
        val s = RoundTrip8418.ultraShare8418(1, 22)!!
        assertEquals(0.5 / 23.0, RoundTrip8418.jupiterRouteFee8418(null, null, s, null), 1e-12)
        assertNull(RoundTrip8418.ultraShare8418(1, 1))
        // unknown share, no quote: never free on no data
        assertEquals(0.5, RoundTrip8418.jupiterRouteFee8418(null, null, null, null), 0.0)
        // a Metis-only quote prices Metis (no platform fee); an Ultra-only quote prices Ultra
        assertEquals(0.0, RoundTrip8418.jupiterRouteFee8418(null, 0.0, null, null), 0.0)
        assertEquals(0.1, RoundTrip8418.jupiterRouteFee8418(0.1, null, null, null), 0.0)
        assertEquals(0.05, RoundTrip8418.jupiterRouteFee8418(0.1, 0.0, 0.5, null), 1e-12)
        assertEquals(0.2, RoundTrip8418.jupiterRouteFee8418(null, null, 1.0, 0.2), 1e-12)   // typical Ultra fee measured
    }

    @Test fun metisQuotesNoLongerShareTheUltraFeeSlot() {
        val mint = "Route8418Mint" + System.nanoTime()
        val now = 5_000L
        Costs8369.noteJupiterQuote8391(sol, mint, org.json.JSONObject("{\"platformFee\":null}"), false, now)
        assertEquals(0.0, Costs8369.routeFee8391("JUPITER", mint, "BUY", now + 1), 0.0)
        Costs8369.noteJupiterQuote8391(sol, mint, org.json.JSONObject("{\"feeBps\":10}"), true, now)
        assertEquals(0.1, Costs8369.routeFee8391("JUPITER", mint, "BUY", now + 1), 1e-12)
        assertEquals(0.5, Costs8369.routeFee8391("JUPITER", mint, "SELL", now + 1), 0.0)
        assertEquals(0.5, Costs8369.routeFee8391("PUMPPORTAL", mint, "BUY", now + 1), 0.0)
    }

    // ── realised slippage ──

    @Test fun slippageIsTheFillAgainstTheExecutedQuoteAndAMismatchIsNoSample() {
        val q = BigInteger.valueOf(30_000_000L); val out = BigInteger.valueOf(1_000_000L)
        assertEquals(1.0, RealisedSlip8418.buySlipPct8418(q, out, 30_025_000L, BigInteger.valueOf(990_000L))!!, 1e-9)
        assertEquals(-0.5, RealisedSlip8418.buySlipPct8418(q, out, 30_000_000L, BigInteger.valueOf(1_005_000L))!!, 1e-9)
        assertNull("another quote's size", RealisedSlip8418.buySlipPct8418(q, out, 60_000_000L, BigInteger.valueOf(990_000L)))
        val tokens = BigInteger.valueOf(1_000_000L); val lamports = BigInteger.valueOf(30_000_000L)
        assertEquals(1.0, RealisedSlip8418.sellSlipPct8418(tokens, lamports, tokens, 29_700_000L)!!, 1e-9)
        assertNull(RealisedSlip8418.sellSlipPct8418(tokens, lamports, BigInteger.valueOf(500_000L), 15_000_000L))
        assertEquals(BigInteger.valueOf(123L), RealisedSlip8418.amount8418("123"))
        assertNull(RealisedSlip8418.amount8418(null))
        assertNull(RealisedSlip8418.amount8418("0"))
    }

    @Test fun receiptsReplaceTheAllowanceAndRetireTheMarkLift() {
        RealisedSlip8418.resetForTest8418()
        val mint = "Slip8418Mint" + System.nanoTime()
        val now = 1_800_000_000_000L
        assertEquals(1.0, RoundTrip8418.slipRoundTripPctLive8418(), 1e-12)   // allowance 0.5 a side before receipts
        assertFalse(RealisedSlip8418.receiptMeasured8418())
        // superseded by V5.0.8418 §MARK_GAP_IS_NOT_A_FEE — the mark gap (market drift + chase) is never charged, receipts or not
        assertNull(RoundTrip8418.markLift8418(9.28, RealisedSlip8418.receiptMeasured8418()))
        Costs8369.noteJupiterQuote8391(sol, mint, org.json.JSONObject("{\"inAmount\":\"30000000\",\"outAmount\":\"1000000\",\"feeBps\":0}"), true, now)
        Costs8369.noteJupiterQuote8391(mint, sol, org.json.JSONObject("{\"inAmount\":\"1000000\",\"outAmount\":\"30000000\"}"), false, now)
        repeat(5) {
            // spent 30,040,000 incl. a 15,000 fee and a 25,000 tip; 997,000 tokens = 0.3 % under the quote
            RealisedSlip8418.noteBuyFill8418(mint, BigInteger.valueOf(997_000L), 30_040_000L, 15_000L, now + 2_000L)
        }
        assertEquals(0.3, RealisedSlip8418.medianPct8418("BUY")!!, 1e-9)
        assertFalse("one side measured is not the trip", RealisedSlip8418.receiptMeasured8418())
        repeat(5) {
            // 29,925,000 delivered gross (net 29,885,000 + 15,000 fee + 25,000 tip) = 0.25 % under the quote
            RealisedSlip8418.noteSellFill8418(mint, BigInteger.valueOf(1_000_000L), 29_885_000L, 15_000L, 25_000L, now + 3_000L)
        }
        assertEquals(0.25, RealisedSlip8418.medianPct8418("SELL")!!, 1e-9)
        assertTrue(RealisedSlip8418.receiptMeasured8418())
        assertEquals(0.55, RoundTrip8418.slipRoundTripPctLive8418(), 1e-9)
        assertNull("the mark gap is reported, never charged", RoundTrip8418.markLift8418(9.28, RealisedSlip8418.receiptMeasured8418()))
        // a fill with no matching quote is counted, never guessed
        RealisedSlip8418.noteBuyFill8418("NoQuote8418", BigInteger.ONE, 1_000L, 0L, now)
        assertTrue(RealisedSlip8418.statusLine8418().contains("unmatched=1"))
        // price improvement is not credited below zero
        assertEquals(0.0 + 0.5, RoundTrip8418.slipRoundTripPct8418(-0.4, null), 1e-12)
        RealisedSlip8418.resetForTest8418()
    }

    // ── impact ──

    @Test fun impactIsRaisedToTheQuotesOwnImpactScaledToTheTicket() {
        // quote: 0.5 % one way at 0.01 SOL -> 1.55 % at 0.031, 3.1 % both ways; depth model 0.4 %
        assertEquals(3.1, RoundTrip8418.impactRoundTripPct8418(0.4, 0.5, 0.01, 0.031), 1e-9)
        assertEquals(0.4, RoundTrip8418.impactRoundTripPct8418(0.4, 0.01, 0.01, 0.031), 1e-9)
        assertEquals(0.4, RoundTrip8418.impactRoundTripPct8418(0.4, null, 0.0, 0.031), 1e-9)
        assertEquals(0.0, RoundTrip8418.impactRoundTripPct8418(Double.NaN, null, 0.0, 0.031), 1e-9)
    }

    // ── itemised trip at three tickets ──

    @Test fun itemisedTripShowsFixedSolApartAndShrinksWithTheTicket() {
        // Jupiter (Metis) round trip on an AMM / graduated pool: fee 21,280 a leg (receipt), tips 25,000 + 200,000
        // (protective sell), rent reclaimed, venue 0.25 % a side, route 0.0213 % a side (landed share), slippage allowance.
        fun at(t: Double) = RoundTrip8418.itemise8418(t, 21_280L, 25_000L, 200_000L, 0.0, 0.25, 0.0213, 0.0, 0.0, 1.0)
        val a = at(0.031); val b = at(0.1); val c = at(0.5)
        // superseded by V5.0.8418 — 0.0002676 was a rounded figure: base 0.00001 + priority 0.00003256 + tips 0.000225
        // (each asserted below) is exactly 0.00026756 SOL.
        assertEquals(0.00026756, a.fixedSol, 1e-10)
        assertEquals(a.baseFeeSol + a.prioritySol + a.tipSol + a.rentSol, a.fixedSol, 1e-15)
        assertEquals(0.00001, a.baseFeeSol, 1e-12)
        assertEquals(0.00003256, a.prioritySol, 1e-12)
        assertEquals(0.000225, a.tipSol, 1e-12)
        assertEquals(0.0, a.rentSol, 0.0)
        assertEquals(0.863, a.fixedPct, 0.001)
        assertEquals(1.5426, a.variablePct, 1e-9)
        assertEquals(2.41, a.totalPct, 0.01)
        assertEquals(1.81, b.totalPct, 0.01)
        assertEquals(1.60, c.totalPct, 0.01)
        // rent that does NOT come back is fixed SOL: a never-listing reclaimer costs the whole deposit (6.58 % at 0.031)
        assertEquals(6.578, RoundTrip8418.itemise8418(0.031, 5_000L, 0L, 0L, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0).fixedPct - 0.0323, 0.001)
    }

    // ── what the cost drives ──

    @Test fun stopsAndEntryBarFollowTheReceiptTripNotTheMarkMedian() {
        // 5.0.8390 mark median 9.28 % widened TREASURY's 5 % stop to 13.92 % and QUALITY's 10 % to 13.92 %
        assertEquals(13.92, FieldManual7715.stopPct8392("TREASURY", 9.28), 1e-9)
        assertEquals(13.92, FieldManual7715.stopPct8392("QUALITY", 9.28), 1e-9)
        // at the receipt trip (~2.41 % at 0.031) every desk trades its mandate again
        assertEquals(5.0, FieldManual7715.stopPct8392("TREASURY", 2.41), 1e-9)
        assertEquals(10.0, FieldManual7715.stopPct8392("QUALITY", 2.41), 1e-9)
        // the entry bar: 1.5 x cost
        assertFalse(NetEdge8030.entryClears8030(12.0, 9.28))
        assertTrue(NetEdge8030.entryClears8030(12.0, 2.41))
    }

    // ── rent ──

    @Test fun everyLiveFullCloseQueuesTheRentCloseAndPaperNever() {
        assertTrue(PositionCloseLedger.rentReclaimQueued8418("LIVE", 0L))
        assertFalse(PositionCloseLedger.rentReclaimQueued8418("LIVE", 5L))
        assertFalse(PositionCloseLedger.rentReclaimQueued8418("PAPER", 0L))
        val live = "Rent8418Live" + System.nanoTime()
        val paper = "Rent8418Paper" + System.nanoTime()
        PositionCloseLedger.markClosed(live, "CLOSED_BY_CONFIRMED_ZERO_8418", 0, mode = "LIVE")
        PositionCloseLedger.markClosed(paper, "PAPER_CLOSE_8418", 0, mode = "PAPER")
        assertTrue(RentReclaimer7927.queued8418(live))
        assertFalse(RentReclaimer7927.queued8418(paper))
        // the refundable deposit is never charged while the reclaimer closes what it lists
        assertEquals(0.0, Costs8369.rentLossRate8369(0, 4L, 0, listedOnce = true, overdue = true), 0.0)
        assertEquals(1.0, Costs8369.rentLossRate8369(0, 0L, 0, listedOnce = false, overdue = true), 0.0)
    }

    // ── sell-side break-even ──

    @Test fun sellSideBreakEvenUsesTheRoundTripThePositionPays() {
        val ts = TokenState(mint = "BreakEven8418" + System.nanoTime(), symbol = "B")
        ts.lastLiquidityUsd = 30_000.0
        val legacy = 14.7
        val c = LiveRestoreExecutionPolicy.receiptCostPct8418(ts, legacy)
        assertTrue("cost=$c", c.isFinite() && c < legacy)
        assertEquals(NetEdge8030.costPct8030("UNKNOWN", ts.position.costSol, LiveRestoreExecutionPolicy.trustedLiquidityUsd(ts), ts.mint, ts.lastMcap), c, 1e-9)
    }
}

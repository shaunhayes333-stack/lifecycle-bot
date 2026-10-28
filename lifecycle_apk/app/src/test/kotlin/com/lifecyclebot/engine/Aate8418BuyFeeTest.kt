package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalPositionAuthority6441
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8418 — owner: "re add the 0.5% fee on the buy side. leave the performance based fee on the sell side." and the
 * performance fee is 1% of realised net profit on winners. Behaviour of BuyAppFee8418, the canonical booking, the cost
 * model's asymmetric legs and the performance-fee rate.
 */
class Aate8418BuyFeeTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private class Rec8418(val failAccrue: Boolean = false) : BuyAppFee8418.Sink8418 {
        val accrued = ArrayList<Triple<String, Double, String>>()
        val queued = ArrayList<Triple<String, Double, String>>()
        override fun accrue(dest: String, sol: Double, tag: String): Double {
            if (failAccrue) throw IllegalStateException("prefs write failed")
            accrued.add(Triple(dest, sol, tag))
            return sol
        }
        override fun enqueue(dest: String, sol: Double, tag: String) { queued.add(Triple(dest, sol, tag)) }
    }

    private val trader = "TraderWallet8418xxxxxxxxxxxxxxxxxxxxxxxxxxx"

    @Test fun aBuyOfXSolAccruesExactlyHalfAPercentSplitFiftyFifty() {
        val sink = Rec8418()
        val x = 0.2
        val fee = BuyAppFee8418.chargeBuy8418(trader, x, "buy_finalized_8418", sink)
        assertEquals(0.005 * x, fee, 1e-15)
        assertEquals(2, sink.accrued.size)
        assertEquals(BuyAppFee8418.WALLET_1_8418, sink.accrued[0].first)
        assertEquals(BuyAppFee8418.WALLET_2_8418, sink.accrued[1].first)
        assertEquals(0.0025 * x, sink.accrued[0].second, 1e-15)
        assertEquals(0.0025 * x, sink.accrued[1].second, 1e-15)
        assertEquals(0.005 * x, sink.accrued.sumOf { it.second }, 1e-15)
        assertEquals("buy_finalized_8418_w1", sink.accrued[0].third)
        assertTrue(sink.queued.isEmpty())
        assertEquals(0.005 * x, BuyAppFee8418.bookedFeeSol8418(x), 1e-15)
        assertEquals(0.00015, BuyAppFee8418.buyFeeSol8418(0.03), 1e-15)
    }

    @Test fun aSecondChargeForTheSameSignatureIsNotAccruedAgain() {
        // V5.0.8418 integration — the booking key already used (DUPLICATE) means the split was sent once already.
        val sink = Rec8418()
        val first = BuyAppFee8418.chargeUnlessDuplicate8418(CanonicalPositionAuthority6441.MutateResult.APPLIED, trader, 0.2, "topup_8418", sink)
        val again = BuyAppFee8418.chargeUnlessDuplicate8418(CanonicalPositionAuthority6441.MutateResult.DUPLICATE, trader, 0.2, "topup_8418", sink)
        assertEquals(0.001, first, 1e-15)
        assertEquals(0.0, again, 1e-15)
        assertEquals(2, sink.accrued.size)
        assertEquals(0.001, sink.accrued.sumOf { it.second }, 1e-15)
        // A booking that could not land (position gone) still charges: the wallet paid for the buy.
        val orphan = BuyAppFee8418.chargeUnlessDuplicate8418(CanonicalPositionAuthority6441.MutateResult.UNKNOWN_POSITION, trader, 0.2, "t", sink)
        assertEquals(0.001, orphan, 1e-15)
    }

    @Test fun aTopUpPaysTheFeeOnItsReceiptCostNotThePlannedSol() {
        // Receipt: 0.100005 SOL left the owner, 0.000005 of it the network fee -> cost 0.1 (planned was 0.12).
        val fill = Settlement8418.Fill8418("sigTopUp8418", BigInteger.valueOf(5_000_000L), 6, 5.0, 0.1, 0.000005)
        assertEquals(0.0005, BuyAppFee8418.bookedFeeSol8418(fill.costSol), 1e-15)
        assertTrue(BuyAppFee8418.bookedFeeSol8418(fill.costSol) < BuyAppFee8418.bookedFeeSol8418(0.12))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("BuyAppFee8418.chargeLiveBuy8418(wallet, fill8418.costSol,"))
        assertTrue(ex.contains("feeSol = BuyAppFee8418.bookedFeeSol8418(fill8418.costSol))"))
    }

    @Test fun aShareAddressedToTheTradingWalletGoesToTheOtherFeeWallet() {
        val sink = Rec8418()
        BuyAppFee8418.chargeBuy8418(BuyAppFee8418.WALLET_1_8418, 1.0, "t", sink)
        assertEquals(2, sink.accrued.size)
        assertTrue(sink.accrued.all { it.first == BuyAppFee8418.WALLET_2_8418 })
        assertEquals(0.005, sink.accrued.sumOf { it.second }, 1e-15)
    }

    @Test fun aFailedAccrualIsQueuedForRetryNotLost() {
        val sink = Rec8418(failAccrue = true)
        val fee = BuyAppFee8418.chargeBuy8418(trader, 0.1, "topup_8418", sink)
        assertEquals(0.0005, fee, 1e-15)
        assertTrue(sink.accrued.isEmpty())
        assertEquals(2, sink.queued.size)
        assertEquals(0.0005, sink.queued.sumOf { it.second }, 1e-15)
        assertEquals("topup_8418_w1_acc_fail", sink.queued[0].third)
        assertEquals(BuyAppFee8418.WALLET_2_8418, sink.queued[1].first)
    }

    @Test fun dustAndUnknownCostsChargeNothing() {
        val sink = Rec8418()
        assertEquals(0.0, BuyAppFee8418.chargeBuy8418(trader, 0.0005, "t", sink), 0.0)   // 0.0000025 SOL < dust
        assertEquals(0.0, BuyAppFee8418.chargeBuy8418(trader, Double.NaN, "t", sink), 0.0)
        assertEquals(0.0, BuyAppFee8418.chargeBuy8418(trader, -1.0, "t", sink), 0.0)
        assertTrue(sink.accrued.isEmpty() && sink.queued.isEmpty())
        assertEquals(0.0, BuyAppFee8418.bookedFeeSol8418(0.0005), 0.0)
    }

    @Test fun sellsAccrueNoVolumeFee() {
        assertEquals(0.0, CostTrim8151.SELL_APP_FEE_PCT_8418, 0.0)
        assertEquals(0.0, BuyAppFee8418.sellFeeSol8418(1.0), 0.0)
        assertEquals(0.0, BuyAppFee8418.sellFeeSol8418(250.0), 0.0)
        val ex = src("engine/Executor.kt")
        // Exactly two charge sites — the finalized live BUY and the live top-up; no sell, partial or profit-lock site.
        assertEquals(2, ex.split("BuyAppFee8418.chargeLiveBuy8418(").size - 1)
        assertTrue(ex.contains("\"buy_finalized_8418\"") && ex.contains("\"topup_8418\""))
        assertFalse(ex.contains("sendFeeSplit("))
        // Paper mirrors live: the paper buy charges the buy rate, paper sells keep the 0.0 sell rate.
        // superseded by V5.0.8418 — the paper buy fee arithmetic moved into PaperFillCore8418.buyFeeSol8418 (int-8418-leftovers);
        // the buy rate is still what paperBuy passes. Was: "val fee6485 = actualSol * (venueFeePct7287 / 100.0 + MEME_BUY_FEE_PERCENT_8418) +".
        assertTrue(ex.contains("PaperFillCore8418.buyFeeSol8418(actualSol, venueFeePct7287, MEME_BUY_FEE_PERCENT_8418)"))
        assertEquals(0.5 * (1.25 / 100.0 + 0.005) + com.lifecyclebot.engine.truth.PaperVenueCost7287.FIXED_SOL_PER_SIDE,
            com.lifecyclebot.engine.truth.PaperFillCore8418.buyFeeSol8418(0.5, 1.25, CostTrim8151.BUY_APP_FEE_PCT_8418 / 100.0), 1e-15)
        assertTrue(ex.contains("@Volatile private var MEME_TRADING_FEE_PERCENT = 0.0"))
    }

    @Test fun performanceFeeIsOnePercentOfRealisedNetProfitOnWinnersOnly() {
        assertEquals(100L, PerformanceFees8391.RATE_BPS_8418)
        assertEquals(0.01, PerformanceFees8391.RATE, 0.0)
        val win = PerformanceFees8391.assess(PerformanceFees8391.State(), 2_000_000_000L)   // +2 SOL
        assertEquals(20_000_000L, win.fee)                                                  // 0.02 SOL
        val loss = PerformanceFees8391.assess(win.state, -1_000_000_000L)
        assertEquals(0L, loss.fee)
        val back = PerformanceFees8391.assess(loss.state, 1_000_000_000L)                  // back to the old high: carryforward
        assertEquals(0L, back.fee)
        assertEquals("1", PerformanceFees8391.ratePctLabel8418())
    }

    @Test fun theCostModelsBuyLegCarriesTheHalfPercentAndTheSellLegNone() {
        assertEquals(0.25, CostTrim8151.APP_FEE_PCT_PER_SIDE_8151, 1e-12)
        val without = CostTrim8151.roundTripPct8151(0.25, 0.1, 0.0, 0.0, 0.0, 1.0)
        val with = CostTrim8151.roundTripPct8151(0.25, 0.1, CostTrim8151.APP_FEE_PCT_PER_SIDE_8151, 0.0, 0.0, 1.0)
        assertEquals(0.5, with - without, 1e-12)                     // the round trip pays exactly the buy's 0.5%
        assertEquals(0.35 + 0.5, CostTrim8151.buyLegPct8418(with), 1e-12)
        assertEquals(0.35, CostTrim8151.sellLegPct8418(with), 1e-12)
        // (1 + g)(1 - 0.0035) = 1.0085
        assertEquals((1.0085 / 0.9965 - 1.0) * 100.0, CostTrim8151.breakEvenMovePct8418(with), 1e-9)
        assertEquals("appFee=buy0.5%/sell0%+perf1%", CostTrim8151.appFeeLabel8418())
        assertTrue(CostMeter8151.statusLine8151().contains("appFee=buy0.5%/sell0%+perf1%"))
    }

    @Test fun theBuyFeeIsBookedIntoTheCanonicalPositionOnce() {
        val mint = "FEE8418Mint${System.nanoTime()}"
        val pid = "LIVE:$mint:f8418"
        val open = CanonicalPositionAuthority6441.openPosition(
            idempotencyKey = "OPEN8418:$pid", positionId = pid, mint = mint, symbol = "F8418", lane = "SHITCOIN", runId = "r8418",
            entryCostSol = 0.4, openedQtyRaw = BigInteger.valueOf(4_000_000L), tokenDecimals = 6, feesSol = 0.00001, paperMode = false,
            modeOverride = "live", entryPriceUsd = 0.001, entryPriceSource = "TEST_8418",
        )
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED, open)
        val fee = BuyAppFee8418.bookedFeeSol8418(0.4)
        assertEquals(CanonicalPositionAuthority6441.MutateResult.APPLIED,
            CanonicalPositionAuthority6441.bookAppFee8418("APP_BUY_FEE_8418:t:sig1", pid, fee))
        assertEquals(CanonicalPositionAuthority6441.MutateResult.DUPLICATE,
            CanonicalPositionAuthority6441.bookAppFee8418("APP_BUY_FEE_8418:t:sig1", pid, fee))
        val p = CanonicalPositionAuthority6441.getPosition(pid)!!
        assertEquals(0.00001 + 0.002, p.feesSol, 1e-12)
        assertEquals(0.4, p.entryCostSol, 1e-12)                       // the chain-proven cost basis is untouched
        assertEquals(CanonicalPositionAuthority6441.MutateResult.UNKNOWN_POSITION,
            CanonicalPositionAuthority6441.bookAppFee8418("APP_BUY_FEE_8418:t:sig2", "LIVE:missing8418", fee))
    }
}

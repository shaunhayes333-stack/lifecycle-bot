package com.lifecyclebot.engine

import com.lifecyclebot.engine.ExecutableOpenGate
import com.lifecyclebot.engine.ScalingMode
import com.lifecyclebot.engine.SmartSizer
import com.lifecyclebot.engine.SolUsd8418
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.engine.truth.EconomicUnitInvariant7061
import com.lifecyclebot.engine.truth.PaperSeededPrior6991
import com.lifecyclebot.engine.truth.SizeTriple8418
import com.lifecyclebot.perps.PerpsDirection
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsRiskTier
import com.lifecyclebot.perps.PerpsSignal
import com.lifecyclebot.perps.PerpsTraderAI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8418 — owner items 7 (SOL size × SOL/USD, not × the asset price), 8 (requested,
 * submitted and recorded sizes agree; adjustments before the seal) and 10 (no $150/SOL).
 * Behaviour tests: they call the functions and check their outputs.
 */
class Aate8418SizingUnitsTest {

    private fun withSolUsd(price: Double, block: () -> Unit) {
        val saved = WalletManager.lastKnownSolPrice
        try {
            if (price > 0.0) WalletManager.noteObservedSolPrice7042(price, System.currentTimeMillis())
            else WalletManager.lastKnownSolPrice = 0.0
            block()
        } finally {
            WalletManager.lastKnownSolPrice = saved
        }
    }

    @Test
    fun `unit chain is SOL size times SOL-USD then divided by the asset price`() {
        // 0.5 SOL at SOL=$200 is $100 of notional, whatever BTC costs.
        assertEquals(100.0, EconomicUnitInvariant7061.solToUsd8418(0.5, 200.0), 1e-9)
        // $100 of BTC at $50,000 is 0.002 BTC.
        assertEquals(0.002, EconomicUnitInvariant7061.usdToAssetQty8418(100.0, 50_000.0), 1e-12)
        // Unknown rate / price is NaN, never a number.
        assertTrue(EconomicUnitInvariant7061.solToUsd8418(0.5, 0.0).isNaN())
        assertTrue(EconomicUnitInvariant7061.solToUsd8418(0.5, Double.NaN).isNaN())
        assertTrue(EconomicUnitInvariant7061.usdToAssetQty8418(100.0, 0.0).isNaN())
        assertNull(SolUsd8418.usdNotional(0.5, null))
        assertNull(SolUsd8418.assetQuantity(null, 50_000.0))
        assertEquals(0.002, SolUsd8418.assetQuantity(SolUsd8418.usdNotional(0.5, 200.0), 50_000.0)!!, 1e-12)
    }

    @Test
    fun `live notional uses the observed SOL rate and never the traded asset price`() = withSolUsd(200.0) {
        val usd = SolUsd8418.liveUsdNotionalOrRefuse(1.0, "TEST_LIVE_8418")
        assertNotNull(usd)
        assertEquals(200.0, usd!!, 1e-9)
        // The pre-8418 PerpsTraderAI defect: 1 SOL × BTC entry price = $60,000.
        assertFalse(kotlin.math.abs(usd - 60_000.0) < 1.0)
    }

    @Test
    fun `unknown or stale SOL rate refuses with a named counter instead of 150`() = withSolUsd(0.0) {
        val before = SolUsd8418.refusalCount("TEST_UNKNOWN_8418")
        assertNull(SolUsd8418.liveOrNull())
        assertNull(SolUsd8418.liveUsdNotionalOrRefuse(1.0, "TEST_UNKNOWN_8418"))
        assertEquals(before + 1, SolUsd8418.refusalCount("TEST_UNKNOWN_8418"))
        // A real observation older than five minutes is stale too.
        WalletManager.noteObservedSolPrice7042(180.0, System.currentTimeMillis())
        assertNull(SolUsd8418.liveOrNull(System.currentTimeMillis() + 6 * 60_000L))
    }

    @Test
    fun `size triple agrees only within lamport rounding`() {
        assertTrue(SizeTriple8418.check("TEST_OK_8418", 0.5, 0.5, 0.5))
        assertTrue(SizeTriple8418.check("TEST_OK_8418", 0.5, 0.5 + 4e-10, 0.5))
        assertEquals(0L, SizeTriple8418.mismatchCount("TEST_OK_8418"))
        // A correlation penalty applied after the seal: sealed 0.5, recorded 0.25.
        assertFalse(SizeTriple8418.check("TEST_BAD_8418", 0.5, 0.5, 0.25))
        // A rent trim after the seal: submitted 0.488 for a 0.5 ticket.
        assertFalse(SizeTriple8418.check("TEST_BAD_8418", 0.5, 0.488, 0.5))
        assertFalse(SizeTriple8418.check("TEST_BAD_8418", 0.5, Double.NaN, 0.5))
        assertEquals(3L, SizeTriple8418.mismatchCount("TEST_BAD_8418"))
    }

    private fun intent(size: Double, symbol: String) = ExecutableOpenGate.ExecutionIntent(
        attemptId = "a8418", candidateId = "c8418", candidateVersion = 1L, mint = symbol, mode = "PAPER",
        canonicalLane = "PERPS", fdgVerdict = "BUY", fdgAllowed = true, authorityVersion = 1L,
        resolvedSize = size, createdAt = System.currentTimeMillis(), symbol = symbol,
    )

    private fun signal(market: PerpsMarket) = PerpsSignal(
        market = market, direction = PerpsDirection.LONG, score = 70, confidence = 70,
        recommendedLeverage = 2.0, recommendedSizePct = 5.0, recommendedRiskTier = PerpsRiskTier.SNIPER,
        takeProfitPct = 5.0, stopLossPct = 3.0, reasons = emptyList(), aiReasoning = "test",
    )

    @Test
    fun `perps open refuses a size that differs from its sealed intent`() = withSolUsd(200.0) {
        val before = SizeTriple8418.mismatchCount("PERPS_OPEN")
        val opened = PerpsTraderAI.openPosition(
            market = PerpsMarket.ETH, direction = PerpsDirection.LONG, entryPrice = 3_000.0,
            sizeSol = 0.25, leverage = 2.0, signal = signal(PerpsMarket.ETH), isPaper = true,
            executionIntent6565 = intent(0.5, "ETH"),
        )
        assertNull(opened)
        assertEquals(before + 1, SizeTriple8418.mismatchCount("PERPS_OPEN"))
    }

    @Test
    fun `perps open refuses when SOL-USD is unknown instead of pricing at the asset`() = withSolUsd(0.0) {
        val before = SolUsd8418.refusalCount("PERPS_OPEN")
        val opened = PerpsTraderAI.openPosition(
            market = PerpsMarket.ETH, direction = PerpsDirection.LONG, entryPrice = 3_000.0,
            sizeSol = 0.5, leverage = 2.0, signal = signal(PerpsMarket.ETH), isPaper = true,
            executionIntent6565 = intent(0.5, "ETH"),
        )
        assertNull(opened)
        assertEquals(before + 1, SolUsd8418.refusalCount("PERPS_OPEN"))
    }

    @Test
    fun `correlation adjustment is a pre-seal function that leaves an uncorrelated size alone`() {
        assertEquals(0.4, PerpsTraderAI.correlationAdjustedSize8418(PerpsMarket.BTC, 0.4), 1e-12)
    }

    @Test
    fun `smart sizer refuses an unpriced treasury rather than borrowing a constant`() {
        val refused = SmartSizer.unpricedRefusal8418(0.0)
        assertNotNull(refused)
        assertEquals(0.0, refused!!.solAmount, 0.0)
        assertEquals("sol_usd_unknown_8418", refused.cappedBy)
        assertNotNull(SmartSizer.unpricedRefusal8418(Double.NaN))
        assertNull(SmartSizer.unpricedRefusal8418(200.0))
        assertEquals(ScalingMode.Tier.MICRO, SmartSizer.treasuryTier8418(0.0, "TEST_TIER_8418"))
    }

    @Test
    fun `paper impact prior treats an unknown SOL rate as thin depth`() {
        assertEquals(0.25, PaperSeededPrior6991.impactSurvival6992(1.0, 1_000_000.0, 0.0), 0.0)
        // With a real rate, 1 SOL round trip in a $1M pool is noise.
        assertEquals(1.0, PaperSeededPrior6991.impactSurvival6992(1.0, 1_000_000.0, 200.0), 0.0)
    }
}

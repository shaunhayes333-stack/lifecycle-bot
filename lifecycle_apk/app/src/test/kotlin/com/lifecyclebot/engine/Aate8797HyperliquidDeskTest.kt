package com.lifecyclebot.engine

import com.lifecyclebot.hyperliquid.HlApi
import com.lifecyclebot.hyperliquid.HlBrain
import com.lifecyclebot.hyperliquid.HlCosts
import com.lifecyclebot.hyperliquid.HlDesk
import com.lifecyclebot.hyperliquid.HlHistory
import com.lifecyclebot.hyperliquid.HlMsgPack
import com.lifecyclebot.hyperliquid.HlRisk
import com.lifecyclebot.hyperliquid.HlSettings
import com.lifecyclebot.hyperliquid.HlSigner
import com.lifecyclebot.hyperliquid.HlStore
import com.lifecyclebot.hyperliquid.HlWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8797 — owner: "its needs to built out with everything it needs, data intelligence etc" and "also retire what we
 * cant trade". The Hyperliquid desk reads every dex (HIP-3 stocks, indices, metals, commodities), keeps its own market
 * history, learns each coin's cost, runs five strategy families chosen and graded on separate halves, sizes by half-Kelly
 * inside a liquidation guard, and the old Solana markets traders no longer open anything.
 * Expected values for the brain come from a Python port run on the same synthetic markets (java.util.Random reproduced).
 */
class Aate8797HyperliquidDeskTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val key = "0x0123456789012345678901234567890123456789012345678901234567890123"

    @Test fun hip3DexesParseWithTheirOwnAssetIds() {
        assertEquals(listOf("", "xyz", "flx"), HlApi.parsePerpDexs("""[null,{"name":"xyz","fullName":"XYZ"},{"name":"flx"}]"""))
        val body = """[{"universe":[{"name":"xyz:XYZ100","szDecimals":4,"maxLeverage":20},{"name":"NVDA","szDecimals":3,"maxLeverage":10}],"collateralToken":0},
            [{"funding":"0.00001","openInterest":"10","dayNtlVlm":"300000000","markPx":"25000","oraclePx":"24990","premium":null,"impactPxs":null,"midPx":null,"prevDayPx":"24800"},
             {"funding":"0","openInterest":"5","dayNtlVlm":"90000000","markPx":"180.5","oraclePx":"180.4","premium":"0.0002","prevDayPx":"179"}]]"""
        val d = HlApi.parseMetaAndCtxs(body, "xyz", 1)
        assertEquals(listOf("xyz:XYZ100", "xyz:NVDA"), d.assets.map { it.name })
        assertEquals(listOf(110_000, 110_001), d.assets.map { it.index })
        assertTrue(d.assets.all { it.onlyIsolated && it.dex == "xyz" })
        assertTrue(d.assets[0].premium.isNaN()); assertEquals(0.0002, d.assets[1].premium, 1e-12)
        assertEquals(0, d.collateralToken)
        assertEquals(5, HlApi.assetId(0, 5)); assertEquals(120_007, HlApi.assetId(2, 7))
        assertEquals("xyz:NVDA", HlApi.coinName("xyz", "NVDA")); assertEquals("BTC", HlApi.coinName("", "BTC"))
    }

    @Test fun booksAccountsAndOrderAnswersParse() {
        val b = HlApi.parseBook("""{"coin":"BTC","time":1,"levels":[[{"px":"99.9","sz":"2","n":1},{"px":"99.7","sz":"5","n":2}],[{"px":"100.1","sz":"1","n":1},{"px":"100.3","sz":"5","n":3}]]}""")
        assertEquals(99.9, b.bids[0].first, 0.0); assertEquals(5.0, b.asks[1].second, 0.0)
        val a = HlApi.parseAccount("""{"marginSummary":{"accountValue":"150.5","totalNtlPos":"300"},"crossMaintenanceMarginUsed":"3","withdrawable":"80",
            "assetPositions":[{"type":"oneWay","position":{"coin":"xyz:NVDA","szi":"-0.8","entryPx":"181","liquidationPx":null,"marginUsed":"36","unrealizedPnl":"0.4","leverage":{"type":"isolated","value":4,"rawUsd":"180"}}}]}""")
        assertEquals(150.5, a.accountValue, 1e-9); assertEquals(3.0, a.maintenanceUsed, 1e-9)
        assertTrue(a.positions[0].isolated); assertEquals(4, a.positions[0].leverage); assertTrue(a.positions[0].liquidationPx.isNaN())
        val filled = HlApi.parseOrderResponse("""{"status":"ok","response":{"type":"order","data":{"statuses":[{"filled":{"totalSz":"0.02","avgPx":"1891.4","oid":77747314}}]}}}""")
        assertEquals(HlApi.OrderResult.Filled(0.02, 1891.4, 77747314L), filled[0])
        assertEquals(HlApi.OrderResult.Resting(77738308L), HlApi.parseOrderResponse("""{"status":"ok","response":{"type":"order","data":{"statuses":[{"resting":{"oid":77738308}}]}}}""")[0])
        val err = HlApi.parseOrderResponse("""{"status":"ok","response":{"type":"order","data":{"statuses":[{"error":"Order must have minimum value of ${'$'}10."}]}}}""")[0]
        assertTrue(err is HlApi.OrderResult.Rejected && err.error.contains("minimum value"))
        assertTrue(HlApi.parseOrderResponse("""{"status":"err","response":"User or API Wallet does not exist."}""")[0] is HlApi.OrderResult.Rejected)
        assertTrue(HlApi.parseOrderResponse(null)[0] is HlApi.OrderResult.Rejected)
        assertTrue(HlApi.parseOk("""{"status":"ok","response":{"type":"default"}}""")); assertFalse(HlApi.parseOk("""{"status":"err","response":"x"}"""))
    }

    @Test fun leverageActionSignsLikeTheSdk() {
        val a = HlWire.updateLeverageAction(110_003, false, 3)
        assertEquals("84a474797065ae7570646174654c65766572616765a56173736574ce0001adb3a7697343726f7373c2a86c6576657261676503", hex(HlMsgPack.pack(a)))
        assertEquals("43b3ce1655a008614b62c03825379669e2c552c9ffce53d1f7c5e521765145ca", hex(HlSigner.actionHash(a, null, 1_794_700_000_000L)))
        val s = HlSigner.signL1Action(a, 1_794_700_000_000L, key, mainnet = true)
        assertEquals("0xeddc3806fb770760f4663e1f1f2d3db2c6ca023974bfb42cb30e55c5735f3e7c", s.r)
        assertEquals("0xf9bcfe6e1a8d9c7c0833be98b3fed3d47c89415d7965f7a57670505fde883b9", s.s)
        assertEquals(28, s.v)
    }

    @Test fun historyIsKeptAndRestored() {
        val h = HlHistory("xyz:NVDA")
        h.bars[20_000L] = doubleArrayOf(1.0, 2.0, 0.5, 1.5, 1e6); h.funding[20_000L] = 0.0002; h.fundingFromDay = 19_990L
        h.observe(20_000L, 10.0, 180.0, 0.001); h.observe(20_000L, 12.0, 181.0, 0.003)
        val back = HlStore.fromJson(HlStore.toJson(h))
        assertEquals("xyz:NVDA", back.coin); assertEquals(1.5, back.bars[20_000L]!![3], 0.0)
        assertEquals(12.0 * 181.0, back.oi[20_000L]!!, 1e-9)                  // last read of the day
        assertEquals(0.004, back.premium[20_000L]!![0], 1e-12); assertEquals(2.0, back.premium[20_000L]!![1], 0.0)
        assertEquals(19_990L, back.fundingFromDay)
        h.trim(20_001L); assertTrue(h.bars.isEmpty() && h.oi.isEmpty())
    }

    @Test fun costsAreMeasuredFromTheBookAndTheFills() {
        val asks = listOf(100.1 to 1.0, 100.3 to 5.0); val bids = listOf(99.9 to 2.0, 99.7 to 5.0)
        assertEquals(0.0019980019980019303, HlCosts.walk(asks, 100.0, 200.0, true), 1e-12)
        assertEquals(0.0010020040080159776, HlCosts.walk(bids, 100.0, 200.0, false), 1e-12)
        assertTrue(HlCosts.walk(asks, 100.0, 1_000.0, true).isNaN())            // thinner than the order: unknown, not cheap
        assertEquals(0.001500003003008954, HlCosts.bookSide(HlApi.Book(bids, asks), 200.0), 1e-12)
        assertEquals(HlCosts.FEE_HIP3, HlCosts.feeOf("xyz:NVDA"), 0.0); assertEquals(HlCosts.FEE_MAIN, HlCosts.feeOf("BTC"), 0.0)
        assertEquals(HlCosts.UNMEASURED_SLIP, HlCosts.slipOf(null), 0.0)
        assertEquals(0.002, HlCosts.slipOf(HlCosts.Est(0.002, 3, 0.01, 2)), 1e-12)   // two fills are not yet a measurement
        assertEquals(0.006, HlCosts.slipOf(HlCosts.Est(0.002, 3, 0.01, 3)), 1e-12)
    }

    private fun synth(seed: Long, drift: (Int, Int) -> Double, n: Int = 400, coins: Int = 5): HlBrain.Panel {
        val rnd = java.util.Random(seed)
        val close = Array(coins) { DoubleArray(n) }
        for (c in 0 until coins) { var x = 100.0; for (d in 0 until n) { x *= Math.exp(drift(c, d) + 0.02 * rnd.nextGaussian()); close[c][d] = x } }
        val nan = Array(coins) { DoubleArray(n) { Double.NaN } }
        return HlBrain.Panel((0 until coins).map { "C$it" }, 0L, close, Array(coins) { DoubleArray(n) { 1e6 } },
            Array(coins) { DoubleArray(n) { 0.0001 } }, nan, nan, DoubleArray(coins) { 0.001 })
    }

    @Test fun theBrainArmsOnlyOnWhatItProvedOutOfSample() {
        val trend = HlBrain.evaluate(synth(7L, { c, d -> 0.006 * (if ((d / 60 + c) % 2 == 0) 1 else -1) }), 2.0)
        assertTrue("persistent trends are learned and proven on the unseen half", trend.armed)
        assertEquals(2.0, trend.leverage, 1e-9)                                // half-Kelly above the owner's cap: capped
        assertEquals(HlBrain.Family.values().toList(), trend.picks.map { it.family })
        assertTrue(trend.test!!.days >= HlBrain.MIN_TEST_DAYS && trend.test!!.meanPct - trend.test!!.sePct > 0.15)
        assertTrue(trend.targets.sumOf { Math.abs(it) } <= 1.0 + 1e-9)        // the book is gross 1 before leverage
        assertTrue(trend.blend.keys.any { it.family == HlBrain.Family.TREND })
        val noise = HlBrain.evaluate(synth(11L, { _, _ -> 0.0 }), 2.0)
        assertFalse("a market with nothing in it never arms", noise.armed)
        assertFalse(HlBrain.evaluate(synth(7L, { _, _ -> 0.004 }, n = 200), 2.0).armed)   // too little history to grade
        assertEquals(0.0, HlBrain.evaluate(synth(7L, { c, d -> 0.006 * (if ((d / 60 + c) % 2 == 0) 1 else -1) }), 0.0).leverage, 0.0)
    }

    @Test fun signalsAndTheSolver() {
        val up = synth(3L, { _, _ -> 0.01 })
        assertEquals(1, HlBrain.trendSignal(up, 0, 300, 40, true))
        val down = synth(3L, { _, _ -> -0.01 })
        assertEquals(-1, HlBrain.trendSignal(down, 0, 300, 40, true)); assertEquals(0, HlBrain.trendSignal(down, 0, 300, 40, false))
        assertEquals(0, HlBrain.trendSignal(up, 0, 10, 40, true))
        val x = HlBrain.solve(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)), doubleArrayOf(3.0, 5.0))!!
        assertEquals(0.8, x[0], 1e-12); assertEquals(1.4, x[1], 1e-12)
        assertNull(HlBrain.solve(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)), doubleArrayOf(1.0, 2.0)))
        assertEquals(0.0, HlBrain.halfKelly(DoubleArray(30) { -0.01 }), 0.0)
    }

    private fun asset(name: String, dex: String, maxLev: Int, mark: Double = 100.0, vlm: Double = 1e8) =
        HlApi.Asset(0, dex, name, 3, maxLev, dex.isNotEmpty(), mark, mark, 0.0, 0.0, vlm, 1.0, mark)

    @Test fun riskSizesPerDexInsideTheLiquidationGuard() {
        assertEquals(2, HlRisk.guardLeverage(0.05, 10)); assertEquals(7, HlRisk.guardLeverage(0.02, 40))
        assertEquals(1, HlRisk.guardLeverage(Double.NaN, 40)); assertEquals(3, HlRisk.guardLeverage(0.005, 3))
        val assets = mapOf("BTC" to asset("BTC", "", 40), "xyz:NVDA" to asset("xyz:NVDA", "xyz", 10))
        val w = mapOf("BTC" to 0.5, "xyz:NVDA" to -0.5); val vol = mapOf("BTC" to 0.02, "xyz:NVDA" to 0.03)
        val unfunded = HlRisk.plan(w, 2.0, mapOf("" to 100.0, "xyz" to 0.0), 1_000.0, assets, vol)
        assertEquals(listOf("BTC"), unfunded.map { it.coin })                 // an unfunded HIP-3 dex holds nothing
        assertEquals(100.0, unfunded[0].targetUsd, 1e-9); assertEquals(7, unfunded[0].leverage); assertFalse(unfunded[0].isolated)
        val funded = HlRisk.plan(w, 2.0, mapOf("" to 100.0, "xyz" to 50.0), 1_000.0, assets, vol).associateBy { it.coin }
        assertEquals(150.0, funded.getValue("BTC").targetUsd, 1e-9); assertEquals(-150.0, funded.getValue("xyz:NVDA").targetUsd, 1e-9)
        assertEquals(4, funded.getValue("xyz:NVDA").leverage); assertTrue(funded.getValue("xyz:NVDA").isolated)
        val capped = HlRisk.plan(w, 2.0, mapOf("" to 100.0, "xyz" to 50.0), 120.0, assets, vol).associateBy { it.coin }
        assertEquals(60.0, capped.getValue("BTC").targetUsd, 1e-9)            // the owner's budget binds
        // unknown volatility: 1x only, so $20 of book needs $20 of margin against $8 of room: the dex's book shrinks to fit
        val tight = HlRisk.plan(mapOf("xyz:NVDA" to 1.0), 2.0, mapOf("xyz" to 10.0), 1_000.0, assets, emptyMap())
        assertEquals(8.0, tight[0].targetUsd, 1e-9); assertEquals(1, tight[0].leverage)
        assertEquals(Triple(false, 0.001, true), HlRisk.orderFor(0.001, -50.0, 95_000.0, 5))   // a flip closes first
        assertEquals(0.003, HlRisk.slipLimit(0.0), 1e-12); assertEquals(0.01, HlRisk.slipLimit(0.02), 1e-12)
    }

    @Test fun universeLeavesOutWhatCannotBeTraded() {
        val assets = listOf(asset("BTC", "", 40, vlm = 5e9), asset("ETH", "", 25, vlm = 2e9), asset("DOGE", "", 10, vlm = 1e8),
            asset("xyz:NVDA", "xyz", 10, vlm = 3e8), asset("abc:FOO", "abc", 5, vlm = 1e8), asset("xyz:OLD", "xyz", 5, vlm = 0.0))
        val (chosen, out) = HlDesk.chooseUniverse(assets, mapOf("" to 0, "xyz" to 0, "abc" to 5), setOf("DOGE"), setOf("SOL"), 2, 2)
        assertEquals(listOf("BTC", "ETH", "xyz:NVDA"), chosen)
        assertEquals("AT_OPEN_INTEREST_CAP", out["DOGE"]); assertEquals("NON_USDC_COLLATERAL", out["abc:FOO"])
        assertNull(out["xyz:OLD"])
        assertEquals("DAILY_STOP", HlDesk.stopHit(89.0, 100.0, 120.0, 10.0, 25.0))
        assertEquals("DRAWDOWN_STOP", HlDesk.stopHit(95.0, 100.0, 130.0, 10.0, 25.0))
        assertNull(HlDesk.stopHit(99.0, 100.0, 110.0, 10.0, 25.0))
        assertEquals("NO_LEVERAGE_ALLOWED", HlSettings.liveRefusal(HlSettings.Config("0x14791697260e4c9a71f18484c9f997b308e59325", key, false, true, 50.0, 10.0, maxLeverage = 0.0)))
    }

    @Test fun theOldMarketsTradersAreRetired() {
        val plan = src("engine/truth/TraderRuntimePlan6526.kt")
        val bot = src("engine/BotService.kt")
        val retired = src("perps/RetiredMarkets8797.kt")
        assertFalse(plan.contains("stocksEffective") || plan.contains("forexEffective") || plan.contains("perpsEffective"))
        assertFalse(plan.contains("s += EnabledTraderAuthority.Trader.MARKETS_STOCKS") || plan.contains("s += EnabledTraderAuthority.Trader.PERPS"))
        assertTrue(bot.contains("com.lifecyclebot.perps.RetiredMarkets8797.disableEntries()"))
        assertTrue(bot.contains("com.lifecyclebot.perps.RetiredMarkets8797.windDown("))
        assertFalse(bot.contains("com.lifecyclebot.perps.ForexTrader.start()") || bot.contains("com.lifecyclebot.perps.TokenizedStockTrader.start()"))
        val multi = src("ui/MultiAssetActivity.kt")
        assertFalse(multi.contains("ForexTrader.start()") || multi.contains("TokenizedStockTrader.start()") || multi.contains("PerpsExecutionEngine.start("))
        assertTrue(retired.contains("ForexTrader.setEnabled(false)") && retired.contains("PerpsTraderAI.setEnabled(false)"))
        assertTrue(src("engine/PipelineHealthCollector.kt").contains("com.lifecyclebot.perps.RetiredMarkets8797.statusLine()"))
    }
}

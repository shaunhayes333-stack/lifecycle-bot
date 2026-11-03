package com.lifecyclebot.engine

import com.lifecyclebot.perps.JupiterPriceOracle
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketData
import com.lifecyclebot.perps.PerpsMarketDataFetcher
import com.lifecyclebot.perps.PriceAggregator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Aate8521MarketSourceClockTest {
    private val now = 10_000_000L

    private fun base() = PerpsMarketData(
        market = PerpsMarket.SOL, price = 0.0, indexPrice = 0.0, markPrice = 0.0,
        fundingRate = 0.0, fundingRateAnnualized = 0.0, nextFundingTime = 0L,
        openInterestLong = 0.0, openInterestShort = 0.0, volume24h = 0.0,
        high24h = 0.0, low24h = 0.0, priceChange24hPct = 0.0, lastUpdate = now,
    )

    @Test fun repeatedCacheReadsCannotRejuvenateAQuoteForPaperClosing() {
        val original = PriceAggregator.PriceResult(120.0, 3.0, "PYTH", observedAtMs8521 = now - 120_000L)
        val first = original.cached8521(now - 10_000L)
        val normal = first.result8521()
        val stale = first.result8521(stale = true)
        val readAgain = stale.cached8521(now).result8521()
        for (quote in listOf(original, normal, stale, readAgain)) {
            assertEquals(original.observedAtMs8521, quote.observedAtMs8521)
            val market = PerpsMarketDataFetcher.withObservedPrice8521(base(), quote)
            assertEquals(120.0, market.price, 0.0)
            assertEquals(original.observedAtMs8521, market.lastUpdate)
            assertFalse(PaperStopLifecycle8521.eligibleMark8521(PerpsMarket.SOL, market, now))
        }
        assertEquals("STALE_PYTH", stale.source)
        assertEquals(0.0, stale.change24h, 0.0)
    }

    @Test fun actualFreshReplacementCanCloseButUnknownOrFutureSourceTimeCannot() {
        for (at in listOf(now - 1_000L, 0L, now + 1L, now - 30_001L)) {
            val quote = PriceAggregator.PriceResult(121.0, 1.0, "BINANCE", observedAtMs8521 = at)
            val market = PerpsMarketDataFetcher.withObservedPrice8521(base(), quote.cached8521(now).result8521())
            assertEquals(at, market.lastUpdate)
            assertEquals(at == now - 1_000L,
                PaperStopLifecycle8521.eligibleMark8521(PerpsMarket.SOL, market, now))
        }
    }

    @Test fun jupiterV3AndLegacyResponsesPopulateTheRealOracleCacheClock() {
        val mint = "So11111111111111111111111111111111111111112"
        val modern = JSONObject().put(mint, JSONObject().put("usdPrice", 123.45))
        val legacy = JSONObject().put("data", JSONObject().put(mint, JSONObject().put("price", "124.50")))
        assertEquals(123.45, JupiterPriceOracle.acceptPrice8521(modern, "CLOCK_TEST_V3", mint, now)!!, 0.0)
        assertEquals(now, JupiterPriceOracle.observedAtMs8521("CLOCK_TEST_V3"))
        assertEquals(124.50, JupiterPriceOracle.acceptPrice8521(legacy, "CLOCK_TEST_V2", mint, now - 1_000L)!!, 0.0)
        assertEquals(now - 1_000L, JupiterPriceOracle.observedAtMs8521("CLOCK_TEST_V2"))
        assertNull(JupiterPriceOracle.acceptPrice8521(JSONObject(), "CLOCK_TEST_V3", mint, now + 1_000L))
        assertEquals("missing response cannot renew an existing price", now,
            JupiterPriceOracle.observedAtMs8521("CLOCK_TEST_V3"))
        assertNull(JupiterPriceOracle.acceptPrice8521(modern, "CLOCK_TEST_MISSING_TIME", mint, 0L))
        assertEquals(0L, JupiterPriceOracle.observedAtMs8521("CLOCK_TEST_MISSING_TIME"))
    }
}

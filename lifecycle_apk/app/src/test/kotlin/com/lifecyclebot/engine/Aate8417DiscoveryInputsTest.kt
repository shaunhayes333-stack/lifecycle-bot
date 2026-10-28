package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.truth.DiscoveryCapture8400
import com.lifecyclebot.engine.truth.PairFlow8169
import com.lifecyclebot.engine.truth.SetupFeatureResolver8169
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual merge, native hunter profiles and setup-input consumers; no network or execution. */
class Aate8417DiscoveryInputsTest {
    private val now = 1_793_000_000_000L
    private val mint = "8417Discovery".padEnd(44, 'A')
    private fun row(at: Long, price: Double, cap: Double, liq: Double, hour: Double) = MarketSweep7297.Row(
        mint, "DISC", "Discovery", price, cap, liq, 12_000.0, 120_000.0, hour, 100, 500, 80.0,
        true, 4.0, setOf("JUP_TRADED"), priceChange5mPct = 2.0, priceChangeH24Pct = -25.0,
        buysH1 = 60, sellsH1 = 40, observedAtMs8169 = at,
    )

    @Test fun freshFrameReplacesStalePeaksAndReachesItsOwnLaneAndSetup() {
        val old = row(now - 20 * 60_000L, 12.0, 2_000_000.0, 400_000.0, 70.0)
        val fresh = row(now, 1.2, 200_000.0, 30_000.0, -12.0).copy(providers = setOf("JUP_TRENDING_5M"))
        for (order in listOf(listOf(old, fresh), listOf(fresh, old))) {
            val merged = MarketSweep7297.merge(order).single()
            assertEquals(fresh.priceUsd, merged.priceUsd, 0.0)
            assertEquals(fresh.mcapUsd, merged.mcapUsd, 0.0)
            assertEquals(fresh.liquidityUsd, merged.liquidityUsd, 0.0)
            assertEquals(fresh.priceChangeH1Pct, merged.priceChangeH1Pct, 0.0)
            assertEquals(now, merged.observedAtMs8169)
            assertEquals(old.providers + fresh.providers, merged.providers)
            assertEquals(2.0 to -12.0, SetupFeatureResolver8169.sweepChg8169(merged, now).value)
            assertEquals(60.0, SetupFeatureResolver8169.sweepBp8169(merged, now).value!!, 0.0)
            val quality = LaneHunter7297.profiles.single { it.lane == "QUALITY" }
            val bluechip = LaneHunter7297.profiles.single { it.lane == "BLUECHIP" }
            val dip = LaneHunter7297.profiles.single { it.lane == "DIP_HUNTER" }
            assertTrue(merged.mcapUsd in quality.baseMin..quality.baseMax && quality.fits(merged))
            assertFalse(merged.mcapUsd in bluechip.baseMin..bluechip.baseMax)
            assertTrue("the real pullback, not an old rally, reaches the dip hunter", dip.fits(merged))
            assertFalse(dip.fits(old))
        }
    }

    @Test fun aNewUndatedPoolCannotReplaceAPricedFrameOrMakeTheTokenYoung() {
        val dated = row(now - 60_000L, 1.0, 100_000.0, 20_000.0, 4.0)
        val pool = row(0L, 0.0, 0.0, 900_000.0, 0.0).copy(ageHours = 0.01, providers = setOf("RAYDIUM_VOLUME"))
        val merged = MarketSweep7297.merge(listOf(pool, dated)).single()
        assertEquals(1.0, merged.priceUsd, 0.0)
        assertEquals(20_000.0, merged.liquidityUsd, 0.0)
        assertEquals(4.0, merged.ageHours, 0.0)
        assertEquals(dated.observedAtMs8169, merged.observedAtMs8169)
        assertEquals("STALE", SetupFeatureResolver8169.sweepChg8169(merged, now + 20 * 60_000L).why)
    }

    @Test fun reportedFlatHourIsUsableButMissingHourRemainsMissing() {
        val flat = row(now, 1.0, 100_000.0, 20_000.0, 0.0)
        assertNull(SetupFeatureResolver8169.sweepChg8169(flat, now).value)
        assertEquals(2.0 to 0.0, SetupFeatureResolver8169.sweepChg8169(flat.copy(hourChangeObserved8417 = true), now).value)
        val parse = MarketSweep7297.javaClass.getDeclaredMethod("parseJupiter", org.json.JSONArray::class.java, String::class.java).apply { isAccessible = true }
        val payload = org.json.JSONArray().put(JSONObject().put("id", mint).put("usdPrice", 1.0).put("mcap", 100_000.0)
            .put("stats1h", JSONObject().put("priceChange", 0.0)).put("stats5m", JSONObject().put("priceChange", 2.0)))
        @Suppress("UNCHECKED_CAST")
        val parsed = (parse.invoke(MarketSweep7297, payload, "JUP_TRADED") as List<MarketSweep7297.Row>).single()
        assertTrue(parsed.hourChangeObserved8417)
        assertEquals(2.0 to 0.0, SetupFeatureResolver8169.sweepChg8169(parsed, parsed.observedAtMs8169).value)
    }

    private fun pair(price: Double = 120.0, born: Long = now - 7_200_000L) = JSONObject()
        .put("priceUsd", price.toString()).put("pairCreatedAt", born)
        .put("priceChange", JSONObject().put("m5", 20.0).put("h1", -40.0))

    @Test fun historicalShapeUsesTheSameAnswersPriceEvenAfterTheCurrentMarkDoubles() {
        val ts = TokenState(mint = "8417Shape".padEnd(44, 'B'), symbol = "S").apply {
            lastPrice = 240.0
            history.add(Candle(ts = now + 30_000L, priceUsd = lastPrice, marketCap = 0.0, volumeH1 = 0.0, volume24h = 0.0))
        }
        PairFlow8169.note8169(ts.mint, PairFlow8169.fromJson8169(pair(), now))
        assertEquals(3, DiscoveryCapture8400.seedShape8407(ts, now + 30_000L))
        val prices = ts.history.associate { it.ts to it.priceUsd }
        assertEquals(200.0, prices[now - 3_600_000L]!!, 0.0)
        assertEquals(100.0, prices[now - 300_000L]!!, 0.0)
        assertEquals(120.0, prices[now]!!, 0.0)
        assertEquals(240.0, prices[now + 30_000L]!!, 0.0)
    }

    @Test fun aThreeMinuteOldPairCannotInventFiveMinuteOrHourlyHistoryEvenWhenReadLater() {
        val ts = TokenState(mint = "8417Young".padEnd(44, 'C'), symbol = "Y").apply { lastPrice = 120.0 }
        PairFlow8169.note8169(ts.mint, PairFlow8169.fromJson8169(pair(born = now - 180_000L), now))
        assertEquals(0, DiscoveryCapture8400.seedShape8407(ts, now + 300_000L))
        assertTrue(ts.history.isEmpty())
        val unknown = TokenState(mint = "8417Unknown".padEnd(44, 'D'), symbol = "U").apply { lastPrice = 120.0 }
        PairFlow8169.note8169(unknown.mint, PairFlow8169.fromJson8169(pair(born = 0L), now))
        assertEquals(0, DiscoveryCapture8400.seedShape8407(unknown, now))
    }

    @Test fun anAnswerWithoutPriceCannotBorrowTheTokenStatesPrice() {
        val ts = TokenState(mint = "8417NoPrice".padEnd(44, 'E'), symbol = "N").apply { lastPrice = 120.0 }
        PairFlow8169.note8169(ts.mint, PairFlow8169.fromJson8169(pair().apply { remove("priceUsd") }, now))
        assertEquals(0, DiscoveryCapture8400.seedShape8407(ts, now))
        assertTrue(ts.history.isEmpty())
    }
}

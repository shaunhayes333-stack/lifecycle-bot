package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.MultiTimeframeCharts8521 as Charts
import com.lifecyclebot.engine.truth.MultiTimeframeCharts8521.Frame8521 as Frame
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.*
import org.junit.Test

class Aate8521DiscoveryIntegrityTest {
    private val now = 1_728_086_400_000L

    private fun bar(at: Long, frame: Frame, price: Double, pool: String = "pool-A"): Candle =
        CandleCoverage8284.tag8284(Candle(at, price, 0.0, 0.0, 100.0,
            openUsd = price, highUsd = price, lowUsd = price,
            intervalMs8521 = frame.intervalMs, periodVolumeUsd8521 = 100.0),
            CandleCoverage8284.Quality8284.VALID_POOL_OHLCV, "provider", pool)

    @Test fun aProviderWithoutACallerPoolHintCannotJoinTwoVenuesIntoAFalseRunner() {
        val ts = TokenState("venue-switch-discovery8521")
        val old = (8L downTo 5L).map { bar(now - it * Frame.H1.intervalMs, Frame.H1, 1.0) }
        val fresh = (4L downTo 1L).map { bar(now - it * Frame.H1.intervalMs, Frame.H1, 105.0 - it, "pool-B") }
        Charts.install8521(ts, Frame.H1, old, now, "provider-A", provider8521 = true)
        Charts.install8521(ts, Frame.H1, fresh, now, "provider-B", provider8521 = true)
        val observed = Charts.snapshot8521(ts, Frame.H1, now)
        assertEquals(fresh, observed)
        assertEquals(setOf("pool-B"), observed.map { CandleCoverage8284.tagOf8284(it)!!.pool }.toSet())
        assertEquals(100.0 * (104.0 / 101.0 - 1.0), LifecycleStrategy.chartFrame8521(observed)!!.movePct!!, 1e-9)
        // An old backfill cannot switch the chart back to the obsolete venue.
        Charts.install8521(ts, Frame.H1, old, now, "provider-A", provider8521 = true)
        assertEquals(fresh, Charts.snapshot8521(ts, Frame.H1, now))
    }

    @Test fun missingTradePeriodsCannotBeCompressedIntoVolumeAcceleration() {
        fun tape(ages: List<Long>) = ages.map { age ->
            TradeTape8100.Print8128(now - age, 1.0, 1.0, true, "feed-A", "pool-A")
        }
        val gap = tape(listOf(300_001L, 30_001L, 15_001L, 1L))
        val incomplete = NativeHistory8521.prepare8521(emptyList(), gap, 100.0, now, "pool-A")
        assertEquals(4, incomplete.prices.size)
        assertNull("a feed gap is not an adjacent equal-duration volume sample", incomplete.volumeRatio)
        val complete = NativeHistory8521.prepare8521(emptyList(), tape(listOf(45_001L, 30_001L, 15_001L, 1L)),
            100.0, now, "pool-A")
        assertEquals(1.0, complete.volumeRatio!!, 0.0)
    }

    @Test fun missingCandlePeriodsCannotProveVolumeGrowthEither() {
        val gap = listOf(10L, 3L, 2L, 1L).map { bar(now - it * Frame.M1.intervalMs, Frame.M1, 100.0) }
        assertNull(NativeHistory8521.prepare8521(gap, emptyList(), null, now, "pool-A").volumeRatio)
        val recovered = (4L downTo 1L).map { bar(now - it * Frame.M1.intervalMs, Frame.M1, 100.0) }
        assertEquals(1.0, NativeHistory8521.prepare8521(recovered, emptyList(), null, now, "pool-A").volumeRatio!!, 0.0)
    }

    @Test fun rsiKeepsTheFrequencyOfGainsAndLossesAndIsIndependentOfTokenPriceScale() {
        val rising = (0..13).map { 100.0 + it } + 112.0
        val falling = (0..13).map { 100.0 - it } + 88.0
        assertEquals(100.0 * 13.0 / 14.0, LifecycleStrategy.currentRsi8521(rising), 1e-9)
        assertEquals(100.0 / 14.0, LifecycleStrategy.currentRsi8521(falling), 1e-9)
        assertEquals(LifecycleStrategy.currentRsi8521(rising), LifecycleStrategy.currentRsi8521(rising.map { it * 1e-10 }), 1e-8)
        assertEquals(100.0, LifecycleStrategy.currentRsi8521((0..14).map { 100.0 + it }), 0.0)
        assertEquals(0.0, LifecycleStrategy.currentRsi8521((0..14).map { 100.0 - it }), 0.0)
        assertEquals(50.0, LifecycleStrategy.currentRsi8521(List(15) { 100.0 }), 0.0)
        assertEquals(50.0, LifecycleStrategy.currentRsi8521(rising.drop(1)), 0.0)
    }
}

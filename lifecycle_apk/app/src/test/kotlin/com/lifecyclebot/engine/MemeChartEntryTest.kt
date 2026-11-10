package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.SmartChartScanner.EntryShape
import com.lifecyclebot.engine.SmartChartScanner.PriceCandle
import com.lifecyclebot.engine.market.KineticFlow8506
import com.lifecyclebot.engine.market.MarketSweep7297
import org.junit.Assert.*
import org.junit.Test

class MemeChartEntryTest {
    private fun bar(open: Double, high: Double, low: Double, close: Double) =
        PriceCandle(open, high, low, close, 10.0, 60_000L)

    @Test fun sameBaseCanBreakHoldOrRejectAndHugeExtensionsAreNotFreshBreakouts() {
        val base = List(8) { bar(1.0, 1.02, 0.99, 1.01) }
        fun shape(last: PriceCandle) = SmartChartScanner.entryShape(base + last)
        val held = shape(bar(1.01, 1.09, 1.00, 1.07))
        val rejected = shape(bar(1.02, 1.09, 0.98, 0.99))
        assertEquals(EntryShape.BREAKOUT, held)
        assertEquals("BREAKOUT_EXPANSION", MarketSweep7297.chartSetup("OBSERVING", held, true, 65.0))
        assertEquals("OBSERVING", MarketSweep7297.chartSetup("OBSERVING", held, false, 65.0))
        assertEquals(EntryShape.FAILED_BREAKOUT, rejected)
        assertEquals("EXHAUSTION", MarketSweep7297.chartSetup("CONTINUATION", rejected, true, 65.0))
        assertEquals(EntryShape.UNFORMED, shape(bar(1.01, 2.0, 1.0, 1.9)))
    }

    @Test fun aReclaimRequiresAHeldHigherLowAndACloseThroughThePriorHigh() {
        val bars = listOf(bar(1.2, 1.3, 1.0, 1.1), bar(1.1, 1.2, 0.95, 1.0),
            bar(1.0, 1.1, 0.85, 0.9), bar(0.9, 1.0, 0.88, 0.98))
        assertEquals(EntryShape.RECLAIM, SmartChartScanner.entryShape(bars + bar(0.98, 1.1, 0.94, 1.08)))
        assertEquals(EntryShape.UNFORMED, SmartChartScanner.entryShape(bars + bar(0.98, 1.0, 0.94, 0.99)))
    }

    @Test fun scanUsesClosedObservedBarsAndLeavesADojiNeutral() {
        val end = System.currentTimeMillis() / 60_000L * 60_000L
        val token = TokenState(mint = "chart-source-test", symbol = "TEST")
        repeat(5) { i ->
            token.history.addLast(Candle(ts = end - (5 - i) * 60_000L, priceUsd = 1.0,
                marketCap = 1000.0, volumeH1 = 500.0, volume24h = 5000.0, openUsd = 1.0,
                highUsd = 1.01, lowUsd = 0.99, intervalMs8521 = 60_000L, periodVolumeUsd8521 = 1.0))
        }
        val last = token.history.last()
        token.history.addLast(last.copy(ts = end, priceUsd = 100.0, highUsd = 101.0))
        val read = SmartChartScanner.quickScan(token, SmartChartScanner.Timeframe.M1)!!
        assertEquals("NEUTRAL", read.overallBias)
        assertEquals(1.0, read.closeUsd, 0.0)
        assertEquals(end, read.timestampMs)
        token.history.clear()
        repeat(6) { i -> token.history.addLast(last.copy(ts = end - (6 - i) * 60_000L, synthetic = true)) }
        assertNull(SmartChartScanner.quickScan(token, SmartChartScanner.Timeframe.M1))
    }

    @Test fun anOlderOrRepeatedScanCannotRenewAnExpiredShape() {
        val mint = "cached-shape-test"
        SmartChartCache.update(mint, "BEARISH", 80.0, emptyList(), 5_000L, EntryShape.LOWER_HIGHS)
        SmartChartCache.update(mint, "BULLISH", 90.0, emptyList(), 4_000L, EntryShape.BREAKOUT)
        assertEquals(EntryShape.LOWER_HIGHS, SmartChartCache.shapeAt(mint, 5_001L))
        assertEquals(EntryShape.UNFORMED, SmartChartCache.shapeAt(mint, 125_001L))
        SmartChartCache.clear(mint)
    }

    @Test fun threeDatedSwapsGivingBackTheMoveCannotPromoteTheWave() {
        val now = 1_800_000_000_000L
        fun wave(mint: String, prices: List<Double>): KineticFlow8506.Vector {
            prices.forEachIndexed { i, px ->
                KineticFlow8506.observe(KineticFlow8506.Swap(mint, "CURVE", "sig$i", "wallet$i",
                    true, 1.0, 1.0 / px, now - (4 - i) * 700L, sourceTime = true), now)
            }
            return KineticFlow8506.current(mint, now)!!
        }
        val spent = wave("half-given-back", listOf(1.0, 1.1, 1.8, 1.6, 1.3))
        assertTrue(spent.priceMove > 0.0)
        assertTrue(spent.recentMovePct!! < 0.0)
        assertFalse(spent.active)
        assertTrue(wave("shallow-pause", listOf(1.0, 1.1, 1.8, 1.76, 1.73)).active)
    }
}

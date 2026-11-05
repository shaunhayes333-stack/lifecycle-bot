package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.*
import org.junit.Test

class Aate8521NativeHistoryTest {
    private val now = 12_000_000L

    private fun bar(openAt: Long, price: Double, pool: String = "pool-A", volume: Double = 10.0): Candle =
        CandleCoverage8284.tag8284(Candle(openAt, price, 10_000.0, 999_999.0, 999_999.0,
            openUsd = price - 0.5, highUsd = price + 1.0, lowUsd = price - 1.0,
            intervalMs8521 = 60_000L, periodVolumeUsd8521 = volume),
            CandleCoverage8284.Quality8284.VALID_POOL_OHLCV, "provider-A", pool)

    @Test fun backfilledOlderBarsCannotReverseMomentumOrDuplicateItsEvidence() {
        val oldest = bar(now - 240_000L, 100.0)
        val second = bar(now - 180_000L, 110.0)
        val third = bar(now - 120_000L, 120.0)
        val newest = bar(now - 60_000L, 130.0)
        val read = NativeHistory8521.prepare8521(
            listOf(newest, oldest, third, second, oldest, newest,
                bar(now - 60_000L, 99_999.0, "pool-B"),
                bar(now, 88_888.0), // close is still in the future
                bar(now - 300_000L, 77_777.0).copy(intervalMs8521 = 300_000L)),
            emptyList(), null, now, "pool-A")
        assertEquals(listOf(100.0, 110.0, 120.0, 130.0), read.prices)
        assertEquals(read.prices, read.recentPrices)
        assertEquals(30.0, (read.recentPrices.last() / read.recentPrices.first() - 1.0) * 100.0, 1e-9)
        assertEquals(131.0, read.high!!, 0.0)
        assertEquals("only per-period volume participates", 1.0, read.volumeRatio!!, 0.0)
    }

    @Test fun syntheticShapeAndRollingSnapshotsCannotProveABounceOrVolume() {
        val synthetic = listOf(100.0, 70.0, 75.0, 80.0).mapIndexed { i, price ->
            bar(now - (4L - i) * 60_000L, price).copy(synthetic = true)
        }
        val rolling = listOf(100.0, 70.0, 75.0, 80.0).mapIndexed { i, price ->
            Candle(now - (4L - i) * 8_000L, price, 10_000.0, 999_999.0, 999_999.0,
                openUsd = price, highUsd = price, lowUsd = price)
        }
        val read = NativeHistory8521.prepare8521(synthetic + rolling, emptyList(), null, now, "pool-A")
        assertTrue(read.prices.isEmpty())
        assertNull(read.volumeRatio)
        assertFalse(read.bounce8521(80.0, 70.0))
        val stale = NativeHistory8521.prepare8521(
            (4L downTo 1L).map { bar(now - 600_000L - it * 60_000L, 100.0) }, emptyList(), null, now, "pool-A")
        assertTrue("a fresh wrapper cannot renew an old series", stale.prices.isEmpty())
    }

    @Test fun aYoungLaunchUsesExecutedTapeWithoutWaitingForMinuteOrHigherFrameBars() {
        val real = listOf(
            TradeTape8100.Print8128(now - 45_001L, 10.0, 1.0, true, "feed-A", "pool-A"),
            TradeTape8100.Print8128(now - 30_001L, 8.0, 1.0, false, "feed-A", "pool-A"),
            TradeTape8100.Print8128(now - 15_001L, 8.5, 1.0, true, "feed-A", "pool-A"),
            TradeTape8100.Print8128(now - 1L, 9.0, 1.0, true, "feed-A", "pool-A"),
        )
        val unrelated = TradeTape8100.Print8128(now - 2L, 100_000.0, 99_999.0, true, "feed-B", "pool-B")
        val future = TradeTape8100.Print8128(now + 1L, 100_000.0, 99_999.0, true, "feed-A", "pool-A")
        val read = NativeHistory8521.prepare8521(emptyList(), listOf(real[3], unrelated, real[1], future, real[0], real[2]),
            2.0, now, "pool-A")
        assertEquals(listOf(20.0, 16.0, 17.0, 18.0), read.prices)
        assertEquals(1.0, read.volumeRatio!!, 0.0)
        assertTrue(read.bounce8521(18.0, 60.0))
        assertFalse("an unread buy ratio is not 50% observed buying", read.bounce8521(18.0, Double.NaN))
        val marksOnly = NativeHistory8521.prepare8521(emptyList(), real.map { it.copy(priceOnly = true, sol = 0.0) },
            2.0, now, "pool-A")
        assertEquals("real marks still state price and momentum", read.prices, marksOnly.prices)
        assertNull("a mark states no executed volume", marksOnly.volumeRatio)
        // V5.0.8539: with fewer than four executed prints the venue's observed prices state the bounce's SHAPE (DIP_HUNTER
        // leaves WAIT_FOR_DIP_HISTORY_8535: x3,969, 0 qualified); the bounce itself still needs returning executed volume.
        assertEquals(4, marksOnly.bouncePrices.size)
        assertFalse(marksOnly.bounce8521(18.0, 60.0))
    }
}

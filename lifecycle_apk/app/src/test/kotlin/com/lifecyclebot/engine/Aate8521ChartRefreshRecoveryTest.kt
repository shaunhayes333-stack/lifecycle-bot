package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.truth.CandleCoverage8284
import com.lifecyclebot.engine.truth.MultiTimeframeCharts8521.Frame8521
import org.junit.Assert.*
import org.junit.Test

class Aate8521ChartRefreshRecoveryTest {
    private val now = 1_728_086_400_000L
    private fun bar(frame: Frame8521, at: Long = now - frame.intervalMs) = Candle(
        at, 1.1, 0.0, 0.0, 100.0, highUsd = 1.2, lowUsd = 0.9, openUsd = 1.0,
        intervalMs8521 = frame.intervalMs, periodVolumeUsd8521 = 100.0)

    @Test fun aLocalWaitCannotAcquireAnHourLongRefreshDelay() {
        for (frame in listOf(Frame8521.M5, Frame8521.M15, Frame8521.H1, Frame8521.H4)) {
            assertEquals(30_000L, DataOrchestrator.mtfRetryGap8521(frame, observed = false))
        }
        assertEquals(900_000L, DataOrchestrator.mtfRetryGap8521(Frame8521.H1, observed = true))
        assertEquals(3_600_000L, DataOrchestrator.mtfRetryGap8521(Frame8521.H4, observed = true))
    }

    @Test fun staleOpenAndWrongFrameResponsesCannotSuppressTheNextSource() {
        val frame = Frame8521.H4
        val stale = bar(frame, now - 4 * frame.intervalMs)
        val open = bar(frame, now)
        val wrong = bar(Frame8521.H1)
        assertTrue(DataOrchestrator.mtfEligibleRows8521(listOf(stale, open, wrong), frame, now, "cached").isEmpty())
        val current = bar(frame)
        val accepted = DataOrchestrator.mtfEligibleRows8521(listOf(current, current, open), frame, now, "backup")
        assertEquals(listOf(current), accepted)
        assertEquals(3_600_000L, DataOrchestrator.mtfRetryGap8521(frame, accepted.isNotEmpty()))
    }

    @Test fun aCachedDifferentPoolCannotHideTheRequestedPoolChart() {
        val frame = Frame8521.H1
        val otherPool = CandleCoverage8284.tag8284(bar(frame),
            CandleCoverage8284.Quality8284.VALID_POOL_OHLCV, "keyless", "pool_a")
        assertTrue(DataOrchestrator.mtfEligibleRows8521(listOf(otherPool), frame, now,
            "keyless_cache", "pool_b").isEmpty())
        val requested = CandleCoverage8284.tag8284(bar(frame).copy(priceUsd = 1.15),
            CandleCoverage8284.Quality8284.VALID_POOL_OHLCV, "keyless", "pool_b")
        assertEquals(listOf(requested), DataOrchestrator.mtfEligibleRows8521(listOf(requested), frame, now,
            "keyless", "pool_b"))
    }
}

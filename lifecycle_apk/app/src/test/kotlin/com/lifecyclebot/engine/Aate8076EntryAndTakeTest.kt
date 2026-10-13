package com.lifecyclebot.engine

import com.lifecyclebot.data.Candle
import org.junit.Assert.*
import org.junit.Test

class Aate8076EntryAndTakeTest {
    private val now = 1_000_000L
    private fun candle(at: Long) = Candle(at, 1.0, 0.0, 0.0, 0.0, highUsd = 1.0, lowUsd = 1.0)

    @Test fun fullCandleRangeAndCurrentBreakoutAreRespected() {
        val bars = List(5) { candle(now - it * 60_000L) }
        assertTrue(FlatChart8027.flatCandles8076(bars, 1.0, now))
        assertFalse(FlatChart8027.flatCandles8076(bars.take(2), 1.0, now))
        assertFalse(FlatChart8027.flatCandles8076(List(5) { bars[0] }, 1.0, now))
        assertFalse(FlatChart8027.flatCandles8076(bars, 1.08, now))
        assertFalse(FlatChart8027.flatCandles8076(bars.dropLast(1) + bars.last().copy(highUsd = 1.08), 1.0, now))
        assertFalse(FlatChart8027.flatCandles8076(bars.map { it.copy(synthetic = true) }, 1.0, now))
        assertFalse(FlatChart8027.flatCandles8076(bars.map { it.copy(ts = it.ts - 600_000) }, 1.0, now))
        assertFalse(FlatChart8027.flatCandles8076(bars, Double.NaN, now))
    }

    @Test fun onlyOrderedConfirmedTakesAdvanceAndDuplicatesAreHarmless() {
        val progress = LifecycleHold8065.TakeProgress8076()
        assertEquals(0 to 0.5, LifecycleHold8065.takeDue8065(110.0, progress.taken))
        assertFalse(progress.confirm("LIFECYCLE_STOP_FLOOR_8065_-41PCT"))
        // 8081: one confirmed compound partial may cover two price thresholds
        // crossed on the same observed mark. Never permit duplicate or backward
        // acknowledgement of that finalized economic operation.
        assertTrue(progress.confirm("LIFECYCLE_PROFIT_TAKE2_8065_450PCT"))
        assertEquals(2, progress.taken)
        assertFalse(progress.confirm("LIFECYCLE_PROFIT_TAKE1_8065_110PCT"))
        assertFalse(progress.confirm("LIFECYCLE_PROFIT_TAKE2_8065_450PCT"))
        val sequential = LifecycleHold8065.TakeProgress8076()
        assertTrue(sequential.confirm("LIFECYCLE_PROFIT_TAKE1_8065_110PCT"))
        assertEquals(1, sequential.taken)
        assertTrue(sequential.confirm("LIFECYCLE_PROFIT_TAKE2_8065_450PCT"))
        assertEquals(2, sequential.taken)
        assertFalse(sequential.confirm("LIFECYCLE_PROFIT_TAKE2_8065_450PCT"))
    }
}

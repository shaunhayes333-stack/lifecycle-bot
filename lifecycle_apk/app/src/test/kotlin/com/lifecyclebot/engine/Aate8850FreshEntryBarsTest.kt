package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MarkBars7948
import com.lifecyclebot.engine.truth.TradePlan7739
import org.junit.Assert.*
import org.junit.Test

/** Historical OHLCV must not hide the trade/mark evidence seen after it from a live entry. */
class Aate8850FreshEntryBarsTest {
    private val minute = 60_000L
    private val quarter = TradePlan7739.YOUNG_BAR_MS_8317
    private val base = 1_800_000_000_000L / minute * minute

    private fun bar(at: Long, close: Double): TradePlan7739.Bar =
        TradePlan7739.Bar(at, close, close, close, close)

    @Test fun aFullHistoricalChartStillReadsTheNewFormingMinute() {
        val history = (0..5).map { bar(base + it * minute, 1.0 + it * 0.1) }
        val now = base + 5 * minute + 35_000L
        val observed = listOf(bar(base + 4 * minute, 10.0), bar(base + 5 * minute, 0.75))
        // The old six-bar merge and TradePlan's early return both kept a 1.50 close here.
        assertEquals(history, MarkBars7948.merge7948(history, observed, 6))
        val entry = MarkBars7948.mergeEntryFresh8850(history, observed, now)
        assertEquals(6, entry.size)
        assertEquals(1.4, entry[4].close, 1e-9) // completed provider minute still owns its OHLCV
        assertEquals(0.75, entry.last().close, 1e-9) // NEW observed forming minute owns current stage
        assertEquals("STILL_FALLING", TradePlan7739.currentStageWait8849(
            TradePlan7739.Read(null, "STILL_FALLING"), entry, TradePlan7739.tierFor(6_000.0), 3, now))
    }

    @Test fun aNewExecutedMinuteIsNotLostBehindSixOlderProviderBars() {
        val history = (0..5).map { bar(base + it * minute, 2.0) }
        val now = base + 7 * minute + 15_000L
        val marks = listOf(bar(base + 6 * minute, 1.5), bar(base + 7 * minute, 1.1))
        val merged = MarkBars7948.mergeEntryFresh8850(history, marks, now)
        assertEquals(8, merged.size)
        assertEquals(1.1, merged.last().close, 1e-9)
        assertEquals(base + 7 * minute, merged.last().startMs)
    }

    @Test fun freshExecutedFifteenSecondTapeWinsButStaleOrThinTapeCannot() {
        val historical = (0..5).map { bar(base + it * minute, 2.0) }
        val now = base + 7 * minute + 50_000L
        val executed = (0..5).map { bar(base + 6 * minute + it * quarter, 2.0 - 0.15 * it) }
        val picked = TradePlan7739.preferExecutedEntryBars8850(historical, executed, now, 3 * quarter, 3)
        assertEquals(executed, picked)
        assertEquals(1.25, picked.last().close, 1e-9)
        assertEquals(historical, TradePlan7739.preferExecutedEntryBars8850(historical, executed.take(2), now, 3 * quarter, 3))
        assertEquals(historical, TradePlan7739.preferExecutedEntryBars8850(historical, executed, now + 10 * minute, 3 * quarter, 3))
        val newestAlreadyObserved = historical + bar(executed.last().startMs, 1.20)
        assertEquals(newestAlreadyObserved, TradePlan7739.preferExecutedEntryBars8850(newestAlreadyObserved, executed, now, 3 * quarter, 3))
    }

    @Test fun noFutureMarkCanInventAnEntryBar() {
        val history = (0..5).map { bar(base + it * minute, 1.0) }
        val now = base + 5 * minute + 10_000L
        val merged = MarkBars7948.mergeEntryFresh8850(history, listOf(bar(base + 6 * minute, 100.0)), now)
        assertEquals(history, merged)
    }

    @Test fun signingDoorChecksOnlyFreshPostDecisionObservations() {
        val decision = base + 10 * minute
        val now = decision + 4_000L
        assertFalse(TradePlan7739.postDecisionMark8850(decision, decision, now))
        assertTrue(TradePlan7739.postDecisionMark8850(decision + 2_000L, decision, now))
        assertFalse(TradePlan7739.postDecisionMark8850(decision - 1_000L, decision, now))
        assertFalse(TradePlan7739.postDecisionMark8850(decision + 5_000L, decision, now))
        assertFalse(TradePlan7739.postDecisionMark8850(decision + 1_000L, decision, now + 40_000L))
    }
}

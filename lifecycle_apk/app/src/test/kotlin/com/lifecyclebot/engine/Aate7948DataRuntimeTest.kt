package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.MarkBars7948
import com.lifecyclebot.engine.truth.TradePlan7739
import com.lifecyclebot.network.releasableSubs7948
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

/** V5.0.7948 — mark bars for the plan, provider backoff, Helius subscription release. */
class Aate7948DataRuntimeTest {
    private val min = 60_000L
    private val t0 = 1_700_000_000_000L - (1_700_000_000_000L % 60_000L)

    @Test fun marksFoldIntoOrderIndependentMinuteBars() {
        val bars = TreeMap<Long, DoubleArray>()
        MarkBars7948.fold7948(bars, 1.0, t0 + 1_000L)
        MarkBars7948.fold7948(bars, 1.5, t0 + 20_000L)
        MarkBars7948.fold7948(bars, 0.8, t0 + 40_000L)
        // A late print earlier than the first one becomes the open, not the close.
        MarkBars7948.fold7948(bars, 1.1, t0 + 500L)
        val b = bars[t0 / min]!!
        assertEquals(1.1, b[0], 1e-12)
        assertEquals(1.5, b[1], 1e-12)
        assertEquals(0.8, b[2], 1e-12)
        assertEquals(0.8, b[3], 1e-12)
        MarkBars7948.fold7948(bars, 2.0, t0 + min + 5_000L)
        assertEquals(2, bars.size)
    }

    @Test fun markTapeFillsMinutesHistoryDoesNotCover() {
        MarkBars7948.resetForTest7948()
        val mint = "MintAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        for (i in 0 until 6) {
            MarkBars7948.note7948(mint, 1.0 + i * 0.1, t0 + i * min + 1_000L)
            MarkBars7948.note7948(mint, 1.05 + i * 0.1, t0 + i * min + 30_000L)
        }
        val now = t0 + 5 * min + 40_000L
        val marks = MarkBars7948.bars7948(mint, now, 30L * min)
        // The forming minute (the sixth) is included.
        assertEquals(6, marks.size)
        // A provider bar wins its minute; the rest come from the tape.
        val provider = listOf(TradePlan7739.Bar(t0 + 2 * min, 9.0, 9.0, 9.0, 9.0))
        val merged = MarkBars7948.merge7948(provider, marks, 6)
        assertEquals(6, merged.size)
        assertEquals(9.0, merged[2].close, 1e-12)
        assertTrue(merged.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        // Enough history: unchanged.
        val full = (0 until 6).map { TradePlan7739.Bar(t0 + it * min, 1.0, 1.0, 1.0, 1.0) }
        assertEquals(full, MarkBars7948.merge7948(full, marks, 6))
        // Another mint's tape is not read.
        assertTrue(MarkBars7948.bars7948("OtherMint", now, 30L * min).isEmpty())
    }

    @Test fun deadEnrichmentKeysBackOffForHoursExecutionHostsDoNot() {
        assertEquals(300_000L, ApiBackoff.authBackoffMs7948("birdeye", 1))
        assertEquals(7_200_000L, ApiBackoff.authBackoffMs7948("DexPaprika", 9))
        assertEquals(3_600_000L, ApiBackoff.authBackoffMs7948("pumpfun", 4))
        // Quote/swap path keeps the short ladder (max 10 min).
        assertEquals(60_000L, ApiBackoff.authBackoffMs7948("jupiter", 1))
        assertEquals(600_000L, ApiBackoff.authBackoffMs7948("jupiter_quote", 9))
    }

    @Test fun lowSuccessProvidersAreAskedLessOften() {
        assertEquals(1L, ApiBackoff.adaptiveSoftMultiplier7948(0.06, 5))   // too few samples
        assertEquals(12L, ApiBackoff.adaptiveSoftMultiplier7948(0.06, 100)) // pump.fun frontend
        assertEquals(2L, ApiBackoff.adaptiveSoftMultiplier7948(0.35, 100))  // GeckoTerminal
        assertEquals(1L, ApiBackoff.adaptiveSoftMultiplier7948(0.92, 100))
        var e = 1_000_000L
        repeat(60) { e = ApiBackoff.nextOkEwmaMicros7948(e, ok = false) }
        assertTrue(e < 100_000L)
        repeat(60) { e = ApiBackoff.nextOkEwmaMicros7948(e, ok = true) }
        assertTrue(e > 900_000L)
    }

    @Test fun heliusReleasesOnlyUnwantedUnheldSubscriptions() {
        val desired = listOf("held", "watch", "gone", "hotLaunch")
        val keep = setOf("watch", "hotLaunch")
        assertEquals(listOf("gone"), releasableSubs7948(desired, setOf("held"), { it in keep }))
        assertEquals(emptyList<String>(), releasableSubs7948(desired, desired.toSet(), { false }))
    }
}

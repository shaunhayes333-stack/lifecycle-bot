package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.data.TokenState
import org.junit.Assert.*
import org.junit.Test

/** 5.0.8208 — a cell's age band comes from a direct birth witness; discovery age is only the unwitnessed fallback. */
class Aate8208CellAgeKeyTest {
    @Test fun unwitnessedMintNeverClaimsTheFreshLaunchAgeBand() {
        val ts = TokenState(mint = "Unwitnessed8208" + System.nanoTime(), symbol = "X")
        val now = 1_000_000_000L
        ts.addedToWatchlistAt = 0L
        assertEquals(-1L, ForwardReturnLabeler7731.cellAgeMs8208(ts, now))
        assertEquals("AGE_UNKNOWN", ForwardReturnLabeler7731.ageBand(ForwardReturnLabeler7731.cellAgeMs8208(ts, now)))
        ts.addedToWatchlistAt = now - 60_000L
        assertEquals(-1L, ForwardReturnLabeler7731.cellAgeMs8208(ts, now))
        assertEquals("AGE_UNKNOWN", ForwardReturnLabeler7731.ageBand(ForwardReturnLabeler7731.cellAgeMs8208(ts, now)))
    }

    @Test fun oneRuleKeysWriterAndBothReaders() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        // superseded by V5.0.8337 — FIX 7a: cellStatFor keys on discovery age inside the 8114 legacy scope only (one rule otherwise)
        assertEquals(2, Regex("""val ageMs = cellAgeMs8208\(ts, nowMs\)""").findAll(src).count())
        assertTrue(src.contains("else cellAgeMs8208(ts, nowMs)"))
        assertFalse("no cell key may be built from discovery age directly", src.contains("val ageMs = if (ts.addedToWatchlistAt > 0L) nowMs - ts.addedToWatchlistAt else -1L"))
        assertTrue(src.contains("LaunchBirth8169.launchBirth8169(ts.mint, nowMs)"))
    }
}

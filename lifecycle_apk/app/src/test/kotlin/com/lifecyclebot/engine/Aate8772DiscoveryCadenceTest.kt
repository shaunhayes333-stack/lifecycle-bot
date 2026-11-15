package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8772 — code audit (discovery F1, F3, F4): an evicted coin is not new; the scanner and the loop run at their cadence. */
class Aate8772DiscoveryCadenceTest {
    @Test fun the_scanner_runs_every_10_to_15_seconds_at_a_fixed_rate() {
        assertEquals("the tuner's 60 s is clamped", 15_000L, ScanCadence8772.scanIntervalMs8772(60))
        assertEquals(10_000L, ScanCadence8772.scanIntervalMs8772(3))
        assertEquals("a 4 s batch waits the rest", 6_000L, ScanCadence8772.fixedRateWaitMs8772(10_000L, 4_000L))
        assertEquals("an overrunning batch still yields", 1_000L, ScanCadence8772.fixedRateWaitMs8772(10_000L, 30_000L))
        assertEquals(5, BotService.pollSecondsFor8450(8))
    }

    @Test fun a_removed_coin_returns_with_its_first_admission() {
        assertNull(GlobalTradeRegistry.recallRemoved8772("never-removed-8772", 1_000L))
        assertTrue(GlobalTradeRegistry.EVICTED_TTL_MS_8772 >= 30L * 60_000L)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        assertTrue(src.contains("addedAt = returning8772?.first ?: now,"))
        assertTrue(src.contains("watchlist.remove(victim.mint)?.let { rememberRemoved8772(it) }"))
        assertTrue(src.contains("watchlist.remove(oldest.mint)?.let { rememberRemoved8772(it) }"))
    }
}

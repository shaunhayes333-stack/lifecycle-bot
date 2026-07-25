package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6363 — Scanner source circuit breaker invariants.
 *
 * Operator directive: "scanPumpFunDirect:169 timeouts" — one source burned
 * ~14 minutes of scan-batch time in a 55-min window. Breaker must trip after
 * TRIP_THRESHOLD consecutive timeouts and self-heal the moment a scan
 * succeeds. Non-timeout errors must NOT contribute to the streak.
 */
class ScannerSourceCircuitBreaker6363Test {

    @Before
    fun setUp() {
        ScannerSourceCircuitBreaker6363.reset()
    }

    @Test
    fun healthy_source_never_skips() {
        assertTrue(ScannerSourceCircuitBreaker6363.shouldRun("scanFresh", 1_000L))
        ScannerSourceCircuitBreaker6363.onSuccess("scanFresh")
        assertTrue(ScannerSourceCircuitBreaker6363.shouldRun("scanFresh", 2_000L))
    }

    @Test
    fun trips_after_three_consecutive_timeouts() {
        val src = "scanPumpFunDirect"
        assertFalse(ScannerSourceCircuitBreaker6363.onTimeout(src, 1_000L))
        assertFalse(ScannerSourceCircuitBreaker6363.onTimeout(src, 2_000L))
        val tripped = ScannerSourceCircuitBreaker6363.onTimeout(src, 3_000L)
        assertTrue("3rd consecutive timeout must trip the breaker", tripped)
        assertFalse("shouldRun must refuse a tripped source during cooldown",
            ScannerSourceCircuitBreaker6363.shouldRun(src, 3_500L))
    }

    @Test
    fun tripped_source_recovers_after_cooldown_expires() {
        val src = "scanGeckoTrending"
        repeat(3) { ScannerSourceCircuitBreaker6363.onTimeout(src, 1_000L) }
        assertFalse(ScannerSourceCircuitBreaker6363.shouldRun(src, 1_100L))
        val recoveredAt = 1_000L + ScannerSourceCircuitBreaker6363.COOLDOWN_MS + 1L
        assertTrue("Cooldown expiry re-arms the source",
            ScannerSourceCircuitBreaker6363.shouldRun(src, recoveredAt))
    }

    @Test
    fun success_immediately_clears_streak_and_cooldown() {
        val src = "scanCoinGeckoEstablished"
        repeat(3) { ScannerSourceCircuitBreaker6363.onTimeout(src, 1_000L) }
        assertFalse(ScannerSourceCircuitBreaker6363.shouldRun(src, 1_100L))
        ScannerSourceCircuitBreaker6363.onSuccess(src)
        assertTrue("Success resets state — self-heal on first live response",
            ScannerSourceCircuitBreaker6363.shouldRun(src, 1_200L))
    }

    @Test
    fun non_timeout_error_does_not_trip_streak() {
        val src = "scanRaydiumNewPools"
        // The request completed (with an error), source is reachable → treat as reset.
        ScannerSourceCircuitBreaker6363.onTimeout(src, 1_000L)
        ScannerSourceCircuitBreaker6363.onError(src)
        ScannerSourceCircuitBreaker6363.onTimeout(src, 2_000L)
        ScannerSourceCircuitBreaker6363.onTimeout(src, 3_000L)
        // Only 2 consecutive timeouts after the reset — must NOT be tripped.
        assertTrue(ScannerSourceCircuitBreaker6363.shouldRun(src, 3_500L))
    }

    @Test
    fun trip_counter_increments_on_trip_only() {
        val src = "scanDexTrending"
        val before = ScannerSourceCircuitBreaker6363.tripsTotal()
        ScannerSourceCircuitBreaker6363.onTimeout(src, 1_000L)
        ScannerSourceCircuitBreaker6363.onTimeout(src, 2_000L)
        assertEquals("no trip yet", before, ScannerSourceCircuitBreaker6363.tripsTotal())
        ScannerSourceCircuitBreaker6363.onTimeout(src, 3_000L)
        assertEquals("trip counted", before + 1, ScannerSourceCircuitBreaker6363.tripsTotal())
    }

    @Test
    fun trip_threshold_and_cooldown_are_bounded() {
        // Sanity: the operator's disarming history came from an unbounded skip
        // window (V5.9.1497). Guard the constants against accidental changes.
        assertTrue("TRIP_THRESHOLD must be small enough to catch bad providers fast",
            ScannerSourceCircuitBreaker6363.TRIP_THRESHOLD in 2..5)
        assertTrue("COOLDOWN_MS must be bounded so the source auto-re-enters",
            ScannerSourceCircuitBreaker6363.COOLDOWN_MS in 30_000L..300_000L)
    }
}

package com.lifecyclebot.engine.sell

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test

/** V5.9.1568 — retryable sell failures must not hold the close lease until 10m TTL. */
class CloseLeaseTest {
    @Test fun retryable_failure_reenters_after_backoff_without_ttl_wait() {
        val mint = "MintLeaseRetry${System.nanoTime()}111111111111111"
        val first = CloseLease.acquire(mint, "LEASE", "STOP_LOSS")
        assertNotNull(first)
        CloseLease.recordRetry(mint, "RETRYABLE_TEST")
        assertNull("backoff should suppress immediate duplicate", CloseLease.acquire(mint, "LEASE", "STOP_LOSS"))
        Thread.sleep(2_150L)
        val second = CloseLease.acquire(mint, "LEASE", "STOP_LOSS")
        assertNotNull("retryable lease must re-enter after backoff, not wait for TTL", second)
        CloseLease.release(mint, "TEST_DONE")
    }

    @Test fun earlier_fast_retry_replaces_later_backoff_but_never_duplicates_an_earlier_timer() {
        val now = 100_000L
        assertTrue(CloseLease.earlierRetryDue8520(null, now + 250L, now))
        assertTrue(CloseLease.earlierRetryDue8520(now + 10_000L, now + 250L, now))
        assertFalse(CloseLease.earlierRetryDue8520(now + 250L, now + 10_000L, now))
        assertFalse(CloseLease.earlierRetryDue8520(now + 250L, now + 250L, now))
    }

    @Test fun terminal_release_invalidates_a_pending_timer_even_if_the_mint_is_reacquired() {
        val mint = "MintTerminal8520${System.nanoTime()}"
        val previous = CloseLease.emergencyHandoff8087
        val fired = CountDownLatch(1)
        try {
            CloseLease.emergencyHandoff8087 = { m, _ -> if (m == mint) fired.countDown() }
            assertNotNull(CloseLease.acquire(mint, "STOP", "RAPID_CATASTROPHE_STOP"))
            CloseLease.recordPreRouteWait8288(mint)
            CloseLease.release(mint, "CONFIRMED")
            assertNotNull(CloseLease.acquire(mint, "NEW", "TAKE_PROFIT"))
            assertFalse("timer belonging to a closed lease must not enter a new lease",
                fired.await(800L, TimeUnit.MILLISECONDS))
        } finally {
            CloseLease.release(mint, "TEST_DONE")
            CloseLease.emergencyHandoff8087 = previous
        }
    }

    @Test fun pre_route_short_clock_preempts_a_pending_slow_emergency_retry() {
        val mint = "MintEmergency8520${System.nanoTime()}"
        val previous = CloseLease.emergencyHandoff8087
        val fired = CountDownLatch(1)
        try {
            CloseLease.emergencyHandoff8087 = { m, _ -> if (m == mint) fired.countDown() }
            assertNotNull(CloseLease.acquire(mint, "STOP", "RAPID_CATASTROPHE_STOP"))
            CloseLease.scheduleBackoff(mint, "503")
            CloseLease.recordRetry(mint, "RETRYABLE")
            CloseLease.recordPreRouteWait8288(mint)
            assertTrue("fast pre-route retry should not inherit the earlier 4s backoff timer",
                fired.await(2L, TimeUnit.SECONDS))
        } finally {
            CloseLease.release(mint, "TEST_DONE")
            CloseLease.emergencyHandoff8087 = previous
        }
    }
}

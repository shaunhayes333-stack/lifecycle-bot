package com.lifecyclebot.engine.sell

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}

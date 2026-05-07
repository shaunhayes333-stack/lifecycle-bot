package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * V5.9.495z29 — Acceptance test for operator spec items 8 + 10 scenario J:
 *   "High-throughput mode keeps trading hot path separate from slow
 *    background tasks."
 */
class LiveExecutionGateTest {

    @After
    fun reset() {
        LiveExecutionGate.resetAll()
    }

    @Test
    fun spacing_blocks_buys_inside_minSecondsBetweenLiveBuys() {
        LiveExecutionGate.configure(LiveExecutionGate.Config(
            minSecondsBetweenLiveBuys = 4,
            maxLiveTradesPerDay = 100,
            maxConcurrentLivePositions = 100,
        ))
        // First buy allowed.
        val first = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 0)
        assertTrue("first buy must be Allowed", first is LiveExecutionGate.Decision.Allowed)

        // Immediate second buy must be RATE_LIMIT blocked.
        val second = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 1)
        assertTrue("rate-limit must trigger inside spacing window",
            second is LiveExecutionGate.Decision.Blocked)
        assertEquals("RATE_LIMIT",
            (second as LiveExecutionGate.Decision.Blocked).code)
    }

    @Test
    fun concurrent_cap_blocks_when_open_positions_over_limit() {
        LiveExecutionGate.configure(LiveExecutionGate.Config(
            maxConcurrentLivePositions = 5,
            minSecondsBetweenLiveBuys = 0,
            maxLiveTradesPerDay = 100,
        ))
        val d = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 5)
        assertTrue(d is LiveExecutionGate.Decision.Blocked)
        assertEquals("CONCURRENT_CAP",
            (d as LiveExecutionGate.Decision.Blocked).code)
    }

    @Test
    fun daily_quota_blocks_after_max() {
        LiveExecutionGate.configure(LiveExecutionGate.Config(
            maxLiveTradesPerDay = 3,
            minSecondsBetweenLiveBuys = 0,
            maxConcurrentLivePositions = 100,
        ))
        // First three buys allowed.
        repeat(3) {
            val r = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 0)
            assertTrue("buy #${it + 1} must be Allowed", r is LiveExecutionGate.Decision.Allowed)
        }
        // Fourth must be quota-blocked.
        val r4 = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 0)
        assertTrue(r4 is LiveExecutionGate.Decision.Blocked)
        assertEquals("DAILY_QUOTA", (r4 as LiveExecutionGate.Decision.Blocked).code)
    }

    @Test
    fun scenarioJ_skipSlowBackgroundScans_returns_false_when_idle() {
        LiveExecutionGate.configure(LiveExecutionGate.Config(
            skipSlowBackgroundScansWhenLiveBusy = true,
            maxLiveTradesPerDay = 500,
            maxPendingBuyVerifications = 6,
        ))
        // No buys placed, no pending verifications → background scans MUST run.
        assertFalse(LiveExecutionGate.shouldSkipSlowBackgroundScans())
    }

    @Test
    fun stats_string_is_well_formed() {
        LiveExecutionGate.configure(LiveExecutionGate.Config())
        val s = LiveExecutionGate.stats()
        assertTrue(s.contains("today="))
        assertTrue(s.contains("pendingBuys="))
        assertTrue(s.contains("pendingSells="))
    }
}

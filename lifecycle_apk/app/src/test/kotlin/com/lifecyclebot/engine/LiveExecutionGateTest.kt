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
    fun spacing_does_not_block_buys_inside_minSecondsBetweenLiveBuys() {
        // V5.9.611 — operator directive: live = paper fluid parity. Min-spacing
        // gate was removed from tryAcquireBuy so the learned fluid strategy
        // expresses identically in live and paper. Test now asserts the NEW
        // behavior: spacing window does NOT block.
        LiveExecutionGate.configure(LiveExecutionGate.Config(
            minSecondsBetweenLiveBuys = 4,
            maxLiveTradesPerDay = 100,
            maxConcurrentLivePositions = 100,
        ))
        val first = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 0)
        assertTrue("first buy must be Allowed", first is LiveExecutionGate.Decision.Allowed)
        val second = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 1)
        assertTrue("spacing window must NOT block (V5.9.611 fluid-parity)",
            second is LiveExecutionGate.Decision.Allowed)
    }

    @Test
    fun concurrent_cap_does_not_block_when_open_positions_over_limit() {
        // V5.9.611 — concurrent-cap gate removed from tryAcquireBuy.
        LiveExecutionGate.configure(LiveExecutionGate.Config(
            maxConcurrentLivePositions = 5,
            minSecondsBetweenLiveBuys = 0,
            maxLiveTradesPerDay = 100,
        ))
        val d = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 5)
        assertTrue("concurrent-cap must NOT block (V5.9.611 fluid-parity)",
            d is LiveExecutionGate.Decision.Allowed)
    }

    @Test
    fun daily_quota_does_not_block_after_max() {
        // V5.9.611 — daily-quota gate removed from tryAcquireBuy.
        LiveExecutionGate.configure(LiveExecutionGate.Config(
            maxLiveTradesPerDay = 3,
            minSecondsBetweenLiveBuys = 0,
            maxConcurrentLivePositions = 100,
        ))
        repeat(3) {
            val r = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 0)
            assertTrue("buy #${it + 1} must be Allowed", r is LiveExecutionGate.Decision.Allowed)
        }
        val r4 = LiveExecutionGate.tryAcquireBuy(currentLiveOpenCount = 0)
        assertTrue("4th buy must NOT be quota-blocked (V5.9.611 fluid-parity)",
            r4 is LiveExecutionGate.Decision.Allowed)
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

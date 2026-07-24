package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test

class ExecutablePriceStopPreflight6345Test {

    @Test
    fun stop_inside_healthy_window_passes_untouched() {
        val p = ExecutablePriceStopPreflight6345.preflight(
            requestedStopSol = 0.90,
            executableBidSol = 1.00,
            executableAskSol = 1.02,
        )
        assertFalse(p.clamped)
        assertEquals(0.90, p.executableStopSol, 1e-12)
        assertEquals("OK", p.reason)
    }

    @Test
    fun stop_above_bid_is_clamped_to_bid_minus_slack() {
        val p = ExecutablePriceStopPreflight6345.preflight(
            requestedStopSol = 1.10,        // above bid → not a real stop
            executableBidSol = 1.00,
            executableAskSol = 1.02,
            minStopSlackFrac = 0.005,
        )
        assertTrue(p.clamped)
        // mid = 1.01, slack = 0.00505 → clamped = 1.00 - 0.00505 = 0.99495
        assertEquals(0.99495, p.executableStopSol, 1e-6)
        assertTrue(p.reason.contains("STOP_ABOVE_BID_CLAMPED"))
    }

    @Test
    fun stop_far_below_bid_is_clamped_up_to_unreachable_floor() {
        val p = ExecutablePriceStopPreflight6345.preflight(
            requestedStopSol = 0.10,        // 90% below mid → unreachable
            executableBidSol = 1.00,
            executableAskSol = 1.02,
        )
        assertTrue(p.clamped)
        // Unreachable floor = 1.00 × (1 - 0.20) = 0.80
        assertEquals(0.80, p.executableStopSol, 1e-9)
        assertTrue(p.reason.contains("STOP_UNREACHABLE"))
    }

    @Test
    fun missing_quote_returns_requested_stop_and_flags_it() {
        val p = ExecutablePriceStopPreflight6345.preflight(
            requestedStopSol = 0.90,
            executableBidSol = 0.0,
            executableAskSol = 0.0,
        )
        assertEquals("QUOTE_MISSING_6345", p.reason)
        assertEquals(0.90, p.executableStopSol, 1e-12)
    }

    @Test
    fun exactly_on_the_bid_is_treated_as_at_or_above_and_clamped() {
        val p = ExecutablePriceStopPreflight6345.preflight(
            requestedStopSol = 1.00,        // ==bid → equal-or-above test
            executableBidSol = 1.00,
            executableAskSol = 1.02,
        )
        assertTrue(p.clamped)
    }
}

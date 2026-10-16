package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Repair6491AcceptanceTest {
    // V5.0.6653 made the paper minimum immutable (updatePaperExecutableMinimumSol is a
    // no-op) and V5.0.7277 raised it to the fee-aware floor, so a 0.05 request can no
    // longer exercise the whole resolver at the minimum. The 6491 contract itself —
    // minimum comparisons are made in lamports, so floating-point noise in a lane cap
    // equal to the minimum cannot refuse it — is asserted on the resolver's comparator.
    @Test
    fun exact_paper_minimum_survives_floating_point_lane_cap_noise() {
        val noisyFiveHundredths = 0.049999999999999996
        assertTrue(OrderSizeResolver6441.meetsMinimum6491(noisyFiveHundredths, 0.05))
        val floor = OrderSizeResolver6441.paperExecutableMinimumSol()
        assertTrue(floor >= 0.05)
        assertTrue(OrderSizeResolver6441.meetsMinimum6491(floor * (1.0 - 1e-15), floor))
        // A genuine one-lamport shortfall is still refused.
        assertFalse(OrderSizeResolver6441.meetsMinimum6491(floor - 0.000000001, floor))
    }
}

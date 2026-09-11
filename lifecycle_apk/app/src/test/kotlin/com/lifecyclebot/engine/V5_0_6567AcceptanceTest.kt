package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import org.junit.Assert.*
import org.junit.Test

class V5_0_6567AcceptanceTest {
    @Test
    fun adaptive_subminimum_size_does_not_authorize_a_larger_order() {
        // Risk, cash and lane caps all constrain the executable minimum.
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.03,
            laneName = "CRYPTO_ALT",
            walletSol = 1.0,
            paperMode = false,
            laneRiskCapSol = 1.0,
            laneMinExecutableSol = 0.05,
            applyPaperMemeMinimum = false,
        )
        assertFalse(r.executable)
        assertEquals(0.0, r.finalSizeSol, 0.0)
        assertEquals("RISK_BELOW_MIN_EXECUTABLE_6737", r.reason)
    }

    @Test
    fun legal_adaptive_size_remains_exact_and_executable() {
        // V5.0.6601 — a legal adaptive request (>= minExec) must NEVER be
        // inflated above the caller's intent, even when the runner ladder
        // recommends a larger size. Sub-minimum risk requests remain deferred.
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.08,
            laneName = "MARKETS_FOREX",
            walletSol = 1.0,
            paperMode = false,
            laneRiskCapSol = 0.5,
            laneMinExecutableSol = 0.05,
            applyPaperMemeMinimum = false,
        )
        assertTrue(r.executable)
        assertEquals("OK", r.reason)
        assertEquals(0.08, r.finalSizeSol, 1e-9)
    }
}

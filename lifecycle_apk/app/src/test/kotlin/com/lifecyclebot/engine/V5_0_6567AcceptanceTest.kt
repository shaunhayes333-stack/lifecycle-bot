package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import org.junit.Assert.*
import org.junit.Test

class V5_0_6567AcceptanceTest {
    @Test
    fun adaptive_subminimum_size_is_rejected_not_promoted() {
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
        assertEquals("BELOW_MIN_EXECUTABLE", r.reason)
        assertEquals(0.03, r.requestedSol, 1e-9)
    }

    @Test
    fun legal_adaptive_size_remains_exact_and_executable() {
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

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import org.junit.Assert.*
import org.junit.Test

class V5_0_6567AcceptanceTest {
    @Test
    fun adaptive_subminimum_size_is_promoted_once_to_min() {
        // V5.0.6600 — sub-minimum requests are promoted exactly once to
        // minExec when hard caps can fund it (operator directive Feb 2026:
        // "If final BUY risk budget can afford the minimum executable
        // notional: clamp the executable order to canonical minimum.").
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.03,
            laneName = "CRYPTO_ALT",
            walletSol = 1.0,
            paperMode = false,
            laneRiskCapSol = 1.0,
            laneMinExecutableSol = 0.05,
            applyPaperMemeMinimum = false,
        )
        assertTrue(r.executable)
        assertEquals(0.05, r.finalSizeSol, 1e-9)
        assertEquals("OK_MIN_PROMOTED_6600", r.reason)
    }

    @Test
    fun legal_adaptive_size_remains_exact_and_executable() {
        // V5.0.6601 — a legal adaptive request (>= minExec) must NEVER be
        // inflated above the caller's intent, even when the runner ladder
        // recommends a larger size. Only sub-min requests are promoted.
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

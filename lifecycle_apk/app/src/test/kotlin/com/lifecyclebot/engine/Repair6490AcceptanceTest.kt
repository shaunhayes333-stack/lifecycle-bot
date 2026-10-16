package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Repair6490AcceptanceTest {
    @Test
    fun sub_minimum_request_is_promoted_once_to_min_when_caps_can_fund_6600() = synchronized(PaperAccountLedger6430) {
        // V5.0.6600 — restore canonical executable-minimum semantics.
        // Operator directive Feb 2026: "If final BUY risk budget can
        // afford the minimum executable notional: clamp the executable
        // order to canonical minimum." Sub-minimum requests are promoted
        // exactly once to minExec when the authoritative cash and lane
        // hard cap can fund it.
        // V5.0.7277 — the paper executable minimum is now the fee-aware floor
        // (~0.1073 SOL, FeeAwareSizeFloor7277), above the 0.05 lane minimum, so the
        // account and lane cap are sized to fund THAT minimum.
        val minExec = OrderSizeResolver6441.paperExecutableMinimumSol()
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(0.2)
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.021,
            laneName = "SHITCOIN",
            walletSol = 0.2,
            paperMode = true,
            laneRiskCapSol = 0.2,
            laneMinExecutableSol = 0.05,
        )
        assertTrue(r.executable)
        assertEquals(minExec, r.finalSizeSol, 1e-9)
        assertEquals("OK_MIN_PROMOTED_6600", r.reason)
    }

    @Test
    fun unaffordable_paper_order_is_rejected_before_ticket_instead_of_inflated() = synchronized(PaperAccountLedger6430) {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(0.0501)
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.05,
            laneName = "SHITCOIN",
            walletSol = 0.0501,
            paperMode = true,
            laneRiskCapSol = 0.05,
            laneMinExecutableSol = 0.05,
        )
        assertFalse(r.executable)
        assertEquals(0.0, r.finalSizeSol, 1e-9)
        assertEquals("CAPITAL_BELOW_MIN_EXECUTABLE_6490", r.reason)
    }
}

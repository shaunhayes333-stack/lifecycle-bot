package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Repair6490AcceptanceTest {
    @Test
    fun sub_minimum_request_is_never_promoted_to_min_6809() = synchronized(PaperAccountLedger6430) {
        // V5.0.6809 §KILL_MIN_SIZE_PROMOTION — supersedes V5.0.6600 min-promotion.
        // Operator mandate Feb 2026: "Never increase an adaptively reduced
        // risk size simply to make the order executable." A sub-minimum
        // request resolves non-executable, regardless of whether cash/lane
        // caps could theoretically fund the minimum; the caller must route
        // to shadow/train observation instead.
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(0.0583)
        val r = OrderSizeResolver6441.resolve(
            requestedSol = 0.021,
            laneName = "SHITCOIN",
            walletSol = 0.0583,
            paperMode = true,
            laneRiskCapSol = 0.05,
            laneMinExecutableSol = 0.05,
        )
        assertFalse(r.executable)
        assertEquals(0.0, r.finalSizeSol, 1e-9)
        assertEquals("SUB_MIN_ADAPTIVE_HELD_6809", r.reason)
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

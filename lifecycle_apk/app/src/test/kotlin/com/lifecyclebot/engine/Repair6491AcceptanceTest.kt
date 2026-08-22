package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Repair6491AcceptanceTest {
    @Test
    fun exact_paper_minimum_survives_floating_point_lane_cap_noise() = synchronized(PaperAccountLedger6430) {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(1.0)
        OrderSizeResolver6441.updatePaperExecutableMinimumSol(0.05)
        val noisyFiveHundredths = 0.049999999999999996
        val result = OrderSizeResolver6441.resolve(
            requestedSol = 0.05,
            laneName = "SHITCOIN",
            walletSol = 1.0,
            paperMode = true,
            laneRiskCapSol = noisyFiveHundredths,
            laneMinExecutableSol = 0.05,
        )
        assertTrue(result.executable)
        assertEquals(0.05, result.finalSizeSol, 0.0)
        assertEquals("OK", result.reason)
    }
}

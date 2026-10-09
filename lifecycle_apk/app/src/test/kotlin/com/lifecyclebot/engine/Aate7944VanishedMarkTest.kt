package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.SellSafetyPolicy
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731.Vanished7944
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7944 — a vanished mark books at its real last price; spike sells race the print. */
class Aate7944VanishedMarkTest {
    private val h60 = 60L * 60_000L

    @Test fun vanishedMarksArePricedNotGuessed() {
        // Read at minute 45 at +12%: booked at that mark.
        assertEquals(Vanished7944.LAST_MARK, ForwardReturnLabeler7731.classifyVanished7944(12.0, 45L * 60_000L, h60))
        // Last read was a collapse and nothing prices it now: dead, -100%.
        assertEquals(Vanished7944.DEAD, ForwardReturnLabeler7731.classifyVanished7944(-85.0, 8L * 60_000L, h60))
        // Read only at minute 5, not collapsed: a gap, not booked either way.
        assertEquals(Vanished7944.GAP, ForwardReturnLabeler7731.classifyVanished7944(3.0, 5L * 60_000L, h60))
        // Never read after the decision.
        assertEquals(Vanished7944.GAP, ForwardReturnLabeler7731.classifyVanished7944(Double.NaN, 0L, h60))
    }

    @Test fun spikeSellsStartWideAndWalkOnce() {
        val r = "SPIKE_CAPTURE_7943_T2_276PCT"
        assertTrue(SellSafetyPolicy.isSpikeCapture7944(r))
        assertEquals(500, SellSafetyPolicy.initialSlippageBps(r))
        assertEquals(listOf(500, 1_000), SellSafetyPolicy.ladder(r))
        assertEquals(1_000, SellSafetyPolicy.maxSlippageBps(r))
        // Ordinary profit locks keep their cap.
        assertFalse(SellSafetyPolicy.isSpikeCapture7944("PROFIT_LOCK"))
        assertEquals(500, SellSafetyPolicy.maxSlippageBps("PROFIT_LOCK"))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** V5.0.7945 — a forward label books what the spike tiers bank, not just the price at the hour. */
class Aate7945SpikeCreditTest {
    @Test fun spikesAreCreditedAtTheirTierPrices() {
        // foff: +276% peak, back at entry by the hour.
        assertEquals(50.32, SpikeCapture7943.realisableGrossPct(276.0, 0.0), 0.01)
        // LAMBO: +105% peak, -10% at the hour.
        assertEquals(15.8, SpikeCapture7943.realisableGrossPct(105.0, -10.0), 0.01)
        // No tier reached: the horizon price stands.
        assertEquals(5.0, SpikeCapture7943.realisableGrossPct(30.0, 5.0), 1e-9)
        // A rug after a spike still banks the first tier.
        assertEquals(19.8 - 40.0, SpikeCapture7943.realisableGrossPct(45.0, -100.0), 0.01)
    }
}

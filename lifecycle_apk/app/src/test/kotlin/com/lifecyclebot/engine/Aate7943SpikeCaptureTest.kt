package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7943 — spikes are sold into on the print; mayhem-mode coins are recognised by supply. */
class Aate7943SpikeCaptureTest {
    @Test fun spikeTiers() {
        assertEquals(0, SpikeCapture7943.tierReached(21.0))
        assertEquals(1, SpikeCapture7943.tierReached(71.0))   // WcErhi18
        assertEquals(1, SpikeCapture7943.tierReached(105.0))  // LAMBO
        assertEquals(2, SpikeCapture7943.tierReached(276.0))  // foff
        assertEquals(3, SpikeCapture7943.tierReached(664.0))  // AsNTKE
        assertEquals(0, SpikeCapture7943.tierReached(Double.NaN))
    }

    @Test fun mayhemSupply() {
        assertTrue(MayhemMode7943.mayhemSupply(2_000_000_000.0))
        assertFalse(MayhemMode7943.mayhemSupply(1_000_000_000.0))
        assertFalse(MayhemMode7943.mayhemSupply(Double.NaN))
    }
}

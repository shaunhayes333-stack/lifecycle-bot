package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexCalibration7901
import org.junit.Assert.assertEquals
import org.junit.Test

/** V5.0.7901 — Cortex v7: the fused edge is shrunk to what it actually earns. */
class Aate7901CalibrationTest {
    @Test fun anOverconfidentCortexIsShrunk() {
        val c = CortexCalibration7901()
        assertEquals(10.0, c.calibrate("MEME", 10.0, 0.0), 1e-9)
        repeat(300) { i -> val d = if (i % 2 == 0) 10.0 else -10.0; c.learn("MEME", d, 0.0, d * 0.5) }
        assertEquals(0.5, c.slope("MEME"), 1e-6)
        assertEquals(5.0, c.calibrate("MEME", 10.0, 0.0), 1e-6)
        assertEquals(1.0, c.slope("OTHER"), 1e-12)
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7898 — Cortex v4: the lane prior and calibration are read in the current regime. */
class Aate7898RegimeCortexTest {
    @Test fun theLanePriorFollowsTheRegime() {
        val led = CortexLedger7885()
        val ids = listOf("X"); val edges = listOf(doubleArrayOf(0.5))
        repeat(100) { led.grade("MEME", ids, edges, doubleArrayOf(Double.NaN), 6.0, 6.0, "NORMAL") }
        repeat(100) { led.grade("MEME", ids, edges, doubleArrayOf(Double.NaN), -8.0, -8.0, "DUMP") }
        val dump = led.fuse("MEME", emptyList(), "DUMP")
        val normal = led.fuse("MEME", emptyList(), "NORMAL")
        assertTrue("dump ${dump.edgePct}", dump.edgePct < -4.0)
        assertTrue("normal ${normal.edgePct}", normal.edgePct > 3.0)
        // A blank regime is the lane-only Cortex.
        assertEquals(-1.0, led.fuse("MEME", emptyList()).edgePct, 1e-9)
    }
}

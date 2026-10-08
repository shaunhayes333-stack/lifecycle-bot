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

/** V5.0.7899 — Cortex v5: a combination is learned only when the pair carries edge its parts do not. */
class Aate7899InteractionCortexTest {
    @Test fun aJointBinCarriesWhatNeitherPartDoes() {
        // XOR world: outcome is +8 when exactly one flag is set, -8 otherwise.
        val led = CortexLedger7885()
        val flag = doubleArrayOf(0.5)
        val cross = DoubleArray(3) { it + 0.5 }
        val ids = listOf("A", "B", "X_A__B")
        val rnd = java.util.Random(7899)
        repeat(400) {
            val a = if (rnd.nextBoolean()) 1.0 else 0.0
            val b = if (rnd.nextBoolean()) 1.0 else 0.0
            val y = (if ((a > 0.5) != (b > 0.5)) 8.0 else -8.0) + rnd.nextGaussian() * 4.0
            led.grade("X", ids, listOf(flag, flag, cross), doubleArrayOf(a, b, a * 2 + b), y, y)
        }
        assertEquals(0.0, led.seats["A|X"]!!.authority(), 1e-12)
        assertEquals(0.0, led.seats["B|X"]!!.authority(), 1e-12)
        assertTrue(led.seats["X_A__B|X"]!!.authority() > 0.5)
    }
}

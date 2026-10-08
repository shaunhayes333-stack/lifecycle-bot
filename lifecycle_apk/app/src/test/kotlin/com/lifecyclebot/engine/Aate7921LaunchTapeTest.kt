package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.market.LaunchTape7921
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7921 — the launch tape promotes a forming crowd and learns its own bar. */
class Aate7921LaunchTapeTest {
    private fun feat(age: Double = 4.0, crowd: Int = 25, net: Double = 5.0, share: Double = 70.0, largest: Double = 15.0,
                     top3: Double = 35.0, dev: Boolean = false, fromPeak: Double = -10.0) =
        LaunchTape7921.Feat(age, crowd, net, crowd / age, share, largest, top3, dev, 40.0, fromPeak,
            LaunchTape7921.heatOf(crowd, age, share, largest, dev))

    @Test fun priorBarReadsACrowdForming() {
        assertTrue(LaunchTape7921.priorPass(feat()))
        assertFalse(LaunchTape7921.priorPass(feat(crowd = 6)))            // no crowd yet
        assertFalse(LaunchTape7921.priorPass(feat(largest = 60.0)))       // one wallet pumping it
        assertFalse(LaunchTape7921.priorPass(feat(dev = true)))           // dev sold
        assertFalse(LaunchTape7921.priorPass(feat(fromPeak = -50.0)))     // already dumped
        assertFalse(LaunchTape7921.priorPass(feat(age = 0.5)))            // judged at birth
        assertFalse(LaunchTape7921.priorPass(feat(age = 30.0)))           // too late to be early
    }

    @Test fun heatRewardsBroadFastBuying() {
        val broad = LaunchTape7921.heatOf(30, 3.0, 70.0, 10.0, false)
        val whale = LaunchTape7921.heatOf(30, 3.0, 70.0, 70.0, false)
        val devSold = LaunchTape7921.heatOf(30, 3.0, 70.0, 10.0, true)
        assertTrue(broad > whale)
        assertTrue(broad > devSold)
        assertEquals(0.0, LaunchTape7921.heatOf(0, 3.0, 70.0, 10.0, false), 1e-9)
    }

    @Test fun learnedBarNeedsProof() {
        val s = CortexLedger7885.Stat()
        repeat(10) { s.add(20.0, false) }
        assertFalse(LaunchTape7921.provenPositive(s))   // too few
        repeat(30) { s.add(if (it % 2 == 0) 25.0 else 5.0, false) }
        assertTrue(LaunchTape7921.provenPositive(s))
        val bad = CortexLedger7885.Stat()
        repeat(40) { bad.add(if (it % 2 == 0) -20.0 else -5.0, false) }
        assertTrue(LaunchTape7921.provenNegative(bad))
    }
}

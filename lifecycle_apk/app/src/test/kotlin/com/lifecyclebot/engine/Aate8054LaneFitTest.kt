package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.NetEdge8030
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8054 — CASHGEN/TREASURY need a coin that moves; QUALITY/BLUECHIP don't buy a finished run. */
class Aate8054LaneFitTest {
    @Test fun scalpLanesNeedAMover() {
        assertTrue(NetEdge8030.scalpMoveTooSmall8054(6.0, 3.5))      // 6% hour on a 3.5% round trip: too flat
        assertFalse(NetEdge8030.scalpMoveTooSmall8054(12.0, 3.5))    // 12% hour: moves enough
        assertTrue(NetEdge8030.scalpMoveTooSmall8054(null, 3.5))     // unknown move: not verified
    }

    @Test fun swingLanesSkipFinishedRuns() {
        // Normie: launchpad coin, a day old, $364K at $156.70 SOL = ~2,323 SOL = ~83x the 28 SOL launch cap.
        assertTrue(NetEdge8030.overextended8054(10.0, true, 600.0, 2_323.0))
        assertTrue(NetEdge8030.overextended8054(140.0, false, null, 50_000.0))   // +140% in the hour
        assertFalse(NetEdge8030.overextended8054(8.0, false, 30_000.0, 300_000.0)) // established coin, normal hour
        assertFalse(NetEdge8030.overextended8054(8.0, true, 600.0, 400.0))         // launchpad coin at a modest cap
    }

    @Test fun wired() {
        val g = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        assertTrue(g.contains("NetEdge8030.laneFitRefusal8054(ts, "))
    }
}

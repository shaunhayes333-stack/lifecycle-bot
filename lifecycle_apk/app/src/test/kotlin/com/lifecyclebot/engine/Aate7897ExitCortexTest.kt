package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexExit7897
import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7897 — Cortex v3: hold/sell authority only on proof across many positions. */
class Aate7897ExitCortexTest {
    private fun stat(n: Int, mean: Double) = CortexLedger7885.Stat().also { s -> repeat(n) { s.add(mean + if (it % 2 == 0) 6.0 else -6.0, false) } }

    @Test fun forwardEdgesBucket() {
        assertEquals(CortexExit7897.Bucket.HOLD_STRONG, CortexExit7897.bucketOf(4.0))
        assertEquals(CortexExit7897.Bucket.SELL_STRONG, CortexExit7897.bucketOf(-4.0))
        assertEquals(CortexExit7897.Bucket.NEUTRAL, CortexExit7897.bucketOf(1.0))
        assertEquals(CortexExit7897.Bucket.NEUTRAL, CortexExit7897.bucketOf(Double.NaN))
    }

    @Test fun holdingAuthorityNeedsSamplesAndDistinctPositions() {
        val good = stat(80, 6.0)
        assertTrue(CortexExit7897.holdProven(good, positions = 20))
        // 80 samples from a handful of positions are not 80 independent trades.
        assertFalse(CortexExit7897.holdProven(good, positions = 5))
        assertFalse(CortexExit7897.holdProven(stat(30, 6.0), positions = 20))
    }

    @Test fun sellAuthorityNeedsAProvenLosingStretch() {
        assertTrue(CortexExit7897.sellProven(stat(80, -6.0), positions = 20))
        assertFalse(CortexExit7897.sellProven(stat(80, -1.0), positions = 20))
    }

    @Test fun noReadMeansNoExitOpinion() {
        val ts = com.lifecyclebot.data.TokenState(mint = "NoRead7897", symbol = "NR")
        assertEquals(null, CortexExit7897.sellReason(ts))
        assertFalse(CortexExit7897.holdVeto(ts, "TRAIL_STOP_PEAK_40"))
    }
}

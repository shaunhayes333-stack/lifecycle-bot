package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731 as L
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8416 — owner's schedule 5m, 15m, 30m, 1h, 4h, 24h; from 30 minutes only coins worth following are followed. */
class Aate8416ForwardScheduleTest {
    private val m = 60_000L
    @Test fun checkpointsPersistAsHorizonsSoTheScheduleCannotShiftAnIndex() {
        assertEquals("H${15 * m}", L.ckField8416(0)); assertEquals("H${30 * m}", L.ckField8416(1)); assertEquals("H${60 * m}", L.ckField8416(2))
        assertEquals("H-1", L.ckField8416(5))
        for (i in 0..4) assertEquals(i, L.ckFromField8416(L.ckField8416(i)))
        assertEquals(5, L.ckFromField8416("H-1"))
    }
    @Test fun legacyIndexesMapToTheSameNextHorizon() {
        assertEquals(0, L.ckFromField8416("0"))   // 15 m next
        assertEquals(2, L.ckFromField8416("1"))   // old 1 = 1 h next -> new 1 h (the 30 m it already passed is not re-asked)
        assertEquals(3, L.ckFromField8416("2"))   // 4 h
        assertEquals(4, L.ckFromField8416("3"))   // 24 h
        assertEquals(5, L.ckFromField8416("4"))   // 48 h (dropped) -> finished
        assertEquals(5, L.ckFromField8416("6"))
        assertEquals(0, L.ckFromField8416(null))
    }
    @Test fun worthFollowingGateStartsAtThirtyMinutes() {
        assertFalse(L.followGate8416(15 * m))
        assertTrue(L.followGate8416(30 * m))
        assertTrue(L.followGate8416(4 * 60 * m))
    }
}

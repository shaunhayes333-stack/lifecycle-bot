package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.StopAuthority7887
import org.junit.Assert.assertEquals
import org.junit.Test

/** V5.0.7887 — one ordinary stop per position, from the components that own it. */
class Aate7887StopAuthorityTest {
    @Test fun aPlannedTradeOwnsItsStopButNeverUnderFourPercent() {
        assertEquals(9.0, StopAuthority7887.compose(-9.0, 12.0, 1.5, runnerLane = false), 1e-9)
        assertEquals(4.0, StopAuthority7887.compose(-2.0, 12.0, 1.0, runnerLane = false), 1e-9)
    }

    @Test fun theLaneBaseCarriesTheLearnedAndRegretMultiplier() {
        // QUALITY base 12% widened by ExitRegret (x1.5) -> 18%.
        assertEquals(18.0, StopAuthority7887.compose(null, 12.0, 1.5, runnerLane = false), 1e-9)
        // Clamped below the catastrophe line.
        assertEquals(25.0, StopAuthority7887.compose(null, 20.0, 1.6, runnerLane = false), 1e-9)
    }

    @Test fun runnerLanesAreNeverTighterThanTheirFloor() {
        assertEquals(15.0, StopAuthority7887.compose(null, 8.0, 0.7, runnerLane = true), 1e-9)
    }
}

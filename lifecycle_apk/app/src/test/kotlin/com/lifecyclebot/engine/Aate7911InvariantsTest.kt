package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexInvariants7911
import com.lifecyclebot.engine.cortex.CortexInvariants7911.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** V5.0.7911 — missing data is UNKNOWN, old data is STALE, never silently zero. */
class Aate7911InvariantsTest {
    @Test fun featureStates() {
        assertEquals(State.UNKNOWN, CortexInvariants7911.stateOf(false, 0L, 1_000L))
        assertEquals(State.STALE, CortexInvariants7911.stateOf(true, 5_000L, 1_000L))
        assertEquals(State.OBSERVED, CortexInvariants7911.stateOf(true, 500L, 1_000L))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7995PlanWaitTest {
    @Test fun payingCellsAreNotMadeToWait() {
        assertTrue(LiveEdgeGate7877.cellSkipsPlanWait7995(11, 8.1))
        assertFalse(LiveEdgeGate7877.cellSkipsPlanWait7995(20, 2.0))   // live, but still plans normally
        assertFalse(LiveEdgeGate7877.cellSkipsPlanWait7995(5, 40.0))   // too few labels
    }
}

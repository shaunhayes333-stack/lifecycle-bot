package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.CanonicalEntryFloor7266
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7936 — entry bars rise only on the lane's own evidence; cold lanes trade to learn. */
class Aate7936EntryBarsTest {
    @Test fun aFloorRaiseNeedsTheLanesOwnCloses() {
        assertEquals(0.0, CanonicalEntryFloor7266.evidencedRaise7936(10.0, 2), 1e-9)
        assertEquals(10.0, CanonicalEntryFloor7266.evidencedRaise7936(10.0, 15), 1e-9)
        assertEquals(0.0, CanonicalEntryFloor7266.evidencedRaise7936(-5.0, 40), 1e-9)
    }

    @Test fun waitMarginFollowsEvidence() {
        assertEquals(10.0, CanonicalEntryFloor7266.waitMargin7936(false, 0.0, 2), 1e-9)
        assertEquals(25.0, CanonicalEntryFloor7266.waitMargin7936(false, 0.6, 30), 1e-9)
        assertEquals(12.5, CanonicalEntryFloor7266.waitMargin7936(true, 0.5, 30), 1e-9)
    }

    @Test fun noTriggerExploresUntilMeasured() {
        assertFalse(LanePlaybook7907.noTriggerMeasured7936(null))
        val thin = CortexLedger7885.Stat().apply { repeat(10) { add(-5.0, false) } }
        assertFalse(LanePlaybook7907.noTriggerMeasured7936(thin))
        val mature = CortexLedger7885.Stat().apply { repeat(45) { add(-5.0, false) } }
        assertTrue(LanePlaybook7907.noTriggerMeasured7936(mature))
    }
}

/** V5.0.7937 — scoring reflects the lane playbook. */
class Aate7937PlaybookScoreTest {
    @Test fun playbookScoreReadsExpectedReturnAndConfluence() {
        // superseded: 8535 §C05 — the display is a range-preserving shape (50 ± 50·tanh(x/8)), no longer 10 points per % clipped at ±5 %
        assertEquals(50.0, LanePlaybook7907.scoreOf7937(0.0, 0), 1e-9)
        assertEquals(50.0 + 50.0 * kotlin.math.tanh(1.0 / 8.0), LanePlaybook7907.scoreOf7937(1.0, 0), 1e-9)
        assertEquals(LanePlaybook7907.scoreOf7937(1.0, 0) + 6.0, LanePlaybook7907.scoreOf7937(1.0, 2), 1e-9)
        assertTrue(LanePlaybook7907.scoreOf7937(-9.0, 0) in 5.0..15.0)
        assertEquals(100.0, LanePlaybook7907.scoreOf7937(8.0, 5), 1e-9)   // confluence can still fill the display cap
    }
}

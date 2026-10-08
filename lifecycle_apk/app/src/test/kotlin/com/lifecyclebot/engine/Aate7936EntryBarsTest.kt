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

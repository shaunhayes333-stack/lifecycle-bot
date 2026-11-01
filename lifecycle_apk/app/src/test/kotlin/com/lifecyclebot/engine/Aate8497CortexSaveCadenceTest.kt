package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8497 — full Cortex checkpoints are spaced by their own measured cost. */
class Aate8497CortexSaveCadenceTest {
    @Test fun expensiveSavesAreSpacedCheapSavesKeepTheOldCadence() {
        // cheap save: unchanged 8377 behaviour
        assertTrue(Cortex7885.shadowFlushDue8377(Cortex7885.SHADOW_FLUSH_MIN_BATCH_8377, 0L, 100L))
        assertTrue(Cortex7885.shadowFlushDue8377(1, Cortex7885.SHADOW_FLUSH_MAX_WAIT_MS_8377, 100L))
        // 5.0.8492 cost (encode 2.9 s + db 0.8 s): a 64-batch no longer forces a 38 MB rewrite every few seconds
        assertFalse(Cortex7885.shadowFlushDue8377(64, 6_000L, 3_700L))
        assertFalse(Cortex7885.shadowFlushDue8377(64, 60_000L, 3_700L))
        assertTrue(Cortex7885.shadowFlushDue8377(64, 74_000L, 3_700L))
        assertTrue("a very large batch still goes now", Cortex7885.shadowFlushDue8377(Cortex7885.LARGE_BATCH_8497, 0L, 3_700L))
        assertTrue("never more than the cap", Cortex7885.shadowFlushDue8377(1, Cortex7885.SAVE_GAP_CAP_MS_8497, 60_000L))
    }
}

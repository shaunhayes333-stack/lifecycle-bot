package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8966 — the playbook save was the largest single allocator in the 24 Nov 00:39 CPU sample (2.3 s of the app's 6.7 s of
 * string / JSON / collection CPU; the collector 10.9 s of 51 s): it re-encoded every lane book about every 7 s under the
 * playbook lock. A real book now saves at most every 30 s (19x its cost when dearer, 60 s cap); a flush never waits.
 */
class Aate8966SavePaceTest {
    private val p = LanePlaybook7907

    @Test fun aHeavySaveWaitsAtLeastThirtySeconds() {
        assertEquals(100L, p.HEAVY_SAVE_MS_8966)
        assertEquals(30_000L, p.MIN_HEAVY_PACE_MS_8966)
        assertEquals("the measured 724 ms save: 30 s, not 2.9 s", 30_000L, p.checkpointWaitMs8901(100_000L, 724L, 100_000L, false))
        assertEquals("a 2 s save: 19x = 38 s", 38_000L, p.checkpointWaitMs8901(100_000L, 2_000L, 100_000L, false))
        assertEquals("time already waited counts", 10_000L, p.checkpointWaitMs8901(100_000L, 724L, 120_000L, false))
        assertEquals("never past the cap", 60_000L, p.checkpointWaitMs8901(100_000L, 5_000L, 100_000L, false))
    }

    @Test fun aLightSaveAndAFlushAreNotHeldBack() {
        assertEquals(1_881L, p.checkpointWaitMs8901(100_000L, 99L, 100_000L, false))
        assertEquals(0L, p.checkpointWaitMs8901(100_000L, 724L, 100_001L, true))
        assertEquals(0L, p.checkpointWaitMs8901(0L, 724L, 100_000L, false))
    }

    @Test fun eachSaveDrainsAPacesDeliveries() {
        assertEquals(32 * 60, p.SAVE_BATCH_8901)
        val src = File("src/main/kotlin/com/lifecyclebot/engine/cortex/LanePlaybook7907.kt").readText()
        assertTrue(src.contains(".take(SAVE_BATCH_8901) else emptyList()"))
        assertTrue(p.checkpointLine8901().contains("a shutdown flush never waits (8966)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 152. The playbook save is paced to its weight (8966)"))
    }
}

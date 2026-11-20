package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8901 — the playbook checkpoint saved back to back, once per forward outcome (02:09 CPU sample: 4.9 s of 30 s on its
 * thread). A save now starts at most once per pace; a shutdown flush does not wait; each save drains a pace of deliveries.
 */
class Aate8901PacedCheckpointTest {
    @Test fun oneSavePerPaceUnlessUrgent() {
        val pace = LanePlaybook7907.CHECKPOINT_PACE_MS_8901
        assertEquals(10_000L, pace)
        assertEquals("first save never waits", 0L, LanePlaybook7907.checkpointWaitMs8901(0L, 5_000L, false))
        assertEquals(pace - 2_000L, LanePlaybook7907.checkpointWaitMs8901(100_000L, 102_000L, false))
        assertEquals(0L, LanePlaybook7907.checkpointWaitMs8901(100_000L, 100_000L + pace, false))
        assertEquals("a shutdown flush skips the pace", 0L, LanePlaybook7907.checkpointWaitMs8901(100_000L, 100_001L, true))
        assertEquals("clock moved back", 0L, LanePlaybook7907.checkpointWaitMs8901(100_000L, 99_000L, false))
        assertEquals("a pace of the producer's 32-per-pass", 320, LanePlaybook7907.SAVE_BATCH_8901)
    }

    @Test fun theWorkerWaitsAndTheFlushIsUrgent() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/cortex/LanePlaybook7907.kt").readText()
        val loop = p.substringAfter("while (checkpointRequested8372.getAndSet(false)) {").substringBefore("} finally {")
        assertTrue(loop.indexOf("awaitCheckpointPace8901()") in 0 until loop.indexOf("try { saveCheckpoint8871() }"))
        assertTrue(p.substringAfter("fun persistNow7930() {").substringBefore("persist()").contains("checkpointUrgent8901 = true"))
        assertTrue(p.contains(".take(SAVE_BATCH_8901) else emptyList()"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/DiagnosisBrief8438.kt").readText().contains("LanePlaybook7907.checkpointLine8901()"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 90. Audit of 20-21 Nov, and the playbook save paced (8901)"))
    }
}

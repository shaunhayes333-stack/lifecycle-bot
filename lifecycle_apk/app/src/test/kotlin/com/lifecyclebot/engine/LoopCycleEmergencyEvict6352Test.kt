package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class LoopCycleEmergencyEvict6352Test {

    @Before
    fun reset() {
        LoopCycleEmergencyEvict6352.resetForTest()
        ScannerHydrationQueues6347.resetForTest()
    }

    @Test
    fun no_shed_below_twenty_seconds() {
        assertFalse(LoopCycleEmergencyEvict6352.onCycleOverrun(prevCycleMs = 15_000L))
        assertEquals(0L, LoopCycleEmergencyEvict6352.totalShedCount())
    }

    @Test
    fun sheds_hydrating_bucket_when_cycle_over_twenty_seconds() {
        for (i in 1..5) {
            ScannerHydrationQueues6347.enqueue("M$i", ScannerHydrationQueues6347.Bucket.HYDRATING)
        }
        ScannerHydrationQueues6347.enqueue("KEEP", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        assertTrue(LoopCycleEmergencyEvict6352.onCycleOverrun(prevCycleMs = 33_000L))
        val sizes = ScannerHydrationQueues6347.sizesByBucket()
        assertEquals(0, sizes[ScannerHydrationQueues6347.Bucket.HYDRATING])
        // LIVE_READY must NEVER be evicted — the trading queue is sacred.
        assertEquals(1, sizes[ScannerHydrationQueues6347.Bucket.LIVE_READY])
    }

    @Test
    fun cooldown_prevents_double_shed_within_thirty_seconds() {
        ScannerHydrationQueues6347.enqueue("M1", ScannerHydrationQueues6347.Bucket.HYDRATING)
        assertTrue(LoopCycleEmergencyEvict6352.onCycleOverrun(prevCycleMs = 21_000L))
        // Second overrun immediately — must be gated by cooldown.
        ScannerHydrationQueues6347.enqueue("M2", ScannerHydrationQueues6347.Bucket.HYDRATING)
        assertFalse(LoopCycleEmergencyEvict6352.onCycleOverrun(prevCycleMs = 21_000L))
        // The second HYDRATING row is still present because cooldown blocked shed.
        assertEquals(1, ScannerHydrationQueues6347.sizesByBucket()[ScannerHydrationQueues6347.Bucket.HYDRATING])
    }

    @Test
    fun shed_count_increments_on_each_successful_shed() {
        assertEquals(0L, LoopCycleEmergencyEvict6352.totalShedCount())
        assertTrue(LoopCycleEmergencyEvict6352.onCycleOverrun(prevCycleMs = 25_000L))
        assertEquals(1L, LoopCycleEmergencyEvict6352.totalShedCount())
    }
}

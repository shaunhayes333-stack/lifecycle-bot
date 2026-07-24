package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScannerHydrationQueues6347Test {

    @Before fun reset() { ScannerHydrationQueues6347.resetForTest() }

    @Test
    fun enqueue_routes_into_the_correct_bucket() {
        ScannerHydrationQueues6347.enqueue("M1", ScannerHydrationQueues6347.Bucket.LIVE_READY, "STANDARD")
        ScannerHydrationQueues6347.enqueue("M2", ScannerHydrationQueues6347.Bucket.HYDRATING, "STANDARD")
        val sizes = ScannerHydrationQueues6347.sizesByBucket()
        assertEquals(1, sizes[ScannerHydrationQueues6347.Bucket.LIVE_READY])
        assertEquals(1, sizes[ScannerHydrationQueues6347.Bucket.HYDRATING])
    }

    @Test
    fun drain_returns_only_matching_bucket_and_removes_rows() {
        ScannerHydrationQueues6347.enqueue("A", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        ScannerHydrationQueues6347.enqueue("B", ScannerHydrationQueues6347.Bucket.HYDRATING)
        ScannerHydrationQueues6347.enqueue("C", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        val drained = ScannerHydrationQueues6347.drain(ScannerHydrationQueues6347.Bucket.LIVE_READY)
        assertEquals(2, drained.size)
        assertTrue(drained.contains("A"))
        assertTrue(drained.contains("C"))
        val sizes = ScannerHydrationQueues6347.sizesByBucket()
        assertEquals(0, sizes[ScannerHydrationQueues6347.Bucket.LIVE_READY])
        assertEquals(1, sizes[ScannerHydrationQueues6347.Bucket.HYDRATING])
    }

    @Test
    fun hydrating_promotes_to_live_ready_on_reassignment() {
        ScannerHydrationQueues6347.enqueue("X", ScannerHydrationQueues6347.Bucket.HYDRATING)
        ScannerHydrationQueues6347.enqueue("X", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        val sizes = ScannerHydrationQueues6347.sizesByBucket()
        assertEquals(1, sizes[ScannerHydrationQueues6347.Bucket.LIVE_READY])
        assertEquals(0, sizes[ScannerHydrationQueues6347.Bucket.HYDRATING])
    }

    @Test
    fun rejectWithTtl_blocks_subsequent_enqueue_until_ttl_lapses() {
        ScannerHydrationQueues6347.rejectWithTtl("R1", ttlMs = 60_000L, note = "SPREAD_TOO_WIDE")
        // Attempted re-enqueue while TTL is live must be ignored.
        ScannerHydrationQueues6347.enqueue("R1", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        val sizes = ScannerHydrationQueues6347.sizesByBucket()
        assertEquals(1, sizes[ScannerHydrationQueues6347.Bucket.REJECTED_WITH_TTL])
        assertEquals(0, sizes[ScannerHydrationQueues6347.Bucket.LIVE_READY])
    }

    @Test
    fun drain_max_respects_limit() {
        for (i in 1..10) {
            ScannerHydrationQueues6347.enqueue("M$i", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        }
        val d = ScannerHydrationQueues6347.drain(ScannerHydrationQueues6347.Bucket.LIVE_READY, max = 4)
        assertEquals(4, d.size)
        val remaining = ScannerHydrationQueues6347.sizesByBucket()[ScannerHydrationQueues6347.Bucket.LIVE_READY]
        assertEquals(6, remaining)
    }

    @Test
    fun snapshot_string_is_stable_and_labelled() {
        ScannerHydrationQueues6347.enqueue("X", ScannerHydrationQueues6347.Bucket.LIVE_READY)
        val s = ScannerHydrationQueues6347.snapshot()
        assertTrue(s.startsWith("SCANNER_HYD_QUEUES_6347"))
        assertTrue(s.contains("LIVE_READY=1/1"))
    }
}

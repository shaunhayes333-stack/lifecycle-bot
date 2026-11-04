package com.lifecyclebot.network

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Aate8527ObservationFanoutTest {
    @Test fun saturatedBackgroundWorkCannotConsumeHeldPoolCapacity() {
        val background = ObservationFanout8527.Pools(1, 1, 1, 1, "test-background")
        val protective = ObservationFanout8527.Pools(1, 1, 1, 1, "test-held")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejectedRan = AtomicInteger()
        try {
            val bulk = ObservationFanout8527.Batch(3, 5_000L, poolsForTest = background)
            bulk.submit { started.countDown(); release.await(4, TimeUnit.SECONDS) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            bulk.submit { /* one bounded queued job */ }
            bulk.submit { rejectedRan.incrementAndGet() }
            val heldRan = AtomicInteger()
            val held = ObservationFanout8527.Batch(1, 2_000L, held = true, poolsForTest = protective)
            held.submit { heldRan.incrementAndGet() }
            assertTrue(held.await())
            assertEquals(1, heldRan.get())
            assertEquals(1, background.source.largestPoolSize)
            assertTrue(background.source.queue.size <= 1)
            release.countDown()
            assertFalse("saturation remains retryable unknown", bulk.await())
            assertEquals(0, rejectedRan.get())
        } finally { release.countDown(); background.shutdown(); protective.shutdown() }
    }

    @Test fun aBlockedSourceParentNeverQueuesItsChildBehindItself() {
        val pools = ObservationFanout8527.Pools(1, 1, 1, 1, "test-nested")
        val leafRan = AtomicInteger()
        val childOk = AtomicInteger()
        try {
            val source = ObservationFanout8527.Batch(1, 3_000L, poolsForTest = pools)
            source.submit {
                val leaf = ObservationFanout8527.Batch(1, 3_000L, leaf = true, poolsForTest = pools)
                leaf.submit { leafRan.incrementAndGet() }
                if (leaf.await()) childOk.incrementAndGet()
            }
            assertTrue(source.await())
            assertEquals(1, leafRan.get())
            assertEquals(1, childOk.get())
        } finally { pools.shutdown() }
    }

    @Test fun queuedExpiredWorkIsPurgedWithoutReachingItsSource() {
        val pools = ObservationFanout8527.Pools(1, 1, 1, 1, "test-expiry")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val expiredRan = AtomicInteger()
        try {
            val blocker = ObservationFanout8527.Batch(1, 5_000L, poolsForTest = pools)
            blocker.submit { started.countDown(); release.await(4, TimeUnit.SECONDS) }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val expired = ObservationFanout8527.Batch(1, 1L, poolsForTest = pools, deadlineNanos = System.nanoTime() - 1L)
            expired.submit { expiredRan.incrementAndGet() }
            assertFalse(expired.await())
            assertEquals(0, pools.source.queue.size)
            release.countDown()
            assertTrue(blocker.await())
            assertEquals(0, expiredRan.get())
        } finally { release.countDown(); pools.shutdown() }
    }

    @Test fun nestedDeadlineNeverRestartsTheParentsClock() {
        val scope = ObservationHttpScope8527.Scope(System.nanoTime() - 1L)
        val pools = ObservationFanout8527.Pools(1, 1, 1, 1, "test-parent-expired")
        val ran = AtomicInteger()
        try {
            ObservationHttpScope8527.withScope(scope) {
                val child = ObservationFanout8527.Batch(1, 5_000L, leaf = true, poolsForTest = pools)
                child.submit { ran.incrementAndGet() }
                assertFalse(child.await())
            }
            assertEquals(0, ran.get())
        } finally { scope.cancel(); pools.shutdown() }
    }
    @Test fun productionPartitionsHeldReadsAndKeepsTheirCurveBatchFreeOfShadowBacklog() {
        val source = File("src/main/kotlin/com/lifecyclebot/network/ParallelMarkFanout7088.kt").readText()
        assertTrue(source.contains("HeldPositionSupervisor7246.isHeld(mint)"))
        assertTrue(source.contains("held = protective8527, deadlineNanos = deadline8527"))
        assertTrue(source.contains("latencyCritical8527 = ObservationHttpScope8527.current()?.held == true"))
        assertTrue(source.contains("val mints8359 = if (latencyCritical8527) mints else"))
        assertFalse(source.contains("newCachedThreadPool"))
    }

}

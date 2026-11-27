package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DatasetReplay8863
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Failed loading retains queued live labels; a rebuild and a live mutation cannot overlap. */
class Aate9043SeedLiveOrderingTest {
    @After fun cleanup() {
        DatasetReplay8863.readinessForTest9043 = null
        DatasetReplay8863.retrySleepForTest9043 = null
    }

    @Test fun labelsQueuedBeforeOrAfterAFailedLoaderWaitForOneCompleteGeneration() {
        val ready = AtomicBoolean(false)
        val failedReadiness = CountDownLatch(2)
        val writes = AtomicInteger()
        val active = AtomicInteger()
        val error = AtomicReference<Throwable?>()
        DatasetReplay8863.retrySleepForTest9043 = 1L
        DatasetReplay8863.readinessForTest9043 = { ready.get().also { if (!it) failedReadiness.countDown() } }
        val workers = (0 until 2).map {
            Thread {
                try {
                    DatasetReplay8863.afterSeed9043 {
                        assertEquals("one generation owner during live mutation", 1, active.incrementAndGet())
                        try { Thread.yield(); writes.incrementAndGet() } finally { active.decrementAndGet() }
                    }
                } catch (t: Throwable) { error.compareAndSet(null, t) }
            }
        }
        try {
            workers.forEach { it.start() }
            assertTrue(failedReadiness.await(5, TimeUnit.SECONDS))
            assertEquals("failed loading cannot publish live mutations", 0, writes.get())
            ready.set(true)
            workers.forEach { it.join(5_000L); assertFalse(it.isAlive) }
            error.get()?.let { throw it }
            assertEquals("both retained callbacks applied once", 2, writes.get())
        } finally { workers.forEach { it.interrupt(); it.join(5_000L) } }
    }

    @Test fun cancellationCannotPublishAnUnseededLiveLabel() {
        val attempted = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val writes = AtomicInteger()
        DatasetReplay8863.readinessForTest9043 = { attempted.countDown(); false }
        val worker = Thread {
            try { DatasetReplay8863.afterSeed9043 { writes.incrementAndGet() } }
            catch (_: InterruptedException) { interrupted.set(true) }
        }
        try {
            worker.start(); assertTrue(attempted.await(5, TimeUnit.SECONDS)); worker.interrupt(); worker.join(5_000L)
            assertFalse(worker.isAlive); assertTrue(interrupted.get()); assertEquals(0, writes.get())
        } finally { worker.interrupt(); worker.join(5_000L) }
    }

    @Test fun aPartlyAppliedLiveMutationIsNeverAutomaticallyRetried() {
        val mutations = AtomicInteger()
        DatasetReplay8863.readinessForTest9043 = { true }
        try {
            DatasetReplay8863.afterSeed9043 { mutations.incrementAndGet(); throw IllegalStateException("after write") }
            fail("mutation failure must propagate")
        } catch (e: IllegalStateException) { assertEquals("after write", e.message) }
        assertEquals(1, mutations.get())
    }

    @Test fun cancellationWhileWaitingForTheGenerationOwnerCannotPublish() {
        val ownerEntered = CountDownLatch(1)
        val releaseOwner = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val writes = AtomicInteger()
        DatasetReplay8863.readinessForTest9043 = { true }
        val owner = Thread { DatasetReplay8863.afterSeed9043 {
            ownerEntered.countDown(); releaseOwner.await(5, TimeUnit.SECONDS)
        } }
        val waiter = Thread {
            try { DatasetReplay8863.afterSeed9043 { writes.incrementAndGet() } }
            catch (_: InterruptedException) { interrupted.set(true) }
        }
        try {
            owner.start(); assertTrue(ownerEntered.await(5, TimeUnit.SECONDS)); waiter.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (waiter.state != Thread.State.BLOCKED && waiter.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, waiter.state)
            waiter.interrupt(); releaseOwner.countDown(); waiter.join(5_000L); owner.join(5_000L)
            assertFalse(waiter.isAlive); assertTrue(interrupted.get()); assertEquals(0, writes.get())
        } finally {
            releaseOwner.countDown(); owner.interrupt(); waiter.interrupt(); owner.join(5_000L); waiter.join(5_000L)
        }
    }
}

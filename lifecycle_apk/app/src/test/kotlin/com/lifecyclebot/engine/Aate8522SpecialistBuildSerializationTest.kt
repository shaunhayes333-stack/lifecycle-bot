package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.market.SpecialistCandidateBooks7803
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class Aate8522SpecialistBuildSerializationTest {
    @After fun resetAuthorities() {
        SpecialistCandidateBooks7803.resetForTests()
        SpecialistElection8136.resetForTests8136()
    }

    private fun awaitMintMonitor(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        assertEquals("the queued call reached the mint monitor before publication was released",
            Thread.State.BLOCKED, thread.state)
    }

    private fun sheet(lane: String): ToolkitSignalSheet.Sheet = ToolkitSignalSheet.Sheet(
        ToolkitSignalSheet.Setup.CHART_PULLBACK_RECLAIM, 80.0, "reclaim", "native", "native",
        1.0, 1.0, 1.0, setOf(lane), emptyMap(), emptySet(), emptyList(), setOf(lane))

    private fun ready(mint: String, lane: String, version: Long) =
        SpecialistCandidateBooks7803.markReady(lane, mint, "T", version, 80, 80.0, "current_native")

    @Test fun oldAdvisoryBuildFinishesBeforeNewWorkerCanPublishItsRealReadyCandidate() {
        val ts = TokenState("serial8522-${System.nanoTime()}").apply { lastMcap = 1_000_000.0 }
        val version = 8522L
        ready(ts.mint, "CASHGEN", version)
        val oldBuildStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val workerRequested = CountDownLatch(1)
        val workerBuilding = CountDownLatch(1)
        val workerThread = AtomicReference<Thread>()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val old = pool.submit<ToolkitSignalSheet.Sheet> {
                ToolkitSignalSheet.completedSheet8522(ts) {
                    oldBuildStarted.countDown()
                    assertTrue(releaseOld.await(5, TimeUnit.SECONDS))
                    ready(ts.mint, "QUALITY", version)
                    SpecialistCandidateBooks7803.markLost("CASHGEN", ts.mint, version, "old_native_refusal")
                    sheet("QUALITY")
                }
            }
            assertTrue(oldBuildStarted.await(5, TimeUnit.SECONDS))
            ts.lastMcap = 2_000_000.0 // New evidence arrived while the older native assessment was computing.
            val worker = pool.submit<ToolkitSignalSheet.Sheet> {
                workerThread.set(Thread.currentThread())
                workerRequested.countDown()
                ToolkitSignalSheet.completedSheet8522(ts) {
                    workerBuilding.countDown()
                    ready(ts.mint, "CASHGEN", version)
                    SpecialistCandidateBooks7803.markLost("QUALITY", ts.mint, version, "current_native_refusal")
                    sheet("CASHGEN")
                }
            }
            assertTrue(workerRequested.await(5, TimeUnit.SECONDS))
            awaitMintMonitor(workerThread.get())
            assertFalse("same-mint publication cannot overtake the older native/LOST writes",
                workerBuilding.await(100, TimeUnit.MILLISECONDS))
            releaseOld.countDown()
            old.get(5, TimeUnit.SECONDS)
            val current = worker.get(5, TimeUnit.SECONDS)
            assertEquals(setOf("CASHGEN"), current.executableLanes7863)
            assertEquals(setOf("CASHGEN"), SpecialistCandidateBooks7803.readyFor(ts.mint, version).keys)
            assertTrue(SpecialistElection8136.nativeEvalSlot8214(ts.mint, "CASHGEN", "QUALITY", version,
                "CASHGEN" in current.executableLanes7863))
        } finally {
            releaseOld.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun queuedAdvisoryReadsCacheInsideGuardAndCannotReplayItsOlderRefusal() {
        val ts = TokenState("queued8522-${System.nanoTime()}")
        val version = 8522L
        val workerBuilding = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val advisoryRequested = CountDownLatch(1)
        val advisoryThread = AtomicReference<Thread>()
        val staleBuilderRan = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val worker = pool.submit<ToolkitSignalSheet.Sheet> {
                ToolkitSignalSheet.completedSheet8522(ts) {
                    workerBuilding.countDown()
                    assertTrue(releaseWorker.await(5, TimeUnit.SECONDS))
                    ready(ts.mint, "CASHGEN", version)
                    sheet("CASHGEN")
                }
            }
            assertTrue(workerBuilding.await(5, TimeUnit.SECONDS))
            val advisory = pool.submit<ToolkitSignalSheet.Sheet> {
                advisoryThread.set(Thread.currentThread())
                advisoryRequested.countDown()
                ToolkitSignalSheet.completedSheet8522(ts) {
                    staleBuilderRan.set(true)
                    SpecialistCandidateBooks7803.markLost("CASHGEN", ts.mint, version, "queued_old_refusal")
                    sheet("QUALITY")
                }
            }
            assertTrue(advisoryRequested.await(5, TimeUnit.SECONDS))
            awaitMintMonitor(advisoryThread.get())
            releaseWorker.countDown()
            assertSame(worker.get(5, TimeUnit.SECONDS), advisory.get(5, TimeUnit.SECONDS))
            assertFalse(staleBuilderRan.get())
            assertEquals(setOf("CASHGEN"), SpecialistCandidateBooks7803.readyFor(ts.mint, version).keys)
        } finally {
            releaseWorker.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun directNativeAndToolkitShareAReentrantGuardWithoutBlockingOtherMintsOrRetainingLocks() {
        val mint = "guard8522-${System.nanoTime()}"
        val baseline = SpecialistBuild8522.activeMints8522()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val held = pool.submit<String> {
                SpecialistBuild8522.local8522(mint) {
                    // Toolkit -> SpecialistBrainBridge takes the same guard again on this thread.
                    SpecialistBuild8522.local8522(mint) {
                        entered.countDown()
                        assertTrue(release.await(5, TimeUnit.SECONDS))
                        "nested"
                    }
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val other = pool.submit<String> { SpecialistBuild8522.local8522("$mint-other") { "independent" } }
            assertEquals("independent", other.get(5, TimeUnit.SECONDS))
            assertEquals(baseline + 1, SpecialistBuild8522.activeMints8522())
            release.countDown()
            assertEquals("nested", held.get(5, TimeUnit.SECONDS))
            try {
                SpecialistBuild8522.local8522(mint) { throw IllegalStateException("native failure") }
                fail("the native exception must reach the caller")
            } catch (expected: IllegalStateException) {
                assertEquals("native failure", expected.message)
            }
            assertEquals(baseline, SpecialistBuild8522.activeMints8522())
        } finally {
            release.countDown()
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.data.SnapshotCache8864
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * V5.0.8864 — 5.0.8863 froze at launch with the dashboard's main thread waiting for ConfigStore's monitor behind another
 * thread's rebuild. The cached config is now a snapshot no reader queues for, and the dashboard's first render waits for
 * the lifecycle transaction to end.
 */
class Aate8864NoReaderWaitsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun aFreshSnapshotIsReturnedWithoutBuilding() {
        var now = 1_000L
        val c = SnapshotCache8864<String>(2_000L) { now }
        var builds = 0
        assertNull(c.fresh())
        assertEquals("v1", c.get { builds++; "v1" })
        now += 1_999L
        assertEquals("v1", c.fresh())
        assertEquals("v1", c.get { builds++; "v2" })
        assertEquals(1, builds)
    }

    @Test fun anAgedSnapshotIsRebuiltByTheCallerThatFindsTheStoreFree() {
        var now = 0L
        val c = SnapshotCache8864<String>(2_000L) { now }
        var builds = 0
        assertEquals("v1", c.get { builds++; "v1" })
        now += 2_000L
        assertNull("aged", c.fresh())
        assertEquals("v2", c.get { builds++; "v2" })
        assertEquals("v2", c.fresh())
        assertEquals(2, builds)
    }

    @Test(timeout = 30_000) fun aReaderIsNotQueuedBehindAnotherThreadsRebuild() {
        val now = AtomicLong(0L)
        val c = SnapshotCache8864<String>(2_000L) { now.get() }
        c.get { "old" }
        now.addAndGet(5_000L)
        val inBuild = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rebuilt = AtomicReference("")
        val slow = thread(name = "slow-rebuilder-8864") { rebuilt.set(c.get { inBuild.countDown(); release.await(); "new" }) }
        assertTrue(inBuild.await(10, TimeUnit.SECONDS))
        // the rebuilder holds the store until this test releases it: a reader that queued behind it would never return
        var built = false
        assertEquals("old", c.get { built = true; "never" })
        assertEquals("old", c.get { built = true; "never" })
        assertFalse("a reader ran its own rebuild beside the one in progress", built)
        assertNull("the held snapshot is aged, and says so", c.fresh())
        release.countDown()
        slow.join(10_000L)
        assertEquals("new", rebuilt.get())
        assertEquals("new", c.fresh())
        val status = c.statusLine("cache")
        assertTrue(status, status.contains("servedWhileBusy=2") && status.contains("builds=2") && status.contains("slow-rebuilder-8864/prio"))
    }

    @Test(timeout = 30_000) fun withNothingHeldEveryReaderGetsTheOneBuild() {
        val c = SnapshotCache8864<String>(2_000L) { 0L }
        val builds = AtomicInteger()
        val inBuild = CountDownLatch(1)
        val release = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<String>()
        val first = thread { results.add(c.get { builds.incrementAndGet(); inBuild.countDown(); release.await(); "v1" }) }
        assertTrue(inBuild.await(10, TimeUnit.SECONDS))
        val others = (1..4).map { thread { results.add(c.get { builds.incrementAndGet(); "another build" }) } }
        release.countDown()
        first.join(10_000L)
        others.forEach { it.join(10_000L) }
        assertEquals(1, builds.get())
        assertEquals(List(5) { "v1" }, results.toList())
    }

    @Test(timeout = 30_000) fun readersDuringAWriteGetTheSnapshotFromBeforeItAndReadersAfterItTheNewOne() {
        val now = AtomicLong(0L)
        val c = SnapshotCache8864<String>(2_000L) { now.get() }
        val store = AtomicReference("before")
        c.get { store.get() }
        val inWrite = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(ArrayList<String>())
        val writer = thread {
            c.write(
                change = { store.set("after"); inWrite.countDown(); release.await() },
                reread = { order.add("announced with " + (c.fresh() ?: "nothing held")); store.get() },
            )
            order.add("returned")
        }
        assertTrue(inWrite.await(10, TimeUnit.SECONDS))
        var built = false
        assertEquals("a fresh snapshot, while the write is in progress", "before", c.get { built = true; "never" })
        now.addAndGet(5_000L)
        assertEquals("an aged one too: the store is busy", "before", c.get { built = true; "never" })
        assertFalse(built)
        release.countDown()
        writer.join(10_000L)
        // the snapshot was dropped before the write's follow-up step ran, and the store was read again before it returned
        assertEquals(listOf("announced with nothing held", "returned"), order.toList())
        assertEquals("after", c.fresh())
        assertEquals("after", c.get { built = true; "never" })
        assertFalse(built)
    }

    @Test fun aWriteOrRereadThatFailsLeavesNothingHeld() {
        val c = SnapshotCache8864<String>(2_000L) { 0L }
        c.get { "v1" }
        try {
            c.write(change = { error("store unavailable") }, reread = { "unused" })
            fail("the write's failure is the caller's to see")
        } catch (_: IllegalStateException) {}
        assertNull(c.fresh())
        assertEquals("v2", c.get { "v2" })
        c.write(change = {}, reread = { error("cannot read back") })       // the write itself succeeded: it returns
        assertNull(c.fresh())
        assertEquals("v3", c.get { "v3" })
        c.invalidate()
        assertNull(c.fresh())
        assertEquals("v4", c.get { "v4" })
    }

    @Test(timeout = 60_000) fun manyReadersAndWritersNeverSeeAValueGoBackwards() {
        val c = SnapshotCache8864<Long>(1L)                       // the real monotonic clock, a 1 ms ttl: constant rebuilds
        val store = AtomicLong(0L)
        val stop = AtomicLong(0L)
        val failures = AtomicInteger()
        val readers = (1..8).map {
            thread {
                var last = -1L
                while (stop.get() == 0L) {
                    val v = c.fresh() ?: c.get { store.get() }
                    if (v < last) failures.incrementAndGet()
                    last = v
                }
            }
        }
        val writer = thread { repeat(2_000) { n -> c.write(change = { store.set(n + 1L) }, reread = { store.get() }) } }
        writer.join(50_000L)
        stop.set(1L)
        readers.forEach { it.join(5_000L) }
        assertEquals("a reader saw an older value after a newer one", 0, failures.get())
        assertEquals(2_000L, c.get { store.get() })
    }

    @Test fun theConfigStoreTheDashboardAndTheDeviceRunUseIt() {
        val cfg = src("data/BotConfig.kt")
        assertFalse("load is queued on the object's monitor", cfg.contains("@Synchronized\n    fun load("))
        assertFalse("save is queued on the object's monitor", cfg.contains("@Synchronized\n    fun save("))
        assertTrue(cfg.contains("private val snapshot8864 = SnapshotCache8864<BotConfig>(CONFIG_CACHE_MS)"))
        assertTrue(cfg.contains("return snapshot8864.fresh() ?: snapshot8864.get { read8864(ctx) }"))
        val save = cfg.substringAfter("fun save(ctx: Context, cfg: BotConfig) {").substringBefore("private fun writeStores8864(")
        assertTrue(save.contains("change = { writeStores8864(ctx, cfg) }"))
        // the mode authority is told after the snapshot is dropped (inside reread), and before the store is read again
        val reread = save.substringAfter("reread = {")
        assertTrue(reread.indexOf("RuntimeModeAuthority.publishConfig(") in 0 until reread.indexOf("read8864(ctx)"))
        assertTrue(src("data/SnapshotCache8864.kt").contains("private val clock: () -> Long = { System.nanoTime() / 1_000_000L }"))

        val main = src("ui/MainActivity.kt")
        val collector = main.substringAfter("repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {").substringBefore("updateUi(state)")
        assertTrue(collector.indexOf("kotlinx.coroutines.yield()") in 0 until collector.indexOf("vm.ui.collect { state ->"))

        assertTrue(src("engine/PipelineHealthCollector.kt").contains("Config store (§8864): "))
        val device = File("../../ci/runtime_device_checks_8418.sh").readText()
        assertTrue(device.contains("holder of \$lock (the lock the main thread is waiting for)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 55. No reader waits for another reader's rebuild (8864)"))
    }
}

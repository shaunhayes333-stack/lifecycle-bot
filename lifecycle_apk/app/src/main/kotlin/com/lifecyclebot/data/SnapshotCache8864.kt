package com.lifecyclebot.data

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * V5.0.8864 §NO_READER_WAITS_FOR_ANOTHER_READERS_REBUILD — owner, on the builds that froze at launch: "f8x it bro."
 *
 * Runtime acceptance on 5.0.8863 (19 Nov 00:00:42): "ANR in com.lifecyclebot.aate (SplashActivity) ... Waited 5001ms for
 * FocusEvent(hasFocus=false)", the process at 261 % CPU, load average 32. The dashboard was the one already open (5.0.8863
 * stopped a second one being built: the stack is Activity.performStart, not a launch), and its main thread was
 *
 *     "main" prio=5 tid=1 Blocked
 *       at com.lifecyclebot.data.ConfigStore.load(BotConfig.kt)
 *       - waiting to lock <0x0f336245> (a com.lifecyclebot.data.ConfigStore)
 *       at com.lifecyclebot.ui.MainActivity.updateUi$updateCyclicPanel(MainActivity.kt:4752)
 *
 * ConfigStore.load was @Synchronized and so was its cache check. Every two seconds the cache expired, and whichever of the
 * app's ~250 threads asked first rebuilt the whole BotConfig inside that monitor (17 values decrypted from the encrypted
 * store, 137 preferences read) while every other caller — 166 call sites, the dashboard's main thread and the entry
 * threads among them — queued behind it, although a usable config two seconds old was already held. The trace as CI
 * prints it does not name the holder. What makes a milliseconds-long rebuild hold everyone for seconds is known: a Java
 * monitor does not lend its holder the waiter's priority, so a holder that is itself waiting for a CPU (the load average
 * was 32 on four cores; the app also runs workers at Java priority 4, which Android schedules in the background group)
 * keeps the main thread waiting with it. 5.0.8793 fixed the same shape on the journal's monitor.
 *
 * This cache has no such queue.
 *  - A snapshot younger than [ttlMs] is returned from one volatile read.
 *  - An older one is rebuilt by the first caller that finds the store free; a caller that finds it busy (another thread is
 *    rebuilding, or a write is in progress) is given the snapshot that is held, at once. A snapshot is therefore served
 *    past its age only while its replacement is being made.
 *  - With nothing held (the first read of the process, or after [invalidate]) a caller waits for the one build, as before.
 *  - A write ([write]) runs with the store held; readers that arrive while it writes are served the snapshot from before
 *    it. When it has written, the snapshot is dropped and the store read again before the write returns: no read that
 *    starts after a write returns, and none that starts after the write's own follow-up step began, sees the old value.
 *
 * Ages are measured on the monotonic clock, so a wall-clock change cannot make a snapshot look fresh.
 */
class SnapshotCache8864<T : Any>(
    private val ttlMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private class Held<T>(val value: T, val atMs: Long)

    @Volatile private var held: Held<T>? = null
    private val store = ReentrantLock()

    private val builds = AtomicLong()
    private val servedWhileBusy = AtomicLong()
    private val waitedForFirst = AtomicLong()
    private val writes = AtomicLong()
    @Volatile private var lastBuildMs = 0L
    @Volatile private var maxBuildMs = 0L
    @Volatile private var maxBuildBy = "-"

    /** The held snapshot when it is younger than the ttl, else null. One volatile read: the path nearly every read takes. */
    fun fresh(): T? {
        val h = held ?: return null
        return if (clock() - h.atMs < ttlMs) h.value else null
    }

    /** The snapshot: the held one while fresh or while its replacement is being made, else built by this caller. */
    fun get(build: () -> T): T {
        val h = held
        if (h != null) {
            if (clock() - h.atMs < ttlMs) return h.value
            if (!store.tryLock()) {
                servedWhileBusy.incrementAndGet()
                return h.value
            }
            try { return buildHoldingStore(build) } finally { store.unlock() }
        }
        waitedForFirst.incrementAndGet()
        store.lock()
        try { return buildHoldingStore(build) } finally { store.unlock() }
    }

    private fun buildHoldingStore(build: () -> T): T {
        val h = held
        if (h != null && clock() - h.atMs < ttlMs) return h.value   // built by the thread this one waited for
        val t0 = System.nanoTime()
        val value = build()
        held = Held(value, clock())
        noteBuild(t0)
        return value
    }

    /**
     * A write to the store the snapshot is read from. [change] runs with the store held (readers are served the snapshot
     * from before it). The snapshot is then dropped and [reread] reads the store again; whatever [reread] does first runs
     * after the drop, so nothing it announces can be followed by a read of the old value. A [change] that throws leaves
     * nothing held and rethrows; a [reread] that throws leaves nothing held (the next read builds, and reports its own
     * failure to its own caller).
     */
    fun write(change: () -> Unit, reread: () -> T) {
        store.lock()
        try {
            writes.incrementAndGet()
            try { change() } finally { held = null }
            val t0 = System.nanoTime()
            val value = try { reread() } catch (_: Throwable) { return }
            held = Held(value, clock())
            noteBuild(t0)
        } finally { store.unlock() }
    }

    /** Drop the snapshot: the next read builds (and readers wait for that one build). */
    fun invalidate() { held = null }

    private fun noteBuild(startedNs: Long) {
        val ms = (System.nanoTime() - startedNs) / 1_000_000L
        builds.incrementAndGet()
        lastBuildMs = ms
        if (ms >= maxBuildMs) {
            maxBuildMs = ms
            val t = Thread.currentThread()
            maxBuildBy = t.name + "/prio" + t.priority
        }
    }

    fun statusLine(name: String): String =
        "$name[builds=${builds.get()} lastBuildMs=$lastBuildMs maxBuildMs=$maxBuildMs by=$maxBuildBy servedWhileBusy=${servedWhileBusy.get()} " +
            "waitedWithNothingHeld=${waitedForFirst.get()} writes=${writes.get()} ttlMs=$ttlMs " +
            "rule=a reader is never queued behind another thread's rebuild or write while a snapshot is held]"
}

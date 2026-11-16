package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8815 §WHERE_THE_CPU_GOES — report 5815972e (5.0.8813, 47 min): 1.1-1.4 GB allocated every minute in steady state
 * (not only at start), GC blocking for most of the run, and the CPU table names the thread POOL that burns it
 * (DefaultDispatcher 32 s of CPU per minute across 53 workers, AATE-Entry 9 s, launch-replay 7 s) but not the code. 8813's
 * job meter covers named batch jobs; the pool runs hundreds of different coroutines. This samples the running code instead:
 * once a second every RUNNABLE thread's stack is read and tallied by
 *  - its top app frame (the code on the CPU; a library leaf such as org.json is named beside it), and
 *  - its deepest app frame (the job that called it),
 * per thread family. A thread whose top frame is a socket / file / poll read is counted as io, not CPU. The report lists the
 * largest shares, so the next fix is aimed at measured code. Cost: one stack read per running thread per second.
 */
object CpuSample8815 {
    private const val PERIOD_MS = 1_000L
    private const val THREADS_REFRESH_MS = 30_000L
    private const val MAX_KEYS = 1_500
    private const val APP = "com.lifecyclebot."
    internal const val IO = "(io)"
    private const val SELF = "cpu-sample-8815"

    private val top = ConcurrentHashMap<String, AtomicLong>()
    private val entry = ConcurrentHashMap<String, AtomicLong>()
    private val samples = AtomicLong(0L)
    private val running = AtomicLong(0L)
    private val io = AtomicLong(0L)
    private val overflow = AtomicLong(0L)
    @Volatile private var started = false
    @Volatile private var startedAtMs = 0L

    // ── pure pieces ──

    /** Pure: the frame's class without lambda / inner-class suffixes and package, plus the method ("PocketLedger8610.record"). */
    internal fun short8815(e: StackTraceElement): String {
        val cls = e.className.substringAfterLast('.').substringBefore('$')
        val m = e.methodName.substringBefore('$')
        return "$cls.$m"
    }

    /** Pure: does the top frame wait on a socket, file, poll or sleep (RUNNABLE to the VM, not CPU work)? */
    internal fun waiting8815(e: StackTraceElement): Boolean {
        val c = e.className; val m = e.methodName
        return c.startsWith("libcore.io.") || c.startsWith("java.net.") || c.startsWith("sun.nio.") ||
            (c.startsWith("com.android.org.conscrypt.") && (m.contains("read", true) || m.contains("poll", true))) ||
            (c == "android.os.MessageQueue" && m == "nativePollOnce") || (c == "java.lang.Thread" && m == "sleep") ||
            c.startsWith("java.io.FileInputStream") || c.startsWith("java.io.FileOutputStream")
    }

    /**
     * Pure: the (top, entry) keys of one RUNNABLE stack ([frames] innermost first), or [IO] for a waiting thread, or null
     * when no app frame is on the stack (framework / library-only threads).
     */
    internal fun keys8815(frames: Array<StackTraceElement>): Pair<String, String>? {
        if (frames.isEmpty()) return null
        if (waiting8815(frames[0])) return IO to IO
        var firstApp = -1; var lastApp = -1
        for (i in frames.indices) if (frames[i].className.startsWith(APP)) { if (firstApp < 0) firstApp = i; lastApp = i }
        if (firstApp < 0) return null
        val app = short8815(frames[firstApp])
        val topKey = if (firstApp == 0) app else "$app <${short8815(frames[0])}>"
        return topKey to short8815(frames[lastApp])
    }

    /** Pure: the thread family of [name] (digits folded), as the heap log names it. */
    internal fun family8815(name: String): String = HeapLog8618.family8622(name)

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        val c = map[key] ?: if (map.size >= MAX_KEYS) { overflow.incrementAndGet(); return } else map.computeIfAbsent(key) { AtomicLong(0L) }
        c.incrementAndGet()
    }

    /** Tallies one RUNNABLE stack of a thread named [threadName]. */
    internal fun note8815(threadName: String, frames: Array<StackTraceElement>) {
        val k = keys8815(frames) ?: return
        if (k.first == IO) { io.incrementAndGet(); return }
        running.incrementAndGet()
        val fam = family8815(threadName)
        bump(top, "${k.first} [$fam]")
        bump(entry, "${k.second} [$fam]")
    }

    // ── the sampler thread ──

    private fun allThreads(): Array<Thread> {
        var g = Thread.currentThread().threadGroup ?: return emptyArray()
        while (true) g = g.parent ?: break
        var arr = arrayOfNulls<Thread>(g.activeCount() + 32)
        var n = g.enumerate(arr, true)
        while (n >= arr.size) { arr = arrayOfNulls(arr.size * 2); n = g.enumerate(arr, true) }
        return Array(n) { arr[it]!! }
    }

    /** Called from the heap log's periodic sample: starts the sampler once, on the device only. */
    fun ensureStarted8815() {
        if (started || com.lifecyclebot.AATEApp.appContextOrNull() == null) return
        synchronized(this) {
            if (started) return
            started = true
            startedAtMs = System.currentTimeMillis()
        }
        try {
            Thread({
                var threads: Array<Thread> = emptyArray(); var refreshedAt = 0L
                while (true) {
                    try {
                        val now = System.currentTimeMillis()
                        if (now - refreshedAt >= THREADS_REFRESH_MS) { threads = allThreads(); refreshedAt = now }
                        samples.incrementAndGet()
                        for (t in threads) {
                            if (t.name == SELF || t.state != Thread.State.RUNNABLE) continue
                            val st = try { t.stackTrace } catch (_: Throwable) { continue }
                            note8815(t.name, st)
                        }
                        Thread.sleep(PERIOD_MS)
                    } catch (_: InterruptedException) { return@Thread } catch (_: Throwable) {
                        try { Thread.sleep(PERIOD_MS) } catch (_: InterruptedException) { return@Thread }
                    }
                }
            }, SELF).apply { isDaemon = true; priority = Thread.NORM_PRIORITY }.start()
        } catch (_: Throwable) {}
    }

    private fun rank(map: ConcurrentHashMap<String, AtomicLong>, total: Long, n: Int): String =
        map.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }.take(n)
            .joinToString(" | ") { (k, v) -> "$k ${if (total > 0) "%.1f".format(100.0 * v / total) else "0"}%" }.ifBlank { "-" }

    fun statusLine8815(nowMs: Long = System.currentTimeMillis()): String {
        val r = running.get()
        return "cpuSample8815[started=$started secs=${if (startedAtMs > 0) (nowMs - startedAtMs) / 1000 else 0} samples=${samples.get()} " +
            "cpuStacks=$r ioStacks=${io.get()} overflow=${overflow.get()}\n" +
            "      ON CPU (top app frame <library leaf> [thread family], share of CPU stacks): ${rank(top, r, 20)}\n" +
            "      CALLED FROM (deepest app frame [family]): ${rank(entry, r, 12)}]"
    }

    internal fun resetForTest8815() { top.clear(); entry.clear(); samples.set(0L); running.set(0L); io.set(0L); overflow.set(0L) }
}

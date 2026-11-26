package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.9031 §WHICH_CODE_IS_RUNNING — the 26 Nov 21:03 report (5.0.9030, 13 min up): 74 GB allocated, the collector blocking
 * for 235 s of 805 s, the bot cycle 5.8 s, a ticket 12-16 s from its seal to its send. Build 9029 lowered the heap's floor
 * from 311 MB to 254 MB and the blocking share did not move (31 % -> 29 %): the stall is the allocation rate, and nothing in
 * the report names the code that allocates. The heap map names what is HELD; the job meter (§8813) sees only its batch jobs,
 * and as an upper bound; the CPU sample (§8892) needs the owner's tap and a file sent back.
 *
 * This is a sampler that needs neither: every [PERIOD_MS_9031] ms it reads the stack of each thread that is running (not
 * waiting, sleeping or blocked on a socket) and counts three names per stack — the app method at the top of the stack (the
 * code executing or calling into a library), the outermost app method (the job that owns the work), and the library call at
 * the very top (StringBuilder, JSON, formatting: the allocators). The report prints the largest of each as a share of the
 * running stacks. The code that runs most is the code that allocates most.
 *
 * What it holds: class and method names and counts — no argument, field or variable value, no key, nothing of the wallet.
 * Its own cost is bounded: at most [MAX_STACKS_9031] stacks a sample, the next sample waits [COST_MULT_9031] times the last
 * one's cost (so it takes under 5 % of one core), names are capped at [MAX_KEYS_9031] a table. It changes nothing it reads.
 */
object HotFrames9031 {
    internal const val PERIOD_MS_9031 = 500L
    internal const val MAX_STACKS_9031 = 24
    internal const val MAX_KEYS_9031 = 1500
    internal const val COST_MULT_9031 = 20L
    internal const val GAP_CAP_MS_9034 = 120_000L
    private const val APP_PREFIX_9031 = "com.lifecyclebot"
    internal const val NO_APP_FRAME_9031 = "(no app frame)"
    internal const val APP_CODE_9031 = "(app code itself)"

    private val started = AtomicBoolean(false)
    private val lock = Any()
    private val self = HashMap<String, IntArray>()
    private val root = HashMap<String, IntArray>()
    private val leaf = HashMap<String, IntArray>()
    private val family = HashMap<String, IntArray>()
    @Volatile private var samples = 0L
    @Volatile private var busyStacks = 0L
    @Volatile private var startedAtMs = 0L
    @Volatile private var costNs = 0L
    @Volatile private var failed = 0L

    /** One sampled stack reduced to its three names. */
    data class Fold9031(val self: String, val root: String, val leaf: String)

    /** Pure: is this top frame a wait, not work? A thread parked in native code reports RUNNABLE on ART. */
    fun idleLeaf9031(cls: String, method: String): Boolean = when {
        method == "wait" || method == "sleep" || method == "park" || method == "parkNanos" || method == "parkUntil" -> true
        method == "nativePollOnce" || method == "transactNative" -> true
        cls == "libcore.io.Linux" -> method == "poll" || method == "ppoll" || method == "epoll_wait" || method == "recvfrom" ||
            method == "accept" || method == "connect" || method == "select" || method == "readBytes" || method == "writeBytes"
        cls.endsWith("NativeCrypto") -> method == "SSL_read" || method == "SSL_do_handshake" || method == "ENGINE_SSL_read_direct" ||
            method == "ENGINE_SSL_do_handshake"
        cls.endsWith("SocketInputStream") || cls.endsWith("PlainSocketImpl") || cls.endsWith("LocalSocketImpl") -> true
        else -> false
    }

    /** Pure: "com.lifecyclebot.engine.cortex.Cortex7885" + "assess" -> "Cortex7885.assess". */
    fun short9031(cls: String, method: String): String = cls.substringAfterLast('.') + "." + method

    /** Pure: a running stack's three names, or null for an empty stack or one whose top frame is a wait. */
    fun fold9031(stack: Array<StackTraceElement>): Fold9031? {
        if (stack.isEmpty()) return null
        val top = stack[0]
        if (idleLeaf9031(top.className, top.methodName)) return null
        var first: StackTraceElement? = null
        var last: StackTraceElement? = null
        for (e in stack) if (e.className.startsWith(APP_PREFIX_9031) && !e.className.endsWith("HotFrames9031")) { if (first == null) first = e; last = e }
        val f = first
        val l = last
        return Fold9031(
            self = if (f != null) short9031(f.className, f.methodName) else NO_APP_FRAME_9031,
            root = if (l != null) short9031(l.className, l.methodName) else NO_APP_FRAME_9031,
            leaf = if (top.className.startsWith(APP_PREFIX_9031)) APP_CODE_9031 else short9031(top.className, top.methodName))
    }

    /** Pure: a thread's family — its name without its numbers ("DefaultDispatcher-worker-12" -> "DefaultDispatcher-worker-#"). */
    fun family9031(name: String): String = name.replace(Regex("[0-9]+"), "#").take(40)

    private fun bump(m: HashMap<String, IntArray>, k: String) {
        val c = m[k]
        if (c != null) { c[0]++; return }
        if (m.size < MAX_KEYS_9031) m[k] = intArrayOf(1)
    }

    /** One running stack counted (the sampler's own step; a test feeds it directly). */
    internal fun note9031(stack: Array<StackTraceElement>, threadName: String): Boolean {
        val f = fold9031(stack) ?: return false
        synchronized(lock) { bump(self, f.self); bump(root, f.root); bump(leaf, f.leaf); bump(family, family9031(threadName)) }
        busyStacks++
        return true
    }

    private fun sampleOnce(me: Thread) {
        var g = me.threadGroup ?: return
        while (true) { g = g.parent ?: break }
        val arr = arrayOfNulls<Thread>(g.activeCount() + 32)
        val n = g.enumerate(arr, true)
        var taken = 0
        for (i in 0 until n) {
            val t = arr[i] ?: continue
            if (t === me || t.state != Thread.State.RUNNABLE) continue
            val st = try { t.stackTrace } catch (_: Throwable) { continue }
            if (note9031(st, t.name) && ++taken >= MAX_STACKS_9031) break
        }
        samples++
    }

    /** AATEApp (the main process only): starts the sampler once; it runs for the life of the process. */
    fun start9031() {
        if (!started.compareAndSet(false, true)) return
        startedAtMs = System.currentTimeMillis()
        try {
            Thread({
                val me = Thread.currentThread()
                var gapMs = PERIOD_MS_9031
                var run = true
                while (run) {
                    try { Thread.sleep(gapMs) } catch (_: InterruptedException) { run = false }
                    if (!run) break
                    val t0 = System.nanoTime()
                    try { sampleOnce(me) } catch (_: Throwable) { failed++ }
                    val cost = System.nanoTime() - t0
                    costNs += cost
                    gapMs = gapAfter9031(cost / 1_000_000L)
                }
            }, "hot-frames-9031").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (_: Throwable) { started.set(false) }
    }

    /**
     * Pure: the wait before the next sample — the period, or [COST_MULT_9031] x the last sample's cost when that is longer,
     * up to [GAP_CAP_MS_9034]. V5.0.9034 — the cap was 10 s; on 26 Nov 23:38 (the collector blocking for 80 % of the run) a
     * sample cost 2.4 s (costMs=2415), so the sampler ran for 2.4 s in every 12.4: 19 % of the time, not the 5 % it
     * promises, and each sample stops the threads it reads. At that cost it now waits 48 s.
     */
    fun gapAfter9031(lastCostMs: Long): Long = maxOf(PERIOD_MS_9031, minOf(GAP_CAP_MS_9034, lastCostMs.coerceAtLeast(0L) * COST_MULT_9031))

    private fun top(m: HashMap<String, IntArray>, n: Int, total: Long): String {
        val rows = synchronized(lock) { m.entries.map { it.key to it.value[0] } }
        if (rows.isEmpty() || total <= 0L) return "-"
        return rows.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).take(n)
            .joinToString(" · ") { "${it.first} ${"%.1f".format(it.second * 100.0 / total)}%" }
    }

    /** The report block: what ran, as a share of the running stacks sampled since the process started. */
    fun block9031(n: Int = 22): String {
        val total = busyStacks
        val s = samples
        val upS = if (startedAtMs > 0L) (System.currentTimeMillis() - startedAtMs) / 1000L else 0L
        val head = "  HOT CODE   hotFrames9031[samples=$s runningStacks=$total over=${upS}s meanRunning=${if (s > 0L) "%.1f".format(total.toDouble() / s) else "-"} " +
            "costMs=${if (s > 0L) "%.1f".format(costNs / 1_000_000.0 / s) else "-"} failed=$failed rule=every ${PERIOD_MS_9031} ms the stack of each running thread " +
            "(not waiting or on a socket), up to $MAX_STACKS_9031; shares are of the running stacks; class and method names only]\n"
        if (total <= 0L) return head
        return head +
            "    app method running (top of the stack inside the app): ${top(self, n, total)}\n" +
            "    job that owns it (outermost app method): ${top(root, n, total)}\n" +
            "    library call on top (the allocators and the I/O): ${top(leaf, n, total)}\n" +
            "    by thread: ${top(family, 12, total)}\n"
    }

    internal fun resetForTest9031() {
        synchronized(lock) { self.clear(); root.clear(); leaf.clear(); family.clear() }
        samples = 0L; busyStacks = 0L; costNs = 0L; failed = 0L
    }
}

package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.8813 §WHAT_EACH_JOB_COSTS — 5.0.8809 on the device: an OutOfMemoryError after 2.5 hours, then 1.4 GB/min allocated
 * against a 512 MB heap, ~25 s of GC every two minutes, 330 MB the store census cannot name, a 4.9 s bot cycle and only 22 %
 * of forward labels priced. The heap log already shows WHICH THREADS burn CPU (tree-ranker 18 s/min, cortex-capture 18,
 * cortex-persist 14, AATE-Entry 22); it cannot show what one run of a heavy job costs, so each fix so far was aimed by
 * inference. This measures the bot's heavy batch jobs where they run:
 *  - wall time and the job thread's own CPU (Debug.threadCpuTimeNanos);
 *  - bytes the PROCESS allocated while the job ran (ART's art.gc.bytes-allocated before and after). Other threads allocate
 *    meanwhile, so this is an upper bound for one run; a job whose figure stays near the process rate x its wall time is
 *    not the allocator, one far above it is.
 * The report line lists jobs by total allocation, with runs, the last run and the largest run.
 */
object JobCost8813 {
    private const val MB = 1024.0 * 1024.0

    private class Acc {
        var runs = 0L; var wallMs = 0L; var cpuMs = 0L; var allocMb = 0.0
        var lastWallMs = 0L; var lastCpuMs = 0L; var lastAllocMb = 0.0; var maxAllocMb = 0.0; var maxWallMs = 0L
        var failed = 0L
    }

    private val jobs = ConcurrentHashMap<String, Acc>()

    private fun allocBytes(): Long = try { android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: -1L } catch (_: Throwable) { -1L }
    private fun threadCpuNs(): Long = try { android.os.Debug.threadCpuTimeNanos() } catch (_: Throwable) { -1L }

    /** Pure: the delta of two counter readings, or -1 when either is unknown (a stubbed or missing runtime stat). */
    fun delta8813(before: Long, after: Long): Long = if (before >= 0L && after >= before) after - before else -1L

    /** Records one finished run of [name]. Pure apart from the shared table; [allocBytes] / [cpuNs] are -1 when unknown. */
    fun record8813(name: String, wallMs: Long, cpuNs: Long, allocBytes: Long, ok: Boolean = true) {
        val a = jobs.computeIfAbsent(name) { Acc() }
        synchronized(a) {
            a.runs++; a.wallMs += wallMs; a.lastWallMs = wallMs
            if (wallMs > a.maxWallMs) a.maxWallMs = wallMs
            if (cpuNs >= 0L) { val ms = cpuNs / 1_000_000; a.cpuMs += ms; a.lastCpuMs = ms }
            if (allocBytes >= 0L) {
                val mb = allocBytes / MB
                a.allocMb += mb; a.lastAllocMb = mb
                if (mb > a.maxAllocMb) a.maxAllocMb = mb
            }
            if (!ok) a.failed++
        }
    }

    /** Runs [block] as one measured run of [name]; a throw is recorded as a failed run and rethrown. */
    inline fun <T> measure8813(name: String, block: () -> T): T {
        val t0 = System.nanoTime(); val c0 = cpuNow8813(); val a0 = allocNow8813()
        var ok = false
        try {
            val r = block()
            ok = true
            return r
        } finally {
            record8813(name, (System.nanoTime() - t0) / 1_000_000, delta8813(c0, cpuNow8813()), delta8813(a0, allocNow8813()), ok)
        }
    }

    // ── V5.0.8817 §BATCH_JOBS_YIELD_TO_THE_SCREEN ──
    // CI runtime acceptance on 5.0.8813 and 5.0.8816: SplashActivity ANR, the main thread RUNNABLE in an ordinary draw with
    // 47 s of its schedstat spent waiting for a CPU (utm 10 s) while the startup dataset replays (pocket ledger, paying cell,
    // managed bands, tree parse / train, edge replay — 1-5 GB and 8-24 s of CPU each, measured by this meter) ran at the
    // default priority beside it. A batch replay is not time-critical; the screen and the trading loop are. Each runs at
    // Android's background priority for its duration and the thread's own priority is restored after.

    /** [measure8813] at Android's background thread priority for the job's duration (the previous priority restored). */
    inline fun <T> measureBackground8817(name: String, block: () -> T): T {
        val turn9033 = heavyEnter9033(name, waitsForHeadroom = true)   // V5.0.9033 — one batch job at a time, and not into a full heap
        try {
            val before = lowerPriority8817()
            try { return measure8813(name, block) } finally { restorePriority8817(before) }
        } finally { heavyExit9033(turn9033) }
    }

    // ── V5.0.9033 §THE_BATCH_JOBS_TAKE_TURNS — the 26 Nov 23:38 report (5.0.9031, 76 min up): heap 509/512 MB, the collector
    //    blocking for 3,643 s of 4,578 s, the bot cycle 16.7 s, 21 of 40 launch fires never judged and no buy landed. This
    //    meter's own rows: datasetSeed 532 s of wall for 27 s of its own CPU, treeParse 856 s / 24 s, treeTrain 750 s / 31 s,
    //    strategyLab 970 s / 28 s (failed, twice), authorityTable8887 265 s / 10 s. Their allocation rows sum to 71 of the
    //    109 GB the process allocated, but that column is an upper bound (everything the process allocated while the job
    //    ran) and these jobs ran over one another, so the rows count each other. What is measured: five jobs that need
    //    10 to 31 s of CPU each held their parsed rows (the dataset file is 108 MB, the research file 77 MB) for 4 to 16
    //    minutes, overlapping, in a 512 MB heap the trading loop shares. On the run before they took 7 to 29 s each.
    //    Nothing ordered them: each has its own thread and started when its own clock said so.
    //    Now the five jobs measured there ([GATED_9033]) take turns: one runs, the next waits ([heavyEnter9033]). A job
    //    nothing waits for (measureBackground8817) first waits, before it asks for its turn, while memory pressure is HARD
    //    or CRITICAL, up to [HEADROOM_WAIT_MAX_MS_9033]; a job the decision waits for (measureYielding8866: the tree's
    //    refit) takes its turn but does not wait for headroom, and waits for the turn at most [YIELDING_TURN_WAIT_MS_9033].
    //    No wait is unbounded and no job is dropped: past its limit a job runs as it did before. Not gated: the Cortex
    //    snapshot and close commit (durability), and the edge model's replay and conviction seed (they run inside the
    //    model's load lock, which every reader of the model waits on; neither ran in that report). A job started inside
    //    another on the same thread is part of that job's turn. ──
    internal val GATED_9033 = setOf("datasetSeed", "treeParse", "treeTrain", "strategyLab", "authorityTable8887")
    internal const val TURN_WAIT_MAX_MS_9033 = 20L * 60_000L
    internal const val YIELDING_TURN_WAIT_MS_9033 = 3L * 60_000L
    internal const val HEADROOM_WAIT_MAX_MS_9033 = 10L * 60_000L
    internal const val HEADROOM_STEP_MS_9033 = 3_000L
    private val heavyTurn9033 = java.util.concurrent.Semaphore(1, true)
    private val heavyDepth9033 = ThreadLocal<Int>()
    private val turnWaits9033 = java.util.concurrent.atomic.AtomicLong(0L)
    private val turnWaitedMs9033 = java.util.concurrent.atomic.AtomicLong(0L)
    private val headroomWaitedMs9033 = java.util.concurrent.atomic.AtomicLong(0L)
    private val withoutTurn9033 = java.util.concurrent.atomic.AtomicLong(0L)

    /** Pure: does a job that waits for headroom keep waiting at this [pressure] after [waitedMs]? */
    fun headroomWaits9033(pressure: String, waitedMs: Long, waitsForHeadroom: Boolean): Boolean =
        waitsForHeadroom && waitedMs in 0L until HEADROOM_WAIT_MAX_MS_9033 && (pressure.equals("HARD", true) || pressure.equals("CRITICAL", true))

    /** Pure: how long [name] waits for its turn; 0 for a job that is not a gated batch job. */
    fun turnWaitMs9033(name: String, waitsForHeadroom: Boolean): Long =
        if (name !in GATED_9033) 0L else if (waitsForHeadroom) TURN_WAIT_MAX_MS_9033 else YIELDING_TURN_WAIT_MS_9033

    /** Test seam: the memory pressure this meter reads (null: the memory guard's own). */
    @Volatile internal var pressureForTest9033: String? = null
    private fun pressureName9033(): String = pressureForTest9033 ?: try { MemoryGuard7977.pressure8144().name } catch (_: Throwable) { "NORMAL" }

    /** Takes the batch turn for [name] (waiting as the rule above says). True when this call holds the turn and must release it. */
    @PublishedApi internal fun heavyEnter9033(name: String, waitsForHeadroom: Boolean): Boolean {
        val maxWait = turnWaitMs9033(name, waitsForHeadroom)
        if (maxWait <= 0L) return false
        val depth = heavyDepth9033.get() ?: 0
        if (depth > 0) { heavyDepth9033.set(depth + 1); return false }   // inside a job on this thread that already holds the turn
        // headroom first, and without the turn: a job asleep here holds nothing another job waits for
        var waited = 0L
        while (headroomWaits9033(pressureName9033(), waited, waitsForHeadroom)) {
            try { Thread.sleep(HEADROOM_STEP_MS_9033) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
            waited += HEADROOM_STEP_MS_9033
        }
        if (waited > 0L) headroomWaitedMs9033.addAndGet(waited)
        val t0 = System.currentTimeMillis()
        val got = try {
            if (heavyTurn9033.tryAcquire()) true
            else { turnWaits9033.incrementAndGet(); heavyTurn9033.tryAcquire(maxWait, java.util.concurrent.TimeUnit.MILLISECONDS) }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
        turnWaitedMs9033.addAndGet(System.currentTimeMillis() - t0)
        if (got) heavyDepth9033.set(1) else withoutTurn9033.incrementAndGet()
        return got
    }

    /** Ends what [heavyEnter9033] began: releases the turn it took, or steps out of a job nested in one. */
    @PublishedApi internal fun heavyExit9033(heldTurn: Boolean) {
        if (heldTurn) { heavyDepth9033.remove(); heavyTurn9033.release(); return }
        val depth = heavyDepth9033.get() ?: 0
        if (depth > 1) heavyDepth9033.set(depth - 1)
    }

    fun turnsLine9033(): String = "batchTurns9033[waitedForTurn=${turnWaits9033.get()} turnWaitS=${turnWaitedMs9033.get() / 1000} headroomWaitS=${headroomWaitedMs9033.get() / 1000} " +
        "ranWithoutTurn=${withoutTurn9033.get()} rule=the dataset-seed, tree, strategy-lab and authority-table jobs run one at a time; a job nothing waits for " +
        "first waits up to ${HEADROOM_WAIT_MAX_MS_9033 / 60_000} min while memory pressure is HARD or CRITICAL; no wait is unbounded]"

    // ── V5.0.8866 §A_JOB_THE_DECISION_WAITS_FOR_YIELDS_WITHOUT_STARVING ──
    // Android puts a thread at nice >= 10 (THREAD_PRIORITY_BACKGROUND; Java priority below NORM) into the background CPU
    // group. 5.0.8864, the tree ranker's refit, run there: treeParse 12.5 s of CPU in 2,522 s of wall, treeTrain 11.7 s in
    // 1,533 s, and the forecast it feeds stayed flat (-10 %, nothing at zero or better) for the half hour after each start,
    // so live admitted nothing. A job the trading decision waits for runs at nice 5: below every default thread, and in
    // the process's own CPU group. Batch replays nothing waits for stay on [measureBackground8817].
    const val YIELDING_NICE_8866 = 5

    /** Pure: does Android schedule a thread at [nice] in the background CPU group? */
    fun backgroundGroup8866(nice: Int): Boolean = nice >= android.os.Process.THREAD_PRIORITY_BACKGROUND

    /** [measure8813] at nice [YIELDING_NICE_8866] for the job's duration (the previous priority restored). */
    inline fun <T> measureYielding8866(name: String, block: () -> T): T {
        val turn9033 = heavyEnter9033(name, waitsForHeadroom = false)   // V5.0.9033 — takes its turn; the decision waits for it, so no headroom wait
        try {
            val before = yieldingPriority8866()
            try { return measure8813(name, block) } finally { restorePriority8817(before) }
        } finally { heavyExit9033(turn9033) }
    }

    /** Sets the calling thread to [YIELDING_NICE_8866]; returns its previous priority (null when unknown). */
    @PublishedApi internal fun yieldingPriority8866(): Int? = try {
        val tid = android.os.Process.myTid()
        val was = android.os.Process.getThreadPriority(tid)
        if (was != YIELDING_NICE_8866) android.os.Process.setThreadPriority(tid, YIELDING_NICE_8866)
        yieldingRuns8866.incrementAndGet()
        was
    } catch (_: Throwable) { null }

    @PublishedApi internal val yieldingRuns8866 = java.util.concurrent.atomic.AtomicLong(0L)

    /** Lowers the calling thread to THREAD_PRIORITY_BACKGROUND; returns its previous priority (null when unknown). */
    @PublishedApi internal fun lowerPriority8817(): Int? = try {
        val tid = android.os.Process.myTid()
        val was = android.os.Process.getThreadPriority(tid)
        if (was < android.os.Process.THREAD_PRIORITY_BACKGROUND) android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)
        backgroundRuns8817.incrementAndGet()
        was
    } catch (_: Throwable) { null }

    @PublishedApi internal fun restorePriority8817(was: Int?) {
        if (was == null) return
        try { android.os.Process.setThreadPriority(android.os.Process.myTid(), was) } catch (_: Throwable) {}
    }

    @PublishedApi internal val backgroundRuns8817 = java.util.concurrent.atomic.AtomicLong(0L)

    /** The calling thread's CPU time (ns), -1 when unknown. Public for [measure8813]'s inlined body. */
    @PublishedApi internal fun cpuNow8813(): Long = threadCpuNs()

    /** The process's bytes allocated so far, -1 when unknown. Public for [measure8813]'s inlined body. */
    @PublishedApi internal fun allocNow8813(): Long = allocBytes()

    fun statusLine8813(): String {
        val rows = jobs.entries.map { (k, a) -> synchronized(a) {
            Triple(k, a.allocMb, "$k:runs${a.runs}${if (a.failed > 0) " failed${a.failed}" else ""} allocMB=${"%.0f".format(a.allocMb)} " +
                "last=${"%.0f".format(a.lastAllocMb)}MB/${a.lastWallMs}ms/cpu${a.lastCpuMs}ms max=${"%.0f".format(a.maxAllocMb)}MB/${a.maxWallMs}ms " +
                "cpuTotal=${a.cpuMs / 1000}s")
        } }.sortedByDescending { it.second }
        return "jobCost8813[backgroundRuns8817=${backgroundRuns8817.get()} yieldingRuns8866=${yieldingRuns8866.get()} " + (if (rows.isEmpty()) "no runs yet" else rows.joinToString(" | ") { it.third }) +
            " rule=per heavy job: process MB allocated while it ran (upper bound: other threads allocate too), wall ms, its own thread CPU] " + turnsLine9033()
    }

    internal fun resetForTest8813() { jobs.clear() }
}

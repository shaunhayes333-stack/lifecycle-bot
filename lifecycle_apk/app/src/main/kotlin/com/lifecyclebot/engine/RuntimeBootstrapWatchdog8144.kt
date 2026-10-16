package com.lifecyclebot.engine

import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.8144 — the Android side of [RuntimeBootstrapWatch8144].
 *
 * Fed by CanonicalBootstrapTimeline7557.mark (every bootstrap phase) and by
 * BotService.bootFailure7855 (every service-bootstrap failure). Prints to logcat under
 * the AATE_BOOTSTRAP tag, which the runtime smoke already preserves:
 *   SERVICE_BOOTSTRAP_STEP_8144        each phase transition with the step's duration;
 *   SERVICE_BOOTSTRAP_STALL_8144       the live stack of the bootstrap thread once a phase
 *                                      has run 20s (then 40s, 80s …) — the stuck call itself;
 *   SERVICE_BOOTSTRAP_FAILED_STEP_8144 the phase a failure happened in, the class ART
 *                                      rejected (VerifyError), and the throwing frames;
 *   SERVICE_BOOTSTRAP_SLOWEST_8144     the five slowest steps once the bootstrap is terminal.
 * One daemon thread at minimum priority, sleeping 5s between checks. Observation only.
 */
internal object RuntimeBootstrapWatchdog8144 {

    private const val TAG = "AATE_BOOTSTRAP"
    private const val POLL_MS = 5_000L

    private val tracker = RuntimeBootstrapWatch8144.Tracker8144()
    private val started = AtomicBoolean(false)
    @Volatile private var lastThread: WeakReference<Thread>? = null

    fun observe(phase: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        val step = tracker.onPhase(phase, now)
        val current = Thread.currentThread()
        lastThread = WeakReference(current)
        android.util.Log.i(TAG, RuntimeBootstrapWatch8144.stepLine8144(step, current.name))
        if (tracker.terminal) android.util.Log.i(TAG, RuntimeBootstrapWatch8144.summaryLine8144(tracker))
        ensureStarted()
    }

    fun failure(t: Throwable) {
        val now = android.os.SystemClock.elapsedRealtime()
        val since = if (tracker.workingPhaseAtMs > 0L) now - tracker.workingPhaseAtMs else -1L
        val line = RuntimeBootstrapWatch8144.failureLine8144(
            tracker.workingPhase, since, t.javaClass.simpleName, t.message, t.stackTrace.map { it.toString() },
        )
        android.util.Log.e(TAG, line)
        try { ForensicLogger.lifecycle("SERVICE_BOOTSTRAP_FAILED_STEP_8144", line.substringAfter("SERVICE_BOOTSTRAP_FAILED_STEP_8144 ")) } catch (_: Throwable) {}
    }

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread({ loop() }, "AATE-BootWatch-8144").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    private fun loop() {
        while (true) {
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
            try {
                val stalled = tracker.stallDue(android.os.SystemClock.elapsedRealtime())
                if (stalled != null) reportStall(stalled)
            } catch (_: Throwable) {}
        }
    }

    private fun reportStall(stalledMs: Long) {
        val thread = bootstrapThread()
        val frames = thread?.stackTrace?.map { it.toString() } ?: emptyList()
        val line = RuntimeBootstrapWatch8144.stallLine8144(
            tracker.phase, stalledMs, thread?.name ?: "gone", thread?.state?.name ?: "", frames,
        )
        android.util.Log.w(TAG, line)
        try { ForensicLogger.lifecycle("SERVICE_BOOTSTRAP_STALL_8144", line.substringAfter("SERVICE_BOOTSTRAP_STALL_8144 ")) } catch (_: Throwable) {}
        try { PipelineHealthCollector.labelInc("SERVICE_BOOTSTRAP_STALL_8144") } catch (_: Throwable) {}
    }

    /**
     * The thread running the bootstrap coroutine right now: the one whose stack is inside
     * BotService.onCreate's bootstrap lambda (a coroutine may resume on another pool thread
     * after a suspension), else the thread that reported the last phase.
     */
    private fun bootstrapThread(): Thread? {
        try {
            for ((t, stack) in Thread.getAllStackTraces()) {
                if (stack.any { it.className.startsWith("com.lifecyclebot.engine.BotService\$onCreate") }) return t
            }
        } catch (_: Throwable) {}
        return lastThread?.get()
    }
}

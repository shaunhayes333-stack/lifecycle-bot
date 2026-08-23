package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6454 §P0 — WALL-CLOCK RECONCILER.
 *
 * OPERATOR MANDATE:
 *   "DELETE loopCount%9 and loopCount%60 scheduling.
 *    ONE independent reconciler: quick every 5s, full every 30s,
 *    single-flight + bounded timeout.
 *    Its cadence must remain constant whether botLoop takes 5s or 150s."
 *
 * DESIGN
 * ──────
 * Dedicated CoroutineScope on Dispatchers.Default. Two tasks:
 *   quick — every 5s, budget 2s, single-flight
 *   full  — every 30s, budget 8s, single-flight
 * Wrapped in ReconcilerWatchdog6430 so healthStatus() cannot stay
 * UNKNOWN. Missed cadences increment a counter (>2 misses fires
 * RECONCILER_CADENCE_MISS_6454).
 */
object WallClockReconciler6454 {

    private const val QUICK_INTERVAL_MS = 5_000L
    private const val QUICK_BUDGET_MS = 2_000L
    private const val FULL_INTERVAL_MS = 30_000L
    private const val FULL_BUDGET_MS = 8_000L
    private const val MISS_THRESHOLD = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private val quickJob = AtomicReference<Job?>(null)
    private val fullJob = AtomicReference<Job?>(null)

    private val quickTicks = AtomicLong(0L)
    private val quickSuccesses = AtomicLong(0L)
    private val quickMisses = AtomicLong(0L)
    private val quickLastAtMs = AtomicLong(0L)

    private val fullTicks = AtomicLong(0L)
    private val fullSuccesses = AtomicLong(0L)
    private val fullMisses = AtomicLong(0L)
    private val fullLastAtMs = AtomicLong(0L)

    private val fullReconstructorRef = AtomicReference<((rows: Any?) -> Unit)?>(null)
    private val rowSnapshotRef = AtomicReference<(() -> Any?)?>(null)

    /**
     * Start the wall-clock reconciler. `fullReconstructor` receives the
     * output of `rowSnapshot()` — kept as `Any?` here so this module has
     * zero dependencies on the concrete forensic row type.
     */
    fun start(rowSnapshot: () -> Any?, fullReconstructor: (rows: Any?) -> Unit) {
        rowSnapshotRef.set(rowSnapshot)
        fullReconstructorRef.set(fullReconstructor)
        if (!running.compareAndSet(false, true)) return
        try { ForensicLogger.lifecycle("WALL_CLOCK_RECONCILER_STARTED_6454", "quickMs=$QUICK_INTERVAL_MS fullMs=$FULL_INTERVAL_MS") } catch (_: Throwable) {}
        quickJob.set(scope.launch { quickLoop() })
        fullJob.set(scope.launch { fullLoop() })
    }

    private suspend fun quickLoop() {
        var expectedNextMs = System.currentTimeMillis() + QUICK_INTERVAL_MS
        while (scope.isActive && running.get()) {
            delay(QUICK_INTERVAL_MS)
            quickTicks.incrementAndGet()
            val now = System.currentTimeMillis()
            val drift = now - expectedNextMs
            expectedNextMs = now + QUICK_INTERVAL_MS
            val t0 = now
            var success = true
            var err: String? = null
            try { ReconcilerWatchdog6430.beforeAttempt() } catch (_: Throwable) {}
            try { ReconcilerHeartbeat6467.onQuickStart() } catch (_: Throwable) {}
            try {
                withTimeoutOrNull(QUICK_BUDGET_MS) {
                    CanonicalReconciler6441.quickCheck()
                } ?: run {
                    success = false
                    err = "quick_budget_exceeded"
                    quickMisses.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("RECONCILER_CADENCE_MISS_6454_QUICK") } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                success = false
                err = t.message
            }
            try { ReconcilerWatchdog6430.afterAttempt(success, System.currentTimeMillis() - t0, err) } catch (_: Throwable) {}
            if (success) {
                quickSuccesses.incrementAndGet()
                quickLastAtMs.set(now)
                try { ReconcilerHeartbeat6467.onQuickSuccess() } catch (_: Throwable) {}
                // V5.0.6501 §8 — ACCEPTANCE INVARIANT AUTHORITY.
                // Runs on every quick reconciler tick. Compares reported
                // (cash + openMV) vs canonical reconstructed equity;
                // emits ECONOMIC_TRUTH_DIVERGENCE_6501 on divergence >
                // TOLERANCE_SOL so any leaking phantom notional is
                // visible in the root cause banner.
                try { AcceptanceInvariantAuthority6501.check() } catch (_: Throwable) {}
            }
            if (drift > QUICK_INTERVAL_MS * MISS_THRESHOLD) {
                try {
                    ForensicLogger.lifecycle(
                        "RECONCILER_CADENCE_MISS_6454",
                        "quickDriftMs=$drift threshold=${QUICK_INTERVAL_MS * MISS_THRESHOLD}",
                    )
                    PipelineHealthCollector.labelInc("RECONCILER_CADENCE_MISS_6454")
                } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun fullLoop() {
        var expectedNextMs = System.currentTimeMillis() + FULL_INTERVAL_MS
        while (scope.isActive && running.get()) {
            delay(FULL_INTERVAL_MS)
            fullTicks.incrementAndGet()
            val now = System.currentTimeMillis()
            val drift = now - expectedNextMs
            expectedNextMs = now + FULL_INTERVAL_MS
            val t0 = now
            var success = true
            var err: String? = null
            try { ReconcilerWatchdog6430.beforeAttempt() } catch (_: Throwable) {}
            try { ReconcilerHeartbeat6467.onFullStart() } catch (_: Throwable) {}
            try {
                withTimeoutOrNull(FULL_BUDGET_MS) {
                    val rows = try { rowSnapshotRef.get()?.invoke() } catch (_: Throwable) { null }
                    fullReconstructorRef.get()?.invoke(rows)
                } ?: run {
                    success = false
                    err = "full_budget_exceeded"
                    fullMisses.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("RECONCILER_CADENCE_MISS_6454_FULL") } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                success = false
                err = t.message
            }
            try { ReconcilerWatchdog6430.afterAttempt(success, System.currentTimeMillis() - t0, err) } catch (_: Throwable) {}
            if (success) {
                fullSuccesses.incrementAndGet()
                fullLastAtMs.set(now)
                try { ReconcilerHeartbeat6467.onFullSuccess() } catch (_: Throwable) {}
            }
            if (drift > FULL_INTERVAL_MS * MISS_THRESHOLD) {
                try {
                    ForensicLogger.lifecycle(
                        "RECONCILER_CADENCE_MISS_6454_FULL",
                        "fullDriftMs=$drift threshold=${FULL_INTERVAL_MS * MISS_THRESHOLD}",
                    )
                    PipelineHealthCollector.labelInc("RECONCILER_CADENCE_MISS_6454_FULL")
                } catch (_: Throwable) {}
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { quickJob.get()?.cancel() } catch (_: Throwable) {}
        try { fullJob.get()?.cancel() } catch (_: Throwable) {}
    }

    fun statusLine(): String {
        val q = if (quickLastAtMs.get() == 0L) "never" else "${System.currentTimeMillis() - quickLastAtMs.get()}ms"
        val f = if (fullLastAtMs.get() == 0L) "never" else "${System.currentTimeMillis() - fullLastAtMs.get()}ms"
        return "running=${running.get()} quick(tick=${quickTicks.get()} ok=${quickSuccesses.get()} miss=${quickMisses.get()} lastAgo=$q) " +
            "full(tick=${fullTicks.get()} ok=${fullSuccesses.get()} miss=${fullMisses.get()} lastAgo=$f)"
    }
}

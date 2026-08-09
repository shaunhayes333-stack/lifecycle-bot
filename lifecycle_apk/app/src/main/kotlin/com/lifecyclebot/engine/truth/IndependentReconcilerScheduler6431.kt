package com.lifecyclebot.engine.truth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * V5.0.6431 §K — INDEPENDENT RECONCILER SCHEDULER.
 *
 * OPERATOR (V5.0.6424 spec §K):
 *   'The reconciler must NOT run on the main bot loop. Create dedicated
 *    coroutine scope: SupervisorJob, Dispatchers.IO or dedicated
 *    single-thread dispatcher. Schedule: quick reconcile every 5
 *    seconds, full reconcile every 30 seconds.'
 *
 * DESIGN
 * ──────
 * Own SupervisorJob + Dispatchers.IO scope. Two independent tickers:
 *   quickReconcile every 5s (invariant assertions only, cheap)
 *   fullReconcile  every 30s (delegates to callback → the existing
 *                             ForensicReconciler6377.runAll path)
 *
 * The scheduler runs regardless of main bot-loop congestion. When the
 * caller passes a fullReconcileCallback, it is invoked from IO scope
 * and instrumented via ReconcilerWatchdog6430.
 *
 * start(scope, fullReconcileCallback) is called ONCE from BotService
 * onCreate. stop() is called ONCE from BotService onDestroy.
 */
object IndependentReconcilerScheduler6431 {

    private const val QUICK_CADENCE_MS = 5_000L
    private const val FULL_CADENCE_MS = 30_000L

    private val started = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var quickJob: Job? = null
    private var fullJob: Job? = null

    /**
     * Starts the two independent tickers. Idempotent — repeated calls
     * are no-ops. The fullReconcileCallback is invoked from IO scope
     * on the FULL_CADENCE_MS interval; it must be safe to run from a
     * background thread. The quick ticker calls only the in-memory
     * capital-conservation invariant plus a cheap ledger-health probe.
     */
    fun start(fullReconcileCallback: () -> Unit) {
        if (!started.compareAndSet(false, true)) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        quickJob = newScope.launch {
            while (isActive) {
                try {
                    val err = PaperAccountLedger6430.assertInvariant()
                    RunnerAutoCompound6422.setLedgerHealthy(err == null)
                } catch (_: Throwable) {}
                delay(QUICK_CADENCE_MS)
            }
        }
        fullJob = newScope.launch {
            // Initial small stagger so quick + full aren't in lockstep.
            delay(2_000L)
            while (isActive) {
                try {
                    fullReconcileCallback()
                } catch (_: Throwable) {}
                delay(FULL_CADENCE_MS)
            }
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        quickJob?.cancel(); fullJob?.cancel()
        scope?.cancel(); scope = null
    }

    fun isRunning(): Boolean = started.get()

    fun statusLine(): String =
        "running=${started.get()} quickCadenceMs=$QUICK_CADENCE_MS fullCadenceMs=$FULL_CADENCE_MS"
}

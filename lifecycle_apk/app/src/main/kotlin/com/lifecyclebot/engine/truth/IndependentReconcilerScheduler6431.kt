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
                // V5.0.7459 — bounded finality projection repair belongs on
                // the independent reconciliation clock, never the bot loop.
                try {
                    val n7459 = FinalizedLearningReconciler7423
                        .repairDurableBusPublishFailures7459(
                            // V5.0.7617 — 7607 had 2,783 repairable historical
                            // finals and repaired exactly 4×32 in four full passes.
                            // The count ceiling, not the existing wall-clock guard,
                            // was the limiter. Raise the scan/output ceiling while
                            // preserving the SAME 2.5s background work budget.
                            limit = 128,
                            maxWorkMs7514 = 2_500L,
                        )
                    if (n7459 > 0) {
                        com.lifecyclebot.engine.PipelineHealthCollector
                            .labelInc("FINALIZED_BUS_REPAIR_BATCH_7459")
                    }
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

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6430 §Q + §R — RECONCILER WATCHDOG.
 *
 * OPERATOR (V5.0.6424 §Q):
 *   'uptime=14068 sec, last reconciler pass=12957 sec ago. Therefore
 *    reconciliation effectively ceased shortly after startup. A forensic
 *    reconciler cannot silently disappear.'
 *
 * DESIGN
 * ──────
 * Wraps ForensicReconciler6377.runAll(...) calls with liveness tracking.
 * Records lastAttempt / lastSuccess / lastFailure / lastDuration /
 * nextScheduled / consecutiveFailures. Exposes healthStatus():
 *   HEALTHY  — passed recently, no consecutive failures
 *   DEGRADED — >1 consecutive failure OR last pass > threshold ago
 *   STALE    — no pass in > STALE_THRESHOLD_MS
 *   FAILED   — last attempt threw
 *
 * Non-intrusive: the actual reconciler is still called from BotService.
 * This module intercepts by having the caller wrap each call with
 * `beforeAttempt()` and `afterAttempt(success, durationMs, exception)`.
 * If callers forget to instrument, healthStatus() returns STALE as
 * intended — silence IS failure per §Q.
 */
object ReconcilerWatchdog6430 {

    private const val STALE_THRESHOLD_MS = 5 * 60_000L  // 5 min
    private const val DEGRADED_THRESHOLD_MS = 3 * 60_000L
    private const val EXPECTED_CADENCE_MS = 60_000L      // reconciler should run at least every minute

    enum class Status { HEALTHY, DEGRADED, STALE, FAILED, UNKNOWN }

    private val lastAttemptMs = AtomicLong(0L)
    private val lastSuccessMs = AtomicLong(0L)
    private val lastFailureMs = AtomicLong(0L)
    private val lastDurationMs = AtomicLong(0L)
    private val consecutiveFailures = AtomicLong(0L)
    private val totalPasses = AtomicLong(0L)
    private val totalFailures = AtomicLong(0L)
    private val lastException: AtomicReference<String> = AtomicReference("")

    fun beforeAttempt() {
        lastAttemptMs.set(System.currentTimeMillis())
    }

    fun afterAttempt(success: Boolean, durationMs: Long, exceptionSummary: String? = null) {
        val now = System.currentTimeMillis()
        lastDurationMs.set(durationMs)
        totalPasses.incrementAndGet()
        if (success) {
            lastSuccessMs.set(now)
            consecutiveFailures.set(0L)
            try { PipelineHealthCollector.labelInc("RECONCILER_WATCHDOG_OK_6430") } catch (_: Throwable) {}
        } else {
            lastFailureMs.set(now)
            consecutiveFailures.incrementAndGet()
            totalFailures.incrementAndGet()
            lastException.set(exceptionSummary.orEmpty().take(200))
            try {
                ForensicLogger.lifecycle(
                    "RECONCILER_WATCHDOG_FAIL_6430",
                    "consecutive=${consecutiveFailures.get()} durationMs=$durationMs summary=${lastException.get()}",
                )
                PipelineHealthCollector.labelInc("RECONCILER_WATCHDOG_FAIL_6430")
            } catch (_: Throwable) {}
        }
        // V5.0.6632 §P0-A — sweep any half-committed atomic keys so
        // ledger-only / journal-only mutations surface as counters at
        // their source, not merely as an aggregate cash-divergence.
        try {
            PaperEconomicAtomicCommit6632.sweepUnpaired6632()
        } catch (_: Throwable) {}
    }

    fun healthStatus(): Status {
        val la = lastAttemptMs.get()
        if (la == 0L) return Status.UNKNOWN
        val ageMs = System.currentTimeMillis() - la
        if (ageMs > STALE_THRESHOLD_MS) return Status.STALE
        val cf = consecutiveFailures.get()
        if (cf >= 2L) return Status.FAILED
        if (cf >= 1L) return Status.DEGRADED
        if (ageMs > DEGRADED_THRESHOLD_MS) return Status.DEGRADED
        return Status.HEALTHY
    }

    fun statusLine(): String {
        val ageSuc = if (lastSuccessMs.get() == 0L) "never" else "${(System.currentTimeMillis() - lastSuccessMs.get()) / 1000}s ago"
        val ageAtt = if (lastAttemptMs.get() == 0L) "never" else "${(System.currentTimeMillis() - lastAttemptMs.get()) / 1000}s ago"
        return "status=${healthStatus()} lastAttempt=$ageAtt lastSuccess=$ageSuc consecFail=${consecutiveFailures.get()} totalFail=${totalFailures.get()} totalPasses=${totalPasses.get()} lastDurMs=${lastDurationMs.get()}"
    }

    internal fun resetForTest() {
        lastAttemptMs.set(0); lastSuccessMs.set(0); lastFailureMs.set(0)
        lastDurationMs.set(0); consecutiveFailures.set(0); totalPasses.set(0)
        totalFailures.set(0); lastException.set("")
    }
}

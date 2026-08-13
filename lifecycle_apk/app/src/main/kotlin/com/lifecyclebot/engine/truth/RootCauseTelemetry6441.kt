package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6441 §10 — ROOT-CAUSE / SUBSYSTEM PERFORMANCE TELEMETRY.
 *
 * OPERATOR MANDATE §10:
 *   "Root-cause classifier must rank attributed blocking time by
 *    subsystem. UI_ANR cannot be declared primary merely because
 *    watchdog hints exist. Report separately: pipelineCycleMs,
 *    scannerMs, intakeMs, learnerMaintenanceMs, exitMs, executionMs,
 *    reconcilerMs, reportUiMs, unattributedMs. Include worst phase +
 *    causal stack/label. A long synchronous learner phase must surface
 *    as learner/maintenance fault, not UI/scanner overload."
 *
 * Extends the V5.0.6437 SlowCycleDiagnostic phase attribution with
 * SUBSYSTEM-level attribution. The dispatcher tracks per-subsystem
 * cumulative blocking time; the root-cause classifier reports the
 * top offender at cycle end.
 */
object RootCauseTelemetry6441 {

    enum class Subsystem {
        SCANNER, INTAKE, LEARNER_MAINT, EXIT, EXECUTION, RECONCILER, REPORT_UI, UNATTRIBUTED,
    }

    private val subsystemMs = ConcurrentHashMap<Subsystem, Long>()
    private val cycleCount = AtomicLong(0L)
    private val worstSubsystem = ConcurrentHashMap<Subsystem, Long>()
    @Volatile private var lastRootCause: Subsystem = Subsystem.UNATTRIBUTED
    @Volatile private var lastRootCauseMs: Long = 0L

    fun attribute(subsystem: Subsystem, elapsedMs: Long) {
        if (elapsedMs <= 0L) return
        subsystemMs.merge(subsystem, elapsedMs) { a, b -> a + b }
        worstSubsystem.merge(subsystem, elapsedMs) { a, b -> kotlin.math.max(a, b) }
    }

    /**
     * Called at cycle end. Analyses the subsystem attribution for THIS
     * cycle (caller is expected to reset between cycles) and returns
     * the top-attributed subsystem + its ms.
     */
    fun classifyCycle(cycleTotalMs: Long): Pair<Subsystem, Long> {
        cycleCount.incrementAndGet()
        val top = subsystemMs.entries.maxByOrNull { it.value }
        val cause = top?.key ?: Subsystem.UNATTRIBUTED
        val causeMs = top?.value ?: 0L
        lastRootCause = cause
        lastRootCauseMs = causeMs
        if (cycleTotalMs >= 20_000L) {
            try {
                ForensicLogger.lifecycle(
                    "ROOT_CAUSE_CLASSIFIED_6441",
                    "cycleMs=$cycleTotalMs cause=$cause causeMs=$causeMs breakdown=${subsystemBreakdown()}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("ROOT_CAUSE_$cause".take(60)) } catch (_: Throwable) {}
        }
        return cause to causeMs
    }

    /** Reset per-cycle attribution — call at cycle start. */
    fun beginCycle() {
        subsystemMs.clear()
    }

    fun subsystemBreakdown(): String = subsystemMs.entries
        .sortedByDescending { it.value }
        .joinToString(",") { "${it.key}=${it.value}ms" }

    fun statusLine(): String =
        "cycles=${cycleCount.get()} lastRootCause=$lastRootCause lastCauseMs=$lastRootCauseMs worstEver=$worstSubsystem"
}

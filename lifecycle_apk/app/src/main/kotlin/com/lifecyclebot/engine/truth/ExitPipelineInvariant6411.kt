package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6411 §15 — EXIT-PIPELINE CRITICAL INVARIANT.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Add a health invariant:
 *   open live positions > 0 AND no successful exit sweep in 2
 *   expected intervals → EXIT_PIPELINE_CRITICAL.
 *  Exit execution must retain priority over new entries."
 *
 * DESIGN
 * ──────
 *   • Bot loop calls onExitSweepStart() / onExitSweepDone() around
 *     the universal exit sweep phase.
 *   • openLivePositions() callback lets the invariant read the
 *     current live position count without a hard dep on BotService.
 *   • Alarm fires when openPositions > 0 AND
 *     (now - lastSuccessfulSweepAtMs) > 2 × expectedIntervalMs.
 *   • Alarm resets when a sweep completes successfully.
 *
 * When EXIT_PIPELINE_CRITICAL is armed the LiveExecutionReadiness
 * bottleneck bumps to the top of the precedence stack. New entries
 * are strongly discouraged; exits remain the only allowed live tx.
 */
object ExitPipelineInvariant6411 {

    private const val EXPECTED_SWEEP_INTERVAL_MS = 30_000L
    private const val CRITICAL_THRESHOLD_MS = 2L * EXPECTED_SWEEP_INTERVAL_MS

    private val lastSweepDoneMs = AtomicLong(0L)
    private val lastSweepStartMs = AtomicLong(0L)
    private val sweepStarts = AtomicLong(0L)
    private val sweepDones = AtomicLong(0L)
    @Volatile private var criticalArmed: Boolean = false

    fun onExitSweepStart() {
        lastSweepStartMs.set(System.currentTimeMillis())
        sweepStarts.incrementAndGet()
    }

    fun onExitSweepDone(success: Boolean) {
        sweepDones.incrementAndGet()
        if (success) {
            lastSweepDoneMs.set(System.currentTimeMillis())
            if (criticalArmed) {
                criticalArmed = false
                try {
                    ForensicLogger.lifecycle("EXIT_PIPELINE_RECOVERED_6411", "sweeps=${sweepDones.get()}")
                    PipelineHealthCollector.labelInc("EXIT_PIPELINE_RECOVERED_6411")
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Evaluate the invariant. Returns true when EXIT_PIPELINE_CRITICAL
     * should be flagged. Caller passes the current live-position count.
     */
    fun evaluate(openLivePositions: Int): Boolean {
        if (openLivePositions <= 0) {
            criticalArmed = false
            return false
        }
        val now = System.currentTimeMillis()
        val lastOk = lastSweepDoneMs.get()
        // Bootstrap: if we've never had a successful sweep and there
        // are open positions, arm after the same 2×interval budget
        // relative to boot rather than never firing.
        val referenceMs = if (lastOk > 0L) lastOk else lastSweepStartMs.get()
        if (referenceMs <= 0L) return false
        val gap = now - referenceMs
        val trip = gap > CRITICAL_THRESHOLD_MS
        if (trip && !criticalArmed) {
            criticalArmed = true
            try {
                ForensicLogger.lifecycle(
                    "EXIT_PIPELINE_CRITICAL_6411",
                    "openPositions=$openLivePositions gapMs=$gap thresholdMs=$CRITICAL_THRESHOLD_MS " +
                        "sweepStarts=${sweepStarts.get()} sweepDones=${sweepDones.get()}",
                )
                PipelineHealthCollector.labelInc("EXIT_PIPELINE_CRITICAL_6411")
            } catch (_: Throwable) {}
        }
        return criticalArmed
    }

    fun statusLine(): String {
        val now = System.currentTimeMillis()
        val lastDone = lastSweepDoneMs.get()
        val ageMs = if (lastDone > 0L) now - lastDone else -1L
        return "sweeps=${sweepDones.get()}/${sweepStarts.get()} lastDoneAgoMs=$ageMs criticalArmed=$criticalArmed"
    }

    internal fun resetForTest() {
        lastSweepDoneMs.set(0L)
        lastSweepStartMs.set(0L)
        sweepStarts.set(0L)
        sweepDones.set(0L)
        criticalArmed = false
    }
}

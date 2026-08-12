package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6437 — SLOW CYCLE DIAGNOSTIC.
 *
 * OPERATOR (V5.0.6436 dump):
 *   "workerTimeout=62" + "cycleMs=75029". The bot cycle is choking to
 *   75s despite supervisor being hard-bounded to 20s. That means the
 *   overshoot lives OUTSIDE runSupervisorPhase — either the pre-supervisor
 *   learning fanout (Regime/Sentience/Lab/ChronicBleeder), a synchronous
 *   reconcile, or one of the 1500-line unchecked blocks between ENTER
 *   and PRE_SUPERVISOR that currently have zero markProgress markers.
 *
 * DESIGN
 * ──────
 * Passive telemetry only. Every markProgress(phase) call funnels through
 * notePhase(phase) so we track:
 *   • time-in-phase (wall-clock elapsed since last notePhase)
 *   • last phase entered
 *   • top-3 phases by cumulative time within the current cycle
 *
 * At cycle end (noteCycleEnd), if the cycle exceeded the slow threshold
 * (30s by default) we emit SLOW_CYCLE_DIAGNOSTIC_6437 with the top-3
 * phase spend so the operator can see EXACTLY which block wedged.
 *
 * Zero impact on happy-path performance: two ConcurrentHashMap ops per
 * phase transition, one forensic emit per slow cycle.
 */
object SlowCycleDiagnostic6437 {

    private const val SLOW_CYCLE_THRESHOLD_MS = 30_000L

    private val cycleStartMs = AtomicLong(0L)
    private val lastPhaseMs = AtomicLong(0L)
    private val lastPhaseName = AtomicReference<String>("IDLE")
    private val phaseSpendMs = ConcurrentHashMap<String, Long>()
    private val slowCycleCount = AtomicLong(0L)
    private val worstCycleMs = AtomicLong(0L)
    private val worstCyclePhase = AtomicReference<String>("")

    fun beginCycle(loopCount: Int) {
        val now = System.currentTimeMillis()
        cycleStartMs.set(now)
        lastPhaseMs.set(now)
        lastPhaseName.set("ENTER")
        phaseSpendMs.clear()
    }

    /** Record a phase transition. Charges elapsed time to the PREVIOUS phase. */
    fun notePhase(phase: String) {
        val now = System.currentTimeMillis()
        val prev = lastPhaseMs.getAndSet(now)
        val prevPhase = lastPhaseName.getAndSet(phase)
        if (prev > 0L) {
            val delta = (now - prev).coerceAtLeast(0L)
            // Only track deltas > 25ms; noise otherwise.
            if (delta >= 25L) {
                phaseSpendMs.merge(prevPhase, delta) { a, b -> a + b }
            }
        }
    }

    /**
     * Called at cycle end. If cycleMs > threshold, emits a forensic dump
     * with the top-3 phases by cumulative wall-clock time inside this
     * cycle. Returns the phase spend map for the operator diagnostic
     * report (never null, may be empty).
     */
    fun noteCycleEnd(loopCount: Int, cycleMs: Long): Map<String, Long> {
        // Flush the current (last) phase.
        notePhase("CYCLE_EXIT")
        val snapshot: Map<String, Long> = HashMap(phaseSpendMs)
        if (cycleMs >= SLOW_CYCLE_THRESHOLD_MS) {
            slowCycleCount.incrementAndGet()
            val top3 = snapshot.entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(",") { "${it.key}=${it.value}ms" }
            val topPhase = snapshot.entries.maxByOrNull { it.value }?.key ?: "UNKNOWN"
            if (cycleMs > worstCycleMs.get()) {
                worstCycleMs.set(cycleMs)
                worstCyclePhase.set(topPhase)
            }
            try {
                ForensicLogger.lifecycle(
                    "SLOW_CYCLE_DIAGNOSTIC_6437",
                    "loop=$loopCount cycleMs=$cycleMs thresholdMs=$SLOW_CYCLE_THRESHOLD_MS top=$top3 phaseCount=${snapshot.size}",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("SLOW_CYCLE_DIAGNOSTIC_6437") } catch (_: Throwable) {}
        }
        return snapshot
    }

    fun statusLine(): String {
        val count = slowCycleCount.get()
        val worst = worstCycleMs.get()
        val phase = worstCyclePhase.get()
        return "slowCycles=$count worstMs=$worst worstPhase=$phase"
    }

    /** For tests only. */
    internal fun clearForTest() {
        phaseSpendMs.clear()
        slowCycleCount.set(0L)
        worstCycleMs.set(0L)
        worstCyclePhase.set("")
    }
}

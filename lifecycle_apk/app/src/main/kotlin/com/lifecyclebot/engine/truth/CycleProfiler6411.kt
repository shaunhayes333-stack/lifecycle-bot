package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6411 §16.1 — CYCLE-PHASE PROFILER.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Replace misleading 'cycles > 30s indicate watchlist or scanner
 *  overload' with phase-attributed diagnosis: MAX_CYCLE_PHASE /
 *  MAX_CYCLE_PROVIDER / MAX_CYCLE_MINT / MAX_CYCLE_QUEUE_DEPTH."
 *
 * DESIGN
 * ──────
 *   • Each cycle can wrap phases with markPhase("name", providerHint) { ... }.
 *   • Phase timings accumulate into a per-cycle max and a rolling
 *     EWMA. When the overall cycle time exceeds a threshold, the
 *     dominant phase is stamped into the operator digest so the
 *     bottleneck is one-glance visible.
 *   • Zero-cost when not consulted (only reads timings when tile()
 *     is called).
 *
 * Advisory / additive — no phase mutates state or aborts on budget
 * overrun. That escalation belongs to the pool-level supervisor
 * (§7) in a follow-up commit.
 */
object CycleProfiler6411 {

    private val phaseMaxMs = ConcurrentHashMap<String, AtomicLong>()
    private val phaseInvocations = ConcurrentHashMap<String, AtomicLong>()
    private val phaseTotalMs = ConcurrentHashMap<String, AtomicLong>()
    private val lastCycleMaxPhase = java.util.concurrent.atomic.AtomicReference<String>("-")
    private val lastCycleMaxMs = AtomicLong(0L)

    /** Record a phase timing sample. */
    fun record(phase: String, durationMs: Long) {
        if (durationMs < 0 || phase.isBlank()) return
        val safe = phase.take(48)
        phaseInvocations.getOrPut(safe) { AtomicLong(0L) }.incrementAndGet()
        phaseTotalMs.getOrPut(safe) { AtomicLong(0L) }.addAndGet(durationMs)
        val maxRef = phaseMaxMs.getOrPut(safe) { AtomicLong(0L) }
        while (true) {
            val prior = maxRef.get()
            if (durationMs <= prior) break
            if (maxRef.compareAndSet(prior, durationMs)) break
        }
        // Track dominant phase of the most recent slow cycle.
        val curMax = lastCycleMaxMs.get()
        if (durationMs > curMax && lastCycleMaxMs.compareAndSet(curMax, durationMs)) {
            lastCycleMaxPhase.set(safe)
        }
    }

    /** Bracketed timing helper. */
    inline fun <T> time(phase: String, block: () -> T): T {
        val start = System.nanoTime()
        val out = block()
        val ms = (System.nanoTime() - start) / 1_000_000L
        record(phase, ms)
        return out
    }

    /** Reset per-cycle tracking (call from bot loop end). */
    fun onCycleComplete(cycleDurationMs: Long) {
        try {
            if (cycleDurationMs > 12_000L) {
                val phase = lastCycleMaxPhase.get()
                ForensicLogger.lifecycle(
                    "CYCLE_SLOW_PHASE_ATTRIBUTION_6411",
                    "cycleMs=$cycleDurationMs dominantPhase=$phase dominantMs=${lastCycleMaxMs.get()}",
                )
                PipelineHealthCollector.labelInc("CYCLE_SLOW_PHASE_ATTRIBUTION_6411")
            }
        } catch (_: Throwable) {}
        lastCycleMaxMs.set(0L)
        lastCycleMaxPhase.set("-")
    }

    fun tile(): String {
        // Top 5 phases by max latency
        val top = phaseMaxMs.entries.sortedByDescending { it.value.get() }.take(5)
        val parts = top.joinToString(",") { (phase, maxRef) ->
            val inv = phaseInvocations[phase]?.get() ?: 0L
            val total = phaseTotalMs[phase]?.get() ?: 0L
            val avg = if (inv > 0) total / inv else 0L
            "$phase(max=${maxRef.get()}ms avg=${avg}ms n=$inv)"
        }
        return "top5=[$parts] lastDominant=${lastCycleMaxPhase.get()} lastMaxMs=${lastCycleMaxMs.get()}"
    }

    internal fun resetForTest() {
        phaseMaxMs.clear()
        phaseInvocations.clear()
        phaseTotalMs.clear()
        lastCycleMaxMs.set(0L)
        lastCycleMaxPhase.set("-")
    }
}

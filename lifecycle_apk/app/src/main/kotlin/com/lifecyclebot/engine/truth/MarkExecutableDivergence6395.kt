package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/**
 * V5.0.6395 — MARK / EXECUTABLE DIVERGENCE.
 *
 *   markDivergencePct =
 *       abs(displayMarkExitSol - executableNetSol) /
 *       max(executableNetSol, dustFloor) * 100
 *
 * Thresholds:
 *   >20%  -> MARK_EXECUTABLE_DIVERGENCE
 *           * mark = non-authoritative
 *           * executable peak NOT updated from mark
 *           * mark cannot trigger completed take-profit
 *           * UI shows BOTH values
 *   >100% -> NON_EXECUTABLE_MARK_SPIKE
 *           * inspect pair identity / decimals / liquidity
 *           * event does NOT enter canonical learning
 *           * protective exits continue via executable quotes
 *
 * Emitter routes via PipelineHealthCollector.labelInc and ForensicLogger.lifecycle.
 * Both are best-effort — divergence math is pure and stateless.
 */
object MarkExecutableDivergence6395 {

    const val DIVERGENCE_THRESHOLD_PCT: Double = 20.0
    const val SPIKE_THRESHOLD_PCT: Double = 100.0
    const val DUST_FLOOR_SOL: Double = 0.0001

    enum class Severity { NONE, DIVERGENT, SPIKE }

    data class Verdict(
        val severity: Severity,
        val markDivergencePct: Double,
        val markAuthoritative: Boolean,
        val allowExecutablePeakUpdate: Boolean,
        val allowTakeProfitCompletion: Boolean,
        val excludeFromLearning: Boolean,
    )

    val divergenceEvents = AtomicLong(0L)
    val spikeEvents = AtomicLong(0L)

    fun evaluate(displayMarkExitSol: Double, executableNetSol: Double): Verdict {
        val denom = max(executableNetSol, DUST_FLOOR_SOL)
        val div = abs(displayMarkExitSol - executableNetSol) / denom * 100.0
        val sev = when {
            div > SPIKE_THRESHOLD_PCT -> Severity.SPIKE
            div > DIVERGENCE_THRESHOLD_PCT -> Severity.DIVERGENT
            else -> Severity.NONE
        }
        return when (sev) {
            Severity.NONE -> Verdict(
                sev, div, markAuthoritative = true,
                allowExecutablePeakUpdate = true,
                allowTakeProfitCompletion = true,
                excludeFromLearning = false,
            )
            Severity.DIVERGENT -> Verdict(
                sev, div, markAuthoritative = false,
                allowExecutablePeakUpdate = false,
                allowTakeProfitCompletion = false,
                excludeFromLearning = false,
            )
            Severity.SPIKE -> Verdict(
                sev, div, markAuthoritative = false,
                allowExecutablePeakUpdate = false,
                allowTakeProfitCompletion = false,
                excludeFromLearning = true,
            )
        }
    }

    /** Emit and record the verdict. Callers still hold the verdict for gating. */
    fun emit(mint: String, verdict: Verdict) {
        when (verdict.severity) {
            Severity.DIVERGENT -> {
                divergenceEvents.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("MARK_EXECUTABLE_DIVERGENCE_6395") } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("MARK_EXECUTABLE_DIVERGENCE_6395", "mint=${mint.take(10)} divPct=${"%.1f".format(verdict.markDivergencePct)}") } catch (_: Throwable) {}
            }
            Severity.SPIKE -> {
                spikeEvents.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("NON_EXECUTABLE_MARK_SPIKE_6395") } catch (_: Throwable) {}
                try { com.lifecyclebot.engine.ForensicLogger.lifecycle("NON_EXECUTABLE_MARK_SPIKE_6395", "mint=${mint.take(10)} divPct=${"%.1f".format(verdict.markDivergencePct)}") } catch (_: Throwable) {}
            }
            Severity.NONE -> Unit
        }
    }

    internal fun clearForTest() { divergenceEvents.set(0L); spikeEvents.set(0L) }
}

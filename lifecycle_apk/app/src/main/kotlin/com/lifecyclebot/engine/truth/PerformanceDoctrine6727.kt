package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6727 — §PERFORMANCE_DOCTRINE_50_TARGET.
 *
 * Operator diagnostic from 6726: "Overall WR is 22.9%, with a 26-loss
 * maximum streak, but the system reports Floor status: ✅ within band
 * because its doctrine band is only 20-35%. So the bot is not
 * malfunctioning relative to that particular configured floor — it is
 * doing exactly what that floor permits."
 *
 * The historic doctrine encoded a "survival" band; the operator's design
 * expectation is a 50%+ WR runner. This authority owns the single
 * source-of-truth expectation and emits a canonical telemetry counter
 * whenever measured WR falls below the 50% target. Downstream dampers
 * (LaneExpectancyDamper, TacticSwitcher, SourceCohortAdvisory) can key
 * on `belowTarget()` to raise their aggression without re-implementing
 * the doctrine.
 *
 * This authority does NOT touch trading heuristics — it publishes the
 * expectation. Consumers convert it into action.
 */
object PerformanceDoctrine6727 {

    /** Design-target overall winrate — the "50%+ runner compounding" contract. */
    private const val TARGET_WR = 0.50
    /** Minimum trades before the doctrine engages. */
    private const val MIN_DECIDED = 20

    private val readsCount = AtomicLong(0)
    private val belowTargetEmits = AtomicLong(0)

    data class Verdict(
        val measuredWinRatePct: Double,
        val decidedCount: Long,
        val targetWinRatePct: Double,
        val belowTarget: Boolean,
        val gapPct: Double, // negative when measured < target
    )

    /**
     * Evaluate the current runtime WR against the 50% doctrine target.
     * Reads through CanonicalPositionAuthority6441 for win/loss counts on
     * closed positions. Never mutates.
     */
    fun evaluate(): Verdict {
        readsCount.incrementAndGet()
        val (wins, losses) = try {
            val closed = CanonicalPositionAuthority6441.closedPositions()
            var w = 0L
            var l = 0L
            for (p in closed) {
                val realized = try { p.realizedPnlSol } catch (_: Throwable) { 0.0 }
                if (realized > 0.0) w += 1L
                else if (realized < 0.0) l += 1L
                // realized == 0 → indeterminate, skip
            }
            w to l
        } catch (_: Throwable) { 0L to 0L }
        val decided = wins + losses
        val wr = if (decided > 0) wins.toDouble() / decided.toDouble() else 0.0
        val below = decided >= MIN_DECIDED && wr < TARGET_WR
        val gap = wr - TARGET_WR
        if (below) {
            belowTargetEmits.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PERFORMANCE_BELOW_50_TARGET_6727") } catch (_: Throwable) {}
        } else if (decided >= MIN_DECIDED) {
            try { PipelineHealthCollector.labelInc("PERFORMANCE_AT_OR_ABOVE_50_TARGET_6727") } catch (_: Throwable) {}
        }
        return Verdict(
            measuredWinRatePct = wr * 100.0,
            decidedCount = decided,
            targetWinRatePct = TARGET_WR * 100.0,
            belowTarget = below,
            gapPct = gap * 100.0,
        )
    }

    /** Convenience — true when runtime WR is BELOW the 50% target. */
    fun belowTarget(): Boolean = evaluate().belowTarget

    /** Diagnostic snapshot for operator display. */
    fun diagnosticLine(): String {
        val v = evaluate()
        return "PERF_DOCTRINE_6727 target=${"%.0f".format(v.targetWinRatePct)}% measured=${"%.1f".format(v.measuredWinRatePct)}% n=${v.decidedCount} belowTarget=${v.belowTarget} gap=${"%.1f".format(v.gapPct)}%"
    }
}

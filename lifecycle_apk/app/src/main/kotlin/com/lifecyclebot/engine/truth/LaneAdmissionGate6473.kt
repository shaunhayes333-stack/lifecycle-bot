package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6473 §Damping-Wire — LANE ADMISSION GATE.
 *
 * OPERATOR MANDATE (6472 deferred → 6473 wire):
 *   "Wire LaneAdaptiveDamping6472 into the FDG/admission path so the
 *    SHITCOIN damping actually shrinks sizes and shifts cadence in
 *    production."
 *
 * DESIGN
 * ──────
 *   • `admissionDecision(lane, requestedSizeSol, score)` — combines
 *     the current lane damping with an admission decision:
 *       - if damping.allowProbe is false → SKIP (cadence throttle)
 *       - otherwise → ALLOW with size = requestedSizeSol * sizeMult,
 *         and scoreFloor = default + damping.scoreFloorBoost
 *
 *   • Callers stamp the outcome on the executor path so the operator
 *     can watch SHITCOIN's cadence + size shrink at telemetry level
 *     rather than the lane going dark.
 *
 *   • Never returns size = 0. If damping shrinks below the paper
 *     minimum, the caller's own min-cash guard drops the buy cleanly
 *     via V5.0.6471 clampPaperTradeSol semantics.
 */
object LaneAdmissionGate6473 {

    sealed class Decision {
        data class Allow(
            val effectiveSizeSol: Double,
            val effectiveScoreFloor: Int,
            val level: Int,
        ) : Decision()
        data class Skip(val reason: String) : Decision()
    }

    private val allows = AtomicLong(0L)
    private val skips = AtomicLong(0L)
    private val sizeShrinks = AtomicLong(0L)

    fun admissionDecision(
        lane: String,
        requestedSizeSol: Double,
        baseScoreFloor: Int = 0,
    ): Decision {
        val d = LaneAdaptiveDamping6472.damping(lane)
        if (!d.allowProbe && d.cadenceThrottleMs > 0L) {
            skips.incrementAndGet()
            LaneAdaptiveDamping6472.onLaneThrottled(lane, "admission_cadence")
            try {
                PipelineHealthCollector.labelInc("LANE_ADMISSION_SKIPPED_CADENCE_6473_$lane".take(60))
            } catch (_: Throwable) {}
            return Decision.Skip("cadence_throttle_lvl_${d.level}")
        }
        val effective = (requestedSizeSol * d.sizeMultiplier).coerceAtLeast(0.0)
        if (d.sizeMultiplier < 1.0) {
            sizeShrinks.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "LANE_ADMISSION_SIZE_SHRUNK_6473",
                    "lane=$lane requested=$requestedSizeSol effective=$effective mult=${d.sizeMultiplier} lvl=${d.level}",
                )
                PipelineHealthCollector.labelInc("LANE_ADMISSION_SIZE_SHRUNK_6473_$lane".take(60))
            } catch (_: Throwable) {}
        }
        allows.incrementAndGet()
        LaneAdaptiveDamping6472.onLaneAdmission(lane)
        return Decision.Allow(
            effectiveSizeSol = effective,
            effectiveScoreFloor = baseScoreFloor + d.scoreFloorBoost,
            level = d.level,
        )
    }

    fun statusLine(): String {
        val stamp = CanonicalInstanceIdentity6472.stamp("LaneAdmissionGate6473")
        return "allows=${allows.get()} skips=${skips.get()} " +
            "sizeShrinks=${sizeShrinks.get()} $stamp"
    }

    internal fun resetForTest() {
        allows.set(0L); skips.set(0L); sizeShrinks.set(0L)
    }
}

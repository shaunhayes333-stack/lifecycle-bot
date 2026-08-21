package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.learning.TacticSwitcher
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
 *       - cadence pressure rotates the lane-local TacticSwitcher first
 *       - sizing is secondary and cannot fall below the executable floor
 *       - learned pressure never becomes a hard trade block or size-zero error
 *
 *   • Callers stamp the outcome on the executor path so the operator
 *     can watch SHITCOIN's cadence + size shrink at telemetry level
 *     rather than the lane going dark.
 *
 *   • Never returns learned size = 0; callers provide the executable floor.
 */
object LaneAdmissionGate6473 {

    sealed class Decision {
        data class Allow(
            val effectiveSizeSol: Double,
            val effectiveScoreFloor: Int,
            val level: Int,
            val pivotedTactic: String? = null,
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
        candidateScore: Int = baseScoreFloor,
        minExecutableSizeSol: Double = 0.0,
    ): Decision {
        val d = LaneAdaptiveDamping6472.damping(lane)
        var pivotedTactic: String? = null
        if (!d.allowProbe && d.cadenceThrottleMs > 0L) {
            // V5.0.6481 — strategy first: rotate inside this lane instead of
            // returning a learned hard-skip or buying the same setup smaller.
            LaneAdaptiveDamping6472.onLaneThrottled(lane, "lane_local_tactic_pivot")
            pivotedTactic = try {
                TacticSwitcher.rotateForLanePressure(lane, candidateScore, "damp_lvl_${d.level}").name
            } catch (_: Throwable) { null }
            try { PipelineHealthCollector.labelInc("LANE_ADMISSION_TACTIC_PIVOT_6481_$lane".take(60)) } catch (_: Throwable) {}
        }
        val effective = (requestedSizeSol * d.sizeMultiplier)
            .coerceAtLeast(minExecutableSizeSol.coerceAtLeast(0.0))
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
            pivotedTactic = pivotedTactic,
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

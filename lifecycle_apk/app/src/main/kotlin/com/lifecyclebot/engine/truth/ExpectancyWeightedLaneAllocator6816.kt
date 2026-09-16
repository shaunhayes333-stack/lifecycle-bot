package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.LaneExpectancyDamper
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6816 §EXPECTANCY_WEIGHTED_ALLOC — operator directive V5.0.6813 P1:
 *   "Replace near-flat target allocation with adaptive weighting based on
 *    expectedReturn, winProbability, profitFactor, etc. Capital allocation
 *    must accurately follow lane expectancy."
 *
 * DESIGN — additive, single-authority, bounded multiplier.
 *   • Reads the ALREADY-computed `LaneExpectancyDamper.sizeMultiplier`
 *     (which composes mean pnl %, win rate, profit factor, cohort
 *     advisories into a scalar).
 *   • Applies a second-order shape:
 *       – A base multiplier of 1.0 for a lane at neutral expectancy.
 *       – Winners (>=1.10 damper) receive a bounded uplift toward 1.35.
 *       – Bleeders (<=0.60 damper) receive a further haircut toward 0.30.
 *       – Non-runner lanes always cap at 1.00 (no upsize on legacy lanes).
 *   • The final multiplier is CLAMPED to `[0.20, 1.35]` — same envelope
 *     as CapitalRecycleRatioAuthority6814 so the composed adaptive
 *     stack in OrderSizeResolver never overshoots.
 *   • Fail-open: any exception returns 1.0.
 *
 * Callers wire this by multiplying `sizeMultiplier(lane)` into the
 * existing OrderSizeResolver adaptive-multiplier composition.
 */
object ExpectancyWeightedLaneAllocator6816 {

    private const val MIN_MULT = 0.20
    private const val MAX_MULT = 1.35
    private const val WINNER_FLOOR = 1.10
    private const val BLEEDER_CEIL = 0.60
    private const val NON_RUNNER_CAP = 1.00

    // Lanes eligible for winner uplift. Same set the existing damper
    // recognises internally — kept local to avoid a public-API leak.
    private val RUNNER_LANE_KEYS = arrayOf(
        "MOONSHOT", "SHITCOIN", "MEME", "EXPRESS",
        "MANIPULATED", "MANIP", "PRESALE", "PROJECT_SNIPER", "DIP_HUNTER",
    )

    private val queries = AtomicLong(0L)
    private val uplifts = AtomicLong(0L)
    private val haircuts = AtomicLong(0L)
    private val neutrals = AtomicLong(0L)

    private fun isRunnerLane(lane: String): Boolean {
        val s = lane.trim().uppercase()
        for (k in RUNNER_LANE_KEYS) if (s.contains(k)) return true
        return false
    }

    /** Returns a bounded expectancy-weighted multiplier for the lane. */
    fun sizeMultiplier(lane: String?): Double {
        queries.incrementAndGet()
        if (lane.isNullOrBlank()) return 1.0
        return try {
            val damped = try {
                LaneExpectancyDamper.sizeMultiplier(lane)
            } catch (_: Throwable) { 1.0 }
            val runner = isRunnerLane(lane)
            val shaped = when {
                damped >= WINNER_FLOOR -> {
                    // Bounded winner uplift for runner lanes; capped
                    // otherwise. WINNER_FLOOR..1.45 mapped to 1.05..MAX_MULT.
                    val t = ((damped - WINNER_FLOOR) / (1.45 - WINNER_FLOOR)).coerceIn(0.0, 1.0)
                    if (runner) {
                        uplifts.incrementAndGet()
                        (1.05 + t * (MAX_MULT - 1.05)).coerceIn(1.0, MAX_MULT)
                    } else {
                        neutrals.incrementAndGet()
                        NON_RUNNER_CAP
                    }
                }
                damped <= BLEEDER_CEIL -> {
                    // Further haircut proportional to how deep the damper
                    // has pushed. 0.20..0.60 mapped to 0.30..0.85.
                    haircuts.incrementAndGet()
                    val t = ((BLEEDER_CEIL - damped) / (BLEEDER_CEIL - 0.20)).coerceIn(0.0, 1.0)
                    (0.85 - t * (0.85 - 0.30)).coerceIn(MIN_MULT, 1.0)
                }
                else -> {
                    neutrals.incrementAndGet()
                    if (runner) 1.0 else NON_RUNNER_CAP
                }
            }
            val bounded = shaped.coerceIn(MIN_MULT, MAX_MULT)
            if (kotlin.math.abs(bounded - 1.0) > 0.02) {
                try {
                    PipelineHealthCollector.labelInc("EXPECTANCY_WEIGHTED_ALLOC_APPLIED_6816")
                    PipelineHealthCollector.labelInc(
                        "EXPECTANCY_WEIGHTED_ALLOC_APPLIED_6816_${lane.uppercase().take(24)}"
                    )
                } catch (_: Throwable) {}
            }
            bounded
        } catch (_: Throwable) { 1.0 }
    }

    fun statusLine(): String =
        "queries=${queries.get()} uplifts=${uplifts.get()} " +
            "haircuts=${haircuts.get()} neutrals=${neutrals.get()}"

    internal fun clearForTest() {
        queries.set(0L); uplifts.set(0L); haircuts.set(0L); neutrals.set(0L)
    }
}

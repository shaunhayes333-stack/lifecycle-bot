package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.LaneExpectancyDamper
import com.lifecyclebot.engine.LiveEntrySafetyHold
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.RegimeDetector
import com.lifecyclebot.engine.ScoreExpectancyTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7266 §THE_CANONICAL_FLOOR_IS_A_NUMBER_THE_LANE_EARNS.
 *
 * Operator, on 7265: "everything is meant to be fluid. score thresholds,
 * hold times, scoring, exits, entries — everything is meant to move up and
 * down until the stack finds the best ways to trade in each trader,
 * specialist, lane, strategy."
 *
 * V5.0.7243 put one fixed boundary in FinalDecisionGate for both books:
 * canonical V3 score >= 30, WAIT promotion >= 55. The reason was sound
 * (7240: PROJECT_SNIPER S0-10 probes funded from score zero, 3W/20L) but
 * the number was not learned and could not move. On 5.0.7263 it blocked
 * 234 candidates, almost all cold fresh launches scoring 9–32 — the lanes
 * whose whole purpose is the 10x tail — and it would have blocked them
 * identically after a thousand profitable closes at score 25.
 *
 * This authority resolves the boundary per lane from what the stack already
 * knows, and it moves in both directions:
 *
 *   bootstrap  = LiveEntrySafetyHold.minLiveCandidateScore — the entry
 *                governor's own fluid minimum (baseline 15, tightened and
 *                relaxed by the governor).
 *   target     = the lane's learned floor from ScoreExpectancyTracker: the
 *                lowest 10-point score bucket with >= LEARNED_MIN_SAMPLES
 *                closes and a positive mean. If the lane has proven a bucket
 *                at 20–29 pays, the boundary comes down to 20; if only 40+
 *                pays, it goes up to 40. With no proven bucket the target is
 *                7243's mature boundary (30).
 *   maturity   = n / (n + MIN_TRADES) over the lane's same-mode clean closes,
 *                the same evidence curve LaneExpectancyDamper uses.
 *   floor      = bootstrap + (target − bootstrap) × maturity
 *                + RegimeDetector.scoreFloorDelta()
 *                + LaneExpectancyDamper.admissionScoreFloorDelta(lane)
 *   waitFloor  = floor + WAIT_PROMOTION_MARGIN (7243's 55 − 30 gap, kept
 *                relative so it moves with the floor).
 *
 * A cold lane therefore trades from the governor's minimum and earns its
 * way toward the learned boundary; a bleeding lane is raised by the damper
 * and the regime; a proven low-band lane is lowered by its own closes. The
 * fixed 30 survives only as the default target and as the comparison the
 * report prints, so the operator can see where fluid and fixed disagree.
 */
object CanonicalEntryFloor7266 {

    /** 7243's mature boundary — the target when a lane has proven nothing yet. */
    const val MATURE_DEFAULT_FLOOR_7243 = 30.0

    /** 7243's WAIT-promotion gap (55 − 30), kept relative to the fluid floor. */
    const val WAIT_PROMOTION_MARGIN_7243 = 25.0

    /** Closes a 10-point bucket needs before it can move the target. */
    private const val LEARNED_MIN_SAMPLES = 15

    private const val FLOOR_MIN = 0.0
    private const val FLOOR_MAX = 60.0

    data class Resolution(
        val lane: String,
        val floor: Double,
        val waitFloor: Double,
        val bootstrap: Double,
        val target: Double,
        val learnedFloor: Double?,
        val maturity: Double,
        val closes: Int,
        val regimeDelta: Double,
        val damperDelta: Double,
    ) {
        val compact: String
            get() = "lane=$lane floor=${"%.1f".format(floor)} wait=${"%.1f".format(waitFloor)} " +
                "boot=${"%.1f".format(bootstrap)} target=${"%.1f".format(target)} " +
                "learned=${learnedFloor?.let { "%.0f".format(it) } ?: "-"} n=$closes " +
                "maturity=${"%.2f".format(maturity)} regime=${"%+.0f".format(regimeDelta)} damper=${"%+.0f".format(damperDelta)}"
    }

    private val lastByLane = ConcurrentHashMap<String, Resolution>()
    private val resolves = AtomicLong(0L)
    private val belowMature = AtomicLong(0L)
    private val aboveMature = AtomicLong(0L)

    private fun learnedFloor(lane: String): Double? = try {
        var found: Double? = null
        var bucket = 0
        while (bucket <= 9) {
            val score = bucket * 10
            val n = ScoreExpectancyTracker.bucketSamples(lane, score)
            if (n >= LEARNED_MIN_SAMPLES) {
                val mean = ScoreExpectancyTracker.bucketMean(lane, score)
                if (mean != null && mean.isFinite() && mean > 0.0) { found = score.toDouble(); break }
            }
            bucket++
        }
        found
    } catch (_: Throwable) { null }

    fun resolve(rawLane: String?): Resolution {
        val lane = rawLane?.trim()?.uppercase()?.takeIf { it.isNotBlank() } ?: "STANDARD"
        val bootstrap = try {
            LiveEntrySafetyHold.minLiveCandidateScore.takeIf { it.isFinite() } ?: 15.0
        } catch (_: Throwable) { 15.0 }.coerceIn(FLOOR_MIN, MATURE_DEFAULT_FLOOR_7243)
        val learned = learnedFloor(lane)
        val target = learned ?: MATURE_DEFAULT_FLOOR_7243
        val closes = try { LaneExpectancyDamper.sameModeCloses7265(lane) } catch (_: Throwable) { 0 }
        val maturity = (closes.toDouble() /
            (closes.toDouble() + LaneExpectancyDamper.MATURE_EVIDENCE_CLOSES_7265.toDouble())).coerceIn(0.0, 1.0)
        val regimeDelta = try { RegimeDetector.scoreFloorDelta().toDouble() } catch (_: Throwable) { 0.0 }
        val damperDelta = try { LaneExpectancyDamper.admissionScoreFloorDelta(lane) } catch (_: Throwable) { 0.0 }
        val floor = (bootstrap + (target - bootstrap) * maturity + regimeDelta + damperDelta)
            .coerceIn(FLOOR_MIN, FLOOR_MAX)
        val r = Resolution(
            lane = lane, floor = floor, waitFloor = floor + WAIT_PROMOTION_MARGIN_7243,
            bootstrap = bootstrap, target = target, learnedFloor = learned, maturity = maturity,
            closes = closes, regimeDelta = regimeDelta, damperDelta = damperDelta,
        )
        lastByLane[lane] = r
        resolves.incrementAndGet()
        try {
            if (floor < MATURE_DEFAULT_FLOOR_7243 - 0.5) {
                belowMature.incrementAndGet()
                PipelineHealthCollector.labelInc("CANONICAL_FLOOR_FLUID_BELOW_MATURE_7266")
            } else if (floor > MATURE_DEFAULT_FLOOR_7243 + 0.5) {
                aboveMature.incrementAndGet()
                PipelineHealthCollector.labelInc("CANONICAL_FLOOR_FLUID_ABOVE_MATURE_7266")
            }
        } catch (_: Throwable) {}
        return r
    }

    fun statusLine(): String = try {
        val lanes = lastByLane.values.sortedBy { it.lane }
            .joinToString(" · ") { "${it.lane}=${"%.0f".format(it.floor)}/${"%.0f".format(it.waitFloor)}(n=${it.closes}${it.learnedFloor?.let { l -> " L${"%.0f".format(l)}" } ?: ""})" }
        "resolves=${resolves.get()} belowMature=${belowMature.get()} aboveMature=${aboveMature.get()} " +
            "mature=${"%.0f".format(MATURE_DEFAULT_FLOOR_7243)}/${"%.0f".format(MATURE_DEFAULT_FLOOR_7243 + WAIT_PROMOTION_MARGIN_7243)} " +
            (if (lanes.isBlank()) "lanes=none_yet" else lanes)
    } catch (_: Throwable) { "CanonicalEntryFloor7266: unavailable" }
}

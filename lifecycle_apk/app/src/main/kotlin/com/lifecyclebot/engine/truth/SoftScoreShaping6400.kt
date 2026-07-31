package com.lifecyclebot.engine.truth

import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6400 — SOFT SCORE SHAPING.
 *
 * Score is a SIGNAL, not an eligibility gate. This module converts a
 * raw score into a bounded size multiplier / soft-confidence value so
 * downstream sizing consumers can shape the trade without ever
 * blocking eligibility.
 *
 * Missing / null score fails OPEN (neutral shaping) — never a hard reject.
 *
 * The forbiddenScoreFloorRejectCount counter is incremented by any
 * regression that attempts to reintroduce a hard score-floor gate.
 * Bundle6400 asserts this remains zero.
 */
object SoftScoreShaping6400 {

    const val SCORE_POLICY: String = "SOFT_SHAPING_ONLY"

    val forbiddenScoreFloorRejectCount = AtomicLong(0L)
    val lowScoreAllowedCount = AtomicLong(0L)
    val lowScoreShapedCount = AtomicLong(0L)
    val lowScoreMechanicalBlockCount = AtomicLong(0L)

    data class Shaping(
        val mint: String, val symbol: String, val lane: String,
        val rawScore: Double, val scoreAvailable: Boolean,
        val referenceFloor: Double,
        val sizeMultiplier: Double,
        val softConfidence: Double,
        val softSignals: List<String>,
    )

    @Volatile private var lastShapingByMint: Map<String, Shaping> = emptyMap()

    /**
     * Compute a bounded soft size multiplier from the raw score. Missing
     * (NaN / non-finite) score returns a neutral 0.55 — never zero, never
     * rejection.
     */
    fun sizeMultiplierFor(rawScore: Double?): Double {
        if (rawScore == null || !rawScore.isFinite()) return 0.55
        return when {
            rawScore <= 0.0 -> 0.35
            rawScore < 5.0  -> 0.45
            rawScore < 10.0 -> 0.60
            rawScore < 20.0 -> 0.80
            else            -> 1.00
        }
    }

    fun softConfidenceFor(rawScore: Double?): Double {
        if (rawScore == null || !rawScore.isFinite()) return 0.5
        return (rawScore / 30.0).coerceIn(0.10, 1.00)
    }

    fun publish(
        mint: String, symbol: String, lane: String,
        rawScore: Double, referenceFloor: Double,
    ): Shaping {
        val available = rawScore.isFinite()
        val sizeMult = sizeMultiplierFor(if (available) rawScore else null)
        val conf = softConfidenceFor(if (available) rawScore else null)
        val signals = mutableListOf<String>()
        if (!available) signals += "SCORE_UNAVAILABLE"
        if (available && rawScore < referenceFloor) signals += "LOW_SCORE_SIZE_REDUCTION"
        val s = Shaping(mint, symbol, lane, rawScore, available,
            referenceFloor, sizeMult, conf, signals.toList())
        lastShapingByMint = lastShapingByMint + (mint to s)
        // Every LOW_SCORE case counts as an ALLOWED (never a rejection).
        if (available && rawScore < referenceFloor) {
            lowScoreAllowedCount.incrementAndGet()
            lowScoreShapedCount.incrementAndGet()
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LOW_SCORE_ALLOWED_SHAPED_6400")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "LIVE_ENTRY_DECISION_SHAPED_6400",
                    "mint=${mint.take(10)} sym=$symbol lane=$lane score=${rawScore.toInt()} " +
                    "ref=${referenceFloor.toInt()} decision=ALLOW_SHAPED " +
                    "sizeMult=${"%.2f".format(sizeMult)} conf=${"%.2f".format(conf)} " +
                    "signals=${signals.joinToString(",")}",
                )
            } catch (_: Throwable) {}
        }
        return s
    }

    fun lastShaping(mint: String): Shaping? = lastShapingByMint[mint]

    /**
     * Regression trip-wire. Any callsite that would emit a hard
     * SCORE_BELOW_LIVE_FLOOR-style rejection must call this instead of
     * emitting the rejection. The counter is a mandatory-zero invariant.
     */
    fun reportForbiddenScoreFloorReject(mint: String, callsite: String, floor: Double, score: Double) {
        forbiddenScoreFloorRejectCount.incrementAndGet()
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FORBIDDEN_SCORE_FLOOR_REJECT_6400")
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                "FORBIDDEN_SCORE_FLOOR_REJECT_6400",
                "REGRESSION mint=${mint.take(10)} callsite=$callsite score=${score.toInt()} floor=${floor.toInt()}"
            )
        } catch (_: Throwable) {}
    }

    /** Mechanical minimum executable amount was violated post-shaping. */
    fun recordMechanicalMinBlock() { lowScoreMechanicalBlockCount.incrementAndGet() }

    internal fun clearAllForTest() {
        forbiddenScoreFloorRejectCount.set(0L)
        lowScoreAllowedCount.set(0L)
        lowScoreShapedCount.set(0L)
        lowScoreMechanicalBlockCount.set(0L)
        lastShapingByMint = emptyMap()
    }
}

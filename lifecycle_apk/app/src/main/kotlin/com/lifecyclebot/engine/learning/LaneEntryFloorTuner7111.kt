package com.lifecyclebot.engine.learning

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.engine.ScoreExpectancyTracker

/**
 * V5.0.7111 §THE_ENTRY_FLOOR_COULD_ONLY_EVER_GO_UP.
 *
 * LaneExitTuner (V5.9.1379) closed the loop on the EXIT ladder: it reads how a
 * lane actually performed and moves that lane's take-profit and stop by bounded
 * multipliers, in BOTH directions. There has never been an equivalent for the
 * ENTRY bar, and the composition site in BotService shows why that matters —
 * every term in it is a raise:
 *
 *     entryScoreTightenedFloor4591Tuned6984 =
 *           entryScoreTightenedFloor4591Base
 *         + qualityWrRaise6044          raise
 *         + moonshotVolumeRelief6044    -5.0, hardcoded, MOONSHOT only
 *         + streakFloorDelta6961        0 / +8 / +15, coerceIn(0.0, 15.0)
 *
 * One hardcoded relief for one lane under one condition, and otherwise a
 * one-way ratchet. That is the exact shape of the defect V5.0.7091 removed from
 * ExplorationBudget: a gate that can only tighten eventually seals itself shut,
 * and nothing in the system can reopen it on evidence.
 *
 * It also cannot move toward the money. The operator's 5.0.7106 snapshot has
 * the bot computing its own answer and having nowhere to put it:
 *
 *     Optimal entry score: 66-75          (Performance analytics)
 *     LIVE ENTRY AUTHORITY: minScore=15   (what it actually admits at)
 *
 * WHAT THIS DOES. For each lane, find the LOWEST score band that has actually
 * made money in that lane, and put the floor there. If no band has made money,
 * put the floor above the highest band that has demonstrably lost money. If
 * there is no evidence either way, do nothing at all.
 *
 * That is "trade where the money is", expressed as arithmetic over outcomes the
 * bot already records.
 *
 * NO NEW STORE, AND THAT IS DELIBERATE. ScoreExpectancyTracker already keys a
 * persisted 200-sample rolling window of realised pnl% by (lane, score/10), fed
 * from terminal closes. This is a PURE FUNCTION of that window. A second store
 * over the same fact is the duplicate-authority defect this session has spent a
 * dozen builds removing, and a pure function cannot drift from its source,
 * cannot need its own persistence, and cannot disagree with the tracker the
 * oracle reads.
 *
 * DOCTRINE:
 *  · BIDIRECTIONAL. This is the whole point. It may LOWER a floor onto a band
 *    that is proving profitable, not only raise it away from one that is not.
 *  · BOUNDED, both ways. The delta is clamped and the caller already coerces
 *    the composed floor into 0..95, so no lane can ever be floored out of
 *    existence. Never disables a lane, per standing doctrine.
 *  · HYSTERESIS, NOT A HAIR TRIGGER. A band must be clearly profitable to be
 *    lowered onto and clearly unprofitable to be raised above. A band hovering
 *    at zero moves nothing, so the floor does not oscillate on noise.
 *  · BOOTSTRAP-SAFE AND FAIL-OPEN. No evidence returns 0.0, so a fresh install
 *    behaves exactly as it does today; any exception returns 0.0.
 */
object LaneEntryFloorTuner7111 {

    /** Matches ScoreExpectancyTracker's bucket width — one authority over the banding. */
    private const val BUCKET_WIDTH = 10
    private const val BANDS = 10

    /** Closes in a band before its mean is evidence rather than noise. */
    private const val MIN_SAMPLE_7111 = 12

    /**
     * Hysteresis. A band must beat +2% mean to be worth lowering ONTO, and be
     * worse than -2% to be worth raising ABOVE. Between the two the band is
     * undecided and contributes nothing, which is what stops the floor
     * oscillating around zero as a rolling window turns over.
     */
    private const val PROFIT_MARGIN_PCT = 2.0
    private const val LOSS_MARGIN_PCT = -2.0

    /** How far the tuner may move a lane's floor in either direction. */
    private const val DELTA_MIN = -15.0
    private const val DELTA_MAX = 25.0

    data class Verdict7111(
        val delta: Double,
        val targetFloor: Int,
        val reason: String,
        val evidenceBands: Int,
    )

    /**
     * The bounded additive delta for this lane's entry score floor, with the
     * reasoning that produced it.
     *
     * There is deliberately no bare `floorDelta()` convenience wrapper. A caller
     * that moves a live entry bar should have the reason in hand at the moment
     * it moves it — that is what makes LANE_ENTRY_FLOOR_TUNED_7111 traceable to
     * the band that caused it rather than to a number that appeared.
     *
     * @param baseFloor the floor as already composed by the caller, so the delta
     *   is expressed relative to what the runtime would otherwise use.
     */
    fun verdict7111(lane: String, baseFloor: Double): Verdict7111 {
        val laneKey = lane.trim().uppercase()
        if (laneKey.isBlank()) return Verdict7111(0.0, baseFloor.toInt(), "BLANK_LANE", 0)

        var lowestProfitableBand = -1
        var highestLosingBand = -1
        var evidenced = 0
        for (band in 0 until BANDS) {
            val probeScore = band * BUCKET_WIDTH
            val n = try { ScoreExpectancyTracker.bucketSamples(laneKey, probeScore) } catch (_: Throwable) { 0 }
            if (n < MIN_SAMPLE_7111) continue
            val mean = try { ScoreExpectancyTracker.bucketRawMean6715(laneKey, probeScore) } catch (_: Throwable) { null }
                ?: continue
            evidenced++
            if (mean >= PROFIT_MARGIN_PCT && lowestProfitableBand < 0) lowestProfitableBand = band
            if (mean <= LOSS_MARGIN_PCT) highestLosingBand = band
        }

        if (evidenced == 0) {
            return Verdict7111(0.0, baseFloor.toInt(), "NO_EVIDENCE_NEUTRAL", 0)
        }

        val target: Int
        val reason: String
        when {
            // Money has been made down here. Put the bar where the money is,
            // even if that means lowering it — the case the old composition
            // could not express at all.
            lowestProfitableBand >= 0 -> {
                target = lowestProfitableBand * BUCKET_WIDTH
                reason = "LOWEST_PROFITABLE_BAND_S$target"
            }
            // Nothing is proven profitable, but something is proven to lose.
            // Stand above it rather than guessing a number.
            highestLosingBand >= 0 -> {
                target = ((highestLosingBand + 1) * BUCKET_WIDTH).coerceAtMost(95)
                reason = "ABOVE_HIGHEST_LOSING_BAND_S${highestLosingBand * BUCKET_WIDTH}"
            }
            // Bands have samples but every one of them is inside the
            // hysteresis band. Undecided is not a verdict; do nothing.
            else -> {
                return Verdict7111(0.0, baseFloor.toInt(), "ALL_BANDS_UNDECIDED", evidenced)
            }
        }

        val delta = (target - baseFloor).coerceIn(DELTA_MIN, DELTA_MAX)
        return Verdict7111(delta, target, reason, evidenced)
    }

    /**
     * Emit the decision once per composition so a floor that moved can be
     * traced to the band that moved it. Separated from [floorDelta7111] so the
     * hot path can stay silent if a caller ever needs it to.
     */
    fun noteApplied7111(lane: String, baseFloor: Double, v: Verdict7111) {
        if (v.delta == 0.0) return
        try {
            PipelineHealthCollector.labelInc("LANE_ENTRY_FLOOR_TUNED_7111")
            PipelineHealthCollector.labelInc(
                if (v.delta < 0.0) "LANE_ENTRY_FLOOR_LOWERED_7111_${lane.uppercase()}".take(60)
                else "LANE_ENTRY_FLOOR_RAISED_7111_${lane.uppercase()}".take(60)
            )
            ForensicLogger.lifecycle(
                "LANE_ENTRY_FLOOR_TUNED_7111",
                "lane=${lane.uppercase()} base=${"%.1f".format(baseFloor)} " +
                    "delta=${"%+.1f".format(v.delta)} target=${v.targetFloor} " +
                    "reason=${v.reason} evidencedBands=${v.evidenceBands}",
            )
        } catch (_: Throwable) {}
    }

    /**
     * V5.0.7111 — operator report: what each lane's realised bands are saying.
     *
     * V5.0.7113: block body, not `= try { ... }`. An expression body cannot
     * contain `return`, and the early-out for an empty lane list was one. That
     * is what turned 7111 red and, because 7112 stacked on the same file, 7112
     * with it.
     */
    fun statusLine7113(lanes: List<String>): String {
        return try {
            if (lanes.isEmpty()) return "lanes=0"
            val parts = lanes.mapNotNull { lane ->
                val v = verdict7111(lane, 0.0)
                if (v.evidenceBands == 0) null
                else "${lane.uppercase().take(14)}:target=${v.targetFloor}(${v.reason.take(28)},bands=${v.evidenceBands})"
            }
            if (parts.isEmpty()) "lanes=${lanes.size} evidenced=0 (all bootstrap)"
            else "evidenced=${parts.size}/${lanes.size} ${parts.joinToString(" ")}"
        } catch (t: Throwable) { "unavailable(${t.javaClass.simpleName})" }
    }
}

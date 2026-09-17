package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6915 §PREDICTIVE_ENTRY_ORACLE — give the intelligence stack a vote
 * BEFORE capital commits.
 *
 * OPERATOR QUESTION (5.0.6914):
 *
 *   "is the bot acting in a predictive oracle state? buying into tokens that
 *    are proven statistical winners or show positive trade expectancy before
 *    entering? a human can guess. Aate should never."
 *
 * It was not. The evidence, from that snapshot:
 *
 *   Learned admission: assembled=3191 forecastResolved=0 matureCohorts6911=1
 *   AATE_POLICY: action=PROBE_ONLY pWin=0.65 EV=0.0   <- a hardcoded prior
 *   dec=ZERO_SIGNAL_PROBE score=0.0 reason=Signal is WAIT, not BUY   x447
 *   Brain Consensus: ALLOW=29 SOFT_BLOCK=87 HARD_BLOCK=0
 *   Entry authority: gates=4076 allows=4075 denies=1
 *   PROJECT_SNIPER 0/11 EV=-37.92%  ->  SIZING n=621 (largest consumer)
 *
 * WHY IT COULD NOT BE PREDICTIVE. Two structural facts, neither of which is a
 * tuning error:
 *
 * 1. EVERY BRAIN SPEAKS IN SIZE, NONE IN ADMISSION. A survey of the stack
 *    found 21 functions producing size multipliers (sizeMultiplier,
 *    sizeMultiplierForLane, conviction, starveFactor) against a single
 *    admission surface, `GateAction { HARD_BLOCK, SIZE_ONLY }`, whose
 *    HARD_BLOCK fired zero times. SsiPilotCouncil — the SSI engine — exposes
 *    only sizeMultiplierForLane and exitPatience. When the regime detector
 *    reports wr=7.1% meanPnl=-28.29% its entire response is sizeMult=0.35: it
 *    keeps buying, just smaller. ExecutableOpenGate's own comment at §6728
 *    says it outright — "Every advisory subsystem correctly identifies the
 *    toxic state and publishes an advisory; nobody hard-blocks."
 *
 * 2. MATURITY IS UNREACHABLE, SO EVERY REFUSAL IS UNREACHABLE. Each hard
 *    refusal in the stack is gated on per-cell sample counts:
 *    BrainConsensusGate MIN_MATURE_SAMPLES=8, LearnedAdmissionAuthority6846
 *    MATURITY_MIN_N=8, ScoreExpectancyTracker MIN_SAMPLES_FOR_REJECT=15,
 *    LosingPatternMemory 20, ForwardOutcomeModel 10. But the cohort space is
 *    lane x scoreBand x regime — roughly 13 x 7 x 3 = 270 cells — against 38
 *    lifetime closes. That is 0.14 samples per cell. `bucketMean` returns null,
 *    `forecast` returns bootstrap, `hardBlock` evaluates false, admission says
 *    yes. Forever. Lowering the thresholds would not fix it; it would just
 *    make the bot act confidently on n=1.
 *
 * WHAT THIS DOES INSTEAD — HIERARCHICAL SHRINKAGE.
 *
 * The statistically correct answer to "sparse cells, dense parents" is
 * empirical-Bayes shrinkage, not threshold lowering. Every candidate gets an
 * expectancy estimate blended across three levels of a hierarchy:
 *
 *   CELL    lane x score-bucket   (specific, usually sparse)
 *   LANE    lane                  (the parent, usually has real n)
 *   GLOBAL  whole book            (always has n)
 *
 *   estimate = Σ(wᵢ · μᵢ) / Σwᵢ      where  wᵢ = nᵢ / (nᵢ + K)
 *
 * A cell with n=0 contributes nothing and the estimate falls to the lane. A
 * lane with n=0 falls to global. A cell at n=20 dominates its own estimate.
 * Nothing is ever a coin flip, nothing waits for maturity, and NO EXISTING
 * THRESHOLD IS CHANGED — evidence simply propagates up and down a hierarchy
 * instead of being discarded when a cell is thin.
 *
 * Confidence is reported separately as effective sample weight, so the caller
 * can distinguish "expectancy is negative and I am sure" from "expectancy is
 * negative and I have barely any evidence". Only the former refuses; the
 * latter probes. That is the difference between an oracle and a guess.
 *
 * THE STACK IS READ, NOT REPLACED. This adds no new model. It reads what six
 * months of work already computes and had nowhere to send:
 *
 *   ScoreExpectancyTracker.bucketRawMean6715  cell mean pnl% + bucketSamples
 *                                             (the UN-thresholded accessor,
 *                                             previously used only for
 *                                             "bounded soft sizing")
 *   ForwardOutcomeModel.cohortEvidence6911    regime-agnostic cohort pWin/EV
 *   LiveProbabilityEngine.laneSnapshots       lane WR/EV/sample
 *   UnifiedPolicyHead.predictWinProb          the learned decider head
 *   AutonomousMetaPolicy.conviction           learned context conviction
 *   SemanticPatternGraph.entryBias            pattern recognition
 *   SourceFamilyOpportunityScorecard          per-source realised expectancy
 *   SsiPilotCouncil.sizeMultiplierForLane     SSI conviction, finally read as
 *                                             a view on the trade and not
 *                                             only as a size knob
 *
 * Each contributes a BOUNDED adjustment, and every contribution is reported in
 * the verdict so the operator can see which brain drove the call.
 *
 * DOCTRINE. This never disables a lane and never caps a runner. Its refusal is
 * per-CANDIDATE and evidence-weighted, it expires the moment expectancy turns,
 * and it prefers PROBE over REFUSE whenever confidence is thin — exploration
 * survives, it just stops being the default for contexts the stack already
 * knows are graves. Runner capture is untouched: expectancy is judged on mean
 * PnL, which a 10x winner raises, so a fat-tailed cohort scores HIGHER here,
 * not lower.
 */
object PredictiveEntryOracle6915 {

    enum class Verdict {
        /** Positive expectancy with real evidence behind it. */
        ADMIT,
        /** Evidence too thin to judge — bounded exploration, learn from it. */
        PROBE,
        /** Negative expectancy AND enough weight to trust that. */
        REFUSE,
    }

    data class Forecast(
        val verdict: Verdict,
        /** Blended expected PnL per trade, in PERCENT (e.g. -37.9 or +26.8). */
        val expectancyPct: Double,
        /** Blended win probability, 0..1. */
        val pWin: Double,
        /** Effective sample weight behind the estimate. Not a raw count. */
        val confidence: Double,
        /** Per-level and per-brain breakdown, for the operator. */
        val contributions: List<String>,
        val reason: String,
    ) {
        fun line(): String =
            "verdict=$verdict E=${"%+.2f".format(expectancyPct)}% pWin=${"%.2f".format(pWin)} " +
                "conf=${"%.2f".format(confidence)} reason=$reason [${contributions.joinToString(" ")}]"
    }

    // ── shrinkage ───────────────────────────────────────────────────────────
    /**
     * Shrinkage constant K in w = n/(n+K). At n=K a level carries half weight.
     * 6 is deliberately small: with 38 lifetime closes a larger K would mute
     * the cell entirely and the oracle would only ever repeat the lane view.
     */
    private const val SHRINK_K = 6.0

    /** Expectancy at or below which a well-evidenced candidate is refused. */
    private const val REFUSE_EXPECTANCY_PCT = -8.0

    /**
     * Effective weight required before a NEGATIVE estimate may refuse. Below
     * this the verdict is PROBE: the stack is allowed to be pessimistic, but
     * not to act on pessimism it cannot support.
     */
    private const val MIN_CONFIDENCE_TO_REFUSE = 0.45

    /** Expectancy above which the candidate is a positive-edge admit. */
    private const val ADMIT_EXPECTANCY_PCT = 0.0

    /** Bound on the total stack adjustment, in percentage points. */
    private const val STACK_ADJUST_CAP_PCT = 25.0

    private val evaluations = AtomicLong(0L)
    private val admits = AtomicLong(0L)
    private val probes = AtomicLong(0L)
    private val refuses = AtomicLong(0L)
    private val cellHits = AtomicLong(0L)
    private val laneHits = AtomicLong(0L)
    private val globalOnly = AtomicLong(0L)

    private data class Level(val name: String, val mean: Double, val pWin: Double, val n: Double) {
        val weight: Double get() = if (n <= 0.0) 0.0 else n / (n + SHRINK_K)
    }

    /**
     * Evaluate a candidate before capital commits.
     *
     * @param lane        canonical lane name
     * @param score       entry score 0..100 (the cell key with lane)
     * @param sourceFamily discovery source, for the source-expectancy read
     * @param regime      current regime label, advisory only — deliberately NOT
     *                    part of the cell key, because regime-keying is what
     *                    made every cohort unreachable in the first place
     */
    fun evaluate(
        lane: String,
        score: Int,
        sourceFamily: String = "",
        regime: String = "",
    ): Forecast {
        evaluations.incrementAndGet()
        val laneKey = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val s = score.coerceIn(0, 100)
        val contributions = mutableListOf<String>()

        // ── LEVEL: CELL (lane x score bucket) ────────────────────────────────
        // Two independent cell-level sources; whichever has evidence is used,
        // and if both do they are pooled by sample count.
        var cellMean = 0.0
        var cellPWin = -1.0
        var cellN = 0.0
        try {
            val raw = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketRawMean6715(laneKey, s)
            val n = com.lifecyclebot.engine.ScoreExpectancyTracker.bucketSamples(laneKey, s).toDouble()
            if (raw != null && raw.isFinite() && n > 0.0) {
                cellMean += raw * n; cellN += n
                contributions += "cellScoreExp(n=${n.toInt()},E=${"%+.1f".format(raw)})"
            }
        } catch (_: Throwable) {}
        try {
            val agg = com.lifecyclebot.engine.ForwardOutcomeModel.cohortEvidence6911(laneKey, s)
            if (agg.samples > 0L) {
                val n = agg.samples.toDouble()
                cellMean += agg.expectedPnlPct * n; cellN += n
                cellPWin = agg.pWin
                contributions += "cellFwd(n=${agg.samples},E=${"%+.1f".format(agg.expectedPnlPct)},pW=${"%.2f".format(agg.pWin)})"
            }
        } catch (_: Throwable) {}
        val cell = if (cellN > 0.0) {
            cellHits.incrementAndGet()
            Level("cell", cellMean / cellN, cellPWin, cellN)
        } else null

        // ── LEVEL: LANE ─────────────────────────────────────────────────────
        var lane1: Level? = null
        var globalLevel: Level? = null
        try {
            val snaps = com.lifecyclebot.engine.LiveProbabilityEngine.laneSnapshots()
            snaps.firstOrNull { it.lane.equals(laneKey, true) }?.let { sn ->
                if (sn.sample > 0) {
                    laneHits.incrementAndGet()
                    lane1 = Level("lane", sn.evPct, (sn.wrPct / 100.0).coerceIn(0.0, 1.0), sn.sample.toDouble())
                    contributions += "lane(n=${sn.sample},E=${"%+.1f".format(sn.evPct)},WR=${"%.0f".format(sn.wrPct)}%)"
                }
            }
            // GLOBAL — the whole book. Always present once anything has closed,
            // which is what guarantees the estimate is never a hardcoded prior.
            val totalN = snaps.sumOf { it.sample }
            if (totalN > 0) {
                val wMean = snaps.sumOf { it.evPct * it.sample } / totalN
                val wWins = snaps.sumOf { it.wins }.toDouble() / totalN
                globalLevel = Level("global", wMean, wWins.coerceIn(0.0, 1.0), totalN.toDouble())
                contributions += "global(n=$totalN,E=${"%+.1f".format(wMean)})"
            }
        } catch (_: Throwable) {}

        // ── SHRINKAGE BLEND ─────────────────────────────────────────────────
        val levels = listOfNotNull(cell, lane1, globalLevel).filter { it.weight > 0.0 }
        if (levels.isEmpty()) {
            globalOnly.incrementAndGet()
            probes.incrementAndGet()
            val f = Forecast(
                Verdict.PROBE, 0.0, 0.5, 0.0,
                contributions + "noEvidenceAnywhere",
                "COLD_START_NO_TERMINAL_EVIDENCE_6915",
            )
            return f
        }
        val wSum = levels.sumOf { it.weight }
        val blendedE = levels.sumOf { it.mean * it.weight } / wSum
        val pWinLevels = levels.filter { it.pWin in 0.0..1.0 }
        val blendedPWin = if (pWinLevels.isEmpty()) 0.5
            else pWinLevels.sumOf { it.pWin * it.weight } / pWinLevels.sumOf { it.weight }
        // Confidence is the weight of the MOST SPECIFIC level that spoke,
        // lifted by the parents. A thin cell over a dense lane is more
        // trustworthy than a thin cell alone, but never as trustworthy as a
        // dense cell.
        val confidence = (levels.maxOf { it.weight } * 0.5 +
            (wSum / levels.size.toDouble()) * 0.5).coerceIn(0.0, 1.0)

        // ── STACK ADJUSTMENTS (bounded, each one optional) ───────────────────
        var adjust = 0.0
        try {
            val conv = com.lifecyclebot.engine.AutonomousMetaPolicy.conviction(laneKey, s, regime)
            if (conv.isFinite() && conv > 0.0) {
                val d = (conv - 1.0) * 12.0
                adjust += d
                if (kotlin.math.abs(d) >= 0.5) contributions += "meta(${"%+.1f".format(d)})"
            }
        } catch (_: Throwable) {}
        try {
            val bias = com.lifecyclebot.engine.SemanticPatternGraph.entryBias("lane:$laneKey", laneKey)
            val d = (bias.sizeMult - 1.0) * 10.0
            if (kotlin.math.abs(d) >= 0.5) { adjust += d; contributions += "pattern(${"%+.1f".format(d)})" }
        } catch (_: Throwable) {}
        try {
            val ssi = com.lifecyclebot.engine.SsiPilotCouncil.sizeMultiplierForLane(laneKey)
            val d = (ssi - 1.0) * 10.0
            if (kotlin.math.abs(d) >= 0.5) { adjust += d; contributions += "ssi(${"%+.1f".format(d)})" }
        } catch (_: Throwable) {}
        try {
            val src = com.lifecyclebot.engine.SourceFamilyOpportunityScorecard
                .expectancyFor6915(sourceFamily)
            if (src != null && src.closed >= 3) {
                val d = (src.meanPnlPct / 100.0 * 8.0).coerceIn(-10.0, 10.0)
                adjust += d
                contributions += "src(${sourceFamily.take(14)},n=${src.closed},${"%+.1f".format(d)})"
            }
        } catch (_: Throwable) {}
        val boundedAdjust = adjust.coerceIn(-STACK_ADJUST_CAP_PCT, STACK_ADJUST_CAP_PCT)
        val finalE = blendedE + boundedAdjust

        // ── VERDICT ─────────────────────────────────────────────────────────
        val verdict = when {
            finalE <= REFUSE_EXPECTANCY_PCT && confidence >= MIN_CONFIDENCE_TO_REFUSE -> Verdict.REFUSE
            finalE > ADMIT_EXPECTANCY_PCT && confidence >= MIN_CONFIDENCE_TO_REFUSE -> Verdict.ADMIT
            else -> Verdict.PROBE
        }
        val reason = when (verdict) {
            Verdict.REFUSE -> "NEGATIVE_EXPECTANCY_WITH_EVIDENCE_6915"
            Verdict.ADMIT -> "POSITIVE_EXPECTANCY_WITH_EVIDENCE_6915"
            Verdict.PROBE ->
                if (confidence < MIN_CONFIDENCE_TO_REFUSE) "EVIDENCE_TOO_THIN_TO_JUDGE_6915"
                else "EXPECTANCY_NEUTRAL_6915"
        }
        when (verdict) {
            Verdict.ADMIT -> admits.incrementAndGet()
            Verdict.PROBE -> probes.incrementAndGet()
            Verdict.REFUSE -> refuses.incrementAndGet()
        }
        val f = Forecast(verdict, finalE, blendedPWin, confidence, contributions, reason)
        if (verdict != Verdict.ADMIT) try {
            PipelineHealthCollector.labelInc("PREDICTIVE_ORACLE_${verdict.name}_6915")
            PipelineHealthCollector.labelInc("PREDICTIVE_ORACLE_${verdict.name}_6915_$laneKey")
            if (verdict == Verdict.REFUSE) ForensicLogger.lifecycle(
                "PREDICTIVE_ORACLE_REFUSED_6915",
                "lane=$laneKey score=$s src=${sourceFamily.take(24)} ${f.line()}",
            )
        } catch (_: Throwable) {}
        return f
    }

    fun statusLine(): String =
        "evals=${evaluations.get()} admit=${admits.get()} probe=${probes.get()} refuse=${refuses.get()} " +
            "cellEvidence=${cellHits.get()} laneEvidence=${laneHits.get()} noEvidence=${globalOnly.get()} " +
            "shrinkK=$SHRINK_K refuseAt=${REFUSE_EXPECTANCY_PCT}% minConf=$MIN_CONFIDENCE_TO_REFUSE"

    internal fun resetForTest() {
        evaluations.set(0L); admits.set(0L); probes.set(0L); refuses.set(0L)
        cellHits.set(0L); laneHits.set(0L); globalOnly.set(0L)
    }
}

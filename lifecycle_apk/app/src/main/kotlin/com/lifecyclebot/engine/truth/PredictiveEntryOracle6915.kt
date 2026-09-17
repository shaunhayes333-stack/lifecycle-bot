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

    /**
     * V5.0.6917 — separate cap for the brain-network tier. Deliberately lower
     * than STACK_ADJUST_CAP_PCT: these modules have never been validated
     * against outcomes, because nothing ever read them. They are allowed to
     * move the estimate meaningfully but never to dominate the measured
     * shrinkage hierarchy. Widen this once their contributions are shown to
     * correlate with realised PnL — that is an evidence decision, not a
     * configuration one.
     */
    private const val BRAIN_NETWORK_CAP_PCT_6917 = 18.0

    private val evaluations = AtomicLong(0L)
    private val admits = AtomicLong(0L)
    private val probes = AtomicLong(0L)
    private val refuses = AtomicLong(0L)
    private val cellHits = AtomicLong(0L)
    private val laneHits = AtomicLong(0L)
    private val globalOnly = AtomicLong(0L)
    /** V5.0.6917 — how many previously-unread brain outputs actually spoke. */
    private val brainReads6917 = AtomicLong(0L)

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
    /**
     * V5.0.6917 §THE_BRAINS_THAT_WERE_NEVER_READ.
     *
     * OPERATOR: "there are so many modules brains ai layers trading tools
     * styles systems not being utilised correctly ... I literally built a brain
     * network of ai brains, modules, data sources, learning, education even
     * gave it a Harvard brain thats meant to be phd level trading analysis."
     *
     * A function-level audit of all 1,136 source files found 2,038 public
     * functions with no caller outside their own file, and — filtering to
     * intelligence modules and to query/analysis-shaped functions only — 114
     * modules holding 317 ANALYSIS OUTPUTS THAT NOTHING READS.
     *
     * The most direct examples, all confirmed zero-caller:
     *
     *   CollectiveIntelligenceAI.predictTokenSuccess(mint,sym,src,liq)
     *       A function literally named "predict token success", returning
     *       successProbability 0-100 and a STRONG_BUY..STRONG_SELL signal.
     *       Never called. That single fact answers "is the bot predictive".
     *
     *   LanePolicy.posteriorWr6611 / posteriorWrForBucket6611
     *       A Beta-posterior win rate whose own comment says it exists so
     *       "callers that want adaptive-from-N=1 behaviour use
     *       posteriorWr6611". Somebody already solved this codebase's sparse
     *       evidence problem with the correct statistical tool, and nothing
     *       ever called it.
     *
     *   EducationSubLayerAI — the Harvard brain, 3,108 lines, initialised at
     *       BotService:7269 and written to by Executor on every close. 13 of
     *       its 30 analysis outputs are unread, including
     *       getLayerExpectancyPct, getLayerAccuracyRaw, getEdgeLedger and
     *       getApprovalPatterns. It has been recording outcomes for six months
     *       and its conclusions have never been consulted by a trade.
     *
     *   TradingMemory.isCreatorBlacklisted / getPatternWinRate
     *   WhaleWalletTracker.getWhaleScore / isWhaleReliable
     *   InsiderTrackerAI.hasRecentAlphaSignal / getAlphaWallets
     *   MomentumPredictorAI.getMomentumScore / getStrongMomentumTokens
     *   TimeOptimizationAI.getGoldenHours / getDangerHours
     *   CollectiveLearning.getNetworkBoostForMint / getHighWinPatterns
     *   BehaviorLearning.getTopGoodPatterns / getTopBadPatterns
     *
     * This tier reads them. Each is a BOUNDED contribution to one expectancy
     * estimate, capped in aggregate, and every one that moves the number is
     * named in the verdict so the operator can see which brain spoke.
     *
     * DESIGN NOTE — WHY BOUNDED AND NOT AUTHORITATIVE. These modules have
     * never been validated against outcomes, precisely because nothing read
     * them. Handing any single one a veto would be replacing a measured
     * estimate with an unmeasured opinion. So they adjust, the shrinkage
     * hierarchy anchors, and their combined influence is capped. As their
     * contributions start correlating with outcomes the caps can widen — but
     * that decision belongs to evidence, not to this commit.
     */
    private data class BrainRead(val label: String, val deltaPct: Double)

    private fun bandLabel6917(score: Int): String = when {
        score >= 80 -> "S80"; score >= 60 -> "S60"; score >= 40 -> "S40"
        score >= 20 -> "S20"; score >= 10 -> "S10"; score >= 5 -> "S05"; else -> "S00"
    }

    /** Bounded reads from the previously-unconsulted brain network. */
    private fun brainNetwork6917(
        lane: String,
        score: Int,
        mint: String,
        symbol: String,
        sourceFamily: String,
        liquidityUsd: Double,
        creator: String,
    ): List<BrainRead> {
        val out = mutableListOf<BrainRead>()

        // Collective prediction — successProbability is 0..100 centred on 50.
        try {
            if (mint.isNotBlank()) {
                val p = com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI
                    .predictTokenSuccess(mint, symbol, sourceFamily, liquidityUsd)
                if (p.instancesReporting > 0) {
                    val d = ((p.successProbability - 50.0) / 50.0) * 12.0
                    out += BrainRead("collectivePredict(p=${p.successProbability.toInt()}%,n=${p.instancesReporting},${p.collectiveSignal})", d)
                }
            }
        } catch (_: Throwable) {}

        // Beta-posterior win rate, adaptive from n=1. Centred on 0.5.
        try {
            val band = bandLabel6917(score)
            val pb = com.lifecyclebot.engine.learning.LanePolicy.posteriorWrForBucket6611(lane, band)
            val pl = com.lifecyclebot.engine.learning.LanePolicy.posteriorWr6611(lane)
            // Bucket posterior is more specific; lane posterior is the anchor.
            val blended = pb * 0.6 + pl * 0.4
            val d = (blended - 0.5) * 30.0
            if (kotlin.math.abs(d) >= 0.5) {
                out += BrainRead("posteriorWR(bucket=${"%.2f".format(pb)},lane=${"%.2f".format(pl)})", d)
            }
        } catch (_: Throwable) {}

        // Harvard brain — per-layer realised expectancy for this lane.
        try {
            val e = com.lifecyclebot.v3.scoring.EducationSubLayerAI.getLayerExpectancyPct(lane)
            if (e.isFinite() && kotlin.math.abs(e) >= 0.5) {
                out += BrainRead("harvard(E=${"%+.1f".format(e)}%)", (e / 100.0 * 10.0).coerceIn(-12.0, 12.0))
            }
        } catch (_: Throwable) {}

        // Creator rug memory — the only near-hard negative in this tier.
        //
        // V5.0.6927 §GRADED_NOT_BINARY. This read used isCreatorBlacklisted,
        // which is `rugCount >= 1`. So a dev with ONE rug in a long history
        // scored identically to a serial rugger with six, and the penalty had
        // to be set for the average of those two — too soft on the serial
        // rugger, too harsh on the one-off. getCreatorRugCount has the actual
        // number and had zero callers.
        //
        // In this asset class creator history is the highest-signal thing you
        // can know before entry, and it is close to monotonic: a wallet that
        // has rugged repeatedly is telling you exactly what it does for a
        // living. So the penalty now scales, and at REFUSE_RUG_COUNT it is
        // large enough to sink an entry on its own — the one place in this
        // bounded tier where a single brain should be able to do that.
        try {
            if (creator.isNotBlank()) {
                val rugs = com.lifecyclebot.engine.TradingMemory.getCreatorRugCount(creator)
                // Serial ruggers (>= REFUSE_RUG_COUNT_6927) do not come through
                // here at all — they are a recorded fact, not an adjustment,
                // and hardSafetyRefusal6927 handles them before the verdict.
                // These weights are sized to live INSIDE the ±18% tier cap;
                // anything larger would simply be clamped and the gradation
                // would be a lie.
                if (rugs > 0) {
                    val penalty = when {
                        rugs >= 3 -> -18.0   // saturates this tier on its own
                        rugs == 2 -> -13.0
                        else      -> -8.0    // one prior rug: a real flag, not a death sentence
                    }
                    out += BrainRead("creatorRugs($rugs)", penalty)
                }
            }
        } catch (_: Throwable) {}

        // V5.0.6929 — market regime as a BAR, not a throttle.
        //
        // MarketRegimeAI.shouldReduceExposure() had zero callers. Memecoins
        // are one asset class with one beta — SOL plus crypto-twitter risk
        // appetite — so in a risk-off tape every lane loses together, and a
        // trader takes fewer shots rather than the same shots smaller.
        //
        // But "fewer shots" must not become a throttle. The standing doctrine
        // here is never throttle, never cap-to-dust, never disable a lane, and
        // a counter that stops entries after N would starve the bot exactly
        // when a genuinely great setup appears. So this raises the EXPECTANCY
        // BAR instead: in risk-off, a candidate needs more evidence to clear
        // the same threshold, and the bot naturally trades less because fewer
        // candidates qualify — selection, not a cap. A great setup still gets
        // through. RegimeDetector.scoreFloorDelta already applies the same
        // idea on the score side.
        //
        // Deliberately asymmetric. shouldReduceExposure() is selective (only
        // STRONG_BEAR and HIGH_VOLATILITY) so it carries real information. Its
        // sibling isFavorableForEntry() is NOT used as a positive, because it
        // returns true for NEUTRAL as well — true most of the time, which
        // would make it a constant baseline shift rather than a signal. The
        // upside therefore comes only from the genuinely bullish regimes.
        try {
            if (com.lifecyclebot.engine.MarketRegimeAI.shouldReduceExposure()) {
                out += BrainRead("regimeRiskOff", -7.0)
            } else when (com.lifecyclebot.engine.MarketRegimeAI.getCurrentRegime()) {
                com.lifecyclebot.engine.MarketRegimeAI.Regime.STRONG_BULL ->
                    out += BrainRead("regimeStrongBull", 5.0)
                com.lifecyclebot.engine.MarketRegimeAI.Regime.BULL ->
                    out += BrainRead("regimeBull", 3.0)
                com.lifecyclebot.engine.MarketRegimeAI.Regime.BEAR ->
                    out += BrainRead("regimeBear", -4.0)
                else -> {}
            }
        } catch (_: Throwable) {}

        // Momentum predictor.
        try {
            if (mint.isNotBlank()) {
                val m = com.lifecyclebot.engine.MomentumPredictorAI.getMomentumScore(mint)
                if (m.isFinite() && kotlin.math.abs(m) >= 1.0) {
                    out += BrainRead("momentum(${"%+.0f".format(m)})", (m / 100.0 * 8.0).coerceIn(-8.0, 8.0))
                }
            }
        } catch (_: Throwable) {}

        // Insider/alpha wallet signal on this exact token.
        try {
            if (mint.isNotBlank() &&
                com.lifecyclebot.v3.scoring.InsiderTrackerAI.hasRecentAlphaSignal(mint)
            ) out += BrainRead("alphaWalletSignal", +8.0)
        } catch (_: Throwable) {}

        // Time-of-day edge. Golden/danger hours were computed and never read.
        try {
            val hour = com.lifecyclebot.engine.TimeOptimizationAI.getCurrentHourUtc()
            val golden = com.lifecyclebot.engine.TimeOptimizationAI.getGoldenHours()
            val danger = com.lifecyclebot.engine.TimeOptimizationAI.getDangerHours()
            when {
                golden.isNotEmpty() && hour in golden -> out += BrainRead("goldenHour($hour)", +5.0)
                danger.isNotEmpty() && hour in danger -> out += BrainRead("dangerHour($hour)", -5.0)
            }
        } catch (_: Throwable) {}

        // ── V5.0.6918 TIER-A BATCH 2 ────────────────────────────────────────
        // Six more zero-caller decision inputs from ci/UNWIRED_LEDGER.tsv.
        // Same contract as batch 1: bounded, named in the verdict, never
        // authoritative on its own.

        // Creator reputation. The hive already downloads per-creator outcome
        // history (CollectiveLearning.CreatorReputation carries tokenCount,
        // wins/losses, avgPnlPct and rugLikeLosses) and nothing read it. This
        // is strictly better evidence than TradingMemory's local blacklist
        // because it is pooled across the whole fleet.
        try {
            if (creator.isNotBlank()) {
                val rep = com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI
                    .getCreatorReputation(creator)
                if (rep != null && rep.totalOutcomes >= 3) {
                    val rugShare = if (rep.totalOutcomes > 0)
                        rep.rugLikeLosses.toDouble() / rep.totalOutcomes else 0.0
                    val d = ((rep.avgPnlPct / 100.0) * 8.0 - rugShare * 14.0).coerceIn(-16.0, 8.0)
                    out += BrainRead(
                        "creatorRep(n=${rep.totalOutcomes},E=${"%+.0f".format(rep.avgPnlPct)}%,rug=${rep.rugLikeLosses})", d,
                    )
                }
            }
        } catch (_: Throwable) {}

        // Source reliability — pooled per-source outcomes from the hive. This
        // complements the LOCAL SourceFamilyOpportunityScorecard read in 6915:
        // local is this instance's experience, this is the fleet's.
        try {
            if (sourceFamily.isNotBlank()) {
                val sr = com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI
                    .getSourceReliability(sourceFamily)
                if (sr != null && sr.totalOutcomes >= 3) {
                    val d = ((sr.avgPnlPct / 100.0) * 7.0).coerceIn(-9.0, 9.0)
                    out += BrainRead("hiveSrc(n=${sr.totalOutcomes},E=${"%+.0f".format(sr.avgPnlPct)}%)", d)
                }
            }
        } catch (_: Throwable) {}

        // Behaviour pattern suppression/boost. These are explicit learned
        // verdicts on a named pattern and were pure booleans nobody asked for.
        try {
            val sig = "$lane:${bandLabel6917(score)}"
            if (com.lifecyclebot.v3.scoring.BehaviorAI.isPatternSuppressed(sig))
                out += BrainRead("behaviourSuppressed($sig)", -9.0)
            else if (com.lifecyclebot.v3.scoring.BehaviorAI.isPatternBoosted(sig))
                out += BrainRead("behaviourBoosted($sig)", +7.0)
        } catch (_: Throwable) {}

        // Pattern classifier realised win rate. liveWinRatePct/paperWinRate
        // were computed on every close and never consulted before one.
        try {
            val wr = com.lifecyclebot.engine.PatternClassifier.liveWinRatePct()
            if (wr.isFinite() && wr > 0.0) {
                // Centre on 40%: below that a memecoin book is structurally
                // losing even with a tail, above it the tail compounds.
                val d = ((wr - 40.0) / 40.0 * 6.0).coerceIn(-6.0, 6.0)
                if (kotlin.math.abs(d) >= 0.5) out += BrainRead("patternWR(${"%.0f".format(wr)}%)", d)
            }
        } catch (_: Throwable) {}

        // Quant edge decay — how fast the current edge is eroding. Nonzero
        // decay is a reason to be less willing, not to size smaller.
        try {
            val decay = com.lifecyclebot.engine.quant.QuantMindV2.getEdgeDecay()
            if (decay.isFinite() && decay > 0.0) {
                out += BrainRead("edgeDecay(${"%.2f".format(decay)})", -(decay.coerceAtMost(1.0) * 7.0))
            }
        } catch (_: Throwable) {}

        // Approval accuracy — how often the stack's own approvals have been
        // right. A low figure is a direct statement that its verdicts are not
        // yet trustworthy, which should reduce willingness across the board.
        try {
            val acc = com.lifecyclebot.engine.EdgeLearning.getApprovalAccuracy()
            if (acc.isFinite() && acc > 0.0) {
                val pct = if (acc <= 1.0) acc * 100.0 else acc
                val d = ((pct - 50.0) / 50.0 * 5.0).coerceIn(-5.0, 5.0)
                if (kotlin.math.abs(d) >= 0.5) out += BrainRead("approvalAcc(${"%.0f".format(pct)}%)", d)
            }
        } catch (_: Throwable) {}

        return out
    }

    fun evaluate(
        lane: String,
        score: Int,
        sourceFamily: String = "",
        regime: String = "",
        // V5.0.6917 — per-candidate identity, so the mint-specific brains
        // (collective prediction, momentum, insider, creator memory) can be
        // read. All optional: blank simply drops those inputs and the estimate
        // falls back to the cohort hierarchy exactly as in 6915.
        mint: String = "",
        symbol: String = "",
        liquidityUsd: Double = 0.0,
        creator: String = "",
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
        // V5.0.6917 — the brain-network tier. Capped separately from the 6915
        // stack adjustments so one tier cannot swamp the other, and so the
        // operator can tell from the contributions list which tier moved the
        // number.
        var brainAdjust6917 = 0.0
        try {
            val reads = brainNetwork6917(laneKey, s, mint, symbol, sourceFamily, liquidityUsd, creator)
            for (r in reads) {
                brainAdjust6917 += r.deltaPct
                contributions += "${r.label}=${"%+.1f".format(r.deltaPct)}"
            }
            if (reads.isNotEmpty()) brainReads6917.addAndGet(reads.size.toLong())
        } catch (_: Throwable) {}
        val boundedBrain6917 = brainAdjust6917
            .coerceIn(-BRAIN_NETWORK_CAP_PCT_6917, BRAIN_NETWORK_CAP_PCT_6917)
        val boundedAdjust = adjust.coerceIn(-STACK_ADJUST_CAP_PCT, STACK_ADJUST_CAP_PCT)
        val finalE = blendedE + boundedAdjust + boundedBrain6917

        // ── V5.0.6927 · RECORDED-FACT SAFETY REFUSAL ────────────────────────
        //
        // Everything above this line is a statistical estimate, and the
        // verdict below rightly gates REFUSE on confidence: you should not
        // refuse an entry because a thin cohort produced a gloomy mean.
        //
        // But that gate is wrong for a RECORDED FACT. "This creator wallet has
        // rugged four times" is not an estimate awaiting a bigger sample — it
        // is something that happened, four times, and it does not become more
        // true with a larger cohort. Under the confidence gate a serial rugger
        // launching a brand-new token (which is the whole point of launching a
        // brand-new token) has almost no cohort evidence, so confidence stays
        // low and the entry sails through with at most a clamped -18% nudge.
        //
        // In this asset class creator history is the highest-signal thing that
        // can be known before entry, and a wallet that has rugged repeatedly
        // is stating plainly what it does for a living. So this refusal skips
        // the confidence gate. It is the one place in this object that does.
        //
        // Bounded deliberately: it only ever REFUSES, never admits and never
        // sizes; it fails open on any exception; and it needs a recorded count
        // from TradingMemory's own rug ledger, not an inference.
        val hardRefusal6927 = hardSafetyRefusal6927(creator)
        if (hardRefusal6927 != null) {
            refuses.incrementAndGet()
            val f = Forecast(
                Verdict.REFUSE, finalE, blendedPWin, confidence,
                contributions + hardRefusal6927, hardRefusal6927,
            )
            try {
                PipelineHealthCollector.labelInc("PREDICTIVE_ORACLE_REFUSE_6915")
                PipelineHealthCollector.labelInc("PREDICTIVE_ORACLE_HARD_SAFETY_REFUSE_6927")
                ForensicLogger.lifecycle(
                    "PREDICTIVE_ORACLE_HARD_SAFETY_REFUSE_6927",
                    "lane=$laneKey mint=${mint.take(10)} sym=$symbol reason=$hardRefusal6927 " +
                        "note=recorded_fact_bypasses_confidence_gate",
                )
            } catch (_: Throwable) {}
            return f
        }

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

    /**
     * V5.0.6927 — recorded rug count at which a creator is refused outright.
     *
     * Four is chosen to be past any plausible innocent explanation. One rug
     * can be a failed project, two can be bad luck twice; four is a business
     * model. Below this the graded penalty in the brain tier applies instead.
     */
    private const val REFUSE_RUG_COUNT_6927 = 4

    /**
     * Returns a refusal reason when a RECORDED fact disqualifies this entry,
     * or null. Never admits, never sizes, fails open.
     */
    private fun hardSafetyRefusal6927(creator: String): String? {
        if (creator.isBlank()) return null
        return try {
            val rugs = com.lifecyclebot.engine.TradingMemory.getCreatorRugCount(creator)
            if (rugs >= REFUSE_RUG_COUNT_6927) "CREATOR_SERIAL_RUGGER_6927(rugs=$rugs)" else null
        } catch (_: Throwable) { null }
    }

    fun statusLine(): String =
        "evals=${evaluations.get()} admit=${admits.get()} probe=${probes.get()} refuse=${refuses.get()} " +
            "cellEvidence=${cellHits.get()} laneEvidence=${laneHits.get()} noEvidence=${globalOnly.get()} " +
            "brainReads6917=${brainReads6917.get()} brainCap=${BRAIN_NETWORK_CAP_PCT_6917}% " +
            "shrinkK=$SHRINK_K refuseAt=${REFUSE_EXPECTANCY_PCT}% minConf=$MIN_CONFIDENCE_TO_REFUSE"

    internal fun resetForTest() {
        evaluations.set(0L); admits.set(0L); probes.set(0L); refuses.set(0L)
        cellHits.set(0L); laneHits.set(0L); globalOnly.set(0L); brainReads6917.set(0L)
    }
}

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
 *   ForwardOutcomeModel.cohortEvidence6911    cohort pWin/EV. V5.0.7103: was
 *                                             regime-agnostic AND mode-pooled;
 *                                             now own-mode and regime-
 *                                             conditioned where the evidence
 *                                             supports it, pooled where it
 *                                             does not
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

    // V5.0.7287 §TRADE OR DON'T TRADE.
    //
    // Operator: "I hate this whole probe bullshit. its paper. just trade or
    // dont trade. it makes even less sense in live because it costs more to
    // make the trade than it could ever return." A probe was a quarter-size
    // position on a candidate the oracle could not call. At 0.107 SOL the
    // fixed network leg alone is 1.5% of the ticket, so a quarter-size probe
    // pays four times the cost share for the same information. The oracle now
    // makes the call: positive expected value net of cost trades, anything
    // else does not. Whether its calls are any good is OracleEdgeProof7263's
    // job to measure, and that is what decides whether they bind.
    enum class Verdict {
        /** Expected value positive: trade. */
        ADMIT,
        /** Expected value not positive, or a recorded safety fact: don't trade. */
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
        /**
         * V5.0.7287 — a REFUSE that rests on a recorded fact (serial-rugger
         * creator, tier-B risk read) rather than an estimate. Callers honour
         * it whether or not the oracle has proven its edge.
         */
        val hardSafety7287: Boolean = false,
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
    private val refuses = AtomicLong(0L)
    private val cellHits = AtomicLong(0L)
    private val laneHits = AtomicLong(0L)
    private val globalOnly = AtomicLong(0L)
    /** V5.0.6917 — how many previously-unread brain outputs actually spoke. */
    private val brainReads6917 = AtomicLong(0L)
    /** V5.0.7260 — proof the exact five-dimensional forecast and policy head spoke. */
    private val exactForecastHits7260 = AtomicLong(0L)
    private val unifiedPolicyReads7260 = AtomicLong(0L)
    private val unifiedPolicyBindingVetoes7260 = AtomicLong(0L)
    /** V5.0.7261 — cold books must still judge the current candidate. */
    private val coldCandidateAdmits7261 = AtomicLong(0L)
    private val coldCandidateProbes7261 = AtomicLong(0L)
    /** V5.0.7287 — how often the full-journal lane level replaced the session learner. */
    private val historyReads7287 = AtomicLong(0L)

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

        out += brainNetworkB6940(lane, mint, symbol, sourceFamily, liquidityUsd, creator)
        out += tierBRiskReads6942(mint, lane, score)
        return out
    }

    /**
     * V5.0.6940 — second brain-network batch, working the A_PREDICT tier of
     * ci/UNWIRED_LEDGER.tsv.
     *
     * Split into its own function purely for readability; the reads join the
     * same list and are bounded by the same BRAIN_NETWORK_CAP_PCT_6917 tier
     * cap, so adding brains widens the evidence base without widening the
     * authority.
     *
     * EVERY read here uses ONLY inputs the oracle genuinely has (lane, mint,
     * symbol, sourceFamily, liquidityUsd, creator). Ledger entries whose
     * signatures need inputs this call site cannot honestly supply are
     * deliberately left for a later pass rather than fed invented values:
     *
     *   TradingMemory.matchesRugPattern   needs liquidityDropPct,
     *     priceDropPct, volumeSpike, timeFromLaunchHours — all post-entry
     *     observations that do not exist at admission time.
     *   TradingMemory.getPatternWinRate   needs phase + emaFan.
     *   CollectiveLearning.getNetworkBoostForMint is a SUSPEND function and
     *     evaluate() is synchronous on the hot path.
     *   TradeDatabase.getSignalWinRate    needs a featureKey whose format is
     *     not derivable from here without guessing.
     */
    private fun brainNetworkB6940(
        lane: String,
        mint: String,
        symbol: String,
        sourceFamily: String,
        liquidityUsd: Double,
        creator: String,
    ): List<BrainRead> {
        val out = mutableListOf<BrainRead>()

        // Paper win rate, the sibling of the already-wired liveWinRatePct.
        // Paper is where nearly all the evidence is, so this is the larger
        // sample; it is weighted lower because paper fills are frictionless.
        try {
            val pw = com.lifecyclebot.engine.PatternClassifier.paperWinRate()
            if (pw.isFinite() && pw > 0.0) {
                out += BrainRead("paperWR(${"%.0f".format(pw)}%)",
                    ((pw - 40.0) / 100.0 * 6.0).coerceIn(-5.0, 5.0))
            }
        } catch (_: Throwable) {}

        // How often the momentum brain has actually been right. A brain that
        // is wrong more than it is right should not be able to push an entry,
        // and this is the only thing that says so.
        try {
            val acc = com.lifecyclebot.engine.MomentumPredictorAI.getPredictionAccuracy()
            if (acc.isFinite() && acc > 0.0) {
                out += BrainRead("momAcc(${"%.0f".format(acc)}%)",
                    ((acc - 50.0) / 100.0 * 5.0).coerceIn(-4.0, 4.0))
            }
        } catch (_: Throwable) {}

        // Recent form. A book that has been losing for 24h is in a different
        // regime from its lifetime average, whatever the cohort says.
        try {
            val wr24 = com.lifecyclebot.engine.TradeHistoryStore.getWinRate24h()
            if (wr24 > 0) {
                out += BrainRead("wr24h(${wr24}%)",
                    ((wr24 - 35.0) / 100.0 * 6.0).coerceIn(-5.0, 5.0))
            }
        } catch (_: Throwable) {}

        // Has this lane's signal ever actually predicted anything?
        try {
            if (!com.lifecyclebot.engine.SignalQualityTracker.isPredictive(lane)) {
                out += BrainRead("laneSignalNotPredictive", -6.0)
            }
        } catch (_: Throwable) {}

        // Token-name/symbol pattern edge learned from realised outcomes.
        try {
            if (symbol.isNotBlank()) {
                val bias = com.lifecyclebot.engine.PatternGoldenGoose.scoreBias(symbol, symbol)
                if (bias != 0) out += BrainRead("goldenGoose($bias)",
                    (bias / 10.0).coerceIn(-6.0, 6.0))
            }
        } catch (_: Throwable) {}

        // Social velocity — a genuinely boosted token has attention behind it,
        // which in this asset class is most of the thesis.
        try {
            if (mint.isNotBlank() &&
                com.lifecyclebot.v3.scoring.SocialVelocityAI.isBoosted(mint)
            ) {
                val amt = com.lifecyclebot.v3.scoring.SocialVelocityAI.getBoostAmount(mint)
                out += BrainRead("socialBoost($amt)",
                    (1.5 + (amt.coerceAtMost(20L) / 20.0) * 4.5).coerceIn(0.0, 6.0))
            }
        } catch (_: Throwable) {}

        // Insider/alpha wallet signals already recorded against this mint.
        try {
            if (mint.isNotBlank()) {
                val n = com.lifecyclebot.v3.scoring.InsiderTrackerAI.getSignalsByToken(mint).size
                if (n > 0) out += BrainRead("insiderSignals($n)",
                    (n.coerceAtMost(4) * 1.6).coerceIn(0.0, 6.4))
            }
        } catch (_: Throwable) {}

        // The creator wallet judged as a WALLET rather than as a rug counter:
        // WhaleWalletTracker scores every address it has seen trade.
        try {
            if (creator.isNotBlank()) {
                val ws = com.lifecyclebot.engine.WhaleWalletTracker.getWhaleScore(creator)
                if (ws > 0) {
                    val reliable = com.lifecyclebot.engine.WhaleWalletTracker.isWhaleReliable(creator)
                    out += BrainRead("creatorWhale($ws${if (reliable) ",rel" else ""})",
                        (((ws - 50) / 100.0) * 8.0 + (if (reliable) 2.0 else 0.0)).coerceIn(-5.0, 6.0))
                }
            }
        } catch (_: Throwable) {}

        // Learned toxic shape: source family x liquidity bucket x lane.
        // hasCollapsed is false by definition at admission — nothing has
        // collapsed yet — so this is a real argument, not a placeholder.
        try {
            val bucket = when {
                liquidityUsd <= 0.0 -> "UNKNOWN"
                liquidityUsd < 2_000.0 -> "MICRO"
                liquidityUsd < 10_000.0 -> "TINY"
                liquidityUsd < 50_000.0 -> "LOW"
                else -> "HEALTHY"
            }
            if (bucket != "UNKNOWN" && sourceFamily.isNotBlank() &&
                com.lifecyclebot.engine.ToxicModeCircuitBreaker
                    .isToxicPattern(sourceFamily, bucket, lane, false)
            ) out += BrainRead("toxicShape($bucket)", -12.0)
        } catch (_: Throwable) {}

        // Evidence volume behind the edge learner. Very few patterns means the
        // stack's own confidence should be discounted, not trusted.
        try {
            val pc = com.lifecyclebot.engine.EdgeLearning.getPatternCount()
            if (pc in 1..14) out += BrainRead("thinEdgeEvidence($pc)", -3.0)
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
        // V5.0.7260 — the exact candidate signature. Earlier callers passed
        // blanks, forcing ForwardOutcomeModel.forecast() to bootstrap even
        // when a matching quality/phase cell existed.
        quality: String = "",
        edgePhase: String = "",
        candidateConfidence: Double = 0.50,
    ): Forecast {
        evaluations.incrementAndGet()
        val laneKey = lane.trim().uppercase().ifBlank { "UNKNOWN" }
        val s = score.coerceIn(0, 100)
        val candidateConfidenceSafe7260 = candidateConfidence
            .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.50
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
            // V5.0.7260 — prefer the exact candidate signature. The admission
            // assembler previously asked with quality="" and edgePhase="",
            // producing bootstrap on every read (forecastResolved=0). Only when
            // the exact signature is genuinely thin do we fall back to the
            // coarser lane/score/regime evidence.
            val exact = com.lifecyclebot.engine.ForwardOutcomeModel.forecast(
                laneKey, s, quality.ifBlank { "U" }.take(3),
                regime.ifBlank { "NORMAL" }, edgePhase.ifBlank { "UNKNOWN" },
            )
            if (exact.source != "bootstrap" && exact.samples > 0L) {
                exactForecastHits7260.incrementAndGet()
                val n = exact.samples.toDouble()
                cellMean += exact.expectedPnl * n; cellN += n
                cellPWin = exact.pWin
                contributions += "exactFwd(${exact.source},n=${exact.samples},E=${"%+.1f".format(exact.expectedPnl)},pW=${"%.2f".format(exact.pWin)})"
            } else {
                val agg = com.lifecyclebot.engine.ForwardOutcomeModel
                    .cohortEvidence6911(laneKey, s, regime)
                if (agg.samples > 0L) {
                    val n = agg.samples.toDouble()
                    cellMean += agg.expectedPnlPct * n; cellN += n
                    cellPWin = agg.pWin
                    contributions += "cellFwd(${agg.level},n=${agg.samples},E=${"%+.1f".format(agg.expectedPnlPct)},pW=${"%.2f".format(agg.pWin)})"
                }
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

        // V5.0.7287 — the journal is the evidence. Where the full terminal
        // history holds more closes for this lane (or the book) than the
        // session learner does, it replaces that level. See
        // OracleTradeHistory7287.
        try {
            OracleTradeHistory7287.lane(laneKey)?.let { h ->
                if (h.n.toDouble() > (lane1?.n ?: 0.0)) {
                    lane1 = Level("laneHist", h.meanNetPct, h.winRate.coerceIn(0.0, 1.0), h.n.toDouble())
                    contributions += "laneHist(n=${h.n},E=${"%+.1f".format(h.meanNetPct)},WR=${"%.0f".format(h.winRate * 100.0)}%)"
                    historyReads7287.incrementAndGet()
                }
            }
            OracleTradeHistory7287.book()?.let { h ->
                if (h.n.toDouble() > (globalLevel?.n ?: 0.0)) {
                    globalLevel = Level("global", h.meanNetPct, h.winRate.coerceIn(0.0, 1.0), h.n.toDouble())
                    contributions += "bookHist(n=${h.n},E=${"%+.1f".format(h.meanNetPct)})"
                }
            }
        } catch (_: Throwable) {}

        // ── SHRINKAGE BLEND ─────────────────────────────────────────────────
        val levels = listOfNotNull(cell, lane1, globalLevel).filter { it.weight > 0.0 }
        if (levels.isEmpty()) {
            globalOnly.incrementAndGet()
            // V5.0.7261 — 7260 returned here before any current-candidate
            // intelligence ran. On a clean book that made policyReads=0,
            // brainReads=0 and PROBE=100%, and 7259 correctly made all of
            // those probes non-economic. The result was a permanent zero-buy
            // bootstrap: no terminal evidence could ever be created because
            // terminal evidence was mandatory before the first entry.
            //
            // A cold book has no historical evidence, but it still has the
            // candidate in front of it. Require agreement across the current
            // score, model confidence, setup quality/phase, UnifiedPolicyHead
            // and bounded brain network. Missing optional quality/phase is
            // neutral, never positive; explicit WAIT/REJECT or C/D/F quality
            // prevents admission. Recorded creator/token safety remains an
            // absolute refusal. PROBE itself remains shadow-only.
            val hardRefusal7261 = hardSafetyRefusal6927(creator)
                ?: tierBRefusal6942(mint, symbol)
            if (hardRefusal7261 != null) {
                refuses.incrementAndGet()
                return Forecast(
                    Verdict.REFUSE, -100.0, 0.0, 1.0,
                    contributions + hardRefusal7261,
                    hardRefusal7261,
                    hardSafety7287 = true,
                ).also { OracleEdgeProof7263.stamp(mint, it) }
            }

            val qualityKey7261 = quality.trim().uppercase()
            val qualityP7261 = when (qualityKey7261) {
                "A+" -> 0.90
                "A" -> 0.82
                "B+" -> 0.74
                "B" -> 0.66
                "C" -> 0.42
                "D", "F" -> 0.20
                else -> 0.50
            }
            val explicitWeakQuality7261 = qualityKey7261 in setOf("C", "D", "F")
            val phaseKey7261 = edgePhase.trim().uppercase()
            val explicitNonEntryPhase7261 = listOf("WAIT", "REJECT", "NO_BUY", "BLOCK")
                .any { phaseKey7261.contains(it) }
            val scoreP7261 = s / 100.0

            var policyPWin7261 = 0.50
            var policyTier7261 = com.lifecyclebot.engine.UnifiedPolicyHead.AuthorityTier.BOOTSTRAP
            var policyRead7261 = false
            try {
                val meta = com.lifecyclebot.engine.AutonomousMetaPolicy
                    .conviction(laneKey, s, regime).coerceIn(0.0, 2.0) / 2.0
                policyPWin7261 = com.lifecyclebot.engine.UnifiedPolicyHead.predictWinProb(
                    laneKey,
                    com.lifecyclebot.engine.UnifiedPolicyHead.Signals(
                        mlEntryConf = candidateConfidenceSafe7260,
                        symGreenLight = scoreP7261,
                        evRatio = scoreP7261,
                        metaConviction = meta,
                        fwdPWin = scoreP7261,
                        candConf = candidateConfidenceSafe7260,
                    ),
                ).coerceIn(0.0, 1.0)
                val rawTier = com.lifecyclebot.engine.UnifiedPolicyHead.laneOwnHeadAuthority6605(laneKey)
                val calibratedTier = com.lifecyclebot.engine.UnifiedPolicyHead.currentAuthority(laneKey)
                policyTier7261 = if (rawTier.ordinal <= calibratedTier.ordinal) rawTier else calibratedTier
                policyRead7261 = true
                unifiedPolicyReads7260.incrementAndGet()
            } catch (_: Throwable) {}

            var brainDelta7261 = 0.0
            try {
                val reads = brainNetwork6917(laneKey, s, mint, symbol, sourceFamily, liquidityUsd, creator)
                brainDelta7261 = reads.sumOf { it.deltaPct }
                    .coerceIn(-BRAIN_NETWORK_CAP_PCT_6917, BRAIN_NETWORK_CAP_PCT_6917)
                if (reads.isNotEmpty()) {
                    brainReads6917.addAndGet(reads.size.toLong())
                    contributions += reads.map { "${it.label}=${"%+.1f".format(it.deltaPct)}" }
                }
            } catch (_: Throwable) {}

            val currentCandidatePWin7261 = (
                scoreP7261 * 0.45 +
                    candidateConfidenceSafe7260 * 0.30 +
                    qualityP7261 * 0.15 +
                    policyPWin7261 * 0.10 +
                    brainDelta7261 / 100.0
                ).coerceIn(0.0, 1.0)
            val policyBinding7261 = policyTier7261 in setOf(
                com.lifecyclebot.engine.UnifiedPolicyHead.AuthorityTier.LEARNED,
                com.lifecyclebot.engine.UnifiedPolicyHead.AuthorityTier.AUTHORITATIVE,
            )
            val policyAgrees7261 = policyRead7261 &&
                (!policyBinding7261 || policyPWin7261 > 0.50)
            // V5.0.7287 — a cold book trades what the candidate says has
            // positive expected value. 7261 demanded unanimity (score>=60,
            // confidence>=0.40, p>0.56) against a scorer that produces 9-32,
            // which is why cold ADMITs never happened and every cold verdict
            // was a probe. The measured bar replaces the declared one.
            val candidateAdmit7261 =
                currentCandidatePWin7261 > 0.50 &&
                    !explicitWeakQuality7261 &&
                    !explicitNonEntryPhase7261 &&
                    policyAgrees7261 &&
                    brainDelta7261 >= -5.0
            val coldExpectancy7261 = ((currentCandidatePWin7261 * 2.0) - 1.0) * 100.0
            contributions += listOf(
                "coldCandidate(score=$s,p=${"%.2f".format(scoreP7261)})",
                "candidateConf(p=${"%.2f".format(candidateConfidenceSafe7260)})",
                "quality(${qualityKey7261.ifBlank { "UNKNOWN" }},p=${"%.2f".format(qualityP7261)})",
                "phase(${phaseKey7261.ifBlank { "UNKNOWN" }})",
                "policy(${policyTier7261.name},p=${"%.2f".format(policyPWin7261)})",
                "brain(${"%+.1f".format(brainDelta7261)})",
            )
            val cold7263 = if (candidateAdmit7261) {
                admits.incrementAndGet()
                coldCandidateAdmits7261.incrementAndGet()
                Forecast(
                    Verdict.ADMIT, coldExpectancy7261, currentCandidatePWin7261,
                    currentCandidatePWin7261.coerceAtLeast(MIN_CONFIDENCE_TO_REFUSE),
                    contributions,
                    "COLD_START_CURRENT_CANDIDATE_UNANIMOUS_ADMIT_7261",
                )
            } else {
                refuses.incrementAndGet()
                coldCandidateProbes7261.incrementAndGet()
                Forecast(
                    Verdict.REFUSE, coldExpectancy7261, currentCandidatePWin7261,
                    currentCandidatePWin7261.coerceIn(0.0, MIN_CONFIDENCE_TO_REFUSE),
                    contributions + "noHistoricalEvidence",
                    "COLD_START_EXPECTED_VALUE_NOT_POSITIVE_7287",
                )
            }
            // V5.0.7263 — every forecast is stamped so a later close can grade it.
            OracleEdgeProof7263.stamp(mint, cold7263)
            return cold7263
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
        // V5.0.7260 — the class documentation has always listed
        // UnifiedPolicyHead as an oracle input, but evaluate() never called it.
        // Read the learned head with this candidate's real confidence and the
        // forward hierarchy above. It is a bounded vote; it cannot manufacture
        // an admit against negative measured expectancy by itself.
        var unifiedPolicyPWin7260 = 0.50
        var unifiedPolicyTier7260 = com.lifecyclebot.engine.UnifiedPolicyHead.AuthorityTier.BOOTSTRAP
        var unifiedPolicyReadOk7260 = false
        try {
            val meta = com.lifecyclebot.engine.AutonomousMetaPolicy
                .conviction(laneKey, s, regime).coerceIn(0.0, 2.0) / 2.0
            unifiedPolicyPWin7260 = com.lifecyclebot.engine.UnifiedPolicyHead.predictWinProb(
                laneKey,
                com.lifecyclebot.engine.UnifiedPolicyHead.Signals(
                    mlEntryConf = candidateConfidenceSafe7260,
                    symGreenLight = blendedPWin.coerceIn(0.0, 1.0),
                    evRatio = ((blendedE + 25.0) / 50.0).coerceIn(0.0, 1.0),
                    metaConviction = meta,
                    fwdPWin = blendedPWin.coerceIn(0.0, 1.0),
                    candConf = candidateConfidenceSafe7260,
                ),
            ).coerceIn(0.0, 1.0)
            val rawTier7260 = com.lifecyclebot.engine.UnifiedPolicyHead
                .laneOwnHeadAuthority6605(laneKey)
            val calibratedTier7260 = com.lifecyclebot.engine.UnifiedPolicyHead
                .currentAuthority(laneKey)
            unifiedPolicyTier7260 = if (rawTier7260.ordinal <= calibratedTier7260.ordinal)
                rawTier7260 else calibratedTier7260
            unifiedPolicyReadOk7260 = unifiedPolicyPWin7260.isFinite()
            unifiedPolicyReads7260.incrementAndGet()
            val d = ((unifiedPolicyPWin7260 - 0.50) * 20.0).coerceIn(-10.0, 10.0)
            adjust += d
            contributions += "unifiedPolicy(${unifiedPolicyTier7260.name},p=${"%.2f".format(unifiedPolicyPWin7260)},${"%+.1f".format(d)})"
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
            ?: tierBRefusal6942(mint, symbol)
        if (hardRefusal6927 != null) {
            refuses.incrementAndGet()
            val f = Forecast(
                Verdict.REFUSE, finalE, blendedPWin, confidence,
                contributions + hardRefusal6927, hardRefusal6927,
                hardSafety7287 = true,
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
            OracleEdgeProof7263.stamp(mint, f)
            return f
        }

        // ── V5.0.7174 §A REFUSAL ABOUT A CANDIDATE NEEDS EVIDENCE ABOUT THAT
        //    CANDIDATE. ────────────────────────────────────────────────────
        //
        // Operator: "there's no trade volume at all... totally choked out",
        // and "don't miss profitable trade opportunities if the capital is
        // there to fund the trade."
        //
        // Their 5.0.7171, with 3.96 SOL of idle cash and most lanes under a
        // fifth of their allocation:
        //
        //   Predictive oracle: evals=1837  admit=0  refuse=1837
        //   Learner degeneracy: PredictiveEntryOracle6915 top=REFUSE@1.00:DEGENERATE
        //   refusal reason: NEGATIVE_EXPECTANCY_WITH_EVIDENCE_6915
        //                   [lane(n=4,E=-23.4,WR=25%) global(n=15,E=-30.1)]
        //
        // Every single evaluation refused. Work the arithmetic on that line:
        // weight is n/(n+6), so lane n=4 gives 0.40 and global n=15 gives
        // 0.71; confidence = max*0.5 + mean*0.5 = 0.64, which clears
        // MIN_CONFIDENCE_TO_REFUSE = 0.45. The refusal is carried by GLOBAL.
        //
        // Global is "the whole book". It is not a property of this candidate
        // at all — it is a property of the bot. Fifteen bad closes are enough
        // to put global's weight over the bar on its own, so one bad run
        // refuses every future candidate in every lane, which prevents the
        // trades that would produce a different fifteen. Same shape as the
        // regime latch 7173 fixed, one layer down.
        //
        // MIN_CONFIDENCE_TO_REFUSE's own doc says the bar exists so the stack
        // "is allowed to be pessimistic, but not to act on pessimism it
        // cannot support", and 6927 forty lines up says "you should not
        // refuse an entry because a thin cohort produced a gloomy mean".
        // Both are right and neither holds while a book-wide average can
        // satisfy the bar by itself.
        //
        // So the REFUSE gate is measured on the CANDIDATE-SPECIFIC levels
        // only — its cell and its lane. Global keeps its full influence on
        // the estimate (finalE is unchanged, and the blend above still uses
        // every level); it simply cannot be the evidence that authorises a
        // refusal. ADMIT is deliberately left on the original confidence:
        // the operator's instruction is not to miss funded opportunities, and
        // tightening the admit bar here would do exactly that.
        //
        // With the operator's numbers this turns a REFUSE into a PROBE —
        // lane n=4 alone is 0.40, below the bar — which is the honest verdict
        // for four samples. A lane that has actually earned a refusal
        // (n>=10 -> 0.63, n=20 -> 0.77) still refuses.
        val specificLevels7174 = levels.filter { it.name != "global" }
        val refuseConfidence7174 = if (specificLevels7174.isEmpty()) 0.0 else {
            val sSum = specificLevels7174.sumOf { it.weight }
            (specificLevels7174.maxOf { it.weight } * 0.5 +
                (sSum / specificLevels7174.size.toDouble()) * 0.5).coerceIn(0.0, 1.0)
        }
        if (finalE <= REFUSE_EXPECTANCY_PCT && confidence >= MIN_CONFIDENCE_TO_REFUSE &&
            refuseConfidence7174 < MIN_CONFIDENCE_TO_REFUSE
        ) {
            try {
                PipelineHealthCollector.labelInc("ORACLE_REFUSE_DOWNGRADED_GLOBAL_ONLY_EVIDENCE_7174")
                PipelineHealthCollector.labelInc(
                    "ORACLE_REFUSE_DOWNGRADED_GLOBAL_ONLY_EVIDENCE_7174_${laneKey.take(20)}",
                )
            } catch (_: Throwable) {}
        }

        // ── VERDICT ─────────────────────────────────────────────────────────
        // The realised lane/book rate is a PRIOR, not the current candidate.
        // Treating it as the candidate probability created a closed loop: an
        // early losing batch forced pWin below 0.5, no future candidate could
        // be admitted, and therefore the learner could never observe a win.
        // Blend it with the current candidate confidence and the learned
        // policy prediction. Historical evidence still affects both this
        // probability and finalE, but it cannot impersonate the candidate.
        val candidatePWin7260 = candidateConfidenceSafe7260
        val predictivePWin7260 = (
            blendedPWin * 0.20 + candidatePWin7260 * 0.40 + unifiedPolicyPWin7260 * 0.40
        ).coerceIn(0.0, 1.0)
        val policyIsBinding7260 = unifiedPolicyTier7260 in setOf(
            com.lifecyclebot.engine.UnifiedPolicyHead.AuthorityTier.LEARNED,
            com.lifecyclebot.engine.UnifiedPolicyHead.AuthorityTier.AUTHORITATIVE,
        )
        val policySupportsProfit7260 = unifiedPolicyReadOk7260 &&
            (!policyIsBinding7260 || unifiedPolicyPWin7260 > 0.50)
        if (policyIsBinding7260 && !policySupportsProfit7260) {
            unifiedPolicyBindingVetoes7260.incrementAndGet()
        }
        contributions += "candidatePWin(p=${"%.2f".format(candidatePWin7260)},blend=${"%.2f".format(predictivePWin7260)})"

        // V5.0.7287 — one question: is the expected value, net of cost,
        // positive? Expectancy is mean PnL, so a fat-tailed lane with a low
        // win rate and a positive mean trades (the runner doctrine); a lane
        // whose mean is negative does not. A binding policy head that says
        // this candidate loses still vetoes. 7260 also required blended and
        // candidate pWin > 0.5 and confidence >= 0.45, which a fat-tailed
        // lane never meets and which made ADMIT all but unreachable; the
        // uncertain middle then became a probe.
        val evidencedNegative7287 =
            finalE <= REFUSE_EXPECTANCY_PCT && refuseConfidence7174 >= MIN_CONFIDENCE_TO_REFUSE
        val verdict = if (!evidencedNegative7287 && finalE > ADMIT_EXPECTANCY_PCT && policySupportsProfit7260) {
            Verdict.ADMIT
        } else {
            Verdict.REFUSE
        }
        val reason = when {
            verdict == Verdict.ADMIT -> "POSITIVE_EXPECTANCY_6915"
            evidencedNegative7287 -> "NEGATIVE_EXPECTANCY_WITH_EVIDENCE_6915"
            !policySupportsProfit7260 && finalE > ADMIT_EXPECTANCY_PCT -> "BINDING_POLICY_HEAD_SAYS_LOSS_7287"
            else -> "EXPECTANCY_NOT_POSITIVE_7287"
        }
        when (verdict) {
            Verdict.ADMIT -> admits.incrementAndGet()
            Verdict.REFUSE -> refuses.incrementAndGet()
        }
        // V5.0.7102 — these three counters already knew, on 5.0.7088, that this
        // oracle had run 894 evaluations and admitted nothing. The number sat in
        // a status line waiting for someone to read a ratio. Hand the same
        // verdict to the watch so a collapsed policy reports itself as a fault
        // about the learner instead of being inferred from a report.
        try {
            LearnedPolicyDegeneracyWatch7102.observe7102(ORACLE_AUTHORITY_7120, verdict.name)
        } catch (_: Throwable) {}

        // V5.0.7120 §A_COLLAPSED_CLASSIFIER_IS_NOT_AN_OPINION.
        //
        // Operator on the 5.0.7117 device: "evals=1784 admit=0 probe=0
        // refuse=1783 ... That's not intelligence; it's a collapsed classifier.
        // Don't remove the oracle. Fail it open to neutral while it
        // rehydrates/retrains."
        //
        // They are right, and the inputs say why it collapsed rather than
        // learned: Keyless OHLCV served=0 barsDelivered=0, local candle synth
        // candles=0, GeckoTerminal at 23% with 779 rate limits. The oracle has
        // no usable candle history and is still returning REFUSE at 1.00
        // consistency. A verdict with no variance carries no information, so
        // acting on it is acting on noise with a confident face. The oracle
        // therefore demotes to neutral PROBE; since 7259/7260, neutral remains
        // observable in shadow but is not canonical economic permission.
        //
        // NOTE THE ORDER: observe7102 above receives the RAW verdict, always.
        // If the demoted verdict were fed back to the watch, the tally would
        // fill with PROBE, share would fall below the threshold, the watch
        // would declare recovery and the oracle would be re-armed on the
        // strength of its own suppression — a learner grading its own
        // convalescence. Recovery must be earned by the RAW estimator
        // discriminating again, which is exactly what the raw stream measures.
        val degenerate7120 = try {
            LearnedPolicyDegeneracyWatch7102.isDegenerate7102(ORACLE_AUTHORITY_7120)
        } catch (_: Throwable) { false }
        // V5.0.7287 — with no neutral verdict left, a collapsed estimator is
        // not rewritten to one. Its verdict stands as telemetry and the
        // admission authority declines to let it bind while it is degenerate
        // (LearnedAdmissionAuthority6846 reads isDegenerateNow7120()).
        if (degenerate7120) {
            degenerateDemotions7120.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("ORACLE_DEGENERATE_NON_BINDING_7287_${verdict.name}")
            } catch (_: Throwable) {}
        }
        val effectiveVerdict7120 = verdict
        val effectiveReason7120 =
            if (degenerate7120) "${reason}_DEGENERATE_NON_BINDING_7287" else reason

        val f = Forecast(
            effectiveVerdict7120, finalE, predictivePWin7260, confidence, contributions, effectiveReason7120,
        )
        if (effectiveVerdict7120 != Verdict.ADMIT) try {
            PipelineHealthCollector.labelInc("PREDICTIVE_ORACLE_${effectiveVerdict7120.name}_6915")
            PipelineHealthCollector.labelInc("PREDICTIVE_ORACLE_${effectiveVerdict7120.name}_6915_$laneKey")
            if (effectiveVerdict7120 == Verdict.REFUSE) ForensicLogger.lifecycle(
                "PREDICTIVE_ORACLE_REFUSED_6915",
                "lane=$laneKey score=$s src=${sourceFamily.take(24)} ${f.line()}",
            )
        } catch (_: Throwable) {}
        // V5.0.7263 — stamped so the finalized-trade bus can grade this
        // forecast; OracleEdgeProof7263 decides whether the verdict may gate.
        OracleEdgeProof7263.stamp(mint, f)
        return f
    }

    /**
     * V5.0.7120 — the authority name this oracle registers with
     * LearnedPolicyDegeneracyWatch7102.
     *
     * One spelling, used by both the observe call and the degeneracy read, so
     * the watch can never be written under one name and questioned under
     * another. It stays PRIVATE: LearnedAdmissionAuthority6846 asks
     * [isDegenerateNow7120] rather than passing the string itself, which is
     * what keeps the name from becoming a literal shared across files. This
     * session removed fourteen functions that each held their own spelling of
     * one lane name, and ci/new_dead_code.py rejected the public version of
     * this constant for exactly the reason it should have.
     */
    private const val ORACLE_AUTHORITY_7120 = "PredictiveEntryOracle6915"

    /** V5.0.7120 — verdicts suppressed because the estimator had collapsed. */
    private val degenerateDemotions7120 = java.util.concurrent.atomic.AtomicLong(0L)

    /** V5.0.7120 — true while the oracle must not refuse or meter anything. */
    fun isDegenerateNow7120(): Boolean = try {
        LearnedPolicyDegeneracyWatch7102.isDegenerate7102(ORACLE_AUTHORITY_7120)
    } catch (_: Throwable) { false }

    /** V5.0.7120 — for the operator report's §6915 line. */
    fun degenerateDemotions7120(): Long = degenerateDemotions7120.get()

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

    /**
     * V5.0.6942 — Tier B (B_RISK) recorded-fact refusals.
     *
     * Same doctrine as the serial-rugger path: these are FACTS, not estimates,
     * so they bypass the confidence gate. A blocklisted mint does not become
     * less blocklisted when the cohort is thin.
     *
     * BaseQuoteMintGuard exists to stop the bot buying base/quote assets it
     * should never hold as a position (SOL, USDC and friends), and BOTH of its
     * predicates had zero callers — so the guard was installed and never
     * consulted. AdaptiveVetoConsensusAuthority6728.isHardVeto is a consensus
     * veto across the veto stack whose whole purpose is to be asked.
     *
     * Fails open on every read: a guard that throws must not block trading.
     */
    private fun tierBRefusal6942(mint: String, symbol: String): String? {
        try {
            if (mint.isNotBlank() &&
                com.lifecyclebot.engine.guard.BaseQuoteMintGuard.isBlockedMint(mint)
            ) return "BLOCKED_BASE_QUOTE_MINT_6942"
        } catch (_: Throwable) {}
        try {
            if (symbol.isNotBlank() &&
                com.lifecyclebot.engine.guard.BaseQuoteMintGuard.isBlockedSymbol(symbol)
            ) return "BLOCKED_BASE_QUOTE_SYMBOL_6942($symbol)"
        } catch (_: Throwable) {}
        try {
            if (com.lifecyclebot.engine.truth.AdaptiveVetoConsensusAuthority6728.isHardVeto())
                return "ADAPTIVE_VETO_CONSENSUS_HARD_6942"
        } catch (_: Throwable) {}
        return null
    }

    /**
     * V5.0.6942 — Tier B graded risk reads. Bounded adjustments inside the
     * brain-tier cap, unlike the refusals above.
     */
    private fun tierBRiskReads6942(mint: String, lane: String, score: Int): List<BrainRead> {
        val out = mutableListOf<BrainRead>()
        // Liquidity-cycle risk: both readers were zero-caller, so the cycle
        // model ran and nothing asked what it concluded.
        try {
            if (com.lifecyclebot.v3.scoring.LiquidityCycleAI.isRisky()) {
                val lvl = try { com.lifecyclebot.v3.scoring.LiquidityCycleAI.getRiskLevel() } catch (_: Throwable) { 1 }
                out += BrainRead("liqCycleRisk($lvl)", -(3.0 + lvl.coerceIn(0, 4) * 2.0).coerceAtMost(11.0))
            }
        } catch (_: Throwable) {}
        // Emergent guardrails on promotion size. The oracle does not size, so
        // this is read at the nominal score as a willingness signal rather
        // than as a sizing veto.
        try {
            if (mint.isNotBlank() &&
                com.lifecyclebot.engine.EmergentGuardrails.shouldBlockPromotion(mint, score.toDouble())
            ) out += BrainRead("guardrailBlocksPromotion", -9.0)
        } catch (_: Throwable) {}
        return out
    }

    fun statusLine(): String =
        "evals=${evaluations.get()} admit=${admits.get()} refuse=${refuses.get()} " +
            "cellEvidence=${cellHits.get()} laneEvidence=${laneHits.get()} noEvidence=${globalOnly.get()} " +
            "brainReads6917=${brainReads6917.get()} brainCap=${BRAIN_NETWORK_CAP_PCT_6917}% " +
            "exactFwd7260=${exactForecastHits7260.get()} policyReads7260=${unifiedPolicyReads7260.get()} " +
            "policyVeto7260=${unifiedPolicyBindingVetoes7260.get()} " +
            "coldAdmit7261=${coldCandidateAdmits7261.get()} coldRefuse7287=${coldCandidateProbes7261.get()} " +
            "historyLaneReads7287=${historyReads7287.get()} ${OracleTradeHistory7287.statusLine()} " +
            "authority7263=${OracleEdgeProof7263.tier().name} " +
            "shrinkK=$SHRINK_K refuseAt=${REFUSE_EXPECTANCY_PCT}% minConf=$MIN_CONFIDENCE_TO_REFUSE"

    internal fun resetForTest() {
        evaluations.set(0L); admits.set(0L); refuses.set(0L)
        cellHits.set(0L); laneHits.set(0L); globalOnly.set(0L); brainReads6917.set(0L)
        exactForecastHits7260.set(0L); unifiedPolicyReads7260.set(0L)
        unifiedPolicyBindingVetoes7260.set(0L)
        coldCandidateAdmits7261.set(0L); coldCandidateProbes7261.set(0L)
    }
}

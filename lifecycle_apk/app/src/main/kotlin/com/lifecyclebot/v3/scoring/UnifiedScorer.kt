package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.core.LayerHealthTracker
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.arb.ArbScannerAI
import com.lifecyclebot.v3.arb.ArbEvaluation
import com.lifecyclebot.v4.meta.CrossTalkFusionEngine
import com.lifecyclebot.v4.meta.AATESignal
import android.util.Log

/**
 * V3 Unified Scorer
 * Orchestrates all AI scoring modules
 * 
 * V3 MIGRATION: Added SuppressionAI to convert legacy invalidations
 * (COPY_TRADE_INVALIDATION, WHALE_ACCUMULATION_INVALIDATION, etc.)
 * into score penalties instead of hard blocks.
 * 
 * V3.1 EXPANSION: Added FearGreedAI and SocialVelocityAI
 * - FearGreedAI: Uses Alternative.me free API for market sentiment
 * - SocialVelocityAI: Uses DexScreener boosted tokens for social velocity
 * 
 * V3.2 EXPANSION: Added ArbScannerAI integration
 * - Parallel arb lane for short-horizon mispricing capture
 * - Three arb types: VENUE_LAG, FLOW_IMBALANCE, PANIC_REVERSION
 * 
 * V3.2 EXPANSION: Added 5 new AI layers
 * - VolatilityRegimeAI: Volatility regime detection and squeeze setups
 * - OrderFlowImbalanceAI: Buy/sell pressure detection before price moves
 * - SmartMoneyDivergenceAI: Whale behavior vs price divergence
 * - HoldTimeOptimizerAI: Optimal hold duration prediction
 * - LiquidityCycleAI: Market-wide liquidity cycle tracking
 * 
 * V3.2 EXPANSION: Added MetaCognitionAI (Layer 20)
 * - Self-aware "prefrontal cortex" that monitors all other AI layers
 * - Tracks accuracy of each layer, adjusts trust dynamically
 * - Detects consensus patterns that predict winners/losers
 * - Provides meta-confidence (confidence in our confidence)
 * - Can veto trades when reliable AIs disagree with consensus
 */
class UnifiedScorer(
    private val entryAI: EntryAI = EntryAI(),
    private val momentumAI: MomentumAI = MomentumAI(),
    private val liquidityAI: LiquidityAI = LiquidityAI(),
    private val volumeAI: VolumeProfileAI = VolumeProfileAI(),
    private val holderAI: HolderSafetyAI = HolderSafetyAI(),
    private val narrativeAI: NarrativeAI = NarrativeAI(),
    private val memoryAI: MemoryAI = MemoryAI(),
    private val regimeAI: MarketRegimeAI = MarketRegimeAI(),
    private val timeAI: TimeAI = TimeAI(),
    private val copyTradeAI: CopyTradeAI = CopyTradeAI(),
    private val suppressionAI: SuppressionAI = SuppressionAI(),  // V3 MIGRATION: Legacy suppression as penalty
    private val fearGreedAI: FearGreedAI = FearGreedAI(),        // V3.1: Market sentiment from Alternative.me
    private val socialVelocityAI: SocialVelocityAI = SocialVelocityAI()  // V3.1: Social momentum detection
) {
    /**
     * Score a candidate through all AI modules
     * Returns aggregated ScoreCard
     * 
     * V3.2: Now integrates MetaCognitionAI for:
     * - Recording predictions for each layer
     * - Adjusting total score based on meta-learning
     * - Adding metacognition component to scorecard
     */
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreCard {
        // V5.9.154 — bootstrap mode decision (see explanation below).
        val learningProgress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (_: Exception) { 0.0 }
        val bootstrapBypass = learningProgress < 0.40

        // V5.9.158 — FLUID LAYER MATURATION
        // "Everything's meant to be fluid from bootstrap thru to 5000+ maturity"
        // The 6 V3.2 "new" layers (volatility, orderflow, smartmoney, holdtime,
        // liquiditycycle, insider_tracker) were voting -10 to -20 at full
        // strength during bootstrap on tokens that literally have no history
        // for them to analyse. Stacking 3-4 of those negatives was crushing
        // every bootstrap-era candidate below the execute floor — "hard
        // scoring everything like it's fully learnt".
        //
        // New policy (mirrors the V5.9.123 stack's existing bypass idea but
        // applies it *smoothly* instead of as an on/off switch at 40%):
        //   NEGATIVE votes scale with learning progress:
        //     0% prog → ×0.10 (almost muted; stops veto-stacking)
        //     40% prog → ×0.46
        //     80% prog → ×0.82
        //     100% prog → ×1.00
        //   POSITIVE votes pass through at full strength — we still want
        //   the signal, we just don't want these unseasoned layers to
        //   veto during learning.
        val newLayerNegScale = (0.10 + learningProgress * 0.90).coerceIn(0.10, 1.0)
        val newLayerNames = setOf(
            "volatility", "orderflow", "smartmoney", "holdtime", "liquiditycycle",
            "insider_tracker",
            "correlationhedgeai", "liquidityexitpathai", "mevdetectionai",
            "stablecoinflowai", "operatorfingerprintai", "sessionedgeai",
            "executioncostpredictorai", "drawdowncircuitai", "capitalefficiencyai",
            "tokendnaclusteringai", "peeralphaverificationai", "newsshockai",
            "fundingrateawarenessai", "orderbookimbalancepulseai",
        )
        fun fluidScale(c: ScoreComponent): ScoreComponent {
            if (c.value >= 0) return c
            if (c.name.lowercase() !in newLayerNames) return c
            val scaled = (c.value * newLayerNegScale).toInt()
            return c.copy(
                value = scaled,
                reason = "${c.reason} | FLUID×${"%.2f".format(newLayerNegScale)}"
            )
        }

        // V5.9.210 — READ V4 CROSSTALK SNAPSHOT INTO V3 CONTEXT
        // V4 FinalDecisionEngine and mode router publish market-wide state here.
        // V3 layers can now "feel" the global market through this snapshot.
        // Kill flags from V4 (e.g. NO_MEME, RISK_OFF) add score penalty.
        val v4KillPenalty: Int = try {
            val snap = CrossTalkFusionEngine.getSnapshot()
            when {
                snap == null -> 0
                "NO_MEME" in snap.killFlags    -> -15  // V4 says no meme trades right now
                "NO_NEW_ENTRIES" in snap.killFlags -> -20
                "RISK_OFF" == snap.globalRiskMode.name -> -8
                "CHAOTIC" == snap.globalRiskMode.name  -> -5
                else -> 0
            }
        } catch (_: Exception) { 0 }

        // Classic 27-layer roster (matches V5.9.108 build ~#1915 exactly).
        val classic27Raw = listOf(
            sourceScoreWithTiming(candidate.source, candidate.mint),
            entryAI.score(candidate, ctx),
            momentumAI.score(candidate, ctx),
            liquidityAI.score(candidate, ctx),
            volumeAI.score(candidate, ctx),
            holderAI.score(candidate, ctx),
            narrativeAI.score(candidate, ctx),
            memoryAI.score(candidate, ctx),
            regimeAI.score(candidate, ctx),
            timeAI.score(candidate, ctx),
            copyTradeAI.score(candidate, ctx),
            suppressionAI.score(candidate, ctx),
            fearGreedAI.score(candidate, ctx),
            socialVelocityAI.score(candidate, ctx),
            VolatilityRegimeAI.score(candidate, ctx),
            OrderFlowImbalanceAI.score(candidate, ctx),
            SmartMoneyDivergenceAI.score(candidate, ctx),
            HoldTimeOptimizerAI.score(candidate, ctx),
            LiquidityCycleAI.score(candidate, ctx),
            insiderTrackerScore(candidate),
        )
        val classic27 = classic27Raw.map { fluidScale(it) }

        // Collect scores from all 19 base AI modules
        // V4.1: Use sourceScoreWithTiming for source timing lag penalty
        // V5.9.158: the V5.9.123 stack is still bypassed during bootstrap
        // (<40%), and once it re-engages at mature phase its negative votes
        // are fluid-scaled by newLayerNegScale (see top of fn).
        val preTrust = if (bootstrapBypass) classic27 else (classic27 + listOf(
            // V5.9.123 — Layers 28-42: correlation + exit-path + meta-trust
            // + macro + DNA + operator + session + drawdown + capital-efficiency
            // + news + funding + orderbook-pulse + peer-alpha + MEV + (ReflexAI
            //   is tick-driven, not entry-scored, so it's wired in Executor).
            CorrelationHedgeAI.score(candidate, ctx),
            LiquidityExitPathAI.score(candidate, ctx),
            MEVDetectionAI.score(candidate, ctx),
            StablecoinFlowAI.score(candidate, ctx),
            OperatorFingerprintAI.score(candidate, ctx),
            SessionEdgeAI.score(candidate, ctx),
            ExecutionCostPredictorAI.score(candidate, ctx),
            DrawdownCircuitAI.score(candidate, ctx),
            CapitalEfficiencyAI.score(candidate, ctx),
            TokenDNAClusteringAI.score(candidate, ctx),
            PeerAlphaVerificationAI.score(candidate, ctx),
            NewsShockAI.score(candidate, ctx),
            FundingRateAwarenessAI.score(candidate, ctx),
            OrderbookImbalancePulseAI.score(candidate, ctx),
        ).map { fluidScale(it) })

        // V5.9.154 — BOOTSTRAP BYPASS: restore the V5.9.108 (build ~#1915)
        // memetrader behaviour the user is asking for. During bootstrap the
        // scorer summed 27 plain layer votes and that was it; no trust
        // scaling, no V5.9.123 penalty stack, no approval memory nudge,
        // no auto-mute. User 04-23: 'look at the old build around 1900-1930
        // bootstrap settings and compare to the current ones for the main UI
        // memetrader'. A bootstrap-era token that scored +30 in the old
        // scorer loses 15-20 points today to trust×0.5 + V5.9.123 -25 cap +
        // auto-mute on unproven layers = rejected on the same floor that
        // used to let it through. This isn't learning, it's strangling.
        //
        // Below 40% learning progress, bypass every post-V5.9.108 stage.
        // Above 40%, full V5.9.123/124/139/140 stack re-engages so the
        // bot matures into the tighter regime on its own schedule.

        // V5.9.123 — AITrustNetworkAI meta-weighting. Each layer's positive
        // vote is scaled by its recent precision (0.4×–1.6×). Layers that
        // historically call winners are amplified; layers that fire on
        // losers are muted. Turns the static-50%-everywhere Trust panel
        // into an actual learning meta-layer.
        val preBaseComponents = if (bootstrapBypass) preTrust else run {
            preTrust.map { c ->
                if (c.value > 0) {
                    val w = AITrustNetworkAI.getTrustWeight(c.name)
                    c.copy(value = (c.value * w).toInt())
                } else c
            }
        }
        val baseComponents = preBaseComponents

        // V5.9.93: record per-layer health so zero-emit layers surface in logs
        try { LayerHealthTracker.record(baseComponents) } catch (_: Exception) {}

        // V5.9.210 — inject V4 CrossTalk kill penalty as a score component
        val v4Component = if (v4KillPenalty != 0) {
            val snap = try { CrossTalkFusionEngine.getSnapshot() } catch (_: Exception) { null }
            val reason = snap?.killFlags?.joinToString(",")?.ifBlank { snap?.globalRiskMode?.name } ?: "V4_SIGNAL"
            ScoreComponent("v4_crosstalk", v4KillPenalty, "🌐 V4: $reason")
        } else null
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.9.210 — MERGE V4 CROSSTALK KILL PENALTY into baseComponents before caps
        // v4Component was built just above from CrossTalkFusionEngine.getSnapshot()
        val baseComponentsWithV4 = if (v4Component != null) baseComponents + v4Component else baseComponents

        // V5.8: SOFT PENALTY CAP — cap combined negative from subjective/noisy factors
        // holders + narrative + social + holdtime uncapped = up to -8, drowning valid entries
        // Cap their combined negative at -5 so no single noisy signal can kill a trade alone
        // ═══════════════════════════════════════════════════════════════════════
        val softPenaltyNames = setOf("holders", "narrative", "social", "holdtime")
        val softPenaltyTotal = baseComponentsWithV4
            .filter { it.name.lowercase() in softPenaltyNames && it.value < 0 }
            .sumOf { it.value }
        val cappedBaseComponents = if (softPenaltyTotal < -5) {
            val scale = -5.0 / softPenaltyTotal
            baseComponentsWithV4.map { comp ->
                if (comp.name.lowercase() in softPenaltyNames && comp.value < 0) {
                    comp.copy(value = (comp.value * scale).toInt())
                } else comp
            }
        } else baseComponentsWithV4

        // ═══════════════════════════════════════════════════════════════════════
        // V5.9.124: V5.9.123-LAYER PENALTY CAP
        // The 14 new V5.9.123 scoring layers can cumulatively stack -138 in worst
        // case, which would veto every trade outright. Cap their combined
        // negative contribution at -25 so they add signal without creating
        // all-layer buy/sell blocks. Positive contributions remain uncapped.
        // ═══════════════════════════════════════════════════════════════════════
        val v59123LayerNames = setOf(
            "correlationhedgeai", "liquidityexitpathai", "mevdetectionai",
            "stablecoinflowai", "operatorfingerprintai", "sessionedgeai",
            "executioncostpredictorai", "drawdowncircuitai", "capitalefficiencyai",
            "tokendnaclusteringai", "peeralphaverificationai", "newsshockai",
            "fundingrateawarenessai", "orderbookimbalancepulseai"
        )
        val v59123PenaltyTotal = cappedBaseComponents
            .filter { it.name.lowercase() in v59123LayerNames && it.value < 0 }
            .sumOf { it.value }
        val v59123CappedComponents = if (v59123PenaltyTotal < -25) {
            val scale = -25.0 / v59123PenaltyTotal
            cappedBaseComponents.map { comp ->
                if (comp.name.lowercase() in v59123LayerNames && comp.value < 0) {
                    comp.copy(value = (comp.value * scale).toInt())
                } else comp
            }
        } else cappedBaseComponents

        // ═══════════════════════════════════════════════════════════════════════
        // COLLECTIVE INTELLIGENCE AI - Layer 21: Hive Mind Synthesis
        // Aggregates wisdom from all AATE instances via Turso collective learning
        // ═══════════════════════════════════════════════════════════════════════
        val collectiveComponent = try {
            val insight = CollectiveIntelligenceAI.score(
                mint = candidate.mint,
                symbol = candidate.symbol,
                source = candidate.source.name,  // Convert SourceType to String
                liquidityUsd = candidate.liquidityUsd,
                v3Score = v59123CappedComponents.sumOf { it.value },
                v3Confidence = 70  // Default, will be refined by MetaCognition
            )
            ScoreComponent(
                name = "COLLECTIVE_AI",
                value = insight.score,
                reason = "🧠 ${insight.reasoning} (${insight.signal.name}) conf=${insight.confidence}"
            )
        } catch (e: Exception) {
            ScoreComponent(
                name = "COLLECTIVE_AI",
                value = 0,
                reason = "🧠 NO_DATA"
            )
        }
        
        // Combine base components with collective intelligence
        val allComponents = v59123CappedComponents + collectiveComponent
        
        // ═══════════════════════════════════════════════════════════════════════
        // METACOGNITION AI - Layer 22: Self-Aware Executive Function
        // 
        // 1. Record predictions from all layers (for learning)
        // 2. Calculate meta-confidence (how much to trust this decision)
        // 3. Adjust score based on layer accuracy history
        // 4. Check for veto conditions (reliable AIs disagree)
        // ═══════════════════════════════════════════════════════════════════════
        
        try {
            // Record predictions for future correlation with outcomes
            MetaCognitionAI.recordFromScoreCard(candidate.mint, candidate.symbol, allComponents)
            
            // Build predictions list for meta-analysis
            val predictions = allComponents.mapNotNull { comp ->
                val layer = mapComponentNameToLayer(comp.name) ?: return@mapNotNull null
                val signal = when {
                    comp.value > 5 -> MetaCognitionAI.SignalType.BULLISH
                    comp.value < -5 -> MetaCognitionAI.SignalType.BEARISH
                    else -> MetaCognitionAI.SignalType.NEUTRAL
                }
                MetaCognitionAI.Prediction(
                    layer = layer,
                    signal = signal,
                    confidence = (50.0 + comp.value * 2).coerceIn(0.0, 100.0),
                    rawScore = comp.value.toDouble(),
                )
            }
            
            // Calculate meta-confidence
            val metaResult = MetaCognitionAI.calculateMetaConfidence(predictions)
            
            // Calculate base total and get meta-adjusted score
            val baseTotal = allComponents.sumOf { it.value }
            val adjustedTotal = MetaCognitionAI.adjustScore(baseTotal, predictions)
            val metaAdjustment = adjustedTotal - baseTotal
            
            // Check for veto (reliable AIs disagree with consensus)
            val vetoReason = MetaCognitionAI.checkVeto(predictions, metaResult.confidence)
            
            // Create meta-cognition component
            // V5.9.177 — the user reported "new AI layers created a huge block".
            // MetaCognitionAI's VETO used to fatal-kill every trade where
            // meta-confidence < 20 — which is exactly what happens in paper
            // bootstrap when no layer has a calibrated track record yet.
            // Policy now: VETO stays visible as a scoring penalty, but it is
            // only FATAL when (a) not in bootstrap paper and (b) meta-conf
            // is extremely low. Live mode keeps the original guardrail.
            val paperBootstrap = ctx.mode != com.lifecyclebot.v3.core.V3BotMode.LIVE && learningProgress < 0.40
            val metaFatal = !paperBootstrap && vetoReason != null && metaResult.confidence < 20
            val metaComponent = ScoreComponent(
                name = "metacognition",
                value = metaAdjustment,
                reason = if (vetoReason != null) {
                    val tag = if (metaFatal) "VETO" else "VETO_SOFT"
                    "$tag: $vetoReason | ${metaResult.summary()}"
                } else {
                    metaResult.summary()
                },
                fatal = metaFatal,
            )
            
            // ═══════════════════════════════════════════════════════════════════════
            // BEHAVIOR AI - Layer 25: Trading Behavior Pattern Recognition
            // Integrates trading behavior (streaks, tilt, discipline) into scoring
            // ═══════════════════════════════════════════════════════════════════════
            val behaviorComponent = try {
                // Check for tilt protection (soft block with 1 min cooldown)
                if (BehaviorAI.isTiltProtectionActive()) {
                    val remaining = BehaviorAI.getTiltProtectionRemaining()
                    ScoreComponent(
                        name = "behavior",
                        // V4.0: Reduced penalty from -50 to -15 since cooldown is only 1 min
                        // Don't want to completely kill trades, just add caution
                        value = -15,
                        reason = "🛑 Tilt cooldown: ${remaining}s",
                        fatal = false  // V4.0: No longer fatal - let other factors decide
                    )
                } else {
                    val scoreAdj = BehaviorAI.getScoreAdjustment()
                    val state = BehaviorAI.getState()
                    ScoreComponent(
                        name = "behavior",
                        value = scoreAdj,
                        reason = "${state.sentimentClass} | streak=${state.currentStreak} | " +
                            "tilt=${state.tiltLevel}% disc=${state.disciplineScore}%"
                    )
                }
            } catch (e: Exception) {
                ScoreComponent(
                    name = "behavior",
                    value = 0,
                    reason = "NO_DATA"
                )
            }
            
            // Log significant meta-cognition insights
            if (metaAdjustment != 0 || vetoReason != null) {
                Log.i("UnifiedScorer", "🧠 META: ${candidate.symbol} | adj=$metaAdjustment | " +
                    "conf=${metaResult.confidence.toInt()}% | ${metaResult.dominantSignal}" +
                    (if (vetoReason != null) " | VETO" else ""))
            }
            
            // V5.9.139 — APPROVAL MEMORY BOOST
            // Ask EducationSubLayerAI whether the current bullish vote set
            // matches a previously-winning approval pattern. Positive
            // expectancy patterns get a +3..+10 nudge; actively bad ones
            // get a -6..-2 penalty. This is the "learn from good behaviour"
            // channel the bot has been missing — balances the reject-side
            // learning from ShadowLearningEngine.
            // V5.9.154: bypass during bootstrap — no pattern has earned
            // its weight yet, we want raw signal only.
            val votesForApproval = (v59123CappedComponents + metaComponent + behaviorComponent)
                .associate { it.name to it.value }
            val (approvalNudge, approvalReason) = if (bootstrapBypass) 0 to "BOOTSTRAP" else try {
                EducationSubLayerAI.approvalBoostFor(votesForApproval)
            } catch (_: Exception) { 0 to "ERR" }
            val approvalComponent = ScoreComponent(
                name = "approval_memory",
                value = approvalNudge,
                reason = approvalReason,
            )

            // V5.9.140 — AUTO-MUTE / AUTO-BOOST
            // Wrap each component's vote through EducationSubLayerAI so
            // layers that have actually proven their edge get amplified
            // (×1.25 / ×1.45) and layers below 40% smoothed edge get
            // muted (×0). The reason is appended so the scorer log line
            // shows WHY a particular layer's voice was silenced.
            val mutedNames = mutableListOf<String>()
            val boostedNames = mutableListOf<String>()
            val allComponentsRaw = v59123CappedComponents + metaComponent + behaviorComponent + approvalComponent
            val gatedComponents = allComponentsRaw.map { c ->
                if (c.value == 0) return@map c
                val layerName = EducationSubLayerAI.normalizeComponentName(c.name)
                val (newVote, mult, status) = try {
                    EducationSubLayerAI.applyMuteBoost(layerName, c.value)
                } catch (_: Exception) { Triple(c.value, 1.0, "NORMAL") }
                when (status) {
                    "MUTE"        -> mutedNames.add(layerName)
                    "SOFT_PENALTY"-> mutedNames.add("$layerName(soft)")
                    "BOOST"       -> boostedNames.add(layerName)
                    "HEAVY_BOOST" -> boostedNames.add("$layerName(hvy)")
                }
                if (mult == 1.0) c else c.copy(
                    value = newVote,
                    reason = "${c.reason} | GATE=${status} ×${"%.2f".format(mult)}"
                )
            }
            if (mutedNames.isNotEmpty() || boostedNames.isNotEmpty()) {
                Log.i("UnifiedScorer",
                    "🛡️ GATE ${candidate.symbol} | muted=${mutedNames.joinToString(",")} | " +
                    "boosted=${boostedNames.joinToString(",")}"
                )
            }

            // Return scorecard with gated components (mute/boost applied).
            val finalCard = ScoreCard(gatedComponents)
            // V5.9.126 — capture entry scores for real per-layer accuracy learning
            try { EducationSubLayerAI.recordEntryScores(candidate.mint, finalCard.components) } catch (_: Exception) {}

            // V5.9.210 — PUBLISH V3 UNIVERSE STATE TO V4 CROSSTALK BUS
            // Every scored candidate pushes a signal into CrossTalkFusionEngine so
            // V4 layers (FinalDecisionEngine, ModeRouter) can see what V3 is thinking.
            // This is the "V3 and V4 need to talk to each other" wire.
            // Confidence is derived from the normalised total score (0–100 range maps to 0–1).
            try {
                val totalScore = finalCard.total
                val normConf = ((totalScore + 50.0) / 150.0).coerceIn(0.0, 1.0)
                val direction = if (totalScore > 0) "LONG" else if (totalScore < -10) "SHORT" else null
                CrossTalkFusionEngine.publish(
                    AATESignal(
                        source    = "UnifiedScorerV3",
                        market    = "MEME",
                        symbol    = candidate.symbol,
                        confidence = normConf,
                        direction  = direction,
                        horizonSec = 300,
                        regimeTag  = "V3_SCORED",
                        narrativeHeat = null,
                        riskFlags  = if (totalScore < 0) listOf("V3_BEARISH") else emptyList(),
                    )
                )
            } catch (_: Exception) {}

            return finalCard

        } catch (e: Exception) {
            Log.w("UnifiedScorer", "MetaCognitionAI error: ${e.message}")
            val fallbackCard = ScoreCard(v59123CappedComponents)
            try { EducationSubLayerAI.recordEntryScores(candidate.mint, fallbackCard.components) } catch (_: Exception) {}
            return fallbackCard
        }
    }
    
    /**
     * Map score component names to MetaCognition AI layers
     */
    // V5.9.224 — EXPANDED: all 41 scoring layers now mapped.
    // Previously the 16 V5.9.123 layers (CorrelationHedgeAI, MEVDetectionAI, etc.)
    // returned null here → MetaCognitionAI was completely blind to their accuracy,
    // trust calibration, and contribution to veto/consensus decisions.
    private fun mapComponentNameToLayer(name: String): MetaCognitionAI.AILayer? = when (name.lowercase()) {
        // Legacy V3 core layers
        "source" -> null
        "entry" -> MetaCognitionAI.AILayer.ENTRY_INTELLIGENCE
        "momentum" -> MetaCognitionAI.AILayer.MOMENTUM_PREDICTOR
        "liquidity" -> MetaCognitionAI.AILayer.LIQUIDITY_DEPTH
        "volume" -> MetaCognitionAI.AILayer.ORDER_FLOW_IMBALANCE
        "holders" -> null
        "narrative" -> MetaCognitionAI.AILayer.NARRATIVE_DETECTOR
        "memory" -> MetaCognitionAI.AILayer.TOKEN_WIN_MEMORY
        "regime" -> MetaCognitionAI.AILayer.MARKET_REGIME
        "time" -> MetaCognitionAI.AILayer.TIME_OPTIMIZATION
        "copytrade" -> null
        "suppression" -> null
        "feargreed" -> MetaCognitionAI.AILayer.FEAR_GREED
        "social" -> MetaCognitionAI.AILayer.SOCIAL_VELOCITY
        "volatility" -> MetaCognitionAI.AILayer.VOLATILITY_REGIME
        "orderflow" -> MetaCognitionAI.AILayer.ORDER_FLOW_IMBALANCE
        "smartmoney" -> MetaCognitionAI.AILayer.SMART_MONEY_DIVERGENCE
        "holdtime" -> MetaCognitionAI.AILayer.HOLD_TIME_OPTIMIZER
        "liquiditycycle" -> MetaCognitionAI.AILayer.LIQUIDITY_CYCLE
        "collective_ai" -> MetaCognitionAI.AILayer.COLLECTIVE_INTELLIGENCE
        // V5.9.123 — 16 new layers (previously ALL returned null = invisible to MetaCog)
        "correlationhedgeai" -> MetaCognitionAI.AILayer.CORRELATION_HEDGE
        "liquidityexitpathai" -> MetaCognitionAI.AILayer.LIQUIDITY_EXIT_PATH
        "mevdetectionai" -> MetaCognitionAI.AILayer.MEV_DETECTION
        "stablecoinflowai" -> MetaCognitionAI.AILayer.STABLECOIN_FLOW
        "operatorfingerprintai" -> MetaCognitionAI.AILayer.OPERATOR_FINGERPRINT
        "sessionedgeai" -> MetaCognitionAI.AILayer.SESSION_EDGE
        "executioncostpredictorai" -> MetaCognitionAI.AILayer.EXECUTION_COST_PREDICTOR
        "drawdowncircuitai" -> MetaCognitionAI.AILayer.DRAWDOWN_CIRCUIT
        "capitalefficiencyai" -> MetaCognitionAI.AILayer.CAPITAL_EFFICIENCY
        "tokendnaclusteringai" -> MetaCognitionAI.AILayer.TOKEN_DNA_CLUSTERING
        "peeralphaverficationai", "peeralphaverificationai" -> MetaCognitionAI.AILayer.PEER_ALPHA_VERIFICATION
        "newsshockai" -> MetaCognitionAI.AILayer.NEWS_SHOCK
        "fundingrateawarenessai" -> MetaCognitionAI.AILayer.FUNDING_RATE_AWARENESS
        "orderbookimbalancepulseai" -> MetaCognitionAI.AILayer.ORDERBOOK_IMBALANCE_PULSE
        "aitrustnetworkai" -> MetaCognitionAI.AILayer.AI_TRUST_NETWORK
        "reflexai" -> MetaCognitionAI.AILayer.REFLEX_AI
        // Trader-class layers
        "shitcoin", "shitcointraderai" -> MetaCognitionAI.AILayer.SHITCOIN_TRADER
        "quality", "qualitytraderai" -> MetaCognitionAI.AILayer.QUALITY_TRADER
        "moonshot", "moonshottraderai" -> MetaCognitionAI.AILayer.MOONSHOT_TRADER
        "bluechip", "bluechiptraderai" -> MetaCognitionAI.AILayer.BLUECHIP_TRADER
        "diphunter", "diphunterai" -> MetaCognitionAI.AILayer.DIP_HUNTER
        "metacognition" -> null  // Don't self-reference
        "behavior" -> MetaCognitionAI.AILayer.BEHAVIOR_AI
        "approval_memory" -> null
        "insider_tracker" -> MetaCognitionAI.AILayer.INSIDER_TRACKER
        else -> null
    }
    
    /**
     * Score a candidate AND evaluate for arb opportunities.
     * Returns pair of (ScoreCard, ArbEvaluation?)
     * 
     * Use this for parallel arb lane processing.
     */
    fun scoreWithArb(candidate: CandidateSnapshot, ctx: TradingContext): Pair<ScoreCard, ArbEvaluation?> {
        // Record source timing for arb tracking
        ArbScannerAI.recordSourceSeen(candidate)
        
        // Run normal scoring
        val scoreCard = score(candidate, ctx)
        
        // Run arb evaluation in parallel
        val arbEval = try {
            ArbScannerAI.evaluate(candidate, ctx)
        } catch (e: Exception) {
            null  // Silently ignore arb errors
        }
        
        return Pair(scoreCard, arbEval)
    }
    
    /**
     * Get list of all module names (now 26 with InsiderTrackerAI)
     */
    fun moduleNames(): List<String> = listOf(
        "source", "entry", "momentum", "liquidity", "volume",
        "holders", "narrative", "memory", "regime", "time", 
        "copytrade", "suppression", "feargreed", "social",
        "volatility", "orderflow", "smartmoney", "holdtime", "liquiditycycle",
        "collective_ai",     // Layer 21: Hive mind collective intelligence
        "metacognition",     // Layer 22: Self-aware executive function
        "fluid_learning",    // Layer 23: Centralized fluidity control
        "sell_optimization", // Layer 24: Intelligent exit strategy
        "behavior",          // Layer 25: Trading behavior pattern recognition
        "insider_tracker",   // Layer 27: Insider wallet monitoring (Trump/Pelosi/Whales)
    )
    
    /**
     * V5.7.4: Score based on Insider Tracker signals (Trump/Pelosi/Whale wallets)
     */
    private fun insiderTrackerScore(candidate: CandidateSnapshot): ScoreComponent {
        return try {
            // Check if insider tracker has signals for this token
            val entryBoost = InsiderTrackerAI.getEntryBoost(candidate.mint, candidate.symbol)
            val shouldAvoid = InsiderTrackerAI.shouldAvoid(candidate.mint)
            
            when {
                shouldAvoid -> ScoreComponent(
                    name = "insider_tracker",
                    value = -15,
                    reason = "🚨 INSIDER SELLING: Distribution detected from tracked wallets"
                )
                entryBoost >= 20 -> ScoreComponent(
                    name = "insider_tracker",
                    value = entryBoost,
                    reason = "🔥 ALPHA SIGNAL: Strong insider accumulation detected"
                )
                entryBoost >= 10 -> ScoreComponent(
                    name = "insider_tracker",
                    value = entryBoost,
                    reason = "💰 INSIDER BUY: Tracked wallet accumulating"
                )
                entryBoost > 0 -> ScoreComponent(
                    name = "insider_tracker",
                    value = entryBoost,
                    reason = "📡 Insider activity detected"
                )
                else -> ScoreComponent(
                    name = "insider_tracker",
                    value = 0,
                    reason = "No insider signals"
                )
            }
        } catch (e: Exception) {
            ScoreComponent(
                name = "insider_tracker",
                value = 0,
                reason = "NO_DATA"
            )
        }
    }
}

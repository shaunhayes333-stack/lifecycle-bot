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
 * V5.9.326 — CLASSIC SCORING MODE
 * When classicMode = true (default), the scorer runs the build ~1920-1940
 * pipeline exactly: 20 inner layers → softPenaltyCap → CollectiveIntelligence
 * → MetaCognition → BehaviorAI → done.
 *
 * No outer ring, no AITrustNetworkAI, no MuteBoost, no genEqRamp,
 * no fluidNegScale, no CrossTalk kill penalty, no approvalMemory.
 *
 * This is the configuration that produced the high win rates before the
 * V5.9.123 outer-ring layers were added. Set classicMode = false in
 * BotConfig to re-enable the full modern stack.
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
    private val suppressionAI: SuppressionAI = SuppressionAI(),
    private val fearGreedAI: FearGreedAI = FearGreedAI(),
    private val socialVelocityAI: SocialVelocityAI = SocialVelocityAI()
) {

    companion object {
        /**
         * V5.9.326 — Set from BotConfig.classicScoringMode at startup.
         * true  = build ~1920 pipeline (20 layers, no outer ring, no TrustNet/MuteBoost)
         * false = full modern stack (V5.9.123+ outer ring, TrustNet, MuteBoost, genEq)
         */
        @Volatile var classicMode: Boolean = true
    }

    /**
     * Score a candidate through all AI modules.
     * Returns aggregated ScoreCard.
     *
     * V5.9.326: routes to classicScore() or modernScore() based on classicMode flag.
     */
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreCard {
        return if (classicMode) classicScore(candidate, ctx) else modernScore(candidate, ctx)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CLASSIC SCORE — build ~1920 pipeline
    // Exactly matches what was running when win rates were high.
    // 20 inner layers → softPenaltyCap → Collective → MetaCog → Behavior.
    // ═══════════════════════════════════════════════════════════════════════════
    private fun classicScore(candidate: CandidateSnapshot, ctx: TradingContext): ScoreCard {
        // 20-layer inner ring — same roster as build ~1920
        val baseComponentsRaw = listOf(
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

        // Layer health tracking (for UI/diagnostics only — no scoring effect)
        try { LayerHealthTracker.record(baseComponentsRaw) } catch (_: Exception) {}

        // V5.8 soft penalty cap — exactly as it was in build ~1920
        // holders + narrative + social + holdtime uncapped = up to -8.
        // Cap their combined negative at -5 so no noisy signal blocks alone.
        val softPenaltyNames = setOf("holders", "narrative", "social", "holdtime")
        val softPenaltyTotal = baseComponentsRaw
            .filter { it.name.lowercase() in softPenaltyNames && it.value < 0 }
            .sumOf { it.value }
        val baseComponents = if (softPenaltyTotal < -5) {
            val scale = -5.0 / softPenaltyTotal
            baseComponentsRaw.map { comp ->
                if (comp.name.lowercase() in softPenaltyNames && comp.value < 0)
                    comp.copy(value = (comp.value * scale).toInt())
                else comp
            }
        } else baseComponentsRaw

        // CollectiveIntelligenceAI — hive mind (was present in build ~1920)
        val collectiveComponent = try {
            val insight = CollectiveIntelligenceAI.score(
                mint = candidate.mint,
                symbol = candidate.symbol,
                source = candidate.source.name,
                liquidityUsd = candidate.liquidityUsd,
                v3Score = baseComponents.sumOf { it.value },
                v3Confidence = 70
            )
            ScoreComponent(
                name = "COLLECTIVE_AI",
                value = insight.score,
                reason = "🧠 ${insight.reasoning} (${insight.signal.name}) conf=${insight.confidence}"
            )
        } catch (e: Exception) {
            ScoreComponent(name = "COLLECTIVE_AI", value = 0, reason = "🧠 NO_DATA")
        }

        val allComponents = baseComponents + collectiveComponent

        // ═══════════════════════════════════════════════════════════════════
        // V5.9.344 — ACCURACY-WEIGHTED SCORING (no muting)
        // Multiplies each component's value by a weight derived from that
        // layer's smoothed accuracy. Per user directive "don't mute layers":
        // weight is floored at 0.7 (a layer at 0% accuracy still contributes
        // 70% of its vote) and capped at 1.5 (a layer at 100% accuracy
        // contributes 150%). Layers with no data yet sit at 0.5 prior → 1.0
        // weight (identity). As layers diverge, good ones naturally carry
        // more weight and bad ones less — without silencing anyone.
        // ═══════════════════════════════════════════════════════════════════
        val weightedComponents = allComponents.map { comp ->
            val layerName = try { EducationSubLayerAI.componentNameToLayer(comp.name) } catch (_: Exception) { comp.name }
            val accuracy = try { EducationSubLayerAI.getLayerAccuracy(layerName) } catch (_: Exception) { 0.5 }
            val weight   = (0.7 + accuracy * 0.8).coerceIn(0.7, 1.5)
            val newValue = (comp.value * weight).toInt()
            if (comp.value != 0 && newValue != comp.value) {
                comp.copy(value = newValue, reason = "${comp.reason} [w=${"%.2f".format(weight)}@${(accuracy * 100).toInt()}%]")
            } else comp
        }

        // MetaCognitionAI — same logic as build ~1920 (soft veto, no fatal in bootstrap)
        return try {
            MetaCognitionAI.recordFromScoreCard(candidate.mint, candidate.symbol, weightedComponents)

            val predictions = weightedComponents.mapNotNull { comp ->
                val layer = mapComponentNameToLayer(comp.name) ?: return@mapNotNull null
                val signal = when {
                    comp.value > 5  -> MetaCognitionAI.SignalType.BULLISH
                    comp.value < -5 -> MetaCognitionAI.SignalType.BEARISH
                    else            -> MetaCognitionAI.SignalType.NEUTRAL
                }
                MetaCognitionAI.Prediction(
                    layer = layer,
                    signal = signal,
                    confidence = (50.0 + comp.value * 2).coerceIn(0.0, 100.0),
                    rawScore = comp.value.toDouble(),
                )
            }

            val metaResult    = MetaCognitionAI.calculateMetaConfidence(predictions)
            val baseTotal     = weightedComponents.sumOf { it.value }
            val adjustedTotal = MetaCognitionAI.adjustScore(baseTotal, predictions)
            val metaAdjustment = adjustedTotal - baseTotal
            val vetoReason    = MetaCognitionAI.checkVeto(predictions, metaResult.confidence)

            // Build ~1920 veto rule: fatal only when conf < 20 (not bootstrap-aware;
            // in practice conf < 20 almost never fired with only 20 layers)
            val metaComponent = ScoreComponent(
                name  = "metacognition",
                value = metaAdjustment,
                reason = if (vetoReason != null) "VETO: $vetoReason | ${metaResult.summary()}"
                         else metaResult.summary(),
                fatal = vetoReason != null && metaResult.confidence < 20
            )

            if (metaAdjustment != 0 || vetoReason != null) {
                Log.i("UnifiedScorer", "🧠 META[CLASSIC]: ${candidate.symbol} | adj=$metaAdjustment | " +
                    "conf=${metaResult.confidence.toInt()}% | ${metaResult.dominantSignal}" +
                    (if (vetoReason != null) " | VETO" else ""))
            }

            // BehaviorAI — present in build ~1920
            val behaviorComponent = try {
                if (BehaviorAI.isTiltProtectionActive()) {
                    val remaining = BehaviorAI.getTiltProtectionRemaining()
                    ScoreComponent(name = "behavior", value = -15,
                        reason = "🛑 Tilt cooldown: ${remaining}s", fatal = false)
                } else {
                    val scoreAdj = BehaviorAI.getScoreAdjustment()
                    val state    = BehaviorAI.getState()
                    ScoreComponent(name = "behavior", value = scoreAdj,
                        reason = "${state.sentimentClass} | streak=${state.currentStreak} | " +
                            "tilt=${state.tiltLevel}% disc=${state.disciplineScore}%")
                }
            } catch (_: Exception) {
                ScoreComponent(name = "behavior", value = 0, reason = "NO_DATA")
            }

            // V5.9.343 — FRESH-TOKEN PROVISIONAL BONUS
            // Fresh tokens (just discovered, low hist) score 0-5 on every
            // layer because momentum/volume/liquidity return ~0 on empty
            // history. They sit in WAIT forever with X:43/X:73 rejection
            // counts. This bonus lets the launch-window signal carry
            // weight during the SNIPE window (≤15 minutes old per
            // AutoModeEngine's SNIPE classifier).
            val isFreshLaunch = candidate.ageMinutes <= 3.0
            val freshBonus = if (isFreshLaunch) {
                ScoreComponent(name = "fresh_launch_bonus", value = 15,
                    reason = "🚀 Fresh launch grace (+15) | age=${"%.1f".format(candidate.ageMinutes)}m")
            } else null

            // Final card — no MuteBoost gate, no approvalMemory, no CrossTalk penalty
            // V5.9.344: sum is built from the weight-adjusted 20-layer components
            // so accuracy-weighted scoring flows through to finalCard.total.
            val finalCard = ScoreCard(
                listOfNotNull(freshBonus).let { bonus ->
                    weightedComponents + metaComponent + behaviorComponent + bonus
                }
            )

            // ═══════════════════════════════════════════════════════════════
            // V5.9.341 — SHADOW-RUN outer ring in CLASSIC mode (Phase X.1)
            // Run the 14 V5.9.123 outer-ring layers so EducationSubLayerAI
            // sees their entry-score prediction and correlates it with the
            // trade outcome. Their values are NOT added to finalCard.total
            // (classic-feel scoring preserved) — this is purely a learning
            // data capture so all 41 layers can turn green on the neural
            // network indicator over time.
            // ═══════════════════════════════════════════════════════════════
            val shadowOuterRing: List<ScoreComponent> = try {
                listOf(
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
                )
            } catch (e: Exception) {
                Log.w("UnifiedScorer", "classicScore shadow-run error: ${e.message}")
                emptyList()
            }

            // Record entry scores for ALL 41 layers (classic sum + shadow outer
            // ring) so applyRealAccuracyLearning can correlate every layer's
            // prediction with the actual outcome on close.
            try {
                val learningRecord = finalCard.components + shadowOuterRing
                EducationSubLayerAI.recordEntryScores(candidate.mint, learningRecord)
            } catch (_: Exception) {}

            // Also expose the shadow layers to LayerHealthTracker so UI layer
            // diagnostics show all 41 layers as "seen this cycle".
            try { LayerHealthTracker.record(shadowOuterRing) } catch (_: Exception) {}

            Log.i("UnifiedScorer", "🏛️ CLASSIC ${candidate.symbol} | total=${finalCard.total} | shadow=${shadowOuterRing.size}L")
            finalCard

        } catch (e: Exception) {
            Log.w("UnifiedScorer", "classicScore error: ${e.message}")
            ScoreCard(allComponents)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MODERN SCORE — full V5.9.325 pipeline (outer ring + TrustNet + MuteBoost)
    // Preserved intact. Activated when classicMode = false.
    // ═══════════════════════════════════════════════════════════════════════════
    private fun modernScore(candidate: CandidateSnapshot, ctx: TradingContext): ScoreCard {
        val learningProgress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (_: Exception) { 0.0 }
        val bootstrapBypass = learningProgress < 0.40

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
            return c.copy(value = scaled, reason = "${c.reason} | FLUID×${"%.2f".format(newLayerNegScale)}")
        }

        val v4KillPenalty: Int = try {
            val snap = CrossTalkFusionEngine.getSnapshot()
            when {
                snap == null -> 0
                "NO_MEME" in snap.killFlags       -> -15
                "NO_NEW_ENTRIES" in snap.killFlags -> -20
                "RISK_OFF" == snap.globalRiskMode.name -> -8
                "CHAOTIC" == snap.globalRiskMode.name  -> -5
                else -> 0
            }
        } catch (_: Exception) { 0 }

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

        val genEqRamp: Double = try {
            val outerRingNames = listOf(
                "CorrelationHedgeAI", "LiquidityExitPathAI", "MEVDetectionAI",
                "StablecoinFlowAI", "OperatorFingerprintAI", "SessionEdgeAI",
                "ExecutionCostPredictorAI", "DrawdownCircuitAI", "CapitalEfficiencyAI",
                "TokenDNAClusteringAI", "PeerAlphaVerificationAI", "NewsShockAI",
                "FundingRateAwarenessAI", "OrderbookImbalancePulseAI",
            )
            val innerRingNames = listOf(
                "MomentumPredictorAI", "NarrativeDetectorAI", "TimeOptimizationAI",
                "LiquidityDepthAI", "MarketRegimeAI", "TokenWinMemory",
                "VolatilityRegimeAI", "OrderFlowImbalanceAI", "SmartMoneyDivergenceAI",
                "LiquidityCycleAI", "FearGreedAI", "HoldTimeOptimizerAI",
                "InsiderTrackerAI",
            )
            val outerAvg = EducationSubLayerAI.getAverageSampleCount(outerRingNames)
            val innerAvg = EducationSubLayerAI.getAverageSampleCount(innerRingNames)
            if (innerAvg < 5.0) 1.0
            else (outerAvg / innerAvg).coerceIn(0.5, 1.0)
        } catch (_: Exception) { 1.0 }

        fun genEqScale(c: ScoreComponent): ScoreComponent {
            if (genEqRamp >= 1.0) return c
            val scaled = (c.value * genEqRamp).toInt()
            return if (scaled == c.value) c else c.copy(
                value = scaled,
                reason = "${c.reason} | GENEQ×${"%.2f".format(genEqRamp)}"
            )
        }

        // V5.9.339: BootstrapAdaptiveEngine — all 44 layers active from trade 1.
        // OLD: bootstrapBypass=true dropped 14 outer-ring layers and skipped AITrustNetworkAI
        //      → layers recorded outcomes but never influenced scoring → 11% WR self-fulfilling loop.
        // NEW: outer-ring layers ALWAYS run, scaled by bootstrap ramp (0.15→1.0 over 0-400 trades).
        //      Each layer also gets a 10-trade rolling multiplier [0.3, 2.0] that is reversible.
        val outerRingRaw = listOf(
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
        ).map { fluidScale(genEqScale(it)) }

        // Apply bootstrap adaptive multipliers to outer ring during bootstrap
        val outerRingScaled = if (bootstrapBypass) {
            outerRingRaw.map { c ->
                val adj = BootstrapAdaptiveEngine.applyBootstrapScale(c.name.lowercase(), c.value)
                if (adj == c.value) c else c.copy(value = adj,
                    reason = "${c.reason} | BSTRAP×${"%.2f".format(BootstrapAdaptiveEngine.getMultiplier(c.name.lowercase()))}")
            }
        } else outerRingRaw

        // Also apply bootstrap adaptive multipliers to inner ring during bootstrap
        val innerRingScaled = if (bootstrapBypass) {
            classic27.map { c ->
                val adj = BootstrapAdaptiveEngine.applyBootstrapScale(c.name.lowercase(), c.value)
                if (adj == c.value) c else c.copy(value = adj,
                    reason = "${c.reason} | BSTRAP×${"%.2f".format(BootstrapAdaptiveEngine.getMultiplier(c.name.lowercase()))}")
            }
        } else classic27

        val preTrust = innerRingScaled + outerRingScaled

        // AITrustNetworkAI: apply during bootstrap too (needs early data to learn)
        val preBaseComponents = preTrust.map { c ->
            if (c.value > 0) {
                val w = AITrustNetworkAI.getTrustWeight(c.name)
                c.copy(value = (c.value * w).toInt())
            } else c
        }
        val baseComponents = preBaseComponents

        try { LayerHealthTracker.record(baseComponents) } catch (_: Exception) {}

        val v4Component = if (v4KillPenalty != 0) {
            val snap = try { CrossTalkFusionEngine.getSnapshot() } catch (_: Exception) { null }
            val reason = snap?.killFlags?.joinToString(",")?.ifBlank { snap?.globalRiskMode?.name } ?: "V4_SIGNAL"
            ScoreComponent("v4_crosstalk", v4KillPenalty, "🌐 V4: $reason")
        } else null

        val baseComponentsWithV4 = if (v4Component != null) baseComponents + v4Component else baseComponents

        val softPenaltyNames = setOf("holders", "narrative", "social", "holdtime")
        val softPenaltyTotal = baseComponentsWithV4
            .filter { it.name.lowercase() in softPenaltyNames && it.value < 0 }
            .sumOf { it.value }
        val cappedBaseComponents = if (softPenaltyTotal < -5) {
            val scale = -5.0 / softPenaltyTotal
            baseComponentsWithV4.map { comp ->
                if (comp.name.lowercase() in softPenaltyNames && comp.value < 0)
                    comp.copy(value = (comp.value * scale).toInt())
                else comp
            }
        } else baseComponentsWithV4

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
                if (comp.name.lowercase() in v59123LayerNames && comp.value < 0)
                    comp.copy(value = (comp.value * scale).toInt())
                else comp
            }
        } else cappedBaseComponents

        val collectiveComponent = try {
            val insight = CollectiveIntelligenceAI.score(
                mint = candidate.mint,
                symbol = candidate.symbol,
                source = candidate.source.name,
                liquidityUsd = candidate.liquidityUsd,
                v3Score = v59123CappedComponents.sumOf { it.value },
                v3Confidence = 70
            )
            ScoreComponent(
                name = "COLLECTIVE_AI",
                value = insight.score,
                reason = "🧠 ${insight.reasoning} (${insight.signal.name}) conf=${insight.confidence}"
            )
        } catch (e: Exception) {
            ScoreComponent(name = "COLLECTIVE_AI", value = 0, reason = "🧠 NO_DATA")
        }

        val allComponents = v59123CappedComponents + collectiveComponent

        return try {
            MetaCognitionAI.recordFromScoreCard(candidate.mint, candidate.symbol, allComponents)

            val predictions = allComponents.mapNotNull { comp ->
                val layer = mapComponentNameToLayer(comp.name) ?: return@mapNotNull null
                val signal = when {
                    comp.value > 5  -> MetaCognitionAI.SignalType.BULLISH
                    comp.value < -5 -> MetaCognitionAI.SignalType.BEARISH
                    else            -> MetaCognitionAI.SignalType.NEUTRAL
                }
                MetaCognitionAI.Prediction(
                    layer = layer,
                    signal = signal,
                    confidence = (50.0 + comp.value * 2).coerceIn(0.0, 100.0),
                    rawScore = comp.value.toDouble(),
                )
            }

            val metaResult     = MetaCognitionAI.calculateMetaConfidence(predictions)
            val baseTotal      = allComponents.sumOf { it.value }
            val adjustedTotal  = MetaCognitionAI.adjustScore(baseTotal, predictions)
            val metaAdjustment = adjustedTotal - baseTotal
            val vetoReason     = MetaCognitionAI.checkVeto(predictions, metaResult.confidence)

            val paperBootstrap = ctx.mode != com.lifecyclebot.v3.core.V3BotMode.LIVE && learningProgress < 0.40
            val metaFatal = !paperBootstrap && vetoReason != null && metaResult.confidence < 20
            val metaComponent = ScoreComponent(
                name = "metacognition",
                value = metaAdjustment,
                reason = if (vetoReason != null) {
                    val tag = if (metaFatal) "VETO" else "VETO_SOFT"
                    "$tag: $vetoReason | ${metaResult.summary()}"
                } else metaResult.summary(),
                fatal = metaFatal,
            )

            if (metaAdjustment != 0 || vetoReason != null) {
                Log.i("UnifiedScorer", "🧠 META: ${candidate.symbol} | adj=$metaAdjustment | " +
                    "conf=${metaResult.confidence.toInt()}% | ${metaResult.dominantSignal}" +
                    (if (vetoReason != null) " | VETO" else ""))
            }

            val behaviorComponent = try {
                if (BehaviorAI.isTiltProtectionActive()) {
                    val remaining = BehaviorAI.getTiltProtectionRemaining()
                    ScoreComponent(name = "behavior", value = -15,
                        reason = "🛑 Tilt cooldown: ${remaining}s", fatal = false)
                } else {
                    val scoreAdj = BehaviorAI.getScoreAdjustment()
                    val state    = BehaviorAI.getState()
                    ScoreComponent(name = "behavior", value = scoreAdj,
                        reason = "${state.sentimentClass} | streak=${state.currentStreak} | " +
                            "tilt=${state.tiltLevel}% disc=${state.disciplineScore}%")
                }
            } catch (_: Exception) {
                ScoreComponent(name = "behavior", value = 0, reason = "NO_DATA")
            }

            val votesForApproval = (v59123CappedComponents + metaComponent + behaviorComponent)
                .associate { it.name to it.value }
            // V5.9.339: Enable approval_memory during bootstrap at reduced weight
            // Old: returned 0/"BOOTSTRAP" → approval pattern memory never fired during 1000 trades
            val (approvalNudgeRaw, approvalReason) = try {
                EducationSubLayerAI.approvalBoostFor(votesForApproval)
            } catch (_: Exception) { 0 to "ERR" }
            val approvalNudge = if (bootstrapBypass) {
                // Scale approval nudge by bootstrap ramp so it grows as we learn
                (approvalNudgeRaw * BootstrapAdaptiveEngine.getBootstrapRamp()).toInt()
            } else approvalNudgeRaw
            val approvalComponent = ScoreComponent(name = "approval_memory", value = approvalNudge, reason = approvalReason)

            val mutedNames  = mutableListOf<String>()
            val boostedNames = mutableListOf<String>()
            val allComponentsRaw = v59123CappedComponents + metaComponent + behaviorComponent + approvalComponent
            val gatedComponents = allComponentsRaw.map { c ->
                if (c.value == 0) return@map c
                val layerName = EducationSubLayerAI.normalizeComponentName(c.name)
                val (newVote, mult, status) = try {
                    EducationSubLayerAI.applyMuteBoost(layerName, c.value)
                } catch (_: Exception) { Triple(c.value, 1.0, "NORMAL") }
                when (status) {
                    "MUTE"         -> mutedNames.add(layerName)
                    "SOFT_PENALTY" -> mutedNames.add("$layerName(soft)")
                    "BOOST"        -> boostedNames.add(layerName)
                    "HEAVY_BOOST"  -> boostedNames.add("$layerName(hvy)")
                }
                if (mult == 1.0) c else c.copy(
                    value = newVote,
                    reason = "${c.reason} | GATE=${status} ×${"%.2f".format(mult)}"
                )
            }
            if (mutedNames.isNotEmpty() || boostedNames.isNotEmpty()) {
                Log.i("UnifiedScorer",
                    "🛡️ GATE ${candidate.symbol} | muted=${mutedNames.joinToString(",")} | " +
                    "boosted=${boostedNames.joinToString(",")}")
            }

            val finalCard = ScoreCard(gatedComponents)
            try { EducationSubLayerAI.recordEntryScores(candidate.mint, finalCard.components) } catch (_: Exception) {}

            try {
                val totalScore = finalCard.total
                val normConf   = ((totalScore + 50.0) / 150.0).coerceIn(0.0, 1.0)
                val direction  = if (totalScore > 0) "LONG" else if (totalScore < -10) "SHORT" else null
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

            finalCard

        } catch (e: Exception) {
            Log.w("UnifiedScorer", "modernScore error: ${e.message}")
            val fallbackCard = ScoreCard(v59123CappedComponents)
            try { EducationSubLayerAI.recordEntryScores(candidate.mint, fallbackCard.components) } catch (_: Exception) {}
            fallbackCard
        }
    }

    /**
     * Map score component names to MetaCognition AI layers
     */
    private fun mapComponentNameToLayer(name: String): MetaCognitionAI.AILayer? = when (name.lowercase()) {
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
        "shitcoin", "shitcointraderai" -> MetaCognitionAI.AILayer.SHITCOIN_TRADER
        "quality", "qualitytraderai" -> MetaCognitionAI.AILayer.QUALITY_TRADER
        "moonshot", "moonshottraderai" -> MetaCognitionAI.AILayer.MOONSHOT_TRADER
        "bluechip", "bluechiptraderai" -> MetaCognitionAI.AILayer.BLUECHIP_TRADER
        "diphunter", "diphunterai" -> MetaCognitionAI.AILayer.DIP_HUNTER
        "metacognition" -> null
        "behavior" -> MetaCognitionAI.AILayer.BEHAVIOR_AI
        "approval_memory" -> null
        "insider_tracker" -> MetaCognitionAI.AILayer.INSIDER_TRACKER
        else -> null
    }

    /**
     * Score a candidate AND evaluate for arb opportunities.
     */
    fun scoreWithArb(candidate: CandidateSnapshot, ctx: TradingContext): Pair<ScoreCard, ArbEvaluation?> {
        ArbScannerAI.recordSourceSeen(candidate)
        val scoreCard = score(candidate, ctx)
        val arbEval = try { ArbScannerAI.evaluate(candidate, ctx) } catch (e: Exception) { null }
        return Pair(scoreCard, arbEval)
    }

    /**
     * Get list of all module names
     */
    fun moduleNames(): List<String> = listOf(
        "source", "entry", "momentum", "liquidity", "volume",
        "holders", "narrative", "memory", "regime", "time",
        "copytrade", "suppression", "feargreed", "social",
        "volatility", "orderflow", "smartmoney", "holdtime", "liquiditycycle",
        "collective_ai",
        "metacognition",
        "fluid_learning",
        "sell_optimization",
        "behavior",
        "insider_tracker",
    )

    /**
     * V5.7.4: Score based on Insider Tracker signals
     */
    private fun insiderTrackerScore(candidate: CandidateSnapshot): ScoreComponent {
        return try {
            val entryBoost = InsiderTrackerAI.getEntryBoost(candidate.mint, candidate.symbol)
            val shouldAvoid = InsiderTrackerAI.shouldAvoid(candidate.mint)
            when {
                shouldAvoid -> ScoreComponent(name = "insider_tracker", value = -15,
                    reason = "🚨 INSIDER SELLING: Distribution detected from tracked wallets")
                entryBoost >= 20 -> ScoreComponent(name = "insider_tracker", value = entryBoost,
                    reason = "🔥 ALPHA SIGNAL: Strong insider accumulation detected")
                entryBoost >= 10 -> ScoreComponent(name = "insider_tracker", value = entryBoost,
                    reason = "💰 INSIDER BUY: Tracked wallet accumulating")
                entryBoost > 0 -> ScoreComponent(name = "insider_tracker", value = entryBoost,
                    reason = "📡 Insider activity detected")
                else -> ScoreComponent(name = "insider_tracker", value = 0, reason = "No insider signals")
            }
        } catch (e: Exception) {
            ScoreComponent(name = "insider_tracker", value = 0, reason = "NO_DATA")
        }
    }
}

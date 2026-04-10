package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.arb.ArbScannerAI
import com.lifecyclebot.v3.arb.ArbEvaluation
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
        // Collect scores from all 19 base AI modules
        // V4.1: Use sourceScoreWithTiming for source timing lag penalty
        val baseComponents = listOf(
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
            suppressionAI.score(candidate, ctx),  // V3 MIGRATION: Converts legacy blocks to penalties
            fearGreedAI.score(candidate, ctx),    // V3.1: Fear & Greed Index
            socialVelocityAI.score(candidate, ctx),  // V3.1: Social velocity detection
            // V3.2 NEW AI LAYERS
            VolatilityRegimeAI.score(candidate, ctx),      // Volatility regime & squeeze detection
            OrderFlowImbalanceAI.score(candidate, ctx),    // Order flow analysis
            SmartMoneyDivergenceAI.score(candidate, ctx),  // Smart money divergence
            HoldTimeOptimizerAI.score(candidate, ctx),     // Hold time optimization
            LiquidityCycleAI.score(candidate, ctx),         // Market-wide liquidity cycles
            // V5.7.4 INSIDER TRACKER AI - Layer 27
            insiderTrackerScore(candidate)                     // Insider wallet monitoring
        )
        
        // ═══════════════════════════════════════════════════════════════════════
        // V5.8: SOFT PENALTY CAP — cap combined negative from subjective/noisy factors
        // holders + narrative + social + holdtime uncapped = up to -8, drowning valid entries
        // Cap their combined negative at -5 so no single noisy signal can kill a trade alone
        // ═══════════════════════════════════════════════════════════════════════
        val softPenaltyNames = setOf("holders", "narrative", "social", "holdtime")
        val softPenaltyTotal = baseComponents
            .filter { it.name.lowercase() in softPenaltyNames && it.value < 0 }
            .sumOf { it.value }
        val cappedBaseComponents = if (softPenaltyTotal < -5) {
            val scale = -5.0 / softPenaltyTotal
            baseComponents.map { comp ->
                if (comp.name.lowercase() in softPenaltyNames && comp.value < 0) {
                    comp.copy(value = (comp.value * scale).toInt())
                } else comp
            }
        } else baseComponents

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
                v3Score = cappedBaseComponents.sumOf { it.value },
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
        val allComponents = cappedBaseComponents + collectiveComponent
        
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
            val metaComponent = ScoreComponent(
                name = "metacognition",
                value = metaAdjustment,
                reason = if (vetoReason != null) {
                    "VETO: $vetoReason | ${metaResult.summary()}"
                } else {
                    metaResult.summary()
                },
                fatal = vetoReason != null && metaResult.confidence < 20  // Only fatal on extreme distrust
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
            
            // Return scorecard with all components including behavior
            return ScoreCard(cappedBaseComponents + metaComponent + behaviorComponent)

        } catch (e: Exception) {
            Log.w("UnifiedScorer", "MetaCognitionAI error: ${e.message}")
            return ScoreCard(cappedBaseComponents)
        }
    }
    
    /**
     * Map score component names to MetaCognition AI layers
     */
    private fun mapComponentNameToLayer(name: String): MetaCognitionAI.AILayer? = when (name.lowercase()) {
        "source" -> null  // Source is not an AI layer
        "entry" -> MetaCognitionAI.AILayer.ENTRY_INTELLIGENCE
        "momentum" -> MetaCognitionAI.AILayer.MOMENTUM_PREDICTOR
        "liquidity" -> MetaCognitionAI.AILayer.LIQUIDITY_DEPTH
        "volume" -> MetaCognitionAI.AILayer.ORDER_FLOW_IMBALANCE
        "holders" -> null  // HolderSafetyAI maps to nothing directly
        "narrative" -> MetaCognitionAI.AILayer.NARRATIVE_DETECTOR
        "memory" -> MetaCognitionAI.AILayer.TOKEN_WIN_MEMORY
        "regime" -> MetaCognitionAI.AILayer.MARKET_REGIME
        "time" -> MetaCognitionAI.AILayer.TIME_OPTIMIZATION
        "copytrade" -> null  // CopyTrade is disabled
        "suppression" -> null  // Not a predictive AI
        "feargreed" -> null  // External data, not our AI
        "social" -> null  // External data
        "volatility" -> MetaCognitionAI.AILayer.VOLATILITY_REGIME
        "orderflow" -> MetaCognitionAI.AILayer.ORDER_FLOW_IMBALANCE
        "smartmoney" -> MetaCognitionAI.AILayer.SMART_MONEY_DIVERGENCE
        "holdtime" -> MetaCognitionAI.AILayer.HOLD_TIME_OPTIMIZER
        "liquiditycycle" -> MetaCognitionAI.AILayer.LIQUIDITY_CYCLE
        "collective_ai" -> MetaCognitionAI.AILayer.COLLECTIVE_INTELLIGENCE
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

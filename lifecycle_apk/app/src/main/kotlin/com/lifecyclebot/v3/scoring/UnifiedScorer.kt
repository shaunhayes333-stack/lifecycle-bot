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
        val baseComponents = listOf(
            sourceScore(candidate.source),
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
            LiquidityCycleAI.score(candidate, ctx)         // Market-wide liquidity cycles
        )
        
        // ═══════════════════════════════════════════════════════════════════════
        // METACOGNITION AI - Layer 20: Self-Aware Executive Function
        // 
        // 1. Record predictions from all layers (for learning)
        // 2. Calculate meta-confidence (how much to trust this decision)
        // 3. Adjust score based on layer accuracy history
        // 4. Check for veto conditions (reliable AIs disagree)
        // ═══════════════════════════════════════════════════════════════════════
        
        try {
            // Record predictions for future correlation with outcomes
            MetaCognitionAI.recordFromScoreCard(candidate.mint, candidate.symbol, baseComponents)
            
            // Build predictions list for meta-analysis
            val predictions = baseComponents.mapNotNull { comp ->
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
            val baseTotal = baseComponents.sumOf { it.value }
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
            
            // Log significant meta-cognition insights
            if (metaAdjustment != 0 || vetoReason != null) {
                Log.i("UnifiedScorer", "🧠 META: ${candidate.symbol} | adj=$metaAdjustment | " +
                    "conf=${metaResult.confidence.toInt()}% | ${metaResult.dominantSignal}" +
                    (if (vetoReason != null) " | VETO" else ""))
            }
            
            // Return scorecard with all 20 components
            return ScoreCard(baseComponents + metaComponent)
            
        } catch (e: Exception) {
            Log.w("UnifiedScorer", "MetaCognitionAI error: ${e.message}")
            // Fall back to base components without meta layer
            return ScoreCard(baseComponents)
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
     * Get list of all module names (now 20 with MetaCognitionAI)
     */
    fun moduleNames(): List<String> = listOf(
        "source", "entry", "momentum", "liquidity", "volume",
        "holders", "narrative", "memory", "regime", "time", 
        "copytrade", "suppression", "feargreed", "social",
        "volatility", "orderflow", "smartmoney", "holdtime", "liquiditycycle",
        "metacognition"  // Layer 20: Self-aware executive function
    )
}

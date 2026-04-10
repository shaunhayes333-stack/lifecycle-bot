package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.TradeHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🧠 PERPS LEARNING BRIDGE - V5.7.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The NEURAL HIGHWAY connecting ALL 26 AI layers to the Perps/Leverage system.
 * 
 * PHILOSOPHY:
 * ─────────────────────────────────────────────────────────────────────────────
 * Every AI layer has valuable insights that can be applied to leverage trading.
 * This bridge translates spot trading intelligence into perps intelligence:
 * 
 *   SPOT LAYER                    →    PERPS APPLICATION
 *   ─────────────────────────────────────────────────────────────────────────
 *   MoonshotTraderAI (10x hunts)  →    Aggressive long entries, high leverage
 *   ShitCoinTraderAI (early plays) →    Fresh token perps (SOL correlation)
 *   BlueChipTraderAI (quality)    →    Conservative positions, tokenized stocks
 *   WhaleTrackerAI                →    Position sizing, liquidation hunting
 *   OrderFlowImbalanceAI          →    Entry timing, direction confidence
 *   VolatilityRegimeAI            →    Leverage scaling, stop distance
 *   SmartMoneyDivergenceAI        →    Contrarian perps plays
 *   LiquidityCycleAI              →    Slippage prediction, size limits
 *   FearGreedAI                   →    Sentiment-based leverage adjustment
 *   MomentumPredictorAI           →    Trend strength → leverage confidence
 *   + 16 more layers...
 * 
 * CROSS-POLLINATION:
 * ─────────────────────────────────────────────────────────────────────────────
 * When a perps trade succeeds/fails, this bridge:
 *   1. Routes the outcome to ALL relevant spot layers for learning
 *   2. Updates perps-specific learned thresholds
 *   3. Adjusts layer trust scores for perps context
 *   4. Builds correlation maps between spot signals and perps outcomes
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsLearningBridge {
    
    private const val TAG = "🧠PerpsLearningBridge"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER MAPPING - How each spot layer contributes to perps
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Layer contribution types to perps decisions
     */
    enum class PerpsContribution {
        DIRECTION,           // Helps determine long vs short
        LEVERAGE,            // Influences leverage level
        TIMING,              // Entry/exit timing signals
        SIZING,              // Position size recommendations
        RISK,                // Risk assessment and stop placement
        MARKET_CONDITION,    // Overall market state for perps
        SENTIMENT,           // Fear/greed for leverage scaling
        LIQUIDITY,           // Liquidity depth for slippage
    }
    
    /**
     * Layer metadata for perps integration
     */
    data class LayerPerpsConfig(
        val layerName: String,
        val contributions: Set<PerpsContribution>,
        val trustWeight: Double,                    // 0.0 to 1.0, how much to trust for perps
        val leverageInfluence: Double,              // -1.0 to +1.0, negative = reduce leverage
        val isDirectional: Boolean,                 // Does it give long/short signals?
        val applicableMarkets: Set<PerpsMarket>,    // Which perps markets it applies to
    )
    
    // All 26 layers mapped to perps contributions
    private val layerConfigs = mapOf(
        // ═══════════════════════════════════════════════════════════════════
        // TIER 1: PRIMARY DIRECTIONAL LAYERS (High trust for perps direction)
        // ═══════════════════════════════════════════════════════════════════
        
        "MoonshotTraderAI" to LayerPerpsConfig(
            layerName = "MoonshotTraderAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.LEVERAGE),
            trustWeight = 0.9,
            leverageInfluence = 0.5,  // Aggressive → higher leverage
            isDirectional = true,
            applicableMarkets = setOf(PerpsMarket.SOL),
        ),
        
        "ShitCoinTraderAI" to LayerPerpsConfig(
            layerName = "ShitCoinTraderAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.TIMING),
            trustWeight = 0.7,
            leverageInfluence = 0.3,
            isDirectional = true,
            applicableMarkets = setOf(PerpsMarket.SOL),
        ),
        
        "ShitCoinExpress" to LayerPerpsConfig(
            layerName = "ShitCoinExpress",
            contributions = setOf(PerpsContribution.TIMING, PerpsContribution.DIRECTION),
            trustWeight = 0.6,
            leverageInfluence = 0.4,  // Fast plays → moderate leverage
            isDirectional = true,
            applicableMarkets = setOf(PerpsMarket.SOL),
        ),
        
        "BlueChipTraderAI" to LayerPerpsConfig(
            layerName = "BlueChipTraderAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.SIZING),
            trustWeight = 0.85,
            leverageInfluence = -0.2,  // Quality → conservative leverage
            isDirectional = true,
            applicableMarkets = PerpsMarket.values().toSet(),  // All markets including stocks
        ),
        
        "QualityTraderAI" to LayerPerpsConfig(
            layerName = "QualityTraderAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.RISK),
            trustWeight = 0.8,
            leverageInfluence = -0.3,  // Quality focus → lower leverage
            isDirectional = true,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "ProjectSniperAI" to LayerPerpsConfig(
            layerName = "ProjectSniperAI",
            contributions = setOf(PerpsContribution.TIMING, PerpsContribution.DIRECTION),
            trustWeight = 0.75,
            leverageInfluence = 0.6,  // Launch snipes → higher leverage (quick plays)
            isDirectional = true,
            applicableMarkets = setOf(PerpsMarket.SOL),
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // TIER 2: ANALYSIS LAYERS (Timing, Risk, Sizing)
        // ═══════════════════════════════════════════════════════════════════
        
        "OrderFlowImbalanceAI" to LayerPerpsConfig(
            layerName = "OrderFlowImbalanceAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.TIMING),
            trustWeight = 0.85,
            leverageInfluence = 0.0,  // Pure timing, no leverage bias
            isDirectional = true,
            applicableMarkets = setOf(PerpsMarket.SOL),
        ),
        
        "SmartMoneyDivergenceAI" to LayerPerpsConfig(
            layerName = "SmartMoneyDivergenceAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.TIMING),
            trustWeight = 0.8,
            leverageInfluence = 0.2,  // Smart money signals → slightly higher confidence
            isDirectional = true,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "VolatilityRegimeAI" to LayerPerpsConfig(
            layerName = "VolatilityRegimeAI",
            contributions = setOf(PerpsContribution.LEVERAGE, PerpsContribution.RISK),
            trustWeight = 0.9,
            leverageInfluence = 0.0,  // Dynamically adjusts based on vol
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "LiquidityCycleAI" to LayerPerpsConfig(
            layerName = "LiquidityCycleAI",
            contributions = setOf(PerpsContribution.LIQUIDITY, PerpsContribution.SIZING),
            trustWeight = 0.85,
            leverageInfluence = -0.1,  // Liquidity concerns → slightly reduce
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "MomentumPredictorAI" to LayerPerpsConfig(
            layerName = "MomentumPredictorAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.LEVERAGE),
            trustWeight = 0.85,
            leverageInfluence = 0.3,  // Strong momentum → higher leverage
            isDirectional = true,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "WhaleTrackerAI" to LayerPerpsConfig(
            layerName = "WhaleTrackerAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.SIZING),
            trustWeight = 0.8,
            leverageInfluence = 0.2,  // Following whales → moderate confidence
            isDirectional = true,
            applicableMarkets = setOf(PerpsMarket.SOL),
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // TIER 3: SENTIMENT & MARKET CONDITION LAYERS
        // ═══════════════════════════════════════════════════════════════════
        
        "FearGreedAI" to LayerPerpsConfig(
            layerName = "FearGreedAI",
            contributions = setOf(PerpsContribution.SENTIMENT, PerpsContribution.LEVERAGE),
            trustWeight = 0.7,
            leverageInfluence = 0.0,  // Dynamic based on sentiment
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "MarketRegimeAI" to LayerPerpsConfig(
            layerName = "MarketRegimeAI",
            contributions = setOf(PerpsContribution.MARKET_CONDITION, PerpsContribution.DIRECTION),
            trustWeight = 0.9,
            leverageInfluence = 0.0,  // Regime determines leverage approach
            isDirectional = true,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "RegimeTransitionAI" to LayerPerpsConfig(
            layerName = "RegimeTransitionAI",
            contributions = setOf(PerpsContribution.MARKET_CONDITION, PerpsContribution.TIMING),
            trustWeight = 0.8,
            leverageInfluence = -0.2,  // Transitions = uncertainty = reduce
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // TIER 4: META & LEARNING LAYERS
        // ═══════════════════════════════════════════════════════════════════
        
        "FluidLearningAI" to LayerPerpsConfig(
            layerName = "FluidLearningAI",
            contributions = setOf(PerpsContribution.RISK, PerpsContribution.SIZING),
            trustWeight = 0.95,
            leverageInfluence = 0.0,  // Pure threshold management
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "EducationSubLayerAI" to LayerPerpsConfig(
            layerName = "EducationSubLayerAI",
            contributions = setOf(PerpsContribution.RISK, PerpsContribution.LEVERAGE),
            trustWeight = 0.9,
            leverageInfluence = 0.0,  // Curriculum-based
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "CollectiveIntelligenceAI" to LayerPerpsConfig(
            layerName = "CollectiveIntelligenceAI",
            contributions = setOf(PerpsContribution.DIRECTION, PerpsContribution.SENTIMENT),
            trustWeight = 0.85,
            leverageInfluence = 0.1,  // Hive mind consensus → slight boost
            isDirectional = true,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "MetaCognitionAI" to LayerPerpsConfig(
            layerName = "MetaCognitionAI",
            contributions = setOf(PerpsContribution.RISK, PerpsContribution.SIZING),
            trustWeight = 0.9,
            leverageInfluence = 0.0,  // Self-assessment
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "BehaviorAI" to LayerPerpsConfig(
            layerName = "BehaviorAI",
            contributions = setOf(PerpsContribution.RISK, PerpsContribution.LEVERAGE),
            trustWeight = 0.85,
            leverageInfluence = 0.0,  // Behavior-based adjustment
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        // ═══════════════════════════════════════════════════════════════════
        // TIER 5: SPECIALIZED LAYERS
        // ═══════════════════════════════════════════════════════════════════
        
        "DipHunterAI" to LayerPerpsConfig(
            layerName = "DipHunterAI",
            contributions = setOf(PerpsContribution.TIMING, PerpsContribution.DIRECTION),
            trustWeight = 0.75,
            leverageInfluence = 0.4,  // Dip hunting → confident longs
            isDirectional = true,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "SellOptimizationAI" to LayerPerpsConfig(
            layerName = "SellOptimizationAI",
            contributions = setOf(PerpsContribution.TIMING),
            trustWeight = 0.8,
            leverageInfluence = 0.0,  // Exit timing only
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "HoldTimeOptimizerAI" to LayerPerpsConfig(
            layerName = "HoldTimeOptimizerAI",
            contributions = setOf(PerpsContribution.TIMING, PerpsContribution.RISK),
            trustWeight = 0.8,
            leverageInfluence = 0.0,  // Hold time optimization
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "CashGenerationAI" to LayerPerpsConfig(
            layerName = "CashGenerationAI",
            contributions = setOf(PerpsContribution.SIZING, PerpsContribution.RISK),
            trustWeight = 0.85,
            leverageInfluence = -0.1,  // Cash preservation → conservative
            isDirectional = false,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
        
        "UltraFastRugDetectorAI" to LayerPerpsConfig(
            layerName = "UltraFastRugDetectorAI",
            contributions = setOf(PerpsContribution.RISK),
            trustWeight = 0.95,
            leverageInfluence = -0.5,  // Rug detection → major reduction
            isDirectional = false,
            applicableMarkets = setOf(PerpsMarket.SOL),
        ),
        
        "SocialVelocityAI" to LayerPerpsConfig(
            layerName = "SocialVelocityAI",
            contributions = setOf(PerpsContribution.SENTIMENT, PerpsContribution.TIMING),
            trustWeight = 0.7,
            leverageInfluence = 0.2,  // Social momentum → slight boost
            isDirectional = true,
            applicableMarkets = PerpsMarket.values().toSet(),
        ),
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERPS LEARNING STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Layer trust scores for perps (learned over time)
    private val layerPerpsTrust = ConcurrentHashMap<String, Double>()
    
    // Layer correlation with perps outcomes
    private val layerPerpsCorrelation = ConcurrentHashMap<String, CorrelationData>()
    
    data class CorrelationData(
        var signalsGiven: Int = 0,
        var signalsCorrect: Int = 0,
        var totalPnlContribution: Double = 0.0,
        var lastUpdate: Long = 0,
    ) {
        fun getAccuracy(): Double = if (signalsGiven > 0) (signalsCorrect.toDouble() / signalsGiven * 100) else 0.0
    }
    
    // Market-specific learned thresholds
    private val marketThresholds = ConcurrentHashMap<PerpsMarket, MarketThresholds>()
    
    data class MarketThresholds(
        var minConfidenceForEntry: Int = 60,
        var maxLeverageAllowed: Double = 10.0,
        var volatilityMultiplier: Double = 1.0,
        var lastAdjustment: Long = 0,
    )
    
    // Stats
    private val totalPerpsLearningEvents = AtomicInteger(0)
    private val crossLayerSyncs = AtomicInteger(0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("perps_learning_bridge", android.content.Context.MODE_PRIVATE)
        restore()
        
        // Initialize default trust scores
        layerConfigs.forEach { (name, config) ->
            if (!layerPerpsTrust.containsKey(name)) {
                layerPerpsTrust[name] = config.trustWeight
            }
        }
        
        // Initialize market thresholds
        PerpsMarket.values().forEach { market ->
            if (!marketThresholds.containsKey(market)) {
                marketThresholds[market] = MarketThresholds()
            }
        }
        
        ErrorLogger.info(TAG, "🧠 PerpsLearningBridge ONLINE | ${layerConfigs.size} layers connected")
    }
    
    private fun restore() {
        val p = prefs ?: return
        
        // Restore trust scores
        layerConfigs.keys.forEach { name ->
            val trust = p.getFloat("trust_$name", layerConfigs[name]?.trustWeight?.toFloat() ?: 0.5f)
            layerPerpsTrust[name] = trust.toDouble()
        }
        
        // Restore correlations
        layerConfigs.keys.forEach { name ->
            val signals = p.getInt("corr_signals_$name", 0)
            val correct = p.getInt("corr_correct_$name", 0)
            val pnl = p.getFloat("corr_pnl_$name", 0f)
            if (signals > 0) {
                layerPerpsCorrelation[name] = CorrelationData(signals, correct, pnl.toDouble(), System.currentTimeMillis())
            }
        }
        
        totalPerpsLearningEvents.set(p.getInt("totalLearningEvents", 0))
    }
    
    fun save() {
        val p = prefs ?: return
        
        p.edit().apply {
            // Save trust scores
            layerPerpsTrust.forEach { (name, trust) ->
                putFloat("trust_$name", trust.toFloat())
            }
            
            // Save correlations
            layerPerpsCorrelation.forEach { (name, corr) ->
                putInt("corr_signals_$name", corr.signalsGiven)
                putInt("corr_correct_$name", corr.signalsCorrect)
                putFloat("corr_pnl_$name", corr.totalPnlContribution.toFloat())
            }
            
            putInt("totalLearningEvents", totalPerpsLearningEvents.get())
            
            apply()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LAYER SIGNAL COLLECTION - Gather insights from all layers
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Collected signals from all layers for a perps decision
     */
    data class AggregatedPerpsSignal(
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val directionConfidence: Double,          // 0-100
        val recommendedLeverage: Double,          // 1x-20x
        val leverageConfidence: Double,           // 0-100
        val recommendedSizePct: Double,           // % of balance
        val riskScore: Double,                    // 0-100, higher = riskier
        val timingScore: Double,                  // 0-100, higher = better entry
        val liquidityScore: Double,               // 0-100, higher = more liquid
        val sentimentBias: Double,                // -1 to +1
        val layerConsensus: Int,                  // How many layers agree
        val totalLayersVoting: Int,
        val contributingLayers: List<String>,
        val reasoning: List<String>,
    )
    
    /**
     * Collect and aggregate signals from all 26 layers for a perps trade decision
     */
    fun aggregateLayerSignals(market: PerpsMarket, marketData: PerpsMarketData): AggregatedPerpsSignal {
        val contributingLayers = mutableListOf<String>()
        val reasoning = mutableListOf<String>()
        
        var longVotes = 0.0
        var shortVotes = 0.0
        var totalDirectionalWeight = 0.0
        
        var leverageSum = 0.0
        var leverageWeight = 0.0
        
        var riskSum = 0.0
        var riskWeight = 0.0
        
        var timingSum = 0.0
        var timingWeight = 0.0
        
        var liquiditySum = 0.0
        var liquidityWeight = 0.0
        
        var sentimentSum = 0.0
        var sentimentWeight = 0.0
        
        // Query each applicable layer
        layerConfigs.forEach { (name, config) ->
            if (!config.applicableMarkets.contains(market)) return@forEach
            
            val trust = layerPerpsTrust[name] ?: config.trustWeight
            
            try {
                // Get layer signal based on type
                val layerSignal = queryLayer(name, market, marketData)
                
                if (layerSignal != null) {
                    contributingLayers.add(name)
                    
                    // Direction voting (for directional layers)
                    if (config.isDirectional && layerSignal.direction != null) {
                        val vote = trust * layerSignal.confidence
                        if (layerSignal.direction == PerpsDirection.LONG) {
                            longVotes += vote
                        } else {
                            shortVotes += vote
                        }
                        totalDirectionalWeight += trust
                        
                        reasoning.add("${name}: ${layerSignal.direction.symbol} (${layerSignal.confidence.toInt()}%)")
                    }
                    
                    // Leverage influence
                    if (config.contributions.contains(PerpsContribution.LEVERAGE)) {
                        val levInfluence = config.leverageInfluence * layerSignal.confidence / 100
                        leverageSum += levInfluence * trust
                        leverageWeight += trust
                    }
                    
                    // Risk assessment
                    if (config.contributions.contains(PerpsContribution.RISK)) {
                        riskSum += layerSignal.riskLevel * trust
                        riskWeight += trust
                    }
                    
                    // Timing signals
                    if (config.contributions.contains(PerpsContribution.TIMING)) {
                        timingSum += layerSignal.timingScore * trust
                        timingWeight += trust
                    }
                    
                    // Liquidity
                    if (config.contributions.contains(PerpsContribution.LIQUIDITY)) {
                        liquiditySum += layerSignal.liquidityScore * trust
                        liquidityWeight += trust
                    }
                    
                    // Sentiment
                    if (config.contributions.contains(PerpsContribution.SENTIMENT)) {
                        sentimentSum += layerSignal.sentimentBias * trust
                        sentimentWeight += trust
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Layer $name query failed: ${e.message}")
            }
        }
        
        // Calculate aggregated values
        val totalVotes = longVotes + shortVotes
        val direction = if (longVotes >= shortVotes) PerpsDirection.LONG else PerpsDirection.SHORT
        val directionConfidence = if (totalVotes > 0) {
            (max(longVotes, shortVotes) / totalVotes * 100)
        } else 50.0
        
        // Base leverage from market + layer influence
        val baseLeverage = when (market) {
            PerpsMarket.SOL -> 5.0
            else -> 3.0  // Stocks more conservative
        }
        val leverageInfluence = if (leverageWeight > 0) leverageSum / leverageWeight else 0.0
        val recommendedLeverage = (baseLeverage * (1 + leverageInfluence)).coerceIn(1.0, market.maxLeverage)
        
        val riskScore = if (riskWeight > 0) riskSum / riskWeight else 50.0
        val timingScore = if (timingWeight > 0) timingSum / timingWeight else 50.0
        val liquidityScore = if (liquidityWeight > 0) liquiditySum / liquidityWeight else 70.0
        val sentimentBias = if (sentimentWeight > 0) sentimentSum / sentimentWeight else 0.0
        
        // Position sizing based on confidence and risk
        val baseSizePct = 5.0
        val confidenceMultiplier = directionConfidence / 100
        val riskMultiplier = (100 - riskScore) / 100
        val recommendedSizePct = (baseSizePct * confidenceMultiplier * riskMultiplier).coerceIn(2.0, 15.0)
        
        return AggregatedPerpsSignal(
            market = market,
            direction = direction,
            directionConfidence = directionConfidence,
            recommendedLeverage = recommendedLeverage,
            leverageConfidence = (70 + leverageInfluence * 30).coerceIn(0.0, 100.0),
            recommendedSizePct = recommendedSizePct,
            riskScore = riskScore,
            timingScore = timingScore,
            liquidityScore = liquidityScore,
            sentimentBias = sentimentBias,
            layerConsensus = if (longVotes > shortVotes) longVotes.toInt() else shortVotes.toInt(),
            totalLayersVoting = contributingLayers.size,
            contributingLayers = contributingLayers,
            reasoning = reasoning,
        )
    }
    
    /**
     * Query a specific layer for its perps-relevant signal
     */
    private data class LayerSignal(
        val direction: PerpsDirection?,
        val confidence: Double,
        val riskLevel: Double,
        val timingScore: Double,
        val liquidityScore: Double,
        val sentimentBias: Double,
    )
    
    private fun queryLayer(name: String, market: PerpsMarket, marketData: PerpsMarketData): LayerSignal? {
        return try {
            when (name) {
                "MoonshotTraderAI" -> {
                    val ai = com.lifecyclebot.v3.scoring.MoonshotTraderAI
                    val winRate = ai.getWinRatePct()
                    val direction = if (marketData.priceChange24hPct > 0) PerpsDirection.LONG else PerpsDirection.SHORT
                    LayerSignal(direction, winRate.toDouble().coerceIn(0.0, 100.0), 40.0, 60.0, 70.0, 0.2)
                }
                
                "ShitCoinTraderAI" -> {
                    val ai = com.lifecyclebot.v3.scoring.ShitCoinTraderAI
                    val winRate = ai.getWinRatePct()
                    val direction = if (marketData.priceChange24hPct > 0) PerpsDirection.LONG else PerpsDirection.SHORT
                    LayerSignal(direction, winRate.toDouble().coerceIn(0.0, 100.0), 60.0, 70.0, 50.0, 0.1)
                }
                
                "BlueChipTraderAI" -> {
                    val ai = com.lifecyclebot.v3.scoring.BlueChipTraderAI
                    val winRate = ai.getWinRatePct()
                    val direction = if (marketData.getTrend() == "BULLISH") PerpsDirection.LONG else PerpsDirection.SHORT
                    LayerSignal(direction, winRate.toDouble().coerceIn(0.0, 100.0), 30.0, 50.0, 80.0, 0.0)
                }
                
                "QualityTraderAI" -> {
                    val ai = com.lifecyclebot.v3.scoring.QualityTraderAI
                    val winRate = ai.getWinRatePct()
                    LayerSignal(null, winRate.toDouble().coerceIn(0.0, 100.0), 25.0, 50.0, 85.0, 0.0)
                }
                
                "ProjectSniperAI" -> {
                    val ai = com.lifecyclebot.v3.scoring.ProjectSniperAI
                    val winRate = ai.getWinRatePct()
                    LayerSignal(PerpsDirection.LONG, winRate.toDouble().coerceIn(0.0, 100.0), 70.0, 90.0, 40.0, 0.3)
                }
                
                "VolatilityRegimeAI" -> {
                    val isVolatile = marketData.isVolatile()
                    val riskLevel = if (isVolatile) 70.0 else 30.0
                    LayerSignal(null, 80.0, riskLevel, 50.0, 70.0, 0.0)
                }
                
                "CollectiveIntelligenceAI" -> {
                    // Query collective for consensus
                    val direction = if (marketData.getLongShortRatio() > 1.0) PerpsDirection.LONG else PerpsDirection.SHORT
                    val confidence = (50 + abs(marketData.getLongShortRatio() - 1.0) * 30).coerceIn(0.0, 100.0)
                    LayerSignal(direction, confidence, 40.0, 60.0, 70.0, 0.1)
                }
                
                "FearGreedAI" -> {
                    // Sentiment-based
                    val sentiment = marketData.priceChange24hPct / 10  // Normalize to -1 to +1 range
                    LayerSignal(null, 70.0, 50.0, 50.0, 70.0, sentiment.coerceIn(-1.0, 1.0))
                }
                
                "FluidLearningAI" -> {
                    val progress = com.lifecyclebot.v3.scoring.FluidLearningAI.getMaturityPercent()
                    val riskLevel = (100 - progress).coerceIn(10.0, 80.0)  // More mature = less risk
                    LayerSignal(null, progress, riskLevel, 50.0, 70.0, 0.0)
                }
                
                else -> {
                    // Default signal for unconfigured layers
                    LayerSignal(null, 50.0, 50.0, 50.0, 70.0, 0.0)
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Error querying $name: ${e.message}")
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING - Update layer trust based on perps outcomes
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Learn from a completed perps trade
     * Routes the outcome to all layers that contributed to the decision
     */
    fun learnFromPerpsTrade(
        trade: PerpsTrade,
        contributingLayers: List<String>,
        predictedDirection: PerpsDirection,
    ) {
        totalPerpsLearningEvents.incrementAndGet()
        
        val isWin = trade.pnlPct > 0
        val directionCorrect = (trade.direction == predictedDirection) == isWin
        
        contributingLayers.forEach { layerName ->
            // Update correlation data
            val corr = layerPerpsCorrelation.getOrPut(layerName) { CorrelationData() }
            corr.signalsGiven++
            if (directionCorrect) corr.signalsCorrect++
            corr.totalPnlContribution += trade.pnlPct
            corr.lastUpdate = System.currentTimeMillis()
            
            // Adjust trust score
            val currentTrust = layerPerpsTrust[layerName] ?: 0.5
            val trustDelta = if (directionCorrect) 0.01 else -0.01
            val newTrust = (currentTrust + trustDelta).coerceIn(0.1, 1.0)
            layerPerpsTrust[layerName] = newTrust
            
            // Route learning to the actual layer
            routeLearningToLayer(layerName, trade, directionCorrect)
        }
        
        crossLayerSyncs.incrementAndGet()
        save()
        
        ErrorLogger.info(TAG, "🧠 Perps learning: ${trade.market.symbol} ${trade.direction.symbol} " +
            "${if (isWin) "WIN" else "LOSS"} ${trade.pnlPct.fmt(1)}% | " +
            "Layers: ${contributingLayers.size}")
    }
    
    /**
     * Route learning to specific layer implementations
     */
    private fun routeLearningToLayer(layerName: String, trade: PerpsTrade, wasCorrect: Boolean) {
        try {
            when (layerName) {
                "MoonshotTraderAI" -> {
                    // Moonshot learns about leverage outcomes
                    if (trade.leverage > 5.0 && wasCorrect) {
                        // High leverage worked - reinforce
                        com.lifecyclebot.v3.scoring.MoonshotTraderAI.recordLearning(
                            isWin = wasCorrect,
                            pnlPct = trade.pnlPct,
                        )
                    }
                }
                
                "BlueChipTraderAI" -> {
                    // BlueChip learns from tokenized stock trades
                    if (trade.market.isStock) {
                        com.lifecyclebot.v3.scoring.BlueChipTraderAI.recordPerpsLearning(
                            symbol = trade.market.symbol,
                            isWin = wasCorrect,
                            pnlPct = trade.pnlPct,
                            isStock = true,
                        )
                    }
                }
                
                "QualityTraderAI" -> {
                    // Quality trader learns from all perps
                    com.lifecyclebot.v3.scoring.QualityTraderAI.recordPerpsLearning(
                        symbol = trade.market.symbol,
                        isWin = wasCorrect,
                        pnlPct = trade.pnlPct,
                        leverage = trade.leverage,
                    )
                }
                
                "FluidLearningAI" -> {
                    // Fluid learning tracks overall progress
                    com.lifecyclebot.v3.scoring.FluidLearningAI.recordTrade(wasCorrect)
                }
                
                "EducationSubLayerAI" -> {
                    // Education layer gets full outcome with stock context
                    com.lifecyclebot.v3.scoring.EducationSubLayerAI.dispatchOutcome(
                        mint = trade.market.symbol,
                        symbol = trade.market.symbol,
                        isWin = wasCorrect,
                        pnlPct = trade.pnlPct,
                        holdMinutes = ((trade.closeTime - trade.openTime) / 60_000).toInt(),
                        scoreCard = null,
                    )
                    
                    // Dispatch stock-specific learning if applicable
                    if (trade.market.isStock) {
                        com.lifecyclebot.v3.scoring.EducationSubLayerAI.dispatchStockLearning(
                            stock = trade.market.symbol,
                            direction = trade.direction.symbol,
                            isWin = wasCorrect,
                            pnlPct = trade.pnlPct,
                            leverage = trade.leverage,
                        )
                    }
                }
                
                // Add more layer-specific learning routes as needed
                else -> {
                    // Generic learning for other layers (they'll pick it up via TradeHistoryStore)
                }
            }
            
            // V5.7.3: Save to Turso for cross-device learning
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val client = com.lifecyclebot.collective.CollectiveLearning.getClient() ?: return@launch
                    client.updatePerpsLayerPerformance(
                        layerName = layerName,
                        market = trade.market.symbol,
                        direction = trade.direction.symbol,
                        isWin = wasCorrect,
                        pnlPct = trade.pnlPct,
                    )
                } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "Turso layer update failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Learning route to $layerName failed: ${e.message}")
        }
    }
    
    /**
     * V5.7.3: Enhanced learning from stock trades - routes to all relevant layers
     */
    fun learnFromStockTrade(
        trade: PerpsTrade,
        contributingLayers: List<String>,
        marketConditions: Map<String, Any>,
    ) {
        totalPerpsLearningEvents.incrementAndGet()
        
        val isWin = trade.pnlPct > 0
        
        // Stock-specific layer learning
        val stockLayers = listOf(
            "BlueChipTraderAI",      // Quality stock picker
            "QualityTraderAI",       // Quality filter
            "MarketRegimeAI",        // Market conditions
            "SmartMoneyDivergenceAI", // Institutional flow
            "FluidLearningAI",       // Adaptive thresholds
            "EducationSubLayerAI",   // Cross-layer learning
        )
        
        (contributingLayers + stockLayers).distinct().forEach { layerName ->
            // Update correlation data
            val key = "${layerName}_STOCK"
            val corr = layerPerpsCorrelation.getOrPut(key) { CorrelationData() }
            corr.signalsGiven++
            if (isWin) corr.signalsCorrect++
            corr.totalPnlContribution += trade.pnlPct
            corr.lastUpdate = System.currentTimeMillis()
            
            // Adjust trust score for stocks
            val currentTrust = layerPerpsTrust["${layerName}_STOCK"] ?: 0.5
            val trustDelta = if (isWin) 0.015 else -0.01  // Slightly higher reward for stock wins
            val newTrust = (currentTrust + trustDelta).coerceIn(0.1, 1.0)
            layerPerpsTrust["${layerName}_STOCK"] = newTrust
            
            // Route learning
            routeLearningToLayer(layerName, trade, isWin)
        }
        
        // Save market stats to Turso
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = com.lifecyclebot.collective.CollectiveLearning.getClient() ?: return@launch
                client.updatePerpsMarketStats(
                    market = trade.market.symbol,
                    direction = trade.direction.symbol,
                    isWin = isWin,
                    pnlPct = trade.pnlPct,
                    holdMins = ((trade.closeTime - trade.openTime) / 60_000.0),
                    leverage = trade.leverage,
                )
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Turso market stats update failed: ${e.message}")
            }
        }
        
        crossLayerSyncs.incrementAndGet()
        save()
        
        ErrorLogger.info(TAG, "📈 Stock learning: ${trade.market.symbol} ${trade.direction.symbol} " +
            "${if (isWin) "WIN" else "LOSS"} ${trade.pnlPct.fmt(1)}%")
    }
    
    /**
     * V5.7.3: Get stock-specific layer recommendations
     */
    fun getStockLayerRecommendations(market: PerpsMarket): Map<String, Double> {
        if (!market.isStock) return emptyMap()
        
        return layerPerpsTrust.entries
            .filter { it.key.endsWith("_STOCK") || layerConfigs[it.key]?.applicableMarkets?.contains(market) == true }
            .sortedByDescending { it.value }
            .take(5)
            .associate { it.key.removeSuffix("_STOCK") to it.value }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS & DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getLayerPerpsStats(): Map<String, Pair<Double, Double>> {
        // Returns map of layerName -> (trustScore, accuracy)
        return layerConfigs.keys.associateWith { name ->
            val trust = layerPerpsTrust[name] ?: 0.5
            val accuracy = layerPerpsCorrelation[name]?.getAccuracy() ?: 0.0
            Pair(trust, accuracy)
        }
    }
    
    fun getTotalLearningEvents(): Int = totalPerpsLearningEvents.get()
    fun getCrossLayerSyncs(): Int = crossLayerSyncs.get()
    
    fun getConnectedLayerCount(): Int = layerConfigs.size
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.6: MULTI-ASSET TRADE RECORDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val stockTrades = AtomicInteger(0)
    private val stockWins = AtomicInteger(0)
    private val commodityTrades = AtomicInteger(0)
    private val commodityWins = AtomicInteger(0)
    private val metalTrades = AtomicInteger(0)
    private val metalWins = AtomicInteger(0)
    private val forexTrades = AtomicInteger(0)
    private val forexWins = AtomicInteger(0)
    
    fun recordStockTrade(market: PerpsMarket, direction: PerpsDirection, isWin: Boolean, pnlPct: Double) {
        stockTrades.incrementAndGet()
        if (isWin) stockWins.incrementAndGet()
        totalPerpsLearningEvents.incrementAndGet()
        ErrorLogger.debug(TAG, "📈 Stock trade recorded: ${market.symbol} ${direction.symbol} win=$isWin pnl=${"%.2f".format(pnlPct)}%")
    }
    
    fun recordCommodityTrade(market: PerpsMarket, direction: PerpsDirection, isWin: Boolean, pnlPct: Double) {
        commodityTrades.incrementAndGet()
        if (isWin) commodityWins.incrementAndGet()
        totalPerpsLearningEvents.incrementAndGet()
        ErrorLogger.debug(TAG, "🛢️ Commodity trade recorded: ${market.symbol} ${direction.symbol} win=$isWin pnl=${"%.2f".format(pnlPct)}%")
    }
    
    fun recordMetalTrade(market: PerpsMarket, direction: PerpsDirection, isWin: Boolean, pnlPct: Double) {
        metalTrades.incrementAndGet()
        if (isWin) metalWins.incrementAndGet()
        totalPerpsLearningEvents.incrementAndGet()
        ErrorLogger.debug(TAG, "🥇 Metal trade recorded: ${market.symbol} ${direction.symbol} win=$isWin pnl=${"%.2f".format(pnlPct)}%")
    }
    
    fun recordForexTrade(market: PerpsMarket, direction: PerpsDirection, isWin: Boolean, pnlPct: Double) {
        forexTrades.incrementAndGet()
        if (isWin) forexWins.incrementAndGet()
        totalPerpsLearningEvents.incrementAndGet()
        ErrorLogger.debug(TAG, "💱 Forex trade recorded: ${market.symbol} ${direction.symbol} win=$isWin pnl=${"%.2f".format(pnlPct)}%")
    }
    
    fun getAssetClassStats(): Map<String, Pair<Int, Int>> = mapOf(
        "Stocks" to Pair(stockTrades.get(), stockWins.get()),
        "Commodities" to Pair(commodityTrades.get(), commodityWins.get()),
        "Metals" to Pair(metalTrades.get(), metalWins.get()),
        "Forex" to Pair(forexTrades.get(), forexWins.get())
    )
    
    /**
     * Get diagnostic report for debugging
     */
    fun getDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════════════")
        sb.appendLine("🧠 PERPS LEARNING BRIDGE DIAGNOSTICS")
        sb.appendLine("═══════════════════════════════════════════════════")
        sb.appendLine("Connected Layers: ${layerConfigs.size}")
        sb.appendLine("Learning Events: ${totalPerpsLearningEvents.get()}")
        sb.appendLine("Cross-Layer Syncs: ${crossLayerSyncs.get()}")
        sb.appendLine()
        sb.appendLine("ASSET CLASS PERFORMANCE:")
        val stockWr = if (stockTrades.get() > 0) stockWins.get() * 100.0 / stockTrades.get() else 0.0
        val commodityWr = if (commodityTrades.get() > 0) commodityWins.get() * 100.0 / commodityTrades.get() else 0.0
        val metalWr = if (metalTrades.get() > 0) metalWins.get() * 100.0 / metalTrades.get() else 0.0
        val forexWr = if (forexTrades.get() > 0) forexWins.get() * 100.0 / forexTrades.get() else 0.0
        sb.appendLine("  📈 Stocks: ${stockTrades.get()} trades | ${String.format("%.1f", stockWr)}% WR")
        sb.appendLine("  🛢️ Commodities: ${commodityTrades.get()} trades | ${String.format("%.1f", commodityWr)}% WR")
        sb.appendLine("  🥇 Metals: ${metalTrades.get()} trades | ${String.format("%.1f", metalWr)}% WR")
        sb.appendLine("  💱 Forex: ${forexTrades.get()} trades | ${String.format("%.1f", forexWr)}% WR")
        sb.appendLine()
        sb.appendLine("LAYER TRUST SCORES:")
        layerPerpsTrust.entries.sortedByDescending { it.value }.forEach { (name, trust) ->
            val corr = layerPerpsCorrelation[name]
            val accuracy = corr?.getAccuracy() ?: 0.0
            val signals = corr?.signalsGiven ?: 0
            sb.appendLine("  $name: trust=${String.format("%.2f", trust)} | acc=${String.format("%.1f", accuracy)}% | signals=$signals")
        }
        return sb.toString()
    }
}

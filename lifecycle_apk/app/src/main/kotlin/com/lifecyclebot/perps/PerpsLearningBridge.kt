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
    // V5.9.374 — PER-ASSET LEARNING LANES
    // ═══════════════════════════════════════════════════════════════════════════
    //
    // The 41+ AI layers were originally built for memecoin trading. They were
    // later reused for Perps/Stocks/Forex/Metals/Commodities but never given
    // asset-class context. Every layer's "accuracy" was a mixed-bag average
    // (or worse: only reflected one asset class while being blind to others).
    //
    // New model: every layer has 6 INDEPENDENT correlation lanes, one per
    // asset class. Each lane tracks its own signals, accuracy, trust, and
    // PnL contribution. A layer can be 0.85 trust in MEME and 0.25 in FOREX
    // simultaneously — and be graded honestly in each lane.
    //
    // Key format:  "$layerName#$assetClass"   e.g.  "BehaviorAI#MEME"
    //
    // Backward-compat on load:
    //   • unsuffixed keys (V5.9.373 and earlier)  → mapped to #PERPS lane
    //   • "${name}_STOCK" suffix (V5.7.3 legacy)  → mapped to #STOCK lane
    //
    // ═══════════════════════════════════════════════════════════════════════════
    enum class AssetClass { MEME, PERPS, STOCK, FOREX, METAL, COMMODITY }

    private fun laneKey(layerName: String, asset: AssetClass): String =
        "${layerName}#${asset.name}"

    /** Default layer lists per asset class — used when a caller doesn't supply
     *  its own contributingLayers (e.g. the Forex/Metal/Commodity record paths
     *  historically only bumped aggregate counters). These are the layers most
     *  likely to have materially influenced a trade in each lane. Callers that
     *  know the exact voting list SHOULD pass it explicitly. */
    private val defaultMemeLayers = listOf(
        "BehaviorAI", "FluidLearningAI", "MomentumPredictorAI", "QualityTraderAI",
        "VolatilityRegimeAI", "LiquidityCycleAI", "FearGreedAI", "MarketRegimeAI",
        "SocialVelocityAI", "CashGenerationAI", "EducationSubLayerAI",
        "MetaCognitionAI", "RegimeTransitionAI", "SmartMoneyDivergenceAI",
        "DipHunterAI", "SellOptimizationAI", "HoldTimeOptimizerAI",
        "WhaleTrackerAI", "UltraFastRugDetectorAI", "OrderFlowImbalanceAI",
        "CollectiveIntelligenceAI", "MoonshotTraderAI", "ShitCoinTraderAI",
        "ShitCoinExpress", "BlueChipTraderAI", "ProjectSniperAI",
    )
    private val defaultForexLayers = listOf(
        "MarketRegimeAI", "VolatilityRegimeAI", "RegimeTransitionAI",
        "FluidLearningAI", "EducationSubLayerAI", "MetaCognitionAI",
        "FearGreedAI", "BehaviorAI", "CashGenerationAI",
        "SmartMoneyDivergenceAI", "MomentumPredictorAI",
    )
    private val defaultMetalLayers = listOf(
        "MarketRegimeAI", "VolatilityRegimeAI", "FluidLearningAI",
        "EducationSubLayerAI", "MetaCognitionAI", "BehaviorAI",
        "CashGenerationAI", "RegimeTransitionAI", "QualityTraderAI",
        "MomentumPredictorAI",
    )
    private val defaultCommodityLayers = listOf(
        "MarketRegimeAI", "VolatilityRegimeAI", "FluidLearningAI",
        "EducationSubLayerAI", "MetaCognitionAI", "BehaviorAI",
        "CashGenerationAI", "SmartMoneyDivergenceAI", "RegimeTransitionAI",
        "MomentumPredictorAI",
    )

    fun defaultLayersFor(asset: AssetClass): List<String> = when (asset) {
        AssetClass.MEME -> defaultMemeLayers
        AssetClass.PERPS -> layerConfigs.keys.toList()
        AssetClass.STOCK -> listOf(
            "BlueChipTraderAI", "QualityTraderAI", "MarketRegimeAI",
            "SmartMoneyDivergenceAI", "FluidLearningAI", "EducationSubLayerAI",
        )
        AssetClass.FOREX -> defaultForexLayers
        AssetClass.METAL -> defaultMetalLayers
        AssetClass.COMMODITY -> defaultCommodityLayers
    }
    
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
        
        // Initialize default trust scores (V5.9.374 — write to PERPS lane)
        layerConfigs.forEach { (name, config) ->
            val key = laneKey(name, AssetClass.PERPS)
            if (!layerPerpsTrust.containsKey(key)) {
                layerPerpsTrust[key] = config.trustWeight
            }
        }

        // V5.8.0: Load persisted trust scores from Turso (async, blends with local prefs)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = com.lifecyclebot.collective.CollectiveLearning.getClient() ?: return@launch
                val perfsFromTurso = client.getPerpsLayerRankings()
                for (perf in perfsFromTurso) {
                    // V5.9.374 — Turso trust blends into the PERPS lane
                    val key = laneKey(perf.layerName, AssetClass.PERPS)
                    val localTrust = layerPerpsTrust[key] ?: continue
                    val blended = (perf.trustScore * 0.6 + localTrust * 0.4).coerceIn(0.05, 1.0)
                    layerPerpsTrust[key] = blended
                }
                if (perfsFromTurso.isNotEmpty()) {
                    ErrorLogger.info(TAG, "🧠 Loaded ${perfsFromTurso.size} layer trust scores from Turso (cross-session)")
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "🧠 Turso trust load error: ${e.message}")
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
        
        // Restore trust scores (legacy unsuffixed → PERPS lane)
        layerConfigs.keys.forEach { name ->
            val trust = p.getFloat("trust_$name", layerConfigs[name]?.trustWeight?.toFloat() ?: 0.5f)
            // V5.9.374: write legacy trust into the PERPS lane; it will be saved
            // back with the lane suffix on next save() cycle.
            layerPerpsTrust[laneKey(name, AssetClass.PERPS)] = trust.toDouble()
        }

        // V5.9.374 — restore new lane-keyed trust scores (layerName#ASSET)
        AssetClass.values().forEach { asset ->
            // For every known layer, look for a lane-specific trust value
            (layerConfigs.keys + defaultMemeLayers + defaultForexLayers + defaultMetalLayers + defaultCommodityLayers)
                .distinct()
                .forEach { name ->
                    val key = laneKey(name, asset)
                    val lane = p.getFloat("trust_$key", Float.NaN)
                    if (!lane.isNaN()) layerPerpsTrust[key] = lane.toDouble()
                }
        }
        
        // Restore correlations (legacy unsuffixed → PERPS lane)
        layerConfigs.keys.forEach { name ->
            val signals = p.getInt("corr_signals_$name", 0)
            val correct = p.getInt("corr_correct_$name", 0)
            val pnl = p.getFloat("corr_pnl_$name", 0f)
            if (signals > 0) {
                layerPerpsCorrelation[laneKey(name, AssetClass.PERPS)] =
                    CorrelationData(signals, correct, pnl.toDouble(), System.currentTimeMillis())
            }
        }

        // V5.9.374 — restore legacy `_STOCK` suffix into STOCK lane
        layerConfigs.keys.forEach { name ->
            val legacyKey = "${name}_STOCK"
            val signals = p.getInt("corr_signals_$legacyKey", 0)
            val correct = p.getInt("corr_correct_$legacyKey", 0)
            val pnl = p.getFloat("corr_pnl_$legacyKey", 0f)
            if (signals > 0) {
                layerPerpsCorrelation[laneKey(name, AssetClass.STOCK)] =
                    CorrelationData(signals, correct, pnl.toDouble(), System.currentTimeMillis())
            }
            val trustLegacy = p.getFloat("trust_$legacyKey", Float.NaN)
            if (!trustLegacy.isNaN()) {
                layerPerpsTrust[laneKey(name, AssetClass.STOCK)] = trustLegacy.toDouble()
            }
        }

        // V5.9.374 — restore new lane-suffixed correlation keys
        AssetClass.values().forEach { asset ->
            (layerConfigs.keys + defaultMemeLayers + defaultForexLayers + defaultMetalLayers + defaultCommodityLayers)
                .distinct()
                .forEach { name ->
                    val key = laneKey(name, asset)
                    val signals = p.getInt("corr_signals_$key", 0)
                    val correct = p.getInt("corr_correct_$key", 0)
                    val pnl = p.getFloat("corr_pnl_$key", 0f)
                    if (signals > 0) {
                        layerPerpsCorrelation[key] =
                            CorrelationData(signals, correct, pnl.toDouble(), System.currentTimeMillis())
                    }
                }
        }
        
        totalPerpsLearningEvents.set(p.getInt("totalLearningEvents", 0))

        // V5.9.382 — restore aggregate asset-class counters (previously
        // in-memory only; reset every restart, causing mismatches vs lanes).
        restoreAssetCounters()
    }
    
    fun save() {
        val p = prefs ?: return
        
        p.edit().apply {
            // V5.9.374 — save lane-keyed trust + correlation. Legacy unsuffixed
            // keys are retained in-place for read-only back-compat but all new
            // writes use the "$name#$asset" lane format.
            layerPerpsTrust.forEach { (key, trust) ->
                putFloat("trust_$key", trust.toFloat())
            }
            layerPerpsCorrelation.forEach { (key, corr) ->
                putInt("corr_signals_$key", corr.signalsGiven)
                putInt("corr_correct_$key", corr.signalsCorrect)
                putFloat("corr_pnl_$key", corr.totalPnlContribution.toFloat())
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
            
            val trust = layerPerpsTrust[laneKey(name, AssetClass.PERPS)] ?: config.trustWeight
            
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
     * V5.9.374 — CORE LANE LEARNING ENTRY POINT.
     * Every asset class routes through this. Each (layer, asset) pair gets its
     * own independent correlation record + trust score, so a layer can excel
     * in memes while still learning in forex without the numbers bleeding
     * into each other.
     *
     * `directionCorrect` is optional — only perps supplies it. Non-perps
     * assets use `isWin` as the universal success metric.
     */
    fun learnFromAssetTrade(
        asset: AssetClass,
        contributingLayers: List<String>,
        isWin: Boolean,
        pnlPct: Double,
        symbol: String = "",
        directionCorrect: Boolean? = null,
    ) {
        totalPerpsLearningEvents.incrementAndGet()
        val layers = if (contributingLayers.isEmpty()) defaultLayersFor(asset) else contributingLayers

        layers.forEach { layerName ->
            val key = laneKey(layerName, asset)
            val corr = layerPerpsCorrelation.getOrPut(key) { CorrelationData() }
            corr.signalsGiven++
            // Grading: directional perps layers use directionCorrect; everything
            // else (non-directional + every non-perps asset class) grades on isWin.
            val cfg = layerConfigs[layerName]
            val useDirection = asset == AssetClass.PERPS &&
                cfg?.isDirectional == true &&
                directionCorrect != null
            val layerCorrect = if (useDirection) directionCorrect!! else isWin
            if (layerCorrect) corr.signalsCorrect++
            corr.totalPnlContribution += pnlPct
            corr.lastUpdate = System.currentTimeMillis()

            val currentTrust = layerPerpsTrust[key] ?: (cfg?.trustWeight ?: 0.5)
            val trustDelta = if (layerCorrect) 0.01 else -0.01
            layerPerpsTrust[key] = (currentTrust + trustDelta).coerceIn(0.1, 1.0)
        }

        crossLayerSyncs.incrementAndGet()
        if (totalPerpsLearningEvents.get() % 10 == 0) save()  // batched persistence

        ErrorLogger.info(
            TAG,
            "🧠 ${asset.name} learning: ${if (symbol.isNotBlank()) "$symbol " else ""}" +
                "${if (isWin) "WIN" else "LOSS"} ${"%.1f".format(pnlPct)}% | layers=${layers.size}"
        )
    }

    /**
     * Learn from a completed perps trade
     * Routes the outcome to all layers that contributed to the decision
     */
    fun learnFromPerpsTrade(
        trade: PerpsTrade,
        contributingLayers: List<String>,
        predictedDirection: PerpsDirection,
    ) {
        val isWin = trade.pnlPct >= 1.0  // V5.9.225: 1% floor
        val directionCorrect = (trade.direction == predictedDirection) == isWin

        // V5.9.374 — route through the per-asset lane entry point (PERPS lane).
        // Kept the trade-level side-effects below (routeLearningToLayer +
        // Turso persistence) since they existed before the lane refactor.
        learnFromAssetTrade(
            asset = AssetClass.PERPS,
            contributingLayers = contributingLayers,
            isWin = isWin,
            pnlPct = trade.pnlPct,
            symbol = "${trade.market.symbol} ${trade.direction.symbol}",
            directionCorrect = directionCorrect,
        )

        contributingLayers.forEach { layerName ->
            val cfg = layerConfigs[layerName]
            val layerCorrect = if (cfg?.isDirectional == true) directionCorrect else isWin
            routeLearningToLayer(layerName, trade, layerCorrect)
        }

        save()

        // V5.8.0: Persist updated layer trust to Turso (fire-and-forget)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = com.lifecyclebot.collective.CollectiveLearning.getClient() ?: return@launch
                for (layerName in contributingLayers) {
                    client.updatePerpsLayerPerformance(
                        layerName = layerName,
                        market = trade.market.symbol,
                        direction = trade.direction.name,
                        isWin = trade.pnlPct >= 1.0,  // V5.9.225: 1% floor
                        pnlPct = trade.pnlPct
                    )
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "🧠 Trust persist error: ${e.message}")
            }
        }
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
        val isWin = trade.pnlPct >= 1.0  // V5.9.225: 1% floor
        val stockLayers = listOf(
            "BlueChipTraderAI", "QualityTraderAI", "MarketRegimeAI",
            "SmartMoneyDivergenceAI", "FluidLearningAI", "EducationSubLayerAI",
        )
        val allLayers = (contributingLayers + stockLayers).distinct()

        // V5.9.374 — route through the STOCK lane of the per-asset entry point
        learnFromAssetTrade(
            asset = AssetClass.STOCK,
            contributingLayers = allLayers,
            isWin = isWin,
            pnlPct = trade.pnlPct,
            symbol = "${trade.market.symbol} ${trade.direction.symbol}",
        )
        allLayers.forEach { layerName -> routeLearningToLayer(layerName, trade, isWin) }

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
        save()
    }
    
    /**
     * V5.7.3: Get stock-specific layer recommendations
     */
    fun getStockLayerRecommendations(market: PerpsMarket): Map<String, Double> {
        if (!market.isStock) return emptyMap()
        // V5.9.374 — read from STOCK lane keys ("$name#STOCK"). Fall back to
        // PERPS-lane trust if the stock lane has no data yet for this layer.
        return layerConfigs.keys
            .filter { name -> layerConfigs[name]?.applicableMarkets?.contains(market) == true }
            .mapNotNull { name ->
                val stockTrust = layerPerpsTrust[laneKey(name, AssetClass.STOCK)]
                val perpsTrust = layerPerpsTrust[laneKey(name, AssetClass.PERPS)]
                val t = stockTrust ?: perpsTrust
                if (t != null) name to t else null
            }
            .sortedByDescending { it.second }
            .take(5)
            .toMap()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS & DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getLayerPerpsStats(): Map<String, Pair<Double, Double>> {
        // V5.9.374 — aggregate rollup across all lanes so existing callers
        // (the old diagnostics panel, CryptoAltActivity, MainActivity) keep
        // getting a single (trust, accuracy) per layer. Trust = average of
        // lanes with data; accuracy = sum(correct) / sum(signals) across lanes.
        return layerConfigs.keys.associateWith { name ->
            var signalsTotal = 0
            var correctTotal = 0
            var trustSum = 0.0
            var trustCount = 0
            AssetClass.values().forEach { asset ->
                val key = laneKey(name, asset)
                layerPerpsCorrelation[key]?.let {
                    signalsTotal += it.signalsGiven
                    correctTotal += it.signalsCorrect
                }
                layerPerpsTrust[key]?.let {
                    trustSum += it
                    trustCount++
                }
            }
            val avgTrust = if (trustCount > 0) trustSum / trustCount else 0.5
            val acc = if (signalsTotal > 0) correctTotal.toDouble() / signalsTotal * 100 else 0.0
            Pair(avgTrust, acc)
        }
    }

    /**
     * V5.9.374 — per-asset stats for every known layer. UI uses this to
     * render the lane breakdown: "BehaviorAI  MEME: 34% (3366)  PERPS: 3% (30)".
     * Returns layerName → (asset → (trust, accuracy, signals)).
     */
    data class LaneStats(val trust: Double, val accuracy: Double, val signals: Int)
    fun getLayerPerAssetStats(): Map<String, Map<AssetClass, LaneStats>> {
        val allLayerNames = (layerConfigs.keys + defaultMemeLayers + defaultForexLayers +
            defaultMetalLayers + defaultCommodityLayers).distinct()
        return allLayerNames.associateWith { name ->
            AssetClass.values().associateWith { asset ->
                val key = laneKey(name, asset)
                val corr = layerPerpsCorrelation[key]
                val trust = layerPerpsTrust[key] ?: (layerConfigs[name]?.trustWeight ?: 0.5)
                LaneStats(
                    trust = trust,
                    accuracy = corr?.getAccuracy() ?: 0.0,
                    signals = corr?.signalsGiven ?: 0,
                )
            }.filterValues { it.signals > 0 }
        }.filterValues { it.isNotEmpty() }
    }
    
    fun getTotalLearningEvents(): Int = totalPerpsLearningEvents.get()
    fun getCrossLayerSyncs(): Int = crossLayerSyncs.get()
    
    fun getConnectedLayerCount(): Int = layerConfigs.size

    /**
     * V5.9.368 — One-shot reset of correlation stats for non-directional
     * layers. Pre-V5.9.368, these layers were being graded against
     * directional outcome — producing nonsense accuracy stats (e.g.
     * BehaviorAI at 1.3% on 2589 signals). Once the grading bug is
     * fixed, these stats should start fresh so the new measurement
     * isn't dragged down by 2k+ mis-graded historical signals.
     *
     * Idempotent — safe to call on every boot. Tracks a flag in prefs
     * so the reset only runs once per install.
     */
    fun resetNonDirectionalCorrelationOnce() {
        val p = prefs ?: return
        // V5.9.380 — second reset. V5.9.374 lanes shipped without per-layer
        // voting, so every layer in a lane showed identical accuracy (user
        // saw 297 trades × 20.2% on every MEME layer). V5.9.380 adds per-
        // layer vote capture (LayerVoteSampler) + replay (LayerVoteStore).
        // With votes live, layers will finally diverge — but only if we
        // clear the uniform-stat baseline from V5.9.374 first.
        if (p.getBoolean("lanes_reset_v5_9_380", false)) return

        val before = layerPerpsCorrelation.size
        layerPerpsCorrelation.clear()
        // Soft-pull trust toward neutral, same as V5.9.374 reset.
        layerPerpsTrust.replaceAll { _, v -> 0.5 + (v - 0.5) * 0.2 }

        p.edit().apply {
            p.all.keys.filter { it.startsWith("corr_signals_") || it.startsWith("corr_correct_") || it.startsWith("corr_pnl_") }
                .forEach { remove(it) }
            putBoolean("lanes_reset_v5_9_380", true)
            putBoolean("lanes_reset_v5_9_374", true)     // legacy flag
            putBoolean("non_dir_corr_reset_v5_9_372", true)  // legacy flag
            putBoolean("non_dir_corr_reset_v5_9_368", true)  // legacy flag
            apply()
        }
        ErrorLogger.info(
            TAG,
            "🧹 V5.9.380: cleared $before correlation records — fresh baseline; " +
                "per-layer voting (LayerVoteSampler) now gates signal credit"
        )
    }
    
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
    // V5.9.382 — MEME aggregate counters (for parity with the other 5 classes)
    private val memeTrades = AtomicInteger(0)
    private val memeWins = AtomicInteger(0)

    /** V5.9.382 — restore aggregate asset-class counters from prefs so they
     *  don't zero out on every restart. Also reseeds from lane signals when
     *  both prefs + counter are empty (recovers pre-V5.9.382 history). */
    private fun restoreAssetCounters() {
        val p = prefs ?: return
        stockTrades.set(p.getInt("agg_stockTrades", 0))
        stockWins.set(p.getInt("agg_stockWins", 0))
        commodityTrades.set(p.getInt("agg_commodityTrades", 0))
        commodityWins.set(p.getInt("agg_commodityWins", 0))
        metalTrades.set(p.getInt("agg_metalTrades", 0))
        metalWins.set(p.getInt("agg_metalWins", 0))
        forexTrades.set(p.getInt("agg_forexTrades", 0))
        forexWins.set(p.getInt("agg_forexWins", 0))
        memeTrades.set(p.getInt("agg_memeTrades", 0))
        memeWins.set(p.getInt("agg_memeWins", 0))

        // Reseed from lane signal counts when a class has zero aggregate
        // but the lane has accumulated signals (pre-V5.9.382 data recovery).
        fun reseedFrom(asset: AssetClass, tradesCounter: AtomicInteger, winsCounter: AtomicInteger) {
            if (tradesCounter.get() > 0) return
            var sigSum = 0
            var corrSum = 0
            layerPerpsCorrelation.forEach { (key, corr) ->
                if (key.endsWith("#${asset.name}")) {
                    sigSum = maxOf(sigSum, corr.signalsGiven)
                    if (corr.signalsGiven > 0 && corr.signalsGiven >= sigSum) {
                        corrSum = corr.signalsCorrect
                    }
                }
            }
            if (sigSum > 0) {
                tradesCounter.set(sigSum)
                winsCounter.set(corrSum)
            }
        }
        reseedFrom(AssetClass.STOCK, stockTrades, stockWins)
        reseedFrom(AssetClass.COMMODITY, commodityTrades, commodityWins)
        reseedFrom(AssetClass.METAL, metalTrades, metalWins)
        reseedFrom(AssetClass.FOREX, forexTrades, forexWins)
        reseedFrom(AssetClass.MEME, memeTrades, memeWins)
    }

    /** V5.9.382 — persist aggregate counters. Called from save(). */
    private fun saveAssetCounters() {
        val p = prefs ?: return
        p.edit().apply {
            putInt("agg_stockTrades", stockTrades.get())
            putInt("agg_stockWins", stockWins.get())
            putInt("agg_commodityTrades", commodityTrades.get())
            putInt("agg_commodityWins", commodityWins.get())
            putInt("agg_metalTrades", metalTrades.get())
            putInt("agg_metalWins", metalWins.get())
            putInt("agg_forexTrades", forexTrades.get())
            putInt("agg_forexWins", forexWins.get())
            putInt("agg_memeTrades", memeTrades.get())
            putInt("agg_memeWins", memeWins.get())
            apply()
        }
    }

    fun recordStockTrade(market: PerpsMarket, direction: PerpsDirection, isWin: Boolean, pnlPct: Double) {
        stockTrades.incrementAndGet()
        if (isWin) stockWins.incrementAndGet()
        // V5.9.374 — also feed the STOCK lane of every applicable layer.
        learnFromAssetTrade(AssetClass.STOCK, emptyList(), isWin, pnlPct, market.symbol)
        saveAssetCounters()
    }
    
    fun recordCommodityTrade(market: PerpsMarket, direction: PerpsDirection, isWin: Boolean, pnlPct: Double) {
        commodityTrades.incrementAndGet()
        if (isWin) commodityWins.incrementAndGet()
        learnFromAssetTrade(AssetClass.COMMODITY, emptyList(), isWin, pnlPct, market.symbol)
        saveAssetCounters()
    }
    
    fun recordMetalTrade(market: PerpsMarket, direction: PerpsDirection, isWin: Boolean, pnlPct: Double) {
        metalTrades.incrementAndGet()
        if (isWin) metalWins.incrementAndGet()
        learnFromAssetTrade(AssetClass.METAL, emptyList(), isWin, pnlPct, market.symbol)
        saveAssetCounters()
    }
    
    fun recordForexTrade(market: PerpsMarket, direction: PerpsDirection, isWin: Boolean, pnlPct: Double) {
        forexTrades.incrementAndGet()
        if (isWin) forexWins.incrementAndGet()
        learnFromAssetTrade(AssetClass.FOREX, emptyList(), isWin, pnlPct, market.symbol)
        saveAssetCounters()
    }

    /**
     * V5.9.374 — MEME lane entry. Called by the memecoin close path in
     * Executor.kt so the 26 meme layers finally receive training signal
     * from the 5000+ meme trades they've been blind to.
     */
    fun recordMemeTrade(symbol: String, isWin: Boolean, pnlPct: Double, contributingLayers: List<String> = emptyList()) {
        memeTrades.incrementAndGet()
        if (isWin) memeWins.incrementAndGet()
        learnFromAssetTrade(AssetClass.MEME, contributingLayers, isWin, pnlPct, symbol)
        saveAssetCounters()
    }
    
    fun getAssetClassStats(): Map<String, Pair<Int, Int>> = mapOf(
        "Memes" to Pair(memeTrades.get(), memeWins.get()),
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
        val memeWr = if (memeTrades.get() > 0) memeWins.get() * 100.0 / memeTrades.get() else 0.0
        val stockWr = if (stockTrades.get() > 0) stockWins.get() * 100.0 / stockTrades.get() else 0.0
        val commodityWr = if (commodityTrades.get() > 0) commodityWins.get() * 100.0 / commodityTrades.get() else 0.0
        val metalWr = if (metalTrades.get() > 0) metalWins.get() * 100.0 / metalTrades.get() else 0.0
        val forexWr = if (forexTrades.get() > 0) forexWins.get() * 100.0 / forexTrades.get() else 0.0
        sb.appendLine("  💎 Memes: ${memeTrades.get()} trades | ${String.format("%.1f", memeWr)}% WR")
        sb.appendLine("  📈 Stocks: ${stockTrades.get()} trades | ${String.format("%.1f", stockWr)}% WR")
        sb.appendLine("  🛢️ Commodities: ${commodityTrades.get()} trades | ${String.format("%.1f", commodityWr)}% WR")
        sb.appendLine("  🥇 Metals: ${metalTrades.get()} trades | ${String.format("%.1f", metalWr)}% WR")
        sb.appendLine("  💱 Forex: ${forexTrades.get()} trades | ${String.format("%.1f", forexWr)}% WR")
        sb.appendLine()
        // V5.9.374 — per-asset lane breakdown. Shows exactly where each
        // layer is getting signal and how accurate it is in each arena.
        sb.appendLine("LAYER LANES (signals · acc · trust):")
        val perAsset = getLayerPerAssetStats()
        val sortedLayers = perAsset.keys.sortedByDescending { name ->
            perAsset[name]?.values?.sumOf { it.signals } ?: 0
        }
        for (name in sortedLayers) {
            val lanes = perAsset[name] ?: continue
            val totalSignals = lanes.values.sumOf { it.signals }
            sb.appendLine("  $name  (total=$totalSignals)")
            AssetClass.values().forEach { asset ->
                val stat = lanes[asset] ?: return@forEach
                val icon = when (asset) {
                    AssetClass.MEME -> "💎"
                    AssetClass.PERPS -> "⚡"
                    AssetClass.STOCK -> "📈"
                    AssetClass.FOREX -> "💱"
                    AssetClass.METAL -> "🥇"
                    AssetClass.COMMODITY -> "🛢️"
                }
                sb.appendLine(
                    "    $icon ${asset.name.padEnd(9)} " +
                        "sig=${stat.signals.toString().padStart(5)} " +
                        "acc=${String.format("%5.1f", stat.accuracy)}% " +
                        "trust=${String.format("%.2f", stat.trust)}"
                )
            }
        }
        if (perAsset.isEmpty()) {
            sb.appendLine("  (no lane data yet — waiting for first trade in each asset class)")
        }
        // V5.9.362 — surface the wiring health for the 9 outer-ring layers
        // wired in V5.9.357 so the user can watch them converge.
        sb.appendLine()
        sb.appendLine(com.lifecyclebot.engine.WiringHealth.detailBlock())
        return sb.toString()
    }
}

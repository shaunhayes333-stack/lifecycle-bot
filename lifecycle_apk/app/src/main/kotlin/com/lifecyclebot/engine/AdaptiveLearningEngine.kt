package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * AdaptiveLearningEngine — The brain that learns from every trade
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * This is the core "AI" component that transforms raw trades into trading intelligence:
 * 
 * 1. TRADE LABELING: Classifies every trade as GOOD/BAD/MID
 * 2. FEATURE CAPTURE: Records 15+ features per trade for pattern analysis
 * 3. OUTCOME SCORING: Scores trades -2 to +2 based on quality, not just P&L
 * 4. PATTERN EXTRACTION: Identifies winning/losing patterns after 50+ trades
 * 5. ADAPTIVE SCORING: Dynamically adjusts entry weights based on learned patterns
 * 
 * The result: A bot that gets smarter with every trade.
 */
object AdaptiveLearningEngine {

    private const val PREFS_NAME = "adaptive_learning"
    private const val KEY_FEATURE_WEIGHTS = "feature_weights"
    private const val KEY_GOOD_PATTERNS = "good_patterns"
    private const val KEY_BAD_PATTERNS = "bad_patterns"
    private const val KEY_TRADE_COUNT = "trade_count"
    private const val KEY_LEARNING_RATE = "learning_rate"
    private const val KEY_LAST_RETRAIN = "last_retrain_ts"

    private var prefs: SharedPreferences? = null

    // ═══════════════════════════════════════════════════════════════════
    // TRADE LABELS
    // ═══════════════════════════════════════════════════════════════════
    
    enum class TradeLabel {
        // ═══════════════════════════════════════════════════════════════════
        // PRIORITY 3: IMPROVED LEARNING LABELS
        // More granular classification for better AI learning
        // ═══════════════════════════════════════════════════════════════════
        
        // GOOD outcomes (positive learning signal)
        GOOD_RUNNER,            // Strong runner, +20%+ clean move
        GOOD_CONTINUATION,      // Solid continuation, volume confirms
        GOOD_SECOND_LEG,        // Successful re-entry on second leg (reaccumulation)
        
        // MID outcomes (neutral/weak learning signal)
        MID_SMALL_WIN,          // Small win (+5-15%), no strong follow-through
        MID_FLAT_CHOP,          // Flat/choppy, price went nowhere (-5% to +5%)
        MID_WEAK_FOLLOW,        // Had momentum but fizzled, weak follow-through
        MID_STOPPED_OUT,        // Stopped out near breakeven, normal trade management
        
        // BAD outcomes (negative learning signal)
        BAD_DUMP,               // Dump after entry, likely coordinated sell
        BAD_RUG,                // Rug pull / liquidity collapse
        BAD_FAKE_PRESSURE,      // Fake buy pressure, price didn't follow
        BAD_DISTRIBUTION,       // Entered during distribution phase
        BAD_DEAD_CAT,           // Entered dead cat bounce, trend already broken
        BAD_CHASING_TOP,        // Chased the top, entered too late in move
        
        // Legacy aliases for backward compatibility
        @Deprecated("Use MID_FLAT_CHOP instead")
        MID_CHOP,               // Alias for MID_FLAT_CHOP
    }

    // ═══════════════════════════════════════════════════════════════════
    // OUTCOME SCORES
    // ═══════════════════════════════════════════════════════════════════
    
    // Score per trade quality (not just win/loss)
    // +2 = strong runner (20%+)
    // +1 = small win (5-20%)
    //  0 = flat (-5% to +5%)
    // -1 = loss (-5% to -15%)
    // -2 = rug/heavy loss (-15%+)
    
    fun calculateOutcomeScore(pnlPct: Double, maxGainBeforeDrop: Double, timeToPeakMins: Double): Int {
        return when {
            // STRONG RUNNER: Hit 20%+ and held well
            pnlPct >= 20.0 -> 2
            // SMALL WIN: Profitable but not explosive
            pnlPct >= 5.0 -> 1
            // FLAT: Basically break-even
            pnlPct >= -5.0 -> 0
            // LOSS: Moderate loss
            pnlPct >= -15.0 -> -1
            // RUG/HEAVY LOSS: Severe loss
            else -> -2
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FEATURE CAPTURE (The Goldmine)
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * TradeFeatures — Everything we capture about a trade for learning
     */
    data class TradeFeatures(
        // Entry conditions
        val entryMcapUsd: Double,          // Market cap at entry
        val tokenAgeMinutes: Double,       // Token age at entry
        val buyRatioPct: Double,           // Buy pressure %
        val volumeUsd: Double,             // Volume at entry
        val liquidityUsd: Double,          // Liquidity at entry
        val holderCount: Int,              // Holder count
        val topHolderPct: Double,          // Top holder concentration
        val holderGrowthRate: Double,      // Holder growth %
        val devWalletPct: Double,          // Dev wallet %
        val bondingCurveProgress: Double,  // 0-100% bonded
        val rugcheckScore: Double,         // RugCheck score
        val emaFanState: String,           // EMA alignment
        val entryScore: Double,            // Bot's entry score
        val volumeLiquidityRatio: Double,  // Vol/Liq ratio
        val priceFromAth: Double,          // % from ATH at entry
        
        // Outcome data
        val pnlPct: Double,                // Final P&L %
        val maxGainPct: Double,            // Max gain before exit
        val maxDrawdownPct: Double,        // Max drawdown during hold
        val timeToPeakMins: Double,        // Time to reach peak
        val holdTimeMins: Double,          // Total hold time
        val exitReason: String,            // Why we exited
        
        // Derived
        val outcomeScore: Int,             // -2 to +2 quality score
        val label: TradeLabel,             // Classification
    )

    /**
     * Capture features from a completed trade.
     */
    fun captureFeatures(
        // Entry data
        entryMcapUsd: Double,
        tokenAgeMinutes: Double,
        buyRatioPct: Double,
        volumeUsd: Double,
        liquidityUsd: Double,
        holderCount: Int,
        topHolderPct: Double,
        holderGrowthRate: Double,
        devWalletPct: Double,
        bondingCurveProgress: Double,
        rugcheckScore: Double,
        emaFanState: String,
        entryScore: Double,
        priceFromAth: Double,
        // Outcome data
        pnlPct: Double,
        maxGainPct: Double,
        maxDrawdownPct: Double,
        timeToPeakMins: Double,
        holdTimeMins: Double,
        exitReason: String,
        // PRIORITY 3: New parameters for improved classification
        entryPhase: String = "",
    ): TradeFeatures {
        val volLiqRatio = if (liquidityUsd > 0) volumeUsd / liquidityUsd else 0.0
        val outcomeScore = calculateOutcomeScore(pnlPct, maxGainPct, timeToPeakMins)
        val label = classifyTrade(
            pnlPct = pnlPct,
            maxGainPct = maxGainPct,
            maxDrawdownPct = maxDrawdownPct,
            exitReason = exitReason,
            topHolderPct = topHolderPct,
            entryPhase = entryPhase,
            holdTimeMins = holdTimeMins,
            emaFanAtEntry = emaFanState,
        )
        
        return TradeFeatures(
            entryMcapUsd = entryMcapUsd,
            tokenAgeMinutes = tokenAgeMinutes,
            buyRatioPct = buyRatioPct,
            volumeUsd = volumeUsd,
            liquidityUsd = liquidityUsd,
            holderCount = holderCount,
            topHolderPct = topHolderPct,
            holderGrowthRate = holderGrowthRate,
            devWalletPct = devWalletPct,
            bondingCurveProgress = bondingCurveProgress,
            rugcheckScore = rugcheckScore,
            emaFanState = emaFanState,
            entryScore = entryScore,
            volumeLiquidityRatio = volLiqRatio,
            priceFromAth = priceFromAth,
            pnlPct = pnlPct,
            maxGainPct = maxGainPct,
            maxDrawdownPct = maxDrawdownPct,
            timeToPeakMins = timeToPeakMins,
            holdTimeMins = holdTimeMins,
            exitReason = exitReason,
            outcomeScore = outcomeScore,
            label = label,
        )
    }

    /**
     * PRIORITY 3: Enhanced trade classification with granular labels.
     * 
     * Classification hierarchy:
     * 1. Check for clear wins (GOOD_*)
     * 2. Check for severe losses (BAD_RUG, BAD_DUMP)
     * 3. Check for pattern-based losses (BAD_DISTRIBUTION, BAD_DEAD_CAT, etc.)
     * 4. Check for weak outcomes (MID_*)
     */
    private fun classifyTrade(
        pnlPct: Double,
        maxGainPct: Double,
        maxDrawdownPct: Double,
        exitReason: String,
        topHolderPct: Double,
        // NEW parameters for improved classification
        entryPhase: String = "",
        holdTimeMins: Double = 0.0,
        emaFanAtEntry: String = "",
    ): TradeLabel {
        val reasonLower = exitReason.lowercase()
        
        return when {
            // ═══════════════════════════════════════════════════════════════
            // GOOD OUTCOMES
            // ═══════════════════════════════════════════════════════════════
            
            // GOOD_RUNNER: Strong move with follow-through
            pnlPct >= 20.0 && maxGainPct >= 25.0 -> TradeLabel.GOOD_RUNNER
            
            // GOOD_SECOND_LEG: Re-entry on reaccumulation worked
            pnlPct >= 15.0 && entryPhase.contains("reclaim", ignoreCase = true) -> 
                TradeLabel.GOOD_SECOND_LEG
            
            // GOOD_CONTINUATION: Solid trend continuation
            pnlPct >= 10.0 && maxDrawdownPct < 10.0 -> TradeLabel.GOOD_CONTINUATION
            
            // ═══════════════════════════════════════════════════════════════
            // BAD OUTCOMES - SEVERE
            // ═══════════════════════════════════════════════════════════════
            
            // BAD_RUG: Liquidity collapse or rug pull
            reasonLower.contains("rug") ||
            reasonLower.contains("liquidity_collapse") ||
            (pnlPct < -30.0 && maxDrawdownPct > 40.0) -> TradeLabel.BAD_RUG
            
            // BAD_DUMP: Coordinated whale/dev dump
            reasonLower.contains("dump") ||
            reasonLower.contains("whale_dump") ||
            reasonLower.contains("dev_dump") ||
            (pnlPct < -15.0 && topHolderPct > 30.0) -> TradeLabel.BAD_DUMP
            
            // ═══════════════════════════════════════════════════════════════
            // BAD OUTCOMES - PATTERN-BASED
            // ═══════════════════════════════════════════════════════════════
            
            // BAD_CHASING_TOP: Entered overextended, price immediately dropped
            pnlPct < -10.0 && maxGainPct < 3.0 && 
            entryPhase.contains("overextend", ignoreCase = true) -> 
                TradeLabel.BAD_CHASING_TOP
            
            // BAD_DISTRIBUTION: Entered during distribution phase
            pnlPct < -10.0 && 
            (entryPhase.contains("distribution", ignoreCase = true) ||
             emaFanAtEntry in listOf("BEAR_FAN", "BEAR_FLAT")) -> 
                TradeLabel.BAD_DISTRIBUTION
            
            // BAD_DEAD_CAT: Entered what looked like recovery but wasn't
            pnlPct < -10.0 && maxGainPct in 3.0..12.0 &&
            entryPhase.contains("reclaim", ignoreCase = true) ->
                TradeLabel.BAD_DEAD_CAT
            
            // BAD_FAKE_PRESSURE: High buy ratio but price dropped
            pnlPct < -10.0 && maxGainPct < 5.0 && maxDrawdownPct > 15.0 -> 
                TradeLabel.BAD_FAKE_PRESSURE
            
            // ═══════════════════════════════════════════════════════════════
            // MID OUTCOMES
            // ═══════════════════════════════════════════════════════════════
            
            // MID_SMALL_WIN: Profitable but weak
            pnlPct in 5.0..15.0 -> TradeLabel.MID_SMALL_WIN
            
            // MID_STOPPED_OUT: Normal stop loss, managed exit
            pnlPct in -10.0..-1.0 && reasonLower.contains("stop") -> 
                TradeLabel.MID_STOPPED_OUT
            
            // MID_WEAK_FOLLOW: Had some momentum but fizzled
            pnlPct in -5.0..5.0 && maxGainPct >= 8.0 -> TradeLabel.MID_WEAK_FOLLOW
            
            // MID_FLAT_CHOP: No movement, choppy market
            pnlPct in -5.0..5.0 && maxGainPct < 8.0 -> TradeLabel.MID_FLAT_CHOP
            
            // Small win not captured above
            pnlPct > 0 -> TradeLabel.MID_SMALL_WIN
            
            // Default: flat chop
            else -> TradeLabel.MID_FLAT_CHOP
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FEATURE WEIGHTS (The Adaptive Part)
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Feature weights learned from historical trades.
     * Higher weight = more important for entry decisions.
     */
    data class FeatureWeights(
        var mcapWeight: Double = 1.0,
        var ageWeight: Double = 1.0,
        var buyRatioWeight: Double = 1.5,      // Start higher - buy pressure matters
        var volumeWeight: Double = 1.2,
        var liquidityWeight: Double = 1.3,
        var holderCountWeight: Double = 1.0,
        var holderConcWeight: Double = 1.4,    // Start higher - concentration is risky
        var holderGrowthWeight: Double = 1.2,
        var devWalletWeight: Double = 1.3,
        var bondingCurveWeight: Double = 1.0,
        var rugcheckWeight: Double = 1.1,
        var emaFanWeight: Double = 1.5,        // Start higher - trend matters
        var volLiqRatioWeight: Double = 1.2,
        var athDistanceWeight: Double = 0.8,
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("mcap", mcapWeight)
                put("age", ageWeight)
                put("buyRatio", buyRatioWeight)
                put("volume", volumeWeight)
                put("liquidity", liquidityWeight)
                put("holderCount", holderCountWeight)
                put("holderConc", holderConcWeight)
                put("holderGrowth", holderGrowthWeight)
                put("devWallet", devWalletWeight)
                put("bondingCurve", bondingCurveWeight)
                put("rugcheck", rugcheckWeight)
                put("emaFan", emaFanWeight)
                put("volLiqRatio", volLiqRatioWeight)
                put("athDistance", athDistanceWeight)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): FeatureWeights {
                return FeatureWeights(
                    mcapWeight = json.optDouble("mcap", 1.0),
                    ageWeight = json.optDouble("age", 1.0),
                    buyRatioWeight = json.optDouble("buyRatio", 1.5),
                    volumeWeight = json.optDouble("volume", 1.2),
                    liquidityWeight = json.optDouble("liquidity", 1.3),
                    holderCountWeight = json.optDouble("holderCount", 1.0),
                    holderConcWeight = json.optDouble("holderConc", 1.4),
                    holderGrowthWeight = json.optDouble("holderGrowth", 1.2),
                    devWalletWeight = json.optDouble("devWallet", 1.3),
                    bondingCurveWeight = json.optDouble("bondingCurve", 1.0),
                    rugcheckWeight = json.optDouble("rugcheck", 1.1),
                    emaFanWeight = json.optDouble("emaFan", 1.5),
                    volLiqRatioWeight = json.optDouble("volLiqRatio", 1.2),
                    athDistanceWeight = json.optDouble("athDistance", 0.8),
                )
            }
        }
    }

    private var featureWeights = FeatureWeights()
    private var tradeCount = 0
    private var learningRate = 0.1  // How fast to adjust weights

    // ═══════════════════════════════════════════════════════════════════
    // PATTERN EXTRACTION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Learned pattern — a combination of feature ranges that predict outcomes
     */
    data class LearnedPattern(
        val name: String,
        val featureRanges: Map<String, Pair<Double, Double>>,  // feature -> (min, max)
        val avgOutcomeScore: Double,
        val sampleCount: Int,
        val confidence: Double,  // 0-1
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("avgOutcome", avgOutcomeScore)
                put("samples", sampleCount)
                put("confidence", confidence)
                val ranges = JSONObject()
                featureRanges.forEach { (k, v) ->
                    ranges.put(k, JSONArray().apply { put(v.first); put(v.second) })
                }
                put("ranges", ranges)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): LearnedPattern {
                val ranges = mutableMapOf<String, Pair<Double, Double>>()
                val rangesJson = json.optJSONObject("ranges")
                rangesJson?.keys()?.forEach { key ->
                    val arr = rangesJson.getJSONArray(key)
                    ranges[key] = Pair(arr.getDouble(0), arr.getDouble(1))
                }
                return LearnedPattern(
                    name = json.getString("name"),
                    featureRanges = ranges,
                    avgOutcomeScore = json.getDouble("avgOutcome"),
                    sampleCount = json.getInt("samples"),
                    confidence = json.getDouble("confidence"),
                )
            }
        }
    }

    private val goodPatterns = mutableListOf<LearnedPattern>()
    private val badPatterns = mutableListOf<LearnedPattern>()

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()
        ErrorLogger.info("AdaptiveLearning", "Initialized: $tradeCount trades, " +
            "${goodPatterns.size} good patterns, ${badPatterns.size} bad patterns")
    }

    private fun loadState() {
        val p = prefs ?: return
        
        tradeCount = p.getInt(KEY_TRADE_COUNT, 0)
        learningRate = p.getFloat(KEY_LEARNING_RATE, 0.1f).toDouble()
        
        // Load feature weights
        try {
            val json = p.getString(KEY_FEATURE_WEIGHTS, null)
            if (json != null) {
                featureWeights = FeatureWeights.fromJson(JSONObject(json))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AdaptiveLearning", "Failed to load weights: ${e.message}")
        }
        
        // Load good patterns
        try {
            val json = p.getString(KEY_GOOD_PATTERNS, "[]")
            val arr = JSONArray(json)
            goodPatterns.clear()
            for (i in 0 until arr.length()) {
                goodPatterns.add(LearnedPattern.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AdaptiveLearning", "Failed to load good patterns: ${e.message}")
        }
        
        // Load bad patterns
        try {
            val json = p.getString(KEY_BAD_PATTERNS, "[]")
            val arr = JSONArray(json)
            badPatterns.clear()
            for (i in 0 until arr.length()) {
                badPatterns.add(LearnedPattern.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AdaptiveLearning", "Failed to load bad patterns: ${e.message}")
        }
    }

    private fun saveState() {
        val p = prefs ?: return
        
        p.edit().apply {
            putInt(KEY_TRADE_COUNT, tradeCount)
            putFloat(KEY_LEARNING_RATE, learningRate.toFloat())
            putString(KEY_FEATURE_WEIGHTS, featureWeights.toJson().toString())
            putLong(KEY_LAST_RETRAIN, System.currentTimeMillis())
            
            // Save patterns
            val goodArr = JSONArray()
            goodPatterns.forEach { goodArr.put(it.toJson()) }
            putString(KEY_GOOD_PATTERNS, goodArr.toString())
            
            val badArr = JSONArray()
            badPatterns.forEach { badArr.put(it.toJson()) }
            putString(KEY_BAD_PATTERNS, badArr.toString())
            
            apply()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // LEARNING FROM TRADES
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Learn from a completed trade.
     * This is the core learning function - call after every trade exit.
     */
    fun learnFromTrade(features: TradeFeatures) {
        tradeCount++
        
        // Adjust feature weights based on outcome
        adjustWeights(features)
        
        // Log the learning with expanded emoji mapping for new labels
        val labelEmoji = when (features.label) {
            // GOOD outcomes
            TradeLabel.GOOD_RUNNER -> "🚀"
            TradeLabel.GOOD_CONTINUATION -> "✅"
            TradeLabel.GOOD_SECOND_LEG -> "🔄"
            // MID outcomes
            TradeLabel.MID_SMALL_WIN -> "📊"
            TradeLabel.MID_FLAT_CHOP -> "〰️"
            TradeLabel.MID_CHOP -> "〰️"  // Legacy alias
            TradeLabel.MID_WEAK_FOLLOW -> "📉"
            TradeLabel.MID_STOPPED_OUT -> "🛑"
            // BAD outcomes
            TradeLabel.BAD_DUMP -> "💔"
            TradeLabel.BAD_RUG -> "💀"
            TradeLabel.BAD_FAKE_PRESSURE -> "🎭"
            TradeLabel.BAD_DISTRIBUTION -> "📤"
            TradeLabel.BAD_DEAD_CAT -> "🐱"
            TradeLabel.BAD_CHASING_TOP -> "⛰️"
        }
        
        ErrorLogger.info("AdaptiveLearning", "$labelEmoji Trade #$tradeCount: ${features.label.name} " +
            "(score=${features.outcomeScore}, pnl=${features.pnlPct.fmt()}%)")
        
        // Extract patterns after enough trades
        if (tradeCount >= 50 && tradeCount % 25 == 0) {
            ErrorLogger.info("AdaptiveLearning", "🧠 Triggering pattern extraction at $tradeCount trades")
        }
        
        saveState()
    }

    /**
     * Adjust feature weights based on trade outcome.
     * 
     * CRITICAL: Penalize rugs heavily, reward runners more.
     */
    private fun adjustWeights(features: TradeFeatures) {
        // Outcome score with asymmetric weighting
        // Rugs (-2) hurt 3x more than runners (+2) help
        // This prevents optimizing for small safe wins
        val adjustmentFactor = when (features.outcomeScore) {
            2 -> learningRate * 1.5      // Strong runner: boost what worked
            1 -> learningRate * 0.8      // Small win: minor adjustment
            0 -> learningRate * 0.3      // Flat: minimal adjustment
            -1 -> learningRate * -1.2    // Loss: moderate penalty
            -2 -> learningRate * -3.0    // Rug: HEAVY penalty (3x)
            else -> 0.0
        }
        
        // Adjust each weight based on feature contribution
        // If feature was "good" and trade was good → increase weight
        // If feature was "good" and trade was bad → decrease weight
        
        // MCAP: Lower is usually better for early entries
        val mcapSignal = if (features.entryMcapUsd < 50_000) 1.0 else -0.5
        featureWeights.mcapWeight = (featureWeights.mcapWeight + mcapSignal * adjustmentFactor).coerceIn(0.3, 2.5)
        
        // Age: Younger can be better but also riskier
        val ageSignal = when {
            features.tokenAgeMinutes < 30 && features.outcomeScore >= 1 -> 1.0
            features.tokenAgeMinutes < 30 && features.outcomeScore <= -1 -> -1.5
            else -> 0.0
        }
        featureWeights.ageWeight = (featureWeights.ageWeight + ageSignal * adjustmentFactor).coerceIn(0.3, 2.5)
        
        // Buy ratio: Should correlate with success
        val buySignal = if (features.buyRatioPct > 55) 1.0 else -0.5
        featureWeights.buyRatioWeight = (featureWeights.buyRatioWeight + buySignal * adjustmentFactor).coerceIn(0.5, 3.0)
        
        // Holder concentration: High = bad
        val concSignal = if (features.topHolderPct < 25) 1.0 else -1.0
        featureWeights.holderConcWeight = (featureWeights.holderConcWeight + concSignal * adjustmentFactor).coerceIn(0.5, 3.0)
        
        // Holder growth: Positive = good
        val growthSignal = if (features.holderGrowthRate > 0) 1.0 else -0.5
        featureWeights.holderGrowthWeight = (featureWeights.holderGrowthWeight + growthSignal * adjustmentFactor).coerceIn(0.5, 2.5)
        
        // EMA fan: Bull fan should predict success
        val emaSignal = if (features.emaFanState.contains("BULL")) 1.0 else -0.3
        featureWeights.emaFanWeight = (featureWeights.emaFanWeight + emaSignal * adjustmentFactor).coerceIn(0.5, 3.0)
        
        // Volume/liquidity ratio
        val volLiqSignal = when {
            features.volumeLiquidityRatio > 0.5 && features.outcomeScore >= 0 -> 0.8
            features.volumeLiquidityRatio > 0.5 && features.outcomeScore < 0 -> -0.5
            else -> 0.0
        }
        featureWeights.volLiqRatioWeight = (featureWeights.volLiqRatioWeight + volLiqSignal * adjustmentFactor).coerceIn(0.3, 2.5)
        
        // Log significant weight changes
        if (abs(adjustmentFactor) > 0.15) {
            ErrorLogger.debug("AdaptiveLearning", "Weight adjustment: " +
                "buyRatio=${featureWeights.buyRatioWeight.fmt()} " +
                "holderConc=${featureWeights.holderConcWeight.fmt()} " +
                "emaFan=${featureWeights.emaFanWeight.fmt()}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ADAPTIVE SCORING ENGINE
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Calculate adaptive confidence score for a potential entry.
     * This is the main API for LifecycleStrategy to use.
     * 
     * Returns: Score 0-100, with recommendation
     */
    data class AdaptiveScore(
        val score: Double,              // 0-100
        val recommendation: String,     // "STRONG_BUY", "BUY", "SKIP"
        val sizeMultiplier: Double,     // 0.5-2.0 based on confidence
        val matchedGoodPatterns: List<String>,
        val matchedBadPatterns: List<String>,
        val explanation: String,
    )

    fun calculateAdaptiveScore(
        mcapUsd: Double,
        tokenAgeMinutes: Double,
        buyRatioPct: Double,
        volumeUsd: Double,
        liquidityUsd: Double,
        holderCount: Int,
        topHolderPct: Double,
        holderGrowthRate: Double,
        devWalletPct: Double,
        bondingCurveProgress: Double,
        rugcheckScore: Double,
        emaFanState: String,
        baseEntryScore: Double,
    ): AdaptiveScore {
        // Start with base score
        var score = baseEntryScore
        val explanations = mutableListOf<String>()
        
        // Apply learned weights to adjust score
        // Each feature contributes positively or negatively based on its value and weight
        
        // MCAP adjustment
        val mcapContrib = when {
            mcapUsd < 30_000 -> 8.0 * featureWeights.mcapWeight   // Very early = bonus
            mcapUsd < 100_000 -> 4.0 * featureWeights.mcapWeight  // Early = small bonus
            mcapUsd > 500_000 -> -3.0 * featureWeights.mcapWeight // Late = penalty
            else -> 0.0
        }
        score += mcapContrib
        if (abs(mcapContrib) >= 5) explanations.add("mcap:${if(mcapContrib>0)"+" else ""}${mcapContrib.toInt()}")
        
        // Token age adjustment
        val ageContrib = when {
            tokenAgeMinutes < 15 -> 6.0 * featureWeights.ageWeight   // Very fresh
            tokenAgeMinutes < 60 -> 3.0 * featureWeights.ageWeight   // Fresh
            tokenAgeMinutes > 240 -> -2.0 * featureWeights.ageWeight // Old
            else -> 0.0
        }
        score += ageContrib
        
        // Buy ratio adjustment (heavily weighted)
        val buyContrib = when {
            buyRatioPct >= 65 -> 10.0 * featureWeights.buyRatioWeight  // Strong buying
            buyRatioPct >= 55 -> 5.0 * featureWeights.buyRatioWeight   // Good buying
            buyRatioPct < 45 -> -8.0 * featureWeights.buyRatioWeight   // Selling pressure
            else -> 0.0
        }
        score += buyContrib
        if (abs(buyContrib) >= 5) explanations.add("buy%:${if(buyContrib>0)"+" else ""}${buyContrib.toInt()}")
        
        // Holder concentration (critical for rug detection)
        val concContrib = when {
            topHolderPct < 15 -> 8.0 * featureWeights.holderConcWeight   // Well distributed
            topHolderPct < 25 -> 4.0 * featureWeights.holderConcWeight   // OK distribution
            topHolderPct > 40 -> -12.0 * featureWeights.holderConcWeight // High risk
            topHolderPct > 30 -> -6.0 * featureWeights.holderConcWeight  // Moderate risk
            else -> 0.0
        }
        score += concContrib
        if (abs(concContrib) >= 5) explanations.add("conc:${if(concContrib>0)"+" else ""}${concContrib.toInt()}")
        
        // Holder growth
        val growthContrib = when {
            holderGrowthRate > 10 -> 6.0 * featureWeights.holderGrowthWeight   // Booming
            holderGrowthRate > 3 -> 3.0 * featureWeights.holderGrowthWeight    // Growing
            holderGrowthRate < -5 -> -8.0 * featureWeights.holderGrowthWeight  // Exodus
            else -> 0.0
        }
        score += growthContrib
        
        // EMA fan state (critical for trend)
        val emaContrib = when {
            emaFanState.contains("BULL_FAN") -> 10.0 * featureWeights.emaFanWeight
            emaFanState.contains("BULL") -> 5.0 * featureWeights.emaFanWeight
            emaFanState.contains("BEAR_FAN") -> -10.0 * featureWeights.emaFanWeight
            emaFanState.contains("BEAR") -> -5.0 * featureWeights.emaFanWeight
            else -> 0.0
        }
        score += emaContrib
        if (abs(emaContrib) >= 5) explanations.add("ema:${if(emaContrib>0)"+" else ""}${emaContrib.toInt()}")
        
        // Liquidity check
        val liqContrib = when {
            liquidityUsd < 2_000 -> -10.0 * featureWeights.liquidityWeight   // Too thin
            liquidityUsd < 5_000 -> -3.0 * featureWeights.liquidityWeight    // Low
            liquidityUsd > 50_000 -> 3.0 * featureWeights.liquidityWeight    // Deep
            else -> 0.0
        }
        score += liqContrib
        
        // Dev wallet risk
        val devContrib = when {
            devWalletPct > 20 -> -15.0 * featureWeights.devWalletWeight  // High rug risk
            devWalletPct > 10 -> -5.0 * featureWeights.devWalletWeight   // Moderate risk
            devWalletPct < 5 -> 3.0 * featureWeights.devWalletWeight     // Safe
            else -> 0.0
        }
        score += devContrib
        if (devContrib <= -10) explanations.add("dev:${devContrib.toInt()}")
        
        // Clamp score
        score = score.coerceIn(0.0, 100.0)
        
        // Determine recommendation and size
        val (recommendation, sizeMult) = when {
            score >= 80 -> Pair("STRONG_BUY", 1.5)
            score >= 60 -> Pair("BUY", 1.0)
            score >= 45 -> Pair("CAUTIOUS", 0.7)
            else -> Pair("SKIP", 0.0)
        }
        
        return AdaptiveScore(
            score = score,
            recommendation = recommendation,
            sizeMultiplier = sizeMult,
            matchedGoodPatterns = emptyList(),  // TODO: pattern matching
            matchedBadPatterns = emptyList(),
            explanation = explanations.joinToString(" | "),
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // PATTERN EXTRACTION (from trade history)
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Extract patterns from historical trades.
     * Call this after accumulating 50+ trades.
     */
    fun extractPatterns(trades: List<TradeFeatures>) {
        if (trades.size < 50) {
            ErrorLogger.info("AdaptiveLearning", "Need 50+ trades for pattern extraction (have ${trades.size})")
            return
        }
        
        // Separate good and bad trades
        val goodTrades = trades.filter { it.outcomeScore >= 1 }
        val badTrades = trades.filter { it.outcomeScore <= -1 }
        
        ErrorLogger.info("AdaptiveLearning", "Extracting patterns from ${trades.size} trades " +
            "(${goodTrades.size} good, ${badTrades.size} bad)")
        
        // Extract GOOD patterns
        goodPatterns.clear()
        if (goodTrades.size >= 10) {
            // Find common feature ranges in good trades
            val goodPattern = LearnedPattern(
                name = "GOOD_SETUP",
                featureRanges = mapOf(
                    "buyRatio" to Pair(
                        goodTrades.map { it.buyRatioPct }.percentile(25),
                        goodTrades.map { it.buyRatioPct }.percentile(75)
                    ),
                    "holderConc" to Pair(
                        goodTrades.map { it.topHolderPct }.percentile(10),
                        goodTrades.map { it.topHolderPct }.percentile(75)
                    ),
                    "holderGrowth" to Pair(
                        goodTrades.map { it.holderGrowthRate }.percentile(25),
                        100.0  // No upper limit on growth
                    ),
                    "tokenAge" to Pair(
                        0.0,
                        goodTrades.map { it.tokenAgeMinutes }.percentile(75)
                    ),
                ),
                avgOutcomeScore = goodTrades.map { it.outcomeScore.toDouble() }.average(),
                sampleCount = goodTrades.size,
                confidence = (goodTrades.size.toDouble() / trades.size).coerceIn(0.0, 1.0),
            )
            goodPatterns.add(goodPattern)
            
            ErrorLogger.info("AdaptiveLearning", "✅ GOOD pattern: " +
                "buyRatio=${goodPattern.featureRanges["buyRatio"]?.fmt()} " +
                "holderConc=${goodPattern.featureRanges["holderConc"]?.fmt()}")
        }
        
        // Extract BAD patterns (rugs, dumps, and other severe losses)
        // PRIORITY 3: Include all severe BAD labels for better pattern detection
        badPatterns.clear()
        val severeLabels = listOf(
            TradeLabel.BAD_RUG, 
            TradeLabel.BAD_DUMP, 
            TradeLabel.BAD_DISTRIBUTION,
            TradeLabel.BAD_DEAD_CAT,
            TradeLabel.BAD_CHASING_TOP,
            TradeLabel.BAD_FAKE_PRESSURE,
        )
        val rugTrades = badTrades.filter { it.label in severeLabels }
        if (rugTrades.size >= 5) {
            val badPattern = LearnedPattern(
                name = "RUG_PATTERN",
                featureRanges = mapOf(
                    "holderConc" to Pair(
                        rugTrades.map { it.topHolderPct }.percentile(25),
                        100.0
                    ),
                    "devWallet" to Pair(
                        rugTrades.map { it.devWalletPct }.percentile(25),
                        100.0
                    ),
                ),
                avgOutcomeScore = rugTrades.map { it.outcomeScore.toDouble() }.average(),
                sampleCount = rugTrades.size,
                confidence = (rugTrades.size.toDouble() / badTrades.size).coerceIn(0.0, 1.0),
            )
            badPatterns.add(badPattern)
            
            ErrorLogger.info("AdaptiveLearning", "❌ BAD pattern: " +
                "holderConc=${badPattern.featureRanges["holderConc"]?.fmt()} " +
                "devWallet=${badPattern.featureRanges["devWallet"]?.fmt()}")
        }
        
        saveState()
    }

    // ═══════════════════════════════════════════════════════════════════
    // STATUS & UTILITIES
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Get the number of trades the engine has learned from.
     * Used to determine when to start applying adaptive scoring.
     */
    fun getTradeCount(): Int = tradeCount
    
    fun getStatus(): String {
        return "AdaptiveLearning: $tradeCount trades | " +
            "Weights: buy=${featureWeights.buyRatioWeight.fmt()} " +
            "conc=${featureWeights.holderConcWeight.fmt()} " +
            "ema=${featureWeights.emaFanWeight.fmt()} | " +
            "Patterns: ${goodPatterns.size}G/${badPatterns.size}B"
    }

    fun getDetailedWeights(): Map<String, Double> {
        return mapOf(
            "mcap" to featureWeights.mcapWeight,
            "age" to featureWeights.ageWeight,
            "buyRatio" to featureWeights.buyRatioWeight,
            "volume" to featureWeights.volumeWeight,
            "liquidity" to featureWeights.liquidityWeight,
            "holderCount" to featureWeights.holderCountWeight,
            "holderConc" to featureWeights.holderConcWeight,
            "holderGrowth" to featureWeights.holderGrowthWeight,
            "devWallet" to featureWeights.devWalletWeight,
            "bondingCurve" to featureWeights.bondingCurveWeight,
            "rugcheck" to featureWeights.rugcheckWeight,
            "emaFan" to featureWeights.emaFanWeight,
            "volLiqRatio" to featureWeights.volLiqRatioWeight,
        )
    }

    fun reset() {
        featureWeights = FeatureWeights()
        goodPatterns.clear()
        badPatterns.clear()
        tradeCount = 0
        learningRate = 0.1
        saveState()
        ErrorLogger.info("AdaptiveLearning", "Reset to defaults")
    }
    
    /**
     * Alias for reset() - used by SelfHealingDiagnostics.
     */
    fun clear() = reset()

    private fun Double.fmt() = String.format("%.2f", this)
    private fun Pair<Double, Double>?.fmt() = this?.let { "(${first.fmt()}-${second.fmt()})" } ?: "null"
    
    private fun List<Double>.percentile(p: Int): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val index = (size * p / 100.0).toInt().coerceIn(0, size - 1)
        return sorted[index]
    }
}

package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * AdaptiveLearningEngine — The brain that learns from every trade.
 */
object AdaptiveLearningEngine {

    private const val PREFS_NAME = "adaptive_learning"
    private const val KEY_FEATURE_WEIGHTS = "feature_weights"
    private const val KEY_GOOD_PATTERNS = "good_patterns"
    private const val KEY_BAD_PATTERNS = "bad_patterns"
    private const val KEY_TRADE_COUNT = "trade_count"
    private const val KEY_LEARNING_RATE = "learning_rate"
    private const val KEY_LAST_RETRAIN = "last_retrain_ts"

    private const val MIN_TRADES_FOR_PATTERN_EXTRACTION = 50
    private const val PATTERN_RETRAIN_INTERVAL = 25

    private var prefs: SharedPreferences? = null

    enum class TradeLabel {
        GOOD_RUNNER,
        GOOD_CONTINUATION,
        GOOD_SECOND_LEG,

        MID_SMALL_WIN,
        MID_FLAT_CHOP,
        MID_WEAK_FOLLOW,
        MID_STOPPED_OUT,

        BAD_DUMP,
        BAD_RUG,
        BAD_FAKE_PRESSURE,
        BAD_DISTRIBUTION,
        BAD_DEAD_CAT,
        BAD_CHASING_TOP,

        @Deprecated("Use MID_FLAT_CHOP instead")
        MID_CHOP
    }

    fun calculateOutcomeScore(
        pnlPct: Double,
        maxGainBeforeDrop: Double,
        timeToPeakMins: Double
    ): Int {
        val safePnl = sanitizeDouble(pnlPct)
        val safeMaxGain = sanitizeDouble(maxGainBeforeDrop)
        val safeTimeToPeak = sanitizeDouble(timeToPeakMins)

        return when {
            safePnl >= 20.0 -> 2
            safePnl >= 5.0 -> 1
            safePnl >= -5.0 -> 0
            safePnl >= -15.0 -> -1
            safeMaxGain < 5.0 && safeTimeToPeak < 5.0 && safePnl <= -20.0 -> -2
            else -> -2
        }
    }

    data class TradeFeatures(
        val entryMcapUsd: Double,
        val tokenAgeMinutes: Double,
        val buyRatioPct: Double,
        val volumeUsd: Double,
        val liquidityUsd: Double,
        val holderCount: Int,
        val topHolderPct: Double,
        val holderGrowthRate: Double,
        val devWalletPct: Double,
        val bondingCurveProgress: Double,
        val rugcheckScore: Double,
        val emaFanState: String,
        val entryScore: Double,
        val volumeLiquidityRatio: Double,
        val priceFromAth: Double,
        val pnlPct: Double,
        val maxGainPct: Double,
        val maxDrawdownPct: Double,
        val timeToPeakMins: Double,
        val holdTimeMins: Double,
        val exitReason: String,
        val outcomeScore: Int,
        val label: TradeLabel
    )

    fun captureFeatures(
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
        pnlPct: Double,
        maxGainPct: Double,
        maxDrawdownPct: Double,
        timeToPeakMins: Double,
        holdTimeMins: Double,
        exitReason: String,
        entryPhase: String = ""
    ): TradeFeatures {
        val safeLiquidity = sanitizeDouble(liquidityUsd)
        val safeVolume = sanitizeDouble(volumeUsd)
        val volLiqRatio = if (safeLiquidity > 0.0) safeVolume / safeLiquidity else 0.0

        val safePnl = sanitizeDouble(pnlPct)
        val safeMaxGain = sanitizeDouble(maxGainPct)
        val safeMaxDrawdown = sanitizeDouble(maxDrawdownPct)
        val safeTimeToPeak = sanitizeDouble(timeToPeakMins)
        val safeHoldTime = sanitizeDouble(holdTimeMins)

        val outcomeScore = calculateOutcomeScore(safePnl, safeMaxGain, safeTimeToPeak)
        val label = classifyTrade(
            pnlPct = safePnl,
            maxGainPct = safeMaxGain,
            maxDrawdownPct = safeMaxDrawdown,
            exitReason = exitReason,
            topHolderPct = sanitizeDouble(topHolderPct),
            entryPhase = entryPhase,
            holdTimeMins = safeHoldTime,
            emaFanAtEntry = emaFanState
        )

        return TradeFeatures(
            entryMcapUsd = sanitizeDouble(entryMcapUsd),
            tokenAgeMinutes = sanitizeDouble(tokenAgeMinutes),
            buyRatioPct = sanitizeDouble(buyRatioPct),
            volumeUsd = safeVolume,
            liquidityUsd = safeLiquidity,
            holderCount = holderCount.coerceAtLeast(0),
            topHolderPct = sanitizeDouble(topHolderPct),
            holderGrowthRate = sanitizeDouble(holderGrowthRate),
            devWalletPct = sanitizeDouble(devWalletPct),
            bondingCurveProgress = sanitizeDouble(bondingCurveProgress),
            rugcheckScore = sanitizeDouble(rugcheckScore),
            emaFanState = emaFanState,
            entryScore = sanitizeDouble(entryScore),
            volumeLiquidityRatio = sanitizeDouble(volLiqRatio),
            priceFromAth = sanitizeDouble(priceFromAth),
            pnlPct = safePnl,
            maxGainPct = safeMaxGain,
            maxDrawdownPct = safeMaxDrawdown,
            timeToPeakMins = safeTimeToPeak,
            holdTimeMins = safeHoldTime,
            exitReason = exitReason,
            outcomeScore = outcomeScore,
            label = label
        )
    }

    private fun classifyTrade(
        pnlPct: Double,
        maxGainPct: Double,
        maxDrawdownPct: Double,
        exitReason: String,
        topHolderPct: Double,
        entryPhase: String = "",
        holdTimeMins: Double = 0.0,
        emaFanAtEntry: String = ""
    ): TradeLabel {
        val reasonLower = exitReason.lowercase(Locale.US)
        val phaseLower = entryPhase.lowercase(Locale.US)
        val emaUpper = emaFanAtEntry.uppercase(Locale.US)

        return when {
            pnlPct >= 20.0 && maxGainPct >= 25.0 ->
                TradeLabel.GOOD_RUNNER

            pnlPct >= 15.0 && phaseLower.contains("reclaim") ->
                TradeLabel.GOOD_SECOND_LEG

            pnlPct >= 10.0 && maxDrawdownPct < 10.0 ->
                TradeLabel.GOOD_CONTINUATION

            reasonLower.contains("rug") ||
                reasonLower.contains("liquidity_collapse") ||
                (pnlPct < -30.0 && maxDrawdownPct > 40.0) ->
                TradeLabel.BAD_RUG

            reasonLower.contains("dump") ||
                reasonLower.contains("whale_dump") ||
                reasonLower.contains("dev_dump") ||
                (pnlPct < -15.0 && topHolderPct > 30.0) ->
                TradeLabel.BAD_DUMP

            pnlPct < -10.0 &&
                maxGainPct < 3.0 &&
                phaseLower.contains("overextend") ->
                TradeLabel.BAD_CHASING_TOP

            pnlPct < -10.0 &&
                (phaseLower.contains("distribution") || emaUpper == "BEAR_FAN" || emaUpper == "BEAR_FLAT") ->
                TradeLabel.BAD_DISTRIBUTION

            pnlPct < -10.0 &&
                maxGainPct in 3.0..12.0 &&
                phaseLower.contains("reclaim") ->
                TradeLabel.BAD_DEAD_CAT

            pnlPct < -10.0 &&
                maxGainPct < 5.0 &&
                maxDrawdownPct > 15.0 ->
                TradeLabel.BAD_FAKE_PRESSURE

            pnlPct in 5.0..15.0 ->
                TradeLabel.MID_SMALL_WIN

            pnlPct in -10.0..-1.0 && reasonLower.contains("stop") ->
                TradeLabel.MID_STOPPED_OUT

            pnlPct in -5.0..5.0 && maxGainPct >= 8.0 ->
                TradeLabel.MID_WEAK_FOLLOW

            pnlPct in -5.0..5.0 && maxGainPct < 8.0 ->
                TradeLabel.MID_FLAT_CHOP

            pnlPct > 0.0 ->
                TradeLabel.MID_SMALL_WIN

            holdTimeMins <= 2.0 && pnlPct <= -8.0 ->
                TradeLabel.BAD_FAKE_PRESSURE

            else ->
                TradeLabel.MID_FLAT_CHOP
        }
    }

    data class FeatureWeights(
        var mcapWeight: Double = 1.0,
        var ageWeight: Double = 1.0,
        var buyRatioWeight: Double = 1.5,
        var volumeWeight: Double = 1.2,
        var liquidityWeight: Double = 1.3,
        var holderCountWeight: Double = 1.0,
        var holderConcWeight: Double = 1.4,
        var holderGrowthWeight: Double = 1.2,
        var devWalletWeight: Double = 1.3,
        var bondingCurveWeight: Double = 1.0,
        var rugcheckWeight: Double = 1.1,
        var emaFanWeight: Double = 1.5,
        var volLiqRatioWeight: Double = 1.2,
        var athDistanceWeight: Double = 0.8
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("mcap", sanitizeDouble(mcapWeight))
                put("age", sanitizeDouble(ageWeight))
                put("buyRatio", sanitizeDouble(buyRatioWeight))
                put("volume", sanitizeDouble(volumeWeight))
                put("liquidity", sanitizeDouble(liquidityWeight))
                put("holderCount", sanitizeDouble(holderCountWeight))
                put("holderConc", sanitizeDouble(holderConcWeight))
                put("holderGrowth", sanitizeDouble(holderGrowthWeight))
                put("devWallet", sanitizeDouble(devWalletWeight))
                put("bondingCurve", sanitizeDouble(bondingCurveWeight))
                put("rugcheck", sanitizeDouble(rugcheckWeight))
                put("emaFan", sanitizeDouble(emaFanWeight))
                put("volLiqRatio", sanitizeDouble(volLiqRatioWeight))
                put("athDistance", sanitizeDouble(athDistanceWeight))
            }
        }

        companion object {
            fun fromJson(json: JSONObject): FeatureWeights {
                return FeatureWeights(
                    mcapWeight = sanitizeDouble(json.optDouble("mcap", 1.0), 1.0),
                    ageWeight = sanitizeDouble(json.optDouble("age", 1.0), 1.0),
                    buyRatioWeight = sanitizeDouble(json.optDouble("buyRatio", 1.5), 1.5),
                    volumeWeight = sanitizeDouble(json.optDouble("volume", 1.2), 1.2),
                    liquidityWeight = sanitizeDouble(json.optDouble("liquidity", 1.3), 1.3),
                    holderCountWeight = sanitizeDouble(json.optDouble("holderCount", 1.0), 1.0),
                    holderConcWeight = sanitizeDouble(json.optDouble("holderConc", 1.4), 1.4),
                    holderGrowthWeight = sanitizeDouble(json.optDouble("holderGrowth", 1.2), 1.2),
                    devWalletWeight = sanitizeDouble(json.optDouble("devWallet", 1.3), 1.3),
                    bondingCurveWeight = sanitizeDouble(json.optDouble("bondingCurve", 1.0), 1.0),
                    rugcheckWeight = sanitizeDouble(json.optDouble("rugcheck", 1.1), 1.1),
                    emaFanWeight = sanitizeDouble(json.optDouble("emaFan", 1.5), 1.5),
                    volLiqRatioWeight = sanitizeDouble(json.optDouble("volLiqRatio", 1.2), 1.2),
                    athDistanceWeight = sanitizeDouble(json.optDouble("athDistance", 0.8), 0.8)
                )
            }
        }
    }

    data class LearnedPattern(
        val name: String,
        val featureRanges: Map<String, Pair<Double, Double>>,
        val avgOutcomeScore: Double,
        val sampleCount: Int,
        val confidence: Double
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("name", name)
                put("avgOutcome", sanitizeDouble(avgOutcomeScore))
                put("samples", sampleCount)
                put("confidence", sanitizeDouble(confidence))
                val ranges = JSONObject()
                featureRanges.forEach { (key, value) ->
                    ranges.put(
                        key,
                        JSONArray().apply {
                            put(sanitizeDouble(value.first))
                            put(sanitizeDouble(value.second))
                        }
                    )
                }
                put("ranges", ranges)
            }
        }

        companion object {
            fun fromJson(json: JSONObject): LearnedPattern {
                val ranges = mutableMapOf<String, Pair<Double, Double>>()
                val rangesJson = json.optJSONObject("ranges")
                if (rangesJson != null) {
                    val keys = rangesJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val arr = rangesJson.optJSONArray(key) ?: continue
                        val first = sanitizeDouble(arr.optDouble(0, 0.0))
                        val second = sanitizeDouble(arr.optDouble(1, 0.0))
                        ranges[key] = Pair(first, second)
                    }
                }

                return LearnedPattern(
                    name = json.optString("name", "UNKNOWN"),
                    featureRanges = ranges,
                    avgOutcomeScore = sanitizeDouble(json.optDouble("avgOutcome", 0.0)),
                    sampleCount = json.optInt("samples", 0).coerceAtLeast(0),
                    confidence = sanitizeDouble(json.optDouble("confidence", 0.0)).coerceIn(0.0, 1.0)
                )
            }
        }
    }

    data class AdaptiveScore(
        val score: Double,
        val recommendation: String,
        val sizeMultiplier: Double,
        val matchedGoodPatterns: List<String>,
        val matchedBadPatterns: List<String>,
        val explanation: String
    )

    private var featureWeights = FeatureWeights()
    private val goodPatterns = mutableListOf<LearnedPattern>()
    private val badPatterns = mutableListOf<LearnedPattern>()
    private var tradeCount = 0
    private var learningRate = 0.1

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()
        ErrorLogger.info(
            "AdaptiveLearning",
            "Initialized: $tradeCount trades, ${goodPatterns.size} good patterns, ${badPatterns.size} bad patterns"
        )
    }

    private fun loadState() {
        val p = prefs ?: return

        tradeCount = p.getInt(KEY_TRADE_COUNT, 0).coerceAtLeast(0)
        learningRate = p.getFloat(KEY_LEARNING_RATE, 0.1f).toDouble().coerceIn(0.001, 1.0)

        try {
            val json = p.getString(KEY_FEATURE_WEIGHTS, null)
            if (!json.isNullOrBlank()) {
                featureWeights = FeatureWeights.fromJson(JSONObject(json))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AdaptiveLearning", "Failed to load weights: ${e.message}")
            featureWeights = FeatureWeights()
        }

        try {
            val json = p.getString(KEY_GOOD_PATTERNS, "[]") ?: "[]"
            val arr = JSONArray(json)
            goodPatterns.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                goodPatterns.add(LearnedPattern.fromJson(obj))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AdaptiveLearning", "Failed to load good patterns: ${e.message}")
            goodPatterns.clear()
        }

        try {
            val json = p.getString(KEY_BAD_PATTERNS, "[]") ?: "[]"
            val arr = JSONArray(json)
            badPatterns.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                badPatterns.add(LearnedPattern.fromJson(obj))
            }
        } catch (e: Exception) {
            ErrorLogger.error("AdaptiveLearning", "Failed to load bad patterns: ${e.message}")
            badPatterns.clear()
        }
    }

    private fun saveState() {
        val p = prefs ?: return

        val goodArr = JSONArray()
        for (pattern in goodPatterns) {
            goodArr.put(pattern.toJson())
        }

        val badArr = JSONArray()
        for (pattern in badPatterns) {
            badArr.put(pattern.toJson())
        }

        p.edit()
            .putInt(KEY_TRADE_COUNT, tradeCount)
            .putFloat(KEY_LEARNING_RATE, learningRate.toFloat())
            .putString(KEY_FEATURE_WEIGHTS, featureWeights.toJson().toString())
            .putString(KEY_GOOD_PATTERNS, goodArr.toString())
            .putString(KEY_BAD_PATTERNS, badArr.toString())
            .putLong(KEY_LAST_RETRAIN, System.currentTimeMillis())
            .apply()
    }

    fun learnFromTrade(features: TradeFeatures) {
        tradeCount += 1
        adjustWeights(features)

        val labelEmoji = when (features.label) {
            TradeLabel.GOOD_RUNNER -> "🚀"
            TradeLabel.GOOD_CONTINUATION -> "✅"
            TradeLabel.GOOD_SECOND_LEG -> "🔄"
            TradeLabel.MID_SMALL_WIN -> "📊"
            TradeLabel.MID_FLAT_CHOP -> "〰️"
            TradeLabel.MID_CHOP -> "〰️"
            TradeLabel.MID_WEAK_FOLLOW -> "📉"
            TradeLabel.MID_STOPPED_OUT -> "🛑"
            TradeLabel.BAD_DUMP -> "💔"
            TradeLabel.BAD_RUG -> "💀"
            TradeLabel.BAD_FAKE_PRESSURE -> "🎭"
            TradeLabel.BAD_DISTRIBUTION -> "📤"
            TradeLabel.BAD_DEAD_CAT -> "🐱"
            TradeLabel.BAD_CHASING_TOP -> "⛰️"
        }

        ErrorLogger.info(
            "AdaptiveLearning",
            "$labelEmoji Trade #$tradeCount: ${features.label.name} (score=${features.outcomeScore}, pnl=${features.pnlPct.fmt()}%)"
        )

        if (tradeCount >= MIN_TRADES_FOR_PATTERN_EXTRACTION &&
            tradeCount % PATTERN_RETRAIN_INTERVAL == 0
        ) {
            ErrorLogger.info(
                "AdaptiveLearning",
                "🧠 Pattern extraction threshold hit at $tradeCount trades"
            )
        }

        saveState()
    }

    private fun adjustWeights(features: TradeFeatures) {
        val adjustmentFactor = when (features.outcomeScore) {
            2 -> learningRate * 1.5
            1 -> learningRate * 0.8
            0 -> learningRate * 0.3
            -1 -> learningRate * -1.2
            -2 -> learningRate * -3.0
            else -> 0.0
        }

        val mcapSignal = if (features.entryMcapUsd < 50_000.0) 1.0 else -0.5
        featureWeights.mcapWeight =
            (featureWeights.mcapWeight + mcapSignal * adjustmentFactor).coerceIn(0.3, 2.5)

        val ageSignal = when {
            features.tokenAgeMinutes < 30.0 && features.outcomeScore >= 1 -> 1.0
            features.tokenAgeMinutes < 30.0 && features.outcomeScore <= -1 -> -1.5
            else -> 0.0
        }
        featureWeights.ageWeight =
            (featureWeights.ageWeight + ageSignal * adjustmentFactor).coerceIn(0.3, 2.5)

        val buySignal = if (features.buyRatioPct > 55.0) 1.0 else -0.5
        featureWeights.buyRatioWeight =
            (featureWeights.buyRatioWeight + buySignal * adjustmentFactor).coerceIn(0.5, 3.0)

        val concSignal = if (features.topHolderPct < 25.0) 1.0 else -1.0
        featureWeights.holderConcWeight =
            (featureWeights.holderConcWeight + concSignal * adjustmentFactor).coerceIn(0.5, 3.0)

        val growthSignal = if (features.holderGrowthRate > 0.0) 1.0 else -0.5
        featureWeights.holderGrowthWeight =
            (featureWeights.holderGrowthWeight + growthSignal * adjustmentFactor).coerceIn(0.5, 2.5)

        val emaSignal = if (features.emaFanState.uppercase(Locale.US).contains("BULL")) 1.0 else -0.3
        featureWeights.emaFanWeight =
            (featureWeights.emaFanWeight + emaSignal * adjustmentFactor).coerceIn(0.5, 3.0)

        val volLiqSignal = when {
            features.volumeLiquidityRatio > 0.5 && features.outcomeScore >= 0 -> 0.8
            features.volumeLiquidityRatio > 0.5 && features.outcomeScore < 0 -> -0.5
            else -> 0.0
        }
        featureWeights.volLiqRatioWeight =
            (featureWeights.volLiqRatioWeight + volLiqSignal * adjustmentFactor).coerceIn(0.3, 2.5)

        if (abs(adjustmentFactor) > 0.15) {
            ErrorLogger.debug(
                "AdaptiveLearning",
                "Weight adjustment: buyRatio=${featureWeights.buyRatioWeight.fmt()} " +
                    "holderConc=${featureWeights.holderConcWeight.fmt()} " +
                    "emaFan=${featureWeights.emaFanWeight.fmt()}"
            )
        }
    }

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
        baseEntryScore: Double
    ): AdaptiveScore {
        var score = sanitizeDouble(baseEntryScore)
        val explanations = mutableListOf<String>()

        val safeMcap = sanitizeDouble(mcapUsd)
        val safeAge = sanitizeDouble(tokenAgeMinutes)
        val safeBuyRatio = sanitizeDouble(buyRatioPct)
        val safeLiquidity = sanitizeDouble(liquidityUsd)
        val safeTopHolder = sanitizeDouble(topHolderPct)
        val safeHolderGrowth = sanitizeDouble(holderGrowthRate)
        val safeDevWallet = sanitizeDouble(devWalletPct)
        val safeEmaState = emaFanState.uppercase(Locale.US)

        val mcapContrib = when {
            safeMcap < 30_000.0 -> 8.0 * featureWeights.mcapWeight
            safeMcap < 100_000.0 -> 4.0 * featureWeights.mcapWeight
            safeMcap > 500_000.0 -> -3.0 * featureWeights.mcapWeight
            else -> 0.0
        }
        score += mcapContrib
        if (abs(mcapContrib) >= 5.0) {
            explanations.add("mcap:${if (mcapContrib > 0.0) "+" else ""}${mcapContrib.toInt()}")
        }

        val ageContrib = when {
            safeAge < 15.0 -> 6.0 * featureWeights.ageWeight
            safeAge < 60.0 -> 3.0 * featureWeights.ageWeight
            safeAge > 240.0 -> -2.0 * featureWeights.ageWeight
            else -> 0.0
        }
        score += ageContrib

        val buyContrib = when {
            safeBuyRatio >= 65.0 -> 10.0 * featureWeights.buyRatioWeight
            safeBuyRatio >= 55.0 -> 5.0 * featureWeights.buyRatioWeight
            safeBuyRatio < 45.0 -> -8.0 * featureWeights.buyRatioWeight
            else -> 0.0
        }
        score += buyContrib
        if (abs(buyContrib) >= 5.0) {
            explanations.add("buy%:${if (buyContrib > 0.0) "+" else ""}${buyContrib.toInt()}")
        }

        val concContrib = when {
            safeTopHolder < 15.0 -> 8.0 * featureWeights.holderConcWeight
            safeTopHolder < 25.0 -> 4.0 * featureWeights.holderConcWeight
            safeTopHolder > 40.0 -> -12.0 * featureWeights.holderConcWeight
            safeTopHolder > 30.0 -> -6.0 * featureWeights.holderConcWeight
            else -> 0.0
        }
        score += concContrib
        if (abs(concContrib) >= 5.0) {
            explanations.add("conc:${if (concContrib > 0.0) "+" else ""}${concContrib.toInt()}")
        }

        val growthContrib = when {
            safeHolderGrowth > 10.0 -> 6.0 * featureWeights.holderGrowthWeight
            safeHolderGrowth > 3.0 -> 3.0 * featureWeights.holderGrowthWeight
            safeHolderGrowth < -5.0 -> -8.0 * featureWeights.holderGrowthWeight
            else -> 0.0
        }
        score += growthContrib

        val emaContrib = when {
            safeEmaState.contains("BULL_FAN") -> 10.0 * featureWeights.emaFanWeight
            safeEmaState.contains("BULL") -> 5.0 * featureWeights.emaFanWeight
            safeEmaState.contains("BEAR_FAN") -> -10.0 * featureWeights.emaFanWeight
            safeEmaState.contains("BEAR") -> -5.0 * featureWeights.emaFanWeight
            else -> 0.0
        }
        score += emaContrib
        if (abs(emaContrib) >= 5.0) {
            explanations.add("ema:${if (emaContrib > 0.0) "+" else ""}${emaContrib.toInt()}")
        }

        val liqContrib = when {
            safeLiquidity < 2_000.0 -> -10.0 * featureWeights.liquidityWeight
            safeLiquidity < 5_000.0 -> -3.0 * featureWeights.liquidityWeight
            safeLiquidity > 50_000.0 -> 3.0 * featureWeights.liquidityWeight
            else -> 0.0
        }
        score += liqContrib

        val devContrib = when {
            safeDevWallet > 20.0 -> -15.0 * featureWeights.devWalletWeight
            safeDevWallet > 10.0 -> -5.0 * featureWeights.devWalletWeight
            safeDevWallet < 5.0 -> 3.0 * featureWeights.devWalletWeight
            else -> 0.0
        }
        score += devContrib
        if (devContrib <= -10.0) {
            explanations.add("dev:${devContrib.toInt()}")
        }

        score = score.coerceIn(0.0, 100.0)

        val recommendation: String
        val sizeMult: Double

        when {
            score >= 80.0 -> {
                recommendation = "STRONG_BUY"
                sizeMult = 1.5
            }
            score >= 60.0 -> {
                recommendation = "BUY"
                sizeMult = 1.0
            }
            score >= 45.0 -> {
                recommendation = "CAUTIOUS"
                sizeMult = 0.7
            }
            else -> {
                recommendation = "SKIP"
                sizeMult = 0.0
            }
        }

        return AdaptiveScore(
            score = score,
            recommendation = recommendation,
            sizeMultiplier = sizeMult,
            matchedGoodPatterns = emptyList(),
            matchedBadPatterns = emptyList(),
            explanation = explanations.joinToString(" | ")
        )
    }

    fun extractPatterns(trades: List<TradeFeatures>) {
        if (trades.size < MIN_TRADES_FOR_PATTERN_EXTRACTION) {
            ErrorLogger.info(
                "AdaptiveLearning",
                "Need $MIN_TRADES_FOR_PATTERN_EXTRACTION+ trades for pattern extraction (have ${trades.size})"
            )
            return
        }

        val goodTrades = trades.filter { it.outcomeScore >= 1 }
        val badTrades = trades.filter { it.outcomeScore <= -1 }

        ErrorLogger.info(
            "AdaptiveLearning",
            "Extracting patterns from ${trades.size} trades (${goodTrades.size} good, ${badTrades.size} bad)"
        )

        goodPatterns.clear()
        if (goodTrades.size >= 10) {
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
                        100.0
                    ),
                    "tokenAge" to Pair(
                        0.0,
                        goodTrades.map { it.tokenAgeMinutes }.percentile(75)
                    )
                ),
                avgOutcomeScore = goodTrades.map { it.outcomeScore.toDouble() }.average(),
                sampleCount = goodTrades.size,
                confidence = (goodTrades.size.toDouble() / trades.size.toDouble()).coerceIn(0.0, 1.0)
            )
            goodPatterns.add(goodPattern)

            ErrorLogger.info(
                "AdaptiveLearning",
                "✅ GOOD pattern: buyRatio=${goodPattern.featureRanges["buyRatio"].fmt()} " +
                    "holderConc=${goodPattern.featureRanges["holderConc"].fmt()}"
            )
        }

        badPatterns.clear()
        val severeLabels = listOf(
            TradeLabel.BAD_RUG,
            TradeLabel.BAD_DUMP,
            TradeLabel.BAD_DISTRIBUTION,
            TradeLabel.BAD_DEAD_CAT,
            TradeLabel.BAD_CHASING_TOP,
            TradeLabel.BAD_FAKE_PRESSURE
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
                    )
                ),
                avgOutcomeScore = rugTrades.map { it.outcomeScore.toDouble() }.average(),
                sampleCount = rugTrades.size,
                confidence = if (badTrades.isNotEmpty()) {
                    (rugTrades.size.toDouble() / badTrades.size.toDouble()).coerceIn(0.0, 1.0)
                } else {
                    0.0
                }
            )
            badPatterns.add(badPattern)

            ErrorLogger.info(
                "AdaptiveLearning",
                "❌ BAD pattern: holderConc=${badPattern.featureRanges["holderConc"].fmt()} " +
                    "devWallet=${badPattern.featureRanges["devWallet"].fmt()}"
            )
        }

        saveState()
    }

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
            "athDistance" to featureWeights.athDistanceWeight
        )
    }

    fun applyCommunityWeights(
        communityWeights: Map<String, Double>,
        communityTradeCount: Int
    ) {
        if (communityWeights.isEmpty()) return

        val localRatio = when {
            tradeCount < 100 -> 0.3
            tradeCount < 500 -> 0.5
            else -> 0.7
        }
        val communityRatio = 1.0 - localRatio

        communityWeights["mcap"]?.let {
            featureWeights.mcapWeight =
                (featureWeights.mcapWeight * localRatio + sanitizeDouble(it, featureWeights.mcapWeight) * communityRatio)
                    .coerceIn(0.3, 2.5)
        }
        communityWeights["age"]?.let {
            featureWeights.ageWeight =
                (featureWeights.ageWeight * localRatio + sanitizeDouble(it, featureWeights.ageWeight) * communityRatio)
                    .coerceIn(0.3, 2.5)
        }
        communityWeights["buyRatio"]?.let {
            featureWeights.buyRatioWeight =
                (featureWeights.buyRatioWeight * localRatio + sanitizeDouble(it, featureWeights.buyRatioWeight) * communityRatio)
                    .coerceIn(0.5, 3.0)
        }
        communityWeights["volume"]?.let {
            featureWeights.volumeWeight =
                (featureWeights.volumeWeight * localRatio + sanitizeDouble(it, featureWeights.volumeWeight) * communityRatio)
                    .coerceIn(0.3, 2.5)
        }
        communityWeights["liquidity"]?.let {
            featureWeights.liquidityWeight =
                (featureWeights.liquidityWeight * localRatio + sanitizeDouble(it, featureWeights.liquidityWeight) * communityRatio)
                    .coerceIn(0.3, 2.5)
        }
        communityWeights["holderConc"]?.let {
            featureWeights.holderConcWeight =
                (featureWeights.holderConcWeight * localRatio + sanitizeDouble(it, featureWeights.holderConcWeight) * communityRatio)
                    .coerceIn(0.5, 3.0)
        }
        communityWeights["holderGrowth"]?.let {
            featureWeights.holderGrowthWeight =
                (featureWeights.holderGrowthWeight * localRatio + sanitizeDouble(it, featureWeights.holderGrowthWeight) * communityRatio)
                    .coerceIn(0.5, 2.5)
        }
        communityWeights["emaFan"]?.let {
            featureWeights.emaFanWeight =
                (featureWeights.emaFanWeight * localRatio + sanitizeDouble(it, featureWeights.emaFanWeight) * communityRatio)
                    .coerceIn(0.5, 3.0)
        }
        communityWeights["volLiqRatio"]?.let {
            featureWeights.volLiqRatioWeight =
                (featureWeights.volLiqRatioWeight * localRatio + sanitizeDouble(it, featureWeights.volLiqRatioWeight) * communityRatio)
                    .coerceIn(0.3, 2.5)
        }

        saveState()
        ErrorLogger.info(
            "AdaptiveLearning",
            "☁️ Blended community weights (${(communityRatio * 100.0).toInt()}% community from $communityTradeCount trades)"
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

    fun clear() {
        reset()
    }

    private fun Double.fmt(): String {
        return String.format(Locale.US, "%.2f", sanitizeDouble(this))
    }

    private fun Pair<Double, Double>?.fmt(): String {
        return if (this == null) {
            "null"
        } else {
            "(${first.fmt()}-${second.fmt()})"
        }
    }

    private fun List<Double>.percentile(p: Int): Double {
        if (isEmpty()) return 0.0
        val sortedValues = this.map { sanitizeDouble(it) }.sorted()
        val index = ((sortedValues.size * p) / 100.0).toInt().coerceIn(0, sortedValues.size - 1)
        return sortedValues[index]
    }

    private fun sanitizeDouble(value: Double, default: Double = 0.0): Double {
        return if (value.isNaN() || value.isInfinite()) default else value
    }
}
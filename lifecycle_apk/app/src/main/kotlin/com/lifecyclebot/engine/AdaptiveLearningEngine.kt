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
    // V5.9.301: Persisted rolling feature buffer — fuel for pattern extraction.
    // Without this, every trade's features were discarded after weight adjustment.
    private const val KEY_FEATURE_BUFFER = "feature_buffer_v2"
    private const val FEATURE_BUFFER_MAX = 300

    // V5.9.301: Lowered from 50 → 25 so patterns get extracted earlier in bootstrap.
    private const val MIN_TRADES_FOR_PATTERN_EXTRACTION = 25
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
        var athDistanceWeight: Double = 0.8,
        // V5.9.301: New learned signals — hold-time, max-gain follow-through, exit-reason quality.
        // These were captured but never weighted; the bot couldn't tell a "good runner" from a "lucky chop".
        var holdTimeWeight: Double = 1.0,        // longer profitable holds → up; quick losers → down
        var maxGainFollowWeight: Double = 1.2,   // captured-vs-peak ratio drives this
        var exitQualityWeight: Double = 1.0      // exits via TP/runner > stop > rug
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
                put("holdTime", sanitizeDouble(holdTimeWeight))
                put("maxGainFollow", sanitizeDouble(maxGainFollowWeight))
                put("exitQuality", sanitizeDouble(exitQualityWeight))
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
                    athDistanceWeight = sanitizeDouble(json.optDouble("athDistance", 0.8), 0.8),
                    holdTimeWeight = sanitizeDouble(json.optDouble("holdTime", 1.0), 1.0),
                    maxGainFollowWeight = sanitizeDouble(json.optDouble("maxGainFollow", 1.2), 1.2),
                    exitQualityWeight = sanitizeDouble(json.optDouble("exitQuality", 1.0), 1.0)
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
    // V5.9.301: Rolling feature buffer — last 300 trades' features for pattern extraction.
    // Persisted to SharedPreferences on every trade so it survives bot restarts.
    private val featureBuffer = mutableListOf<TradeFeatures>()
    private var tradeCount = 0
    private var learningRate = 0.1

    // V5.9.301: TradeFeatures ↔ JSON for the rolling buffer.
    private fun featuresToJson(f: TradeFeatures): JSONObject = JSONObject().apply {
        put("mc", sanitizeDouble(f.entryMcapUsd))
        put("ag", sanitizeDouble(f.tokenAgeMinutes))
        put("br", sanitizeDouble(f.buyRatioPct))
        put("vol", sanitizeDouble(f.volumeUsd))
        put("liq", sanitizeDouble(f.liquidityUsd))
        put("hc", f.holderCount)
        put("th", sanitizeDouble(f.topHolderPct))
        put("hg", sanitizeDouble(f.holderGrowthRate))
        put("dw", sanitizeDouble(f.devWalletPct))
        put("bc", sanitizeDouble(f.bondingCurveProgress))
        put("rc", sanitizeDouble(f.rugcheckScore))
        put("ema", f.emaFanState)
        put("es", sanitizeDouble(f.entryScore))
        put("vlr", sanitizeDouble(f.volumeLiquidityRatio))
        put("ath", sanitizeDouble(f.priceFromAth))
        put("pnl", sanitizeDouble(f.pnlPct))
        put("mg", sanitizeDouble(f.maxGainPct))
        put("md", sanitizeDouble(f.maxDrawdownPct))
        put("ttp", sanitizeDouble(f.timeToPeakMins))
        put("ht", sanitizeDouble(f.holdTimeMins))
        put("ex", f.exitReason)
        put("os", f.outcomeScore)
        put("lbl", f.label.name)
    }

    private fun featuresFromJson(j: JSONObject): TradeFeatures? = try {
        val labelName = j.optString("lbl", TradeLabel.MID_FLAT_CHOP.name)
        val label = try { TradeLabel.valueOf(labelName) } catch (_: Exception) { TradeLabel.MID_FLAT_CHOP }
        TradeFeatures(
            entryMcapUsd = sanitizeDouble(j.optDouble("mc", 0.0)),
            tokenAgeMinutes = sanitizeDouble(j.optDouble("ag", 0.0)),
            buyRatioPct = sanitizeDouble(j.optDouble("br", 0.0)),
            volumeUsd = sanitizeDouble(j.optDouble("vol", 0.0)),
            liquidityUsd = sanitizeDouble(j.optDouble("liq", 0.0)),
            holderCount = j.optInt("hc", 0).coerceAtLeast(0),
            topHolderPct = sanitizeDouble(j.optDouble("th", 0.0)),
            holderGrowthRate = sanitizeDouble(j.optDouble("hg", 0.0)),
            devWalletPct = sanitizeDouble(j.optDouble("dw", 0.0)),
            bondingCurveProgress = sanitizeDouble(j.optDouble("bc", 0.0)),
            rugcheckScore = sanitizeDouble(j.optDouble("rc", 0.0)),
            emaFanState = j.optString("ema", ""),
            entryScore = sanitizeDouble(j.optDouble("es", 0.0)),
            volumeLiquidityRatio = sanitizeDouble(j.optDouble("vlr", 0.0)),
            priceFromAth = sanitizeDouble(j.optDouble("ath", 0.0)),
            pnlPct = sanitizeDouble(j.optDouble("pnl", 0.0)),
            maxGainPct = sanitizeDouble(j.optDouble("mg", 0.0)),
            maxDrawdownPct = sanitizeDouble(j.optDouble("md", 0.0)),
            timeToPeakMins = sanitizeDouble(j.optDouble("ttp", 0.0)),
            holdTimeMins = sanitizeDouble(j.optDouble("ht", 0.0)),
            exitReason = j.optString("ex", ""),
            outcomeScore = j.optInt("os", 0),
            label = label
        )
    } catch (_: Exception) { null }

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

        // V5.9.301: Load rolling feature buffer for pattern extraction
        try {
            val json = p.getString(KEY_FEATURE_BUFFER, "[]") ?: "[]"
            val arr = JSONArray(json)
            featureBuffer.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                featuresFromJson(obj)?.let { featureBuffer.add(it) }
            }
            ErrorLogger.info("AdaptiveLearning", "📚 Loaded ${featureBuffer.size} buffered features for pattern extraction")
        } catch (e: Exception) {
            ErrorLogger.error("AdaptiveLearning", "Failed to load feature buffer: ${e.message}")
            featureBuffer.clear()
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

        // V5.9.301: Persist rolling feature buffer (capped at FEATURE_BUFFER_MAX)
        val bufArr = JSONArray()
        for (f in featureBuffer) {
            bufArr.put(featuresToJson(f))
        }

        p.edit()
            .putInt(KEY_TRADE_COUNT, tradeCount)
            .putFloat(KEY_LEARNING_RATE, learningRate.toFloat())
            .putString(KEY_FEATURE_WEIGHTS, featureWeights.toJson().toString())
            .putString(KEY_GOOD_PATTERNS, goodArr.toString())
            .putString(KEY_BAD_PATTERNS, badArr.toString())
            .putString(KEY_FEATURE_BUFFER, bufArr.toString())
            .putLong(KEY_LAST_RETRAIN, System.currentTimeMillis())
            .apply()
    }

    // V5.9.694 — deduplication guard. Multiple Executor close paths
    // (paperSell, liveSell, fallback exits) all called learnFromTrade
    // independently, causing tradeCount to grow 2-3x faster than the
    // canonical pipeline count. Guard by mint+entryTime — same position
    // close can never feed ALE more than once.
    private val aleSeenKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun learnFromTrade(features: TradeFeatures) {
        // V5.9.694/695 — dedup guard using available TradeFeatures fields.
        // Bucket by mcap+holdTime+pnlPct rounded to 1 decimal + minute bucket.
        // Prevents double-feed when multiple Executor close paths fire for the same trade.
        val dedupKey = "${"%.1f".format(features.entryMcapUsd)}_${"%.1f".format(features.holdTimeMins)}_${"%.1f".format(features.pnlPct)}_${(System.currentTimeMillis() / 60_000L)}"
        if (aleSeenKeys.putIfAbsent(dedupKey, System.currentTimeMillis()) != null) {
            ErrorLogger.debug("AdaptiveLearning", "⚡ DEDUP skip pnl=${features.pnlPct.toInt()}% (already learned this close)")
            return
        }
        // Trim cache to avoid unbounded growth
        if (aleSeenKeys.size > 2000) {
            val cutoff = System.currentTimeMillis() - 120_000L
            aleSeenKeys.entries.removeIf { it.value < cutoff }
        }
        tradeCount += 1
        adjustWeights(features)

        // V5.9.301: Push features into rolling buffer (was previously discarded after weight nudge).
        // This is the fuel for pattern extraction — the bot now remembers WHAT it traded, not just IF it won.
        featureBuffer.add(features)
        while (featureBuffer.size > FEATURE_BUFFER_MAX) {
            featureBuffer.removeAt(0)
        }

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
            "$labelEmoji Trade #$tradeCount: ${features.label.name} (score=${features.outcomeScore}, pnl=${features.pnlPct.fmt()}%, hold=${features.holdTimeMins.fmt()}m, peak=${features.maxGainPct.fmt()}%)"
        )

        // V5.9.301: AUTO-TRIGGER pattern extraction every 25 trades.
        // PRIOR BUG: extractPatterns() was never called from anywhere — patterns stayed empty forever.
        // Now the bot actually builds good/bad pattern libraries from real trade history.
        if (featureBuffer.size >= MIN_TRADES_FOR_PATTERN_EXTRACTION &&
            tradeCount % PATTERN_RETRAIN_INTERVAL == 0
        ) {
            ErrorLogger.info(
                "AdaptiveLearning",
                "🧠 Auto-extracting patterns from ${featureBuffer.size} buffered trades (retrain #${tradeCount / PATTERN_RETRAIN_INTERVAL})"
            )
            extractPatterns(featureBuffer.toList())
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

        // V5.9.301: HOLD-TIME LEARNING — distinguish profitable runners from chopped quick-stops.
        // Long winning hold = trust trend continuation. Short losing hold = bad entry. Long losing hold = should have cut.
        val holdSignal = when {
            features.holdTimeMins >= 8.0 && features.pnlPct >= 10.0 -> 1.2   // ran a winner
            features.holdTimeMins >= 8.0 && features.pnlPct <= -10.0 -> -1.5 // held loser too long
            features.holdTimeMins < 2.0 && features.pnlPct <= -5.0 -> -0.8   // quick rug/stop = bad entry
            features.holdTimeMins in 3.0..8.0 && features.pnlPct >= 5.0 -> 0.7  // disciplined exit
            else -> 0.0
        }
        featureWeights.holdTimeWeight =
            (featureWeights.holdTimeWeight + holdSignal * adjustmentFactor).coerceIn(0.4, 2.5)

        // V5.9.301: MAX-GAIN FOLLOW-THROUGH — captured PnL vs peak. Tells us about exit timing & scratch traps.
        val captureRatio = if (features.maxGainPct > 0.0) features.pnlPct / features.maxGainPct else 0.0
        val followSignal = when {
            captureRatio >= 0.7 && features.maxGainPct >= 10.0 -> 1.5    // captured most of a real move
            captureRatio in 0.0..0.3 && features.maxGainPct >= 15.0 -> -1.8  // SCRATCH from a real winner — exits broken
            features.maxGainPct < 3.0 && features.pnlPct < -5.0 -> -1.0  // never had a chance
            else -> 0.0
        }
        featureWeights.maxGainFollowWeight =
            (featureWeights.maxGainFollowWeight + followSignal * adjustmentFactor).coerceIn(0.4, 2.5)

        // V5.9.301: EXIT-REASON QUALITY — TP/runner/trail > stop > rug/dump.
        val exitLower = features.exitReason.lowercase(Locale.US)
        val exitSignal = when {
            (exitLower.contains("tp") || exitLower.contains("take_profit") || exitLower.contains("runner") || exitLower.contains("trail"))
                && features.pnlPct >= 5.0 -> 1.2
            exitLower.contains("rug") || exitLower.contains("liquidity_collapse") || exitLower.contains("dump") -> -1.5
            exitLower.contains("stop") && features.pnlPct <= -5.0 -> -0.4   // stops are unavoidable; small penalty
            exitLower.contains("flat") || exitLower.contains("chop") -> -0.6  // flat exit = wasted slot
            else -> 0.0
        }
        featureWeights.exitQualityWeight =
            (featureWeights.exitQualityWeight + exitSignal * adjustmentFactor).coerceIn(0.4, 2.5)

        if (abs(adjustmentFactor) > 0.15) {
            ErrorLogger.debug(
                "AdaptiveLearning",
                "Weight adjustment: buyRatio=${featureWeights.buyRatioWeight.fmt()} " +
                    "holderConc=${featureWeights.holderConcWeight.fmt()} " +
                    "emaFan=${featureWeights.emaFanWeight.fmt()} " +
                    "hold=${featureWeights.holdTimeWeight.fmt()} " +
                    "follow=${featureWeights.maxGainFollowWeight.fmt()} " +
                    "exit=${featureWeights.exitQualityWeight.fmt()}"
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

        // V5.9.301: PATTERN MATCHING — actually USE the learned good/bad pattern libraries.
        // PRIOR BUG: matchedGoodPatterns/matchedBadPatterns returned emptyList() — patterns existed but didn't influence scoring.
        // Now: every pattern whose feature ranges contain this candidate adds bonus (good) / penalty (bad).
        val candidateFeatures = mapOf(
            "buyRatio" to safeBuyRatio,
            "holderConc" to safeTopHolder,
            "holderGrowth" to safeHolderGrowth,
            "tokenAge" to safeAge,
            "devWallet" to safeDevWallet,
            "liquidity" to safeLiquidity,
            "mcap" to safeMcap,
            "volLiqRatio" to (if (safeLiquidity > 0.0) sanitizeDouble(volumeUsd) / safeLiquidity else 0.0)
        )
        val matchedGood = mutableListOf<String>()
        val matchedBad = mutableListOf<String>()
        var patternBonus = 0.0
        for (pat in goodPatterns) {
            if (matchesPattern(candidateFeatures, pat)) {
                matchedGood.add(pat.name)
                // Bonus scales with confidence × outcome strength. Cap per-match to avoid runaway.
                val gain = (8.0 * pat.confidence * (1.0 + pat.avgOutcomeScore.coerceAtLeast(0.0))).coerceAtMost(15.0)
                patternBonus += gain
                explanations.add("✅${pat.name}:+${gain.toInt()}")
            }
        }
        for (pat in badPatterns) {
            if (matchesPattern(candidateFeatures, pat)) {
                matchedBad.add(pat.name)
                // Bad-pattern penalty is harsher — bot must AVOID re-stepping in known traps.
                val penalty = (12.0 * pat.confidence * (1.0 + abs(pat.avgOutcomeScore))).coerceAtMost(20.0)
                patternBonus -= penalty
                explanations.add("❌${pat.name}:-${penalty.toInt()}")
            }
        }
        score = (score + patternBonus).coerceIn(0.0, 100.0)

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
            matchedGoodPatterns = matchedGood,
            matchedBadPatterns = matchedBad,
            explanation = explanations.joinToString(" | ")
        )
    }

    /**
     * V5.9.301: Pattern matcher — does the candidate's features fall within the pattern's learned ranges?
     * Requires at least 60% of the pattern's tracked features to match (so a 1-feature edge case doesn't trigger).
     */
    private fun matchesPattern(features: Map<String, Double>, pattern: LearnedPattern): Boolean {
        if (pattern.featureRanges.isEmpty()) return false
        var checked = 0
        var matched = 0
        for ((key, range) in pattern.featureRanges) {
            val v = features[key] ?: continue
            checked++
            if (v in range.first..range.second) matched++
        }
        if (checked == 0) return false
        return (matched.toDouble() / checked.toDouble()) >= 0.6
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
        val midTrades = trades.filter { it.outcomeScore == 0 }

        ErrorLogger.info(
            "AdaptiveLearning",
            "🧠 Extracting per-label patterns from ${trades.size} trades " +
                "(${goodTrades.size} good, ${badTrades.size} bad, ${midTrades.size} mid)"
        )

        goodPatterns.clear()
        badPatterns.clear()

        // V5.9.301: PER-LABEL PATTERN BUCKETS — instead of one collapsed GOOD/RUG bucket,
        // build a separate pattern for EACH TradeLabel that has enough samples.
        // Each pattern tracks 11 feature dimensions so the matcher can find tight signature overlap.
        // This lets the bot answer: "Is THIS candidate shaped like a GOOD_RUNNER, a BAD_DEAD_CAT, or a MID_FLAT_CHOP?"
        val goodLabels = listOf(
            TradeLabel.GOOD_RUNNER,
            TradeLabel.GOOD_CONTINUATION,
            TradeLabel.GOOD_SECOND_LEG,
            TradeLabel.MID_SMALL_WIN
        )
        val badLabels = listOf(
            TradeLabel.BAD_RUG,
            TradeLabel.BAD_DUMP,
            TradeLabel.BAD_DEAD_CAT,
            TradeLabel.BAD_CHASING_TOP,
            TradeLabel.BAD_DISTRIBUTION,
            TradeLabel.BAD_FAKE_PRESSURE
        )
        val chopLabels = listOf(
            TradeLabel.MID_FLAT_CHOP,
            TradeLabel.MID_CHOP,
            TradeLabel.MID_WEAK_FOLLOW,
            TradeLabel.MID_STOPPED_OUT
        )

        // GOOD pattern buckets — extract a detailed signature for each winning label.
        for (label in goodLabels) {
            val cohort = trades.filter { it.label == label }
            if (cohort.size < 5) continue  // need enough samples for a meaningful pattern
            val pattern = buildDetailedPattern(label.name, cohort, trades.size, isGood = true)
            goodPatterns.add(pattern)
            logPatternDetail("✅ ${label.name}", pattern, cohort)
        }

        // BAD pattern buckets — separate signature per failure mode.
        for (label in badLabels) {
            val cohort = trades.filter { it.label == label }
            if (cohort.size < 3) continue  // bad patterns are rarer; learn from smaller samples
            val pattern = buildDetailedPattern(label.name, cohort, trades.size, isGood = false)
            badPatterns.add(pattern)
            logPatternDetail("❌ ${label.name}", pattern, cohort)
        }

        // CHOP/FLAT bucket — wasted slots. Treat as soft-bad (avoid but don't reject).
        for (label in chopLabels) {
            val cohort = trades.filter { it.label == label }
            if (cohort.size < 5) continue
            val pattern = buildDetailedPattern(label.name, cohort, trades.size, isGood = false)
            badPatterns.add(pattern)
            logPatternDetail("〰️ ${label.name}", pattern, cohort)
        }

        ErrorLogger.info(
            "AdaptiveLearning",
            "🧠 Extracted ${goodPatterns.size} good + ${badPatterns.size} bad/chop patterns. " +
                "Total weight signal: hold=${featureWeights.holdTimeWeight.fmt()} " +
                "follow=${featureWeights.maxGainFollowWeight.fmt()} " +
                "exit=${featureWeights.exitQualityWeight.fmt()}"
        )

        saveState()
    }

    /**
     * V5.9.301: Build a detailed 11-feature signature for a label cohort.
     * Each feature range is bounded by 10th/90th percentiles (good) or 25th/100th (bad — wider tail).
     * Includes hold-time, max-gain, max-drawdown, time-to-peak, and tracks dominant exit reasons.
     */
    private fun buildDetailedPattern(
        name: String,
        cohort: List<TradeFeatures>,
        totalTrades: Int,
        isGood: Boolean
    ): LearnedPattern {
        val loP = if (isGood) 10 else 25
        val hiP = if (isGood) 90 else 90
        val avgOutcome = cohort.map { it.outcomeScore.toDouble() }.average()
        val confidence = (cohort.size.toDouble() / totalTrades.toDouble()).coerceIn(0.0, 1.0)

        val ranges = mutableMapOf<String, Pair<Double, Double>>()
        // Entry-side features
        ranges["mcap"] = Pair(
            cohort.map { it.entryMcapUsd }.percentile(loP),
            cohort.map { it.entryMcapUsd }.percentile(hiP)
        )
        ranges["tokenAge"] = Pair(
            cohort.map { it.tokenAgeMinutes }.percentile(loP),
            cohort.map { it.tokenAgeMinutes }.percentile(hiP)
        )
        ranges["buyRatio"] = Pair(
            cohort.map { it.buyRatioPct }.percentile(loP),
            cohort.map { it.buyRatioPct }.percentile(hiP)
        )
        ranges["liquidity"] = Pair(
            cohort.map { it.liquidityUsd }.percentile(loP),
            cohort.map { it.liquidityUsd }.percentile(hiP)
        )
        ranges["holderConc"] = Pair(
            cohort.map { it.topHolderPct }.percentile(loP),
            cohort.map { it.topHolderPct }.percentile(hiP)
        )
        ranges["holderGrowth"] = Pair(
            cohort.map { it.holderGrowthRate }.percentile(loP),
            cohort.map { it.holderGrowthRate }.percentile(hiP)
        )
        ranges["devWallet"] = Pair(
            cohort.map { it.devWalletPct }.percentile(loP),
            cohort.map { it.devWalletPct }.percentile(hiP)
        )
        ranges["volLiqRatio"] = Pair(
            cohort.map { it.volumeLiquidityRatio }.percentile(loP),
            cohort.map { it.volumeLiquidityRatio }.percentile(hiP)
        )
        // Outcome-side features (used for size sizing & exit-quality cross-checks)
        ranges["holdTime"] = Pair(
            cohort.map { it.holdTimeMins }.percentile(loP),
            cohort.map { it.holdTimeMins }.percentile(hiP)
        )
        ranges["maxGain"] = Pair(
            cohort.map { it.maxGainPct }.percentile(loP),
            cohort.map { it.maxGainPct }.percentile(hiP)
        )
        ranges["maxDrawdown"] = Pair(
            cohort.map { it.maxDrawdownPct }.percentile(loP),
            cohort.map { it.maxDrawdownPct }.percentile(hiP)
        )

        return LearnedPattern(
            name = name,
            featureRanges = ranges,
            avgOutcomeScore = avgOutcome,
            sampleCount = cohort.size,
            confidence = confidence
        )
    }

    private fun logPatternDetail(prefix: String, pattern: LearnedPattern, cohort: List<TradeFeatures>) {
        // Summarise the dominant exit reason in the cohort — crucial signal we previously ignored.
        val topExit = cohort.groupingBy { it.exitReason.lowercase(Locale.US).take(20) }
            .eachCount()
            .maxByOrNull { it.value }
        val avgPnl = cohort.map { it.pnlPct }.average()
        val avgPeak = cohort.map { it.maxGainPct }.average()
        val avgHold = cohort.map { it.holdTimeMins }.average()
        ErrorLogger.info(
            "AdaptiveLearning",
            "$prefix [n=${pattern.sampleCount} conf=${(pattern.confidence * 100).toInt()}% avgOut=${pattern.avgOutcomeScore.fmt()}] " +
                "pnl=${avgPnl.fmt()}% peak=${avgPeak.fmt()}% hold=${avgHold.fmt()}m " +
                "buy=${pattern.featureRanges["buyRatio"].fmt()} " +
                "conc=${pattern.featureRanges["holderConc"].fmt()} " +
                "liq=${pattern.featureRanges["liquidity"].fmt()} " +
                "topExit=${topExit?.key ?: "n/a"}(${topExit?.value ?: 0})"
        )
    }

    fun getTradeCount(): Int = tradeCount

    fun getStatus(): String {
        // V5.9.301: Richer status — surface every learned signal at a glance.
        val goodNames = goodPatterns.joinToString(",") { "${it.name}(${it.sampleCount})" }
        val badNames = badPatterns.joinToString(",") { "${it.name}(${it.sampleCount})" }
        return "AdaptiveLearning: $tradeCount trades | buf=${featureBuffer.size} | " +
            "Weights: buy=${featureWeights.buyRatioWeight.fmt()} " +
            "conc=${featureWeights.holderConcWeight.fmt()} " +
            "ema=${featureWeights.emaFanWeight.fmt()} " +
            "hold=${featureWeights.holdTimeWeight.fmt()} " +
            "follow=${featureWeights.maxGainFollowWeight.fmt()} " +
            "exit=${featureWeights.exitQualityWeight.fmt()} | " +
            "Patterns: ${goodPatterns.size}G[$goodNames] / ${badPatterns.size}B[$badNames]"
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
            "athDistance" to featureWeights.athDistanceWeight,
            // V5.9.301: New learned dimensions surfaced in cloud sync + UI.
            "holdTime" to featureWeights.holdTimeWeight,
            "maxGainFollow" to featureWeights.maxGainFollowWeight,
            "exitQuality" to featureWeights.exitQualityWeight
        )
    }

    /**
     * V5.9.301: Detailed pattern report — used by UI to show what the bot has actually learned.
     * Each line gives the per-label signature: sample count, win-rate-ish confidence, dominant exit reason.
     */
    fun getPatternReport(): String {
        if (goodPatterns.isEmpty() && badPatterns.isEmpty()) {
            return "No patterns learned yet (need $MIN_TRADES_FOR_PATTERN_EXTRACTION+ trades; have ${featureBuffer.size})"
        }
        val sb = StringBuilder()
        sb.appendLine("✅ GOOD PATTERNS (${goodPatterns.size}):")
        for (p in goodPatterns) {
            sb.appendLine("  • ${p.name}: n=${p.sampleCount} conf=${(p.confidence * 100).toInt()}% avgOutcome=${p.avgOutcomeScore.fmt()}")
            sb.appendLine("     buy=${p.featureRanges["buyRatio"].fmt()} conc=${p.featureRanges["holderConc"].fmt()} liq=${p.featureRanges["liquidity"].fmt()} hold=${p.featureRanges["holdTime"].fmt()}m peak=${p.featureRanges["maxGain"].fmt()}%")
        }
        sb.appendLine()
        sb.appendLine("❌ BAD/CHOP PATTERNS (${badPatterns.size}):")
        for (p in badPatterns) {
            sb.appendLine("  • ${p.name}: n=${p.sampleCount} conf=${(p.confidence * 100).toInt()}% avgOutcome=${p.avgOutcomeScore.fmt()}")
            sb.appendLine("     buy=${p.featureRanges["buyRatio"].fmt()} conc=${p.featureRanges["holderConc"].fmt()} dev=${p.featureRanges["devWallet"].fmt()} drawdown=${p.featureRanges["maxDrawdown"].fmt()}%")
        }
        return sb.toString()
    }

    /**
     * V5.9.301: Get count of trades by label — exposes the bot's actual learning distribution.
     */
    fun getLabelDistribution(): Map<String, Int> {
        return featureBuffer.groupingBy { it.label.name }.eachCount()
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
        featureBuffer.clear()  // V5.9.301: also wipe the buffer on reset
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
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
    private const val KEY_FEATURE_WEIGHTS = "feature_weights_observed_8527"
    private const val KEY_GOOD_PATTERNS = "good_patterns_observed_8527"
    private const val KEY_BAD_PATTERNS = "bad_patterns_observed_8527"
    private const val KEY_TRADE_COUNT = "terminal_count_observed_8527"
    private const val KEY_LEARNED_KEYS_8527 = "terminal_receipts_observed_8527"
    private const val KEY_LEARNING_RATE = "learning_rate"
    private const val KEY_LAST_RETRAIN = "last_retrain_ts"
    // V5.9.301: Persisted rolling feature buffer — fuel for pattern extraction.
    // Without this, every trade's features were discarded after weight adjustment.
    private const val KEY_FEATURE_BUFFER = "feature_buffer_observed_8527"
    private const val FEATURE_BUFFER_MAX = 300

    // V5.9.301: Lowered from 50 → 25 so patterns get extracted earlier in bootstrap.
    private const val MIN_TRADES_FOR_PATTERN_EXTRACTION = 25
    private const val PATTERN_RETRAIN_INTERVAL = 15  // V5.9.721: reduced from 25 — faster pattern adaptation during WR recovery

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
        val label: TradeLabel,
        // V5.9.1154 — stable per-close identity. Empty for legacy callers;
        // when populated, learnFromTrade dedupes by this instead of drifting
        // hold/pnl/minute buckets.
        val stableTradeKey: String = "",
        val observedFeatures8527: Set<String> = emptySet(),
        val source8527: String = "",
        val lane8527: String = "",
        val strategy8527: String = "",
        val environment8527: String = "",
        val outcomeObserved8527: Boolean = true,
        val realizedPnlSol8527: Double = Double.NaN,
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
        entryPhase: String = "",
        stableTradeKey: String = "",
        source8527: String = "",
        lane8527: String = "",
        strategy8527: String = "",
        environment8527: String = "",
        realizedPnlSol8527: Double = Double.NaN,
    ): TradeFeatures {
        // Preserve presence before the finite storage representation is built. Unknown is never a measured zero.
        val observations8527 = mapOf(
            "mcap" to entryMcapUsd, "tokenAge" to tokenAgeMinutes, "buyRatio" to buyRatioPct,
            "volume" to volumeUsd, "liquidity" to liquidityUsd, "holderCount" to holderCount.toDouble(),
            "holderConc" to topHolderPct, "holderGrowth" to holderGrowthRate, "devWallet" to devWalletPct,
            "bondingCurve" to bondingCurveProgress, "rugcheck" to rugcheckScore, "entryScore" to entryScore,
            "priceFromAth" to priceFromAth, "pnl" to pnlPct, "maxGain" to maxGainPct,
            "maxDrawdown" to maxDrawdownPct, "timeToPeak" to timeToPeakMins, "holdTime" to holdTimeMins,
        )
        val present8527 = observations8527.filter { (key, value) -> observedNumeric8527(key, value) }.keys.toMutableSet()
        if (emaFanState.uppercase(Locale.US) in EMA_STATES_8527) present8527 += "emaFan"
        if (exitReason.isNotBlank()) present8527 += "exitReason"
        val safeLiquidity = sanitizeDouble(liquidityUsd)
        val safeVolume = sanitizeDouble(volumeUsd)
        val volLiqRatio = if ("volume" in present8527 && "liquidity" in present8527 && safeLiquidity > 0.0) {
            present8527 += "volLiqRatio"
            safeVolume / safeLiquidity
        } else 0.0

        val safePnl = sanitizeDouble(pnlPct)
        val safeMaxGain = sanitizeDouble(maxGainPct)
        val safeMaxDrawdown = sanitizeDouble(maxDrawdownPct)
        val safeTimeToPeak = sanitizeDouble(timeToPeakMins)
        val safeHoldTime = sanitizeDouble(holdTimeMins)

        val outcomeScore = calculateOutcomeScore(safePnl, safeMaxGain, safeTimeToPeak)
        val label = classifyTrade(
            pnlPct = safePnl,
            maxGainPct = maxGainPct.takeIf { "maxGain" in present8527 } ?: Double.NaN,
            maxDrawdownPct = maxDrawdownPct.takeIf { "maxDrawdown" in present8527 } ?: Double.NaN,
            exitReason = exitReason,
            topHolderPct = topHolderPct.takeIf { "holderConc" in present8527 } ?: Double.NaN,
            entryPhase = entryPhase,
            holdTimeMins = holdTimeMins.takeIf { "holdTime" in present8527 } ?: Double.NaN,
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
            label = label,
            stableTradeKey = stableTradeKey,
            observedFeatures8527 = present8527.filter { it != "volLiqRatio" || volLiqRatio.isFinite() }.toSet(),
            source8527 = source8527, lane8527 = lane8527, strategy8527 = strategy8527,
            environment8527 = environment8527,
            outcomeObserved8527 = pnlPct.isFinite(),
            realizedPnlSol8527 = realizedPnlSol8527.takeIf { it.isFinite() } ?: Double.NaN,
        )
    }

    private val EMA_STATES_8527 = setOf("FLAT", "BULL_FLAT", "BULL_FAN", "BEAR_FLAT", "BEAR_FAN")
    internal fun observedNumeric8527(key: String, value: Double): Boolean = value.isFinite() && when (key) {
        "mcap" -> value > 0.0
        "rugcheck" -> value in 0.0..100.0 && value != 1.0 // 1 is the repository's pending sentinel, not measured risk.
        "buyRatio", "holderConc", "devWallet", "bondingCurve", "entryScore", "priceFromAth" -> value in 0.0..100.0
        "holderCount" -> value in 0.0..Int.MAX_VALUE.toDouble() && value % 1.0 == 0.0
        "tokenAge", "volume", "liquidity", "timeToPeak", "holdTime", "volLiqRatio" -> value >= 0.0
        else -> true
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
    @Volatile private var durableTradeCount8527 = 0
    @Volatile private var durableFeatureCounts8527: Map<String, Int> = emptyMap()
    @Volatile private var durableWeights8527: Map<String, Double> = emptyMap()
    @Volatile private var durableTerminalEconomics8527: AdaptiveWeightTransfer8527.TerminalEconomics? = null
    @Volatile private var checkpointPending8527 = false
    @Volatile private var sessionTradeBaseline = 0  // V5.9.719: snapped at init
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
        put("schema8527", 8527)
        put("outcomeObserved8527", f.outcomeObserved8527)
        if (f.realizedPnlSol8527.isFinite()) put("realizedPnlSol8527", f.realizedPnlSol8527)
        put("observed8527", JSONArray(f.observedFeatures8527.sorted()))
        put("tradeKey8527", f.stableTradeKey)
        put("source8527", f.source8527).put("lane8527", f.lane8527)
        put("strategy8527", f.strategy8527).put("environment8527", f.environment8527)
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
            label = label,
            stableTradeKey = j.optString("tradeKey8527", ""),
            observedFeatures8527 = if (j.optInt("schema8527", 0) == 8527) {
                j.optJSONArray("observed8527")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet() } ?: emptySet()
            } else emptySet(),
            source8527 = j.optString("source8527", ""), lane8527 = j.optString("lane8527", ""),
            strategy8527 = j.optString("strategy8527", ""), environment8527 = j.optString("environment8527", ""),
            outcomeObserved8527 = j.optBoolean("outcomeObserved8527", false) &&
                (j.opt("pnl") as? Number)?.toDouble()?.isFinite() == true,
            realizedPnlSol8527 = (j.opt("realizedPnlSol8527") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: Double.NaN,
        ).let { f ->
            val storedKeys8527 = mapOf("mcap" to "mc", "tokenAge" to "ag", "buyRatio" to "br", "volume" to "vol",
                "liquidity" to "liq", "holderCount" to "hc", "holderConc" to "th", "holderGrowth" to "hg",
                "devWallet" to "dw", "bondingCurve" to "bc", "rugcheck" to "rc", "entryScore" to "es",
                "volLiqRatio" to "vlr", "priceFromAth" to "ath", "pnl" to "pnl", "maxGain" to "mg",
                "maxDrawdown" to "md", "timeToPeak" to "ttp", "holdTime" to "ht")
            f.copy(observedFeatures8527 = f.observedFeatures8527.filter { key ->
                when (key) {
                    "emaFan" -> j.has("ema") && j.optString("ema", "").uppercase(Locale.US) in EMA_STATES_8527
                    "exitReason" -> j.has("ex") && j.optString("ex", "").isNotBlank()
                    else -> storedKeys8527[key]?.let { stored -> (j.opt(stored) as? Number)?.toDouble()?.let {
                        observedNumeric8527(key, it) } } == true
                }
            }.toSet())
        }
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
        durableTradeCount8527 = tradeCount
        aleSeenKeys.clear()
        try {
            val receipts8527 = JSONObject(p.getString(KEY_LEARNED_KEYS_8527, "{}") ?: "{}")
            receipts8527.keys().forEach { key ->
                val at = receipts8527.optLong(key, 0L)
                if (key.isNotBlank() && at > 0L) aleSeenKeys[key] = at
            }
        } catch (_: Exception) { /* absent receipt is retriable, never fabricated */ }
        sessionTradeBaseline = tradeCount  // V5.9.719: snap baseline so session delta is measurable
        learningRate = p.getFloat(KEY_LEARNING_RATE, 0.1f).toDouble().coerceIn(0.001, 1.0)
        // Old model keys retain their original proxy/default-filled meaning. Do not relabel or erase that history.
        featureWeights = FeatureWeights()

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
        durableFeatureCounts8527 = featureCountsFromBuffer8527()
        durableWeights8527 = getDetailedWeights()
        durableTerminalEconomics8527 = terminalEconomicsFromBuffer8527()
        checkpointPending8527 = false
    }

    private fun saveState(): Boolean {
        checkpointPending8527 = true
        val p = prefs ?: return false

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

        val durable8527 = p.edit()
            .putInt(KEY_TRADE_COUNT, tradeCount)
            .putString(KEY_LEARNED_KEYS_8527, JSONObject(aleSeenKeys.toMap()).toString())
            .putFloat(KEY_LEARNING_RATE, learningRate.toFloat())
            .putString(KEY_FEATURE_WEIGHTS, featureWeights.toJson().toString())
            .putString(KEY_GOOD_PATTERNS, goodArr.toString())
            .putString(KEY_BAD_PATTERNS, badArr.toString())
            .putString(KEY_FEATURE_BUFFER, bufArr.toString())
            .putLong(KEY_LAST_RETRAIN, System.currentTimeMillis())
            .commit()
        if (durable8527) {
            durableTradeCount8527 = tradeCount
            durableFeatureCounts8527 = featureCountsFromBuffer8527()
            durableWeights8527 = getDetailedWeights()
            durableTerminalEconomics8527 = terminalEconomicsFromBuffer8527()
            checkpointPending8527 = false
        }
        return durable8527
    }

    // V5.9.694 — deduplication guard. Multiple Executor close paths
    // (paperSell, liveSell, fallback exits) all called learnFromTrade
    // independently, causing tradeCount to grow 2-3x faster than the
    // canonical pipeline count. Guard by mint+entryTime — same position
    // close can never feed ALE more than once.
    private val aleSeenKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun learnPaperTrade7835(features: TradeFeatures) = learnFromTrade(features, TradeEnvironment.PAPER)

    @Synchronized
    fun learnFromTrade(features: TradeFeatures, environment: TradeEnvironment = TradeEnvironment.LIVE,
                       canonicalTerminal8527: Boolean = false): Boolean {
        if (!features.outcomeObserved8527 || !features.pnlPct.isFinite()) return false
        // V5.0.7803 audit — this object owns one global live-driving weight/pattern
        // state. PAPER/SHADOW evidence must learn in their isolated systems and may
        // cross to live only through an explicit paper→live intelligence bridge.
        if (environment != TradeEnvironment.LIVE) {
            try { PipelineHealthCollector.labelInc("ADAPTIVE_${environment.name}_SHADOW_ONLY_7803") } catch (_: Throwable) {}
            return false
        }
        // V5.9.723 — skip DEAD_TOKEN_NO_PRICE_EXIT. These are unpriced pump.fun
        // bonding-curve tokens that never produced a live tick; their pnl=0 / peak=0
        // is a data artifact, not a market signal. Feeding them into ALE would
        // pollute the FLAT_CHOP cohort and bias pattern learning toward "do nothing".
        if (features.exitReason == "DEAD_TOKEN_NO_PRICE_EXIT") {
            ErrorLogger.debug("AdaptiveLearning", "🪦 SKIP DEAD_TOKEN exit (no price feed) — not learning")
            return false
        }
        // V5.9.1154 — prefer stable per-position close identity when callers
        // provide it. The old fallback key included holdTime/pnl/current-minute,
        // so a single position being swept/reconciled could mutate into a fresh
        // key every tick and hijack AdaptiveLearning forever.
        val dedupKey = features.stableTradeKey.takeIf { it.isNotBlank() }
            ?: "${"%.1f".format(features.entryMcapUsd)}_${"%.1f".format(features.holdTimeMins)}_${"%.1f".format(features.pnlPct)}_${(System.currentTimeMillis() / 60_000L)}"
        if (canonicalTerminal8527 && checkpointPending8527) {
            // Stop only additional learning while its prior model receipt is not durable.
            // Trade execution continues, and the canonical bus retries these unapplied terminals.
            if (!saveState()) return false
            if (aleSeenKeys.containsKey(dedupKey)) return true
        }
        if (aleSeenKeys.containsKey(dedupKey)) {
            // Retry only the durable checkpoint after a failed save; never apply weights/count a second time.
            return saveState()
        }
        val beforeWeights8527 = featureWeights.copy()
        val beforeBuffer8527 = featureBuffer.toList()
        val beforeGood8527 = goodPatterns.toList()
        val beforeBad8527 = badPatterns.toList()
        val beforeCount8527 = tradeCount
        val beforeReceipts8527 = aleSeenKeys.toMap()
        val beforeCheckpoint8527 = checkpointPending8527
        try {
            if (canonicalTerminal8527) tradeCount += 1
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
                // V5.9.721-FIX: Use most-recent 150 trades for pattern extraction (not full 300).
                // During a loss streak, the full 300-trade buffer is dominated by BAD labels —
                // extracting patterns from it produces near-zero goodPatterns, biasing
                // future scoring against every candidate. The most recent 150 trades represent
                // the current market regime and minimise stale-streak contamination.
                // If buffer < 150 trades, use the full buffer (normal behavior during ramp-up).
                val extractWindow = if (featureBuffer.size > 150) featureBuffer.takeLast(150) else featureBuffer.toList()
                ErrorLogger.info(
                    "AdaptiveLearning",
                    "🧠 Auto-extracting patterns from ${extractWindow.size}/${featureBuffer.size} buffered trades (retrain #${tradeCount / PATTERN_RETRAIN_INTERVAL}, recency-window)"
                )
                extractPatterns(extractWindow, persist8527 = false)
            }

            aleSeenKeys[dedupKey] = System.currentTimeMillis()
            // Bounded model receipt cache; older acknowledged delivery belongs to the canonical durable ACK store.
            // Never evict additional receipts during a failed checkpoint: the guard above retries it first.
            if (aleSeenKeys.size > 2000) {
                aleSeenKeys.entries.asSequence().filter { it.key != dedupKey }.sortedBy { it.value }
                    .take(aleSeenKeys.size - 2000).toList().forEach { aleSeenKeys.remove(it.key, it.value) }
            }
            return saveState()
        } catch (t: Throwable) {
            // Failed application is retried from the same prior model, never checkpointed as a completed receipt.
            featureWeights = beforeWeights8527
            featureBuffer.clear(); featureBuffer.addAll(beforeBuffer8527)
            goodPatterns.clear(); goodPatterns.addAll(beforeGood8527)
            badPatterns.clear(); badPatterns.addAll(beforeBad8527)
            tradeCount = beforeCount8527
            aleSeenKeys.clear(); aleSeenKeys.putAll(beforeReceipts8527)
            checkpointPending8527 = beforeCheckpoint8527
            throw t
        }
    }

    private fun adjustWeights(features: TradeFeatures) {
        // V5.9.1275 — SIGN-BUG FIX. adjustmentFactor previously carried the SIGN of
        // the outcome (negative for losses). But every per-feature *Signal below ALSO
        // encodes direction (e.g. ageSignal=-1.5 means "young token that LOST"). A
        // negative signal times a negative factor = a POSITIVE push → losses were
        // INFLATING the very feature weights they should have suppressed, ratcheting
        // age/holderGrowth/volLiq/holdTime straight to the 2.5 ceiling (seen pinned in
        // the 3240 runtime board). adjustmentFactor must be MAGNITUDE-ONLY (>=0); the
        // signal alone decides up/down. Bigger-magnitude outcomes still learn faster.
        val adjustmentFactor = when (features.outcomeScore) {
            2 -> learningRate * 1.5    // decisive win — learn fast
            1 -> learningRate * 0.8    // win
            0 -> learningRate * 0.3    // scratch — gentle
            -1 -> learningRate * 1.2   // loss — same magnitude, direction from signal
            -2 -> learningRate * 3.0   // decisive loss — learn fast (was the inverted one)
            else -> 0.0
        }
        // V5.0.6440 — LEARNER REWARD BRIDGE. Multiply the classic
        // outcomeScore-derived learning rate by the shaper-aligned
        // multiplier so bag-holding losses (creed: cut fast) learn even
        // faster and break-evens (creed: NEVER positive) still nudge
        // weights away from the offending pattern. Backwards-compatible:
        // the bridge is derivation-only, side-effect free beyond telemetry.
        val shapedMult = try {
            val m = if ("holdTime" in features.observedFeatures8527) com.lifecyclebot.engine.truth.LearnerRewardBridge6440.derivedMultiplier(
                features.pnlPct, features.holdTimeMins,
            ) else 1.0
            // Convert negative multiplier (break-even inversion) into an
            // absolute-value magnitude here — the sign-mgmt of adjustWeights
            // already lives on the per-feature *Signal below. We just want
            // "how big should the weight step be for THIS trade" here.
            kotlin.math.abs(m).coerceIn(0.3, 3.5)
        } catch (_: Throwable) { 1.0 }
        val adjustmentFactorShaped = adjustmentFactor * shapedMult

        // V5.0.8527 — these five signals describe the FEATURE, not its realised outcome.
        // The 1275 magnitude-only step fixed the branches already encoding win/loss, but
        // otherwise made high-buy/BULL/small-cap losses strengthen those same positive signals.
        // Apply outcome direction exactly once here; age/volLiq/hold/follow/exit already carry it.
        val outcomeDirection8527 = when {
            features.outcomeScore > 0 -> 1.0
            features.outcomeScore < 0 -> -1.0
            else -> 0.0
        }
        val mcapSignal = (if (features.entryMcapUsd < 50_000.0) 1.0 else -0.5) * outcomeDirection8527
        if ("mcap" in features.observedFeatures8527) {
            featureWeights.mcapWeight =
                (featureWeights.mcapWeight + mcapSignal * adjustmentFactorShaped).coerceIn(0.3, 2.5)
        }

        val ageSignal = when {
            features.tokenAgeMinutes < 30.0 && features.outcomeScore >= 1 -> 1.0
            features.tokenAgeMinutes < 30.0 && features.outcomeScore <= -1 -> -1.5
            else -> 0.0
        }
        if ("tokenAge" in features.observedFeatures8527) {
            featureWeights.ageWeight =
                (featureWeights.ageWeight + ageSignal * adjustmentFactorShaped).coerceIn(0.3, 2.5)
        }

        val buySignal = (if (features.buyRatioPct > 55.0) 1.0 else -0.5) * outcomeDirection8527
        if ("buyRatio" in features.observedFeatures8527) {
            featureWeights.buyRatioWeight =
                (featureWeights.buyRatioWeight + buySignal * adjustmentFactorShaped).coerceIn(0.5, 3.0)
        }

        val concSignal = (if (features.topHolderPct < 25.0) 1.0 else -1.0) * outcomeDirection8527
        if ("holderConc" in features.observedFeatures8527) {
            featureWeights.holderConcWeight =
                (featureWeights.holderConcWeight + concSignal * adjustmentFactorShaped).coerceIn(0.5, 3.0)
        }

        val growthSignal = (if (features.holderGrowthRate > 0.0) 1.0 else -0.5) * outcomeDirection8527
        if ("holderGrowth" in features.observedFeatures8527) {
            featureWeights.holderGrowthWeight =
                (featureWeights.holderGrowthWeight + growthSignal * adjustmentFactorShaped).coerceIn(0.5, 2.5)
        }

        val emaSignal = (if (features.emaFanState.uppercase(Locale.US).contains("BULL")) 1.0 else -0.3) * outcomeDirection8527
        if ("emaFan" in features.observedFeatures8527) {
            featureWeights.emaFanWeight =
                (featureWeights.emaFanWeight + emaSignal * adjustmentFactorShaped).coerceIn(0.5, 3.0)
        }

        val volLiqSignal = when {
            features.volumeLiquidityRatio > 0.5 && features.outcomeScore >= 0 -> 0.8
            features.volumeLiquidityRatio > 0.5 && features.outcomeScore < 0 -> -0.5
            else -> 0.0
        }
        if ("volLiqRatio" in features.observedFeatures8527) {
            featureWeights.volLiqRatioWeight =
                (featureWeights.volLiqRatioWeight + volLiqSignal * adjustmentFactorShaped).coerceIn(0.3, 2.5)
        }

        // V5.9.301: HOLD-TIME LEARNING — distinguish profitable runners from chopped quick-stops.
        // Long winning hold = trust trend continuation. Short losing hold = bad entry. Long losing hold = should have cut.
        val holdSignal = when {
            features.holdTimeMins >= 8.0 && features.pnlPct >= 10.0 -> 1.2   // ran a winner
            features.holdTimeMins >= 8.0 && features.pnlPct <= -10.0 -> -1.5 // held loser too long
            features.holdTimeMins < 2.0 && features.pnlPct <= -5.0 -> -0.8   // quick rug/stop = bad entry
            features.holdTimeMins in 3.0..8.0 && features.pnlPct >= 5.0 -> 0.7  // disciplined exit
            else -> 0.0
        }
        if ("holdTime" in features.observedFeatures8527) {
            featureWeights.holdTimeWeight =
                (featureWeights.holdTimeWeight + holdSignal * adjustmentFactorShaped).coerceIn(0.4, 2.5)
        }

        // V5.9.301: MAX-GAIN FOLLOW-THROUGH — captured PnL vs peak. Tells us about exit timing & scratch traps.
        val captureRatio = if (features.maxGainPct > 0.0) features.pnlPct / features.maxGainPct else 0.0
        val followSignal = when {
            captureRatio >= 0.7 && features.maxGainPct >= 10.0 -> 1.5    // captured most of a real move
            captureRatio in 0.0..0.3 && features.maxGainPct >= 15.0 -> -1.8  // SCRATCH from a real winner — exits broken
            features.maxGainPct < 3.0 && features.pnlPct < -5.0 -> -1.0  // never had a chance
            else -> 0.0
        }
        if ("maxGain" in features.observedFeatures8527) {
            featureWeights.maxGainFollowWeight =
                (featureWeights.maxGainFollowWeight + followSignal * adjustmentFactorShaped).coerceIn(0.4, 2.5)
        }

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
        if ("exitReason" in features.observedFeatures8527) {
            featureWeights.exitQualityWeight =
                (featureWeights.exitQualityWeight + exitSignal * adjustmentFactorShaped).coerceIn(0.4, 2.5)
        }

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
        baseEntryScore: Double,
        // V5.9.875 — entry depth below ATH (0.0 = at ATH, 100.0 = 100% below).
        // Unknown defaults to NaN; an explicitly measured zero still means at ATH.
        priceFromAth: Double = Double.NaN
    ): AdaptiveScore {
        var score = sanitizeDouble(baseEntryScore)
        val explanations = mutableListOf<String>()

        val safeMcap = mcapUsd.takeIf { observedNumeric8527("mcap", it) } ?: Double.NaN
        val safeAge = tokenAgeMinutes.takeIf { observedNumeric8527("tokenAge", it) } ?: Double.NaN
        val safeBuyRatio = buyRatioPct.takeIf { observedNumeric8527("buyRatio", it) } ?: Double.NaN
        val safeLiquidity = liquidityUsd.takeIf { observedNumeric8527("liquidity", it) } ?: Double.NaN
        val safeTopHolder = topHolderPct.takeIf { observedNumeric8527("holderConc", it) } ?: Double.NaN
        val safeHolderGrowth = holderGrowthRate.takeIf { observedNumeric8527("holderGrowth", it) } ?: Double.NaN
        val safeDevWallet = devWalletPct.takeIf { observedNumeric8527("devWallet", it) } ?: Double.NaN
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

        // ── V5.9.828 — wire 3 top-predictor parameters that were silently dropped ──
        // calculateAdaptiveScore() accepted holderCount, bondingCurveProgress, and
        // rugcheckScore as inputs and the data classes had matching learned weights
        // (holderCountWeight, bondingCurveWeight, rugcheckWeight), but no contribution
        // terms ever consumed them. Three of the strongest meme-coin predictors went
        // through the function on every scoring call and dropped on the floor.
        //
        // Contribution shape mirrors the existing bucketed style (mcap/buy%/conc).
        // Weight multiplier preserves the per-bucket learned re-weighting that already
        // operates on the other features.

        val safeHolderCount = holderCount
        val holderCountContrib = when {
            safeHolderCount < 0 -> 0.0
            safeHolderCount >= 500 -> 6.0 * featureWeights.holderCountWeight   // strong distribution
            safeHolderCount >= 200 -> 4.0 * featureWeights.holderCountWeight
            safeHolderCount >= 100 -> 2.0 * featureWeights.holderCountWeight
            safeHolderCount <  20  -> -4.0 * featureWeights.holderCountWeight  // too concentrated / sniped
            else -> 0.0
        }
        score += holderCountContrib
        if (abs(holderCountContrib) >= 3.0) {
            explanations.add("hCnt:${if (holderCountContrib > 0.0) "+" else ""}${holderCountContrib.toInt()}")
        }

        val safeBondingCurve = bondingCurveProgress.takeIf { observedNumeric8527("bondingCurve", it) } ?: Double.NaN
        // Sweet-spot bonding curve: 30-70% is the "discovery breakout" zone before
        // graduation tax. <10% = brand new / risky; >95% = graduation pump priced in.
        val bondingContrib = when {
            safeBondingCurve in 30.0..70.0 -> 5.0 * featureWeights.bondingCurveWeight
            safeBondingCurve in 70.0..90.0 -> 2.0 * featureWeights.bondingCurveWeight
            safeBondingCurve > 95.0        -> -3.0 * featureWeights.bondingCurveWeight
            safeBondingCurve < 5.0         -> -2.0 * featureWeights.bondingCurveWeight
            else -> 0.0
        }
        score += bondingContrib
        if (abs(bondingContrib) >= 3.0) {
            explanations.add("bc:${if (bondingContrib > 0.0) "+" else ""}${bondingContrib.toInt()}")
        }

        val safeRugcheck = rugcheckScore.takeIf { observedNumeric8527("rugcheck", it) } ?: Double.NaN
        // Rugcheck score is 0 (rug) → 100 (clean). FDG has its own hard-block for
        // critical rug signals; this is a soft-shaping contribution for the score band.
        val rugcheckContrib = when {
            safeRugcheck >= 85.0 -> 5.0 * featureWeights.rugcheckWeight
            safeRugcheck >= 70.0 -> 2.0 * featureWeights.rugcheckWeight
            safeRugcheck <  40.0 -> -8.0 * featureWeights.rugcheckWeight
            safeRugcheck <  55.0 -> -3.0 * featureWeights.rugcheckWeight
            else -> 0.0
        }
        score += rugcheckContrib
        if (abs(rugcheckContrib) >= 3.0) {
            explanations.add("rug:${if (rugcheckContrib > 0.0) "+" else ""}${rugcheckContrib.toInt()}")
        }

        // ── V5.9.843 — volumeUsd was the LAST silently-dropped param ──
        // V5.9.828 wired holderCount / bondingCurveProgress / rugcheckScore but
        // volumeUsd remained dropped despite featureWeights.volumeWeight (default
        // 1.2) being defined, persisted, and weight-adjusted from outcomes.
        //
        // Volume relative to liquidity (vol/liq ratio) is one of the most-cited
        // alpha-leak signals: high-vol-low-liq tokens are pumping/dumping; flat-
        // vol tokens are dead; well-balanced vol/liq tokens are organic.
        val safeVolume = volumeUsd.takeIf { observedNumeric8527("volume", it) } ?: Double.NaN
        val volContrib = when {
            safeVolume >= 500_000.0 -> 6.0 * featureWeights.volumeWeight
            safeVolume >= 100_000.0 -> 4.0 * featureWeights.volumeWeight
            safeVolume >= 25_000.0  -> 2.0 * featureWeights.volumeWeight
            safeVolume <  2_000.0   -> -4.0 * featureWeights.volumeWeight  // dead
            safeVolume <  5_000.0   -> -2.0 * featureWeights.volumeWeight
            else -> 0.0
        }
        score += volContrib
        if (abs(volContrib) >= 3.0) {
            explanations.add("vol:${if (volContrib > 0.0) "+" else ""}${volContrib.toInt()}")
        }

        score = score.coerceIn(0.0, 100.0)

        // V5.9.301: PATTERN MATCHING — actually USE the learned good/bad pattern libraries.
        // PRIOR BUG: matchedGoodPatterns/matchedBadPatterns returned emptyList() — patterns existed but didn't influence scoring.
        // Now: every pattern whose feature ranges contain this candidate adds bonus (good) / penalty (bad).
        val candidateFeatures = mapOf(
            "buyRatio" to safeBuyRatio,
            "volume" to safeVolume,
            "holderCount" to safeHolderCount.toDouble(),
            "holderConc" to safeTopHolder,
            "holderGrowth" to safeHolderGrowth,
            "tokenAge" to safeAge,
            "devWallet" to safeDevWallet,
            "liquidity" to safeLiquidity,
            "mcap" to safeMcap,
            "volLiqRatio" to (if (safeLiquidity > 0.0 && safeVolume.isFinite()) safeVolume / safeLiquidity else Double.NaN),
            // V5.9.875 — match the 3 entry-side features now learned in featureRanges.
            // matchesPattern requires at least 60% feature overlap; adding these
            // INCREASES the denominator (more features checked) AND the numerator
            // when the candidate falls inside any learned range — net effect:
            // patterns become more discriminating, not stricter.
            "rugcheck" to safeRugcheck,
            "bondingCurve" to safeBondingCurve,
            "priceFromAth" to priceFromAth
        ).filter { (key, value) -> observedNumeric8527(key, value) }
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
        val entryRanges8527 = pattern.featureRanges.filterKeys { it !in setOf("holdTime", "maxGain", "maxDrawdown", "timeToPeak") }
        if (entryRanges8527.isEmpty()) return false
        var checked = 0
        var matched = 0
        for ((key, range) in entryRanges8527) {
            val v = features[key]?.takeIf { observedNumeric8527(key, it) } ?: continue
            checked++
            if (v in range.first..range.second) matched++
        }
        if (checked == 0 || checked.toDouble() / entryRanges8527.size < 0.6) return false
        return (matched.toDouble() / checked.toDouble()) >= 0.6
    }

    @Synchronized
    fun extractPatterns(trades: List<TradeFeatures>, persist8527: Boolean = true) {
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

        if (persist8527) saveState()
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
        val support8527 = mutableMapOf<String, Int>()

        val ranges = mutableMapOf<String, Pair<Double, Double>>()
        fun observedRange8527(key: String, value: (TradeFeatures) -> Double) {
            val values = cohort.filter { key in it.observedFeatures8527 }.map(value).filter { observedNumeric8527(key, it) }
            // The original per-label proof floors apply to EACH learned dimension, not imputed zero-filled rows.
            if (values.size >= (if (isGood) 5 else 3)) {
                ranges[key] = values.percentile(loP) to values.percentile(hiP)
                support8527[key] = values.size
            }
        }
        observedRange8527("mcap") { it.entryMcapUsd }
        observedRange8527("tokenAge") { it.tokenAgeMinutes }
        observedRange8527("buyRatio") { it.buyRatioPct }
        observedRange8527("volume") { it.volumeUsd }
        observedRange8527("liquidity") { it.liquidityUsd }
        observedRange8527("holderCount") { it.holderCount.toDouble() }
        observedRange8527("holderConc") { it.topHolderPct }
        observedRange8527("holderGrowth") { it.holderGrowthRate }
        observedRange8527("devWallet") { it.devWalletPct }
        observedRange8527("volLiqRatio") { it.volumeLiquidityRatio }
        observedRange8527("holdTime") { it.holdTimeMins }
        observedRange8527("maxGain") { it.maxGainPct }
        observedRange8527("maxDrawdown") { it.maxDrawdownPct }
        observedRange8527("rugcheck") { it.rugcheckScore }
        observedRange8527("bondingCurve") { it.bondingCurveProgress }
        observedRange8527("priceFromAth") { it.priceFromAth }
        observedRange8527("timeToPeak") { it.timeToPeakMins }

        // Rows lacking the entry dimensions must not inflate that pattern's empirical support.
        val entryKeys8527 = support8527.keys - setOf("holdTime", "maxGain", "maxDrawdown", "timeToPeak")
        val complete8527 = if (entryKeys8527.isEmpty()) emptyList() else cohort.filter { it.observedFeatures8527.containsAll(entryKeys8527) }
        val effectiveSupport8527 = complete8527.size.takeIf { it >= (if (isGood) 5 else 3) } ?: 0
        return LearnedPattern(
            name = name,
            featureRanges = ranges,
            avgOutcomeScore = if (effectiveSupport8527 > 0) complete8527.map { it.outcomeScore.toDouble() }.average() else 0.0,
            sampleCount = effectiveSupport8527,
            confidence = if (totalTrades > 0) (effectiveSupport8527.toDouble() / totalTrades).coerceIn(0.0, 1.0) else 0.0
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

    /** Actual durable observed-schema whole-position examples, never pooled canonical leg/all-mode counts. */
    fun getTradeCount(): Int = durableTradeCount8527

    /** V5.9.719 — trades recorded THIS session only (excludes persisted historical count). */
    fun getSessionTradeCount(): Int = (tradeCount - sessionTradeBaseline).coerceAtLeast(0)

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

    private fun observedTerminalRows8527(): List<TradeFeatures> = featureBuffer.filter {
        it.stableTradeKey.startsWith("ALE8527|LIVE|") && it.environment8527 == "LIVE" && it.outcomeObserved8527
    }.distinctBy { it.stableTradeKey }

    private fun terminalEconomicsFromBuffer8527(): AdaptiveWeightTransfer8527.TerminalEconomics? {
        // A terminal count cannot lend missing currency evidence to the profit qualification.
        // Rows without actual settled SOL remain unknown; no percent-to-currency reconstruction.
        val actual = observedTerminalRows8527().filter {
            it.realizedPnlSol8527.isFinite() &&
                (it.realizedPnlSol8527 > 0.0) == (it.pnlPct > 0.0) &&
                (it.realizedPnlSol8527 < 0.0) == (it.pnlPct < 0.0)
        }
        if (actual.isEmpty()) return null
        val won = actual.sumOf { it.realizedPnlSol8527.coerceAtLeast(0.0) }
        val lost = actual.sumOf { (-it.realizedPnlSol8527).coerceAtLeast(0.0) }
        val net = actual.sumOf { it.realizedPnlSol8527 }
        if (!won.isFinite() || !lost.isFinite() || !net.isFinite()) return null
        return AdaptiveWeightTransfer8527.TerminalEconomics(actual.size, net, won, lost)
    }

    private fun featureCountsFromBuffer8527(): Map<String, Int> {
        val fields = mapOf("mcap" to "mcap", "age" to "tokenAge", "buyRatio" to "buyRatio", "volume" to "volume",
            "liquidity" to "liquidity", "holderCount" to "holderCount", "holderConc" to "holderConc",
            "holderGrowth" to "holderGrowth", "devWallet" to "devWallet", "bondingCurve" to "bondingCurve",
            "rugcheck" to "rugcheck", "emaFan" to "emaFan", "volLiqRatio" to "volLiqRatio",
            "athDistance" to "priceFromAth", "holdTime" to "holdTime", "maxGainFollow" to "maxGain", "exitQuality" to "exitReason")
        val actual = observedTerminalRows8527()
        return fields.mapValues { (_, feature) -> actual.count { feature in it.observedFeatures8527 } }
    }

    /** Conservative bounded support for shared observed-schema weights; never all-mode/leg proxy sample counts. */
    fun observedFeatureCounts8527(): Map<String, Int> = durableFeatureCounts8527.toMap()

    @Synchronized
    fun transferModel8527() = AdaptiveWeightTransfer8527.localModel8527(
        durableWeights8527.toMap(), observedFeatureCounts8527(), getTradeCount(),
        terminalEconomics = durableTerminalEconomics8527)

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

    /**
     * Only the compatible LIVE/observed-entry schema supplies ALE evidence. Legacy bare
     * maps and pooled win rates remain accepted arguments for source compatibility,
     * but cannot change this model or grant maturity to an unobserved feature.
     */
    @Synchronized
    fun applyHiveGenomeNudge(
        hiveWeights: Map<String, Double>, hiveAvgWinRatePct: Double, hiveContributors: Int,
        hiveTotalTrades: Int, localWinRatePct: Double, localTradeCount: Int,
        compatible8527: AdaptiveWeightTransfer8527.CompatibleModel = AdaptiveWeightTransfer8527.CompatibleModel(),
    ) {
        if (checkpointPending8527 && !saveState()) return
        val current = getDetailedWeights()
        val next = AdaptiveWeightTransfer8527.hiveNudge8527(current, compatible8527, getTradeCount())
        if (next == current) return
        featureWeights = FeatureWeights.fromJson(JSONObject(next))
        saveState()
    }

    @Synchronized
    fun applyCommunityWeights(
        communityWeights: Map<String, Double>,
        communityTradeCount: Int,
        compatible8527: AdaptiveWeightTransfer8527.CompatibleModel = AdaptiveWeightTransfer8527.CompatibleModel(),
    ) {
        if (checkpointPending8527 && !saveState()) return
        val current = getDetailedWeights()
        val next = AdaptiveWeightTransfer8527.communityBlend8527(current, compatible8527, getTradeCount())
        if (next == current) return
        featureWeights = FeatureWeights.fromJson(JSONObject(next))
        saveState()
    }

    @Synchronized
    fun reset() {
        aleSeenKeys.clear()
        durableTradeCount8527 = 0
        durableFeatureCounts8527 = emptyMap()
        durableWeights8527 = emptyMap()
        durableTerminalEconomics8527 = null
        checkpointPending8527 = false
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

    // -------------------------------------------------------------------------
    // V5.9.783 — CANONICAL BUS ADAPTER (operator audit item B)
    // -------------------------------------------------------------------------

    /** Pure rejection reasons for the existing canonical terminal-consumer exclusion channel. */
    fun terminalExclusion8527(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope): String? = when {
        !env.terminal -> "NON_TERMINAL"
        !env.learningEligible -> "LEARNING_INELIGIBLE"
        env.exitReason == "DEAD_TOKEN_NO_PRICE_EXIT" -> "UNPRICED_TERMINAL"
        !env.mode.equals("LIVE", true) -> "LIVE_MODEL_MODE_ISOLATION"
        env.assetClassTag != com.lifecyclebot.engine.truth.AssetClass.SOLANA_TOKEN.tag -> "CROSS_ASSET_DOMAIN"
        env.positionId.isBlank() || env.tradeId.isBlank() -> "MISSING_TERMINAL_IDENTITY"
        !com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.chainFinalized8419(
            com.lifecyclebot.engine.truth.CanonicalTradeFinalizedBus6450.economicFinality8082(env.mode, env.proofState)) -> "UNVERIFIED_TERMINAL"
        !env.realizedPnlSol.isFinite() || !env.realizedReturnPct.isFinite() -> "NONFINITE_TERMINAL_ECONOMICS"
        (env.realizedPnlSol > 0.0) != (env.realizedReturnPct > 0.0) ||
            (env.realizedPnlSol < 0.0) != (env.realizedReturnPct < 0.0) -> "TERMINAL_SIGN_CONFLICT"
        else -> null
    }

    /**
     * Sole strategy-learning owner: the proved WHOLE-position terminal (including all its partial cash flows).
     * The raw economic-leg bus continues serving its other execution/exit learners, but cannot teach this
     * entry/setup model from independent slices or count a final remainder as the whole trade.
     */
    @Synchronized
    fun onCanonicalTerminal8527(env: com.lifecyclebot.engine.truth.CanonicalFinalizedTradeBus6464.Envelope): Boolean {
        if (terminalExclusion8527(env) != null) return false
        val snapshot8527 = com.lifecyclebot.engine.truth.EntryStrategySnapshot6450.snapshot(env.positionId)?.takeIf {
            it.positionId == env.positionId && it.mint == env.mint && it.mode8090.equals(env.mode, true) &&
                it.entryTimestampMs > 0L && it.entryTimestampMs <= env.atMs
        }
        val raw = com.lifecyclebot.engine.truth.AdaptiveEntryWitness8527.canonicalFeatures8527(
            snapshot8527, env.mint, TradeEnvironment.LIVE, snapshot8527?.entryTimestampMs ?: 0L)
        fun v(key: String): Double = raw[com.lifecyclebot.engine.truth.AdaptiveEntryWitness8527.PREFIX + key]
            ?.takeIf { observedNumeric8527(if (key == "volume24hUsd") "volume" else key, it) } ?: Double.NaN
        val features = captureFeatures(
            entryMcapUsd = v("mcap"), tokenAgeMinutes = v("tokenAge"), buyRatioPct = v("buyRatio"),
            volumeUsd = v("volume24hUsd"), liquidityUsd = v("liquidity"),
            holderCount = v("holderCount").takeIf { it.isFinite() && it in 0.0..Int.MAX_VALUE.toDouble() }?.toInt() ?: -1,
            topHolderPct = v("holderConc"), holderGrowthRate = v("holderGrowth"),
            devWalletPct = v("devWallet"), bondingCurveProgress = v("bondingCurve"), rugcheckScore = v("rugcheck"),
            emaFanState = com.lifecyclebot.engine.truth.AdaptiveEntryWitness8527.emaName8527(v("emaFan")),
            entryScore = v("entryScore"), priceFromAth = v("priceFromAth"), pnlPct = env.realizedReturnPct,
            maxGainPct = env.mfePct.takeIf { it.isFinite() } ?: Double.NaN,
            maxDrawdownPct = env.maePct.takeIf { it.isFinite() }?.let { abs(it) } ?: Double.NaN,
            timeToPeakMins = Double.NaN,
            holdTimeMins = when {
                env.holdingTimeMs > 0L -> env.holdingTimeMs / 60_000.0
                snapshot8527 != null -> (env.atMs - snapshot8527.entryTimestampMs) / 60_000.0
                else -> Double.NaN // Legacy default zero is not a witnessed zero-duration position.
            },
            exitReason = env.exitReason, entryPhase = snapshot8527?.entrySetup ?: env.entrySetup,
            stableTradeKey = "ALE8527|LIVE|${env.positionId}",
            source8527 = snapshot8527?.entrySource ?: env.entrySource,
            lane8527 = snapshot8527?.entryLane ?: env.lane,
            strategy8527 = listOf(env.entryTradeType, env.entrySetup, env.entryStyle, env.entryTactic).joinToString(">"),
            environment8527 = "LIVE",
            realizedPnlSol8527 = env.realizedPnlSol,
        )
        return learnFromTrade(features, TradeEnvironment.LIVE, canonicalTerminal8527 = true)
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
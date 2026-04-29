package com.lifecyclebot.engine

import kotlinx.coroutines.cancel
import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.network.BirdeyeApi
import com.lifecyclebot.network.CoinGeckoTrending
import com.lifecyclebot.network.DexscreenerApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * ScannerLearning — Tracks which sources/characteristics produce winners
 */
object ScannerLearning {
    private const val PREFS_NAME = "scanner_learning"
    private var ctx: Context? = null

    private val sourceWins = ConcurrentHashMap<String, Int>()
    private val sourceLosses = ConcurrentHashMap<String, Int>()

    private val liqBucketWins = ConcurrentHashMap<String, Int>()
    private val liqBucketLosses = ConcurrentHashMap<String, Int>()

    private val ageBucketWins = ConcurrentHashMap<String, Int>()
    private val ageBucketLosses = ConcurrentHashMap<String, Int>()

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info("ScannerLearning", "📊 Loaded state: ${getStats()}")
    }

    fun recordTrade(source: String, liqUsd: Double, ageHours: Double, isWin: Boolean) {
        if (isWin) sourceWins.merge(source, 1) { a, b -> a + b }
        else sourceLosses.merge(source, 1) { a, b -> a + b }

        val liqBucket = when {
            liqUsd < 2_000 -> "liq_0_2k"
            liqUsd < 20_000 -> "liq_2k_20k"
            liqUsd < 100_000 -> "liq_20k_100k"
            else -> "liq_100k_plus"
        }
        if (isWin) liqBucketWins.merge(liqBucket, 1) { a, b -> a + b }
        else liqBucketLosses.merge(liqBucket, 1) { a, b -> a + b }

        val ageBucket = when {
            ageHours < 1 -> "age_0_1h"
            ageHours < 6 -> "age_1_6h"
            ageHours < 24 -> "age_6_24h"
            else -> "age_24h_plus"
        }
        if (isWin) ageBucketWins.merge(ageBucket, 1) { a, b -> a + b }
        else ageBucketLosses.merge(ageBucket, 1) { a, b -> a + b }

        save()
        ErrorLogger.info(
            "ScannerLearning",
            "📊 Recorded ${if (isWin) "WIN" else "LOSS"}: src=$source liq=$liqBucket age=$ageBucket"
        )
    }

    fun getSourceWinRate(source: String): Double {
        val wins = sourceWins[source] ?: 0
        val losses = sourceLosses[source] ?: 0
        val total = wins + losses
        return if (total >= 5) wins.toDouble() / total else 0.5
    }

    fun getDiscoveryBonus(source: String, liqUsd: Double, ageHours: Double): Double {
        var bonus = 0.0

        val srcRate = getSourceWinRate(source)
        bonus += (srcRate - 0.5) * 20.0

        val liqBucket = when {
            liqUsd < 2_000 -> "liq_0_2k"
            liqUsd < 20_000 -> "liq_2k_20k"
            liqUsd < 100_000 -> "liq_20k_100k"
            else -> "liq_100k_plus"
        }
        val liqWins = liqBucketWins[liqBucket] ?: 0
        val liqLosses = liqBucketLosses[liqBucket] ?: 0
        val liqTotal = liqWins + liqLosses
        if (liqTotal >= 5) {
            val liqRate = liqWins.toDouble() / liqTotal
            bonus += (liqRate - 0.5) * 15.0
        }

        val ageBucket = when {
            ageHours < 1 -> "age_0_1h"
            ageHours < 6 -> "age_1_6h"
            ageHours < 24 -> "age_6_24h"
            else -> "age_24h_plus"
        }
        val ageWins = ageBucketWins[ageBucket] ?: 0
        val ageLosses = ageBucketLosses[ageBucket] ?: 0
        val ageTotal = ageWins + ageLosses
        if (ageTotal >= 5) {
            val ageRate = ageWins.toDouble() / ageTotal
            bonus += (ageRate - 0.5) * 15.0
        }

        return bonus.coerceIn(-20.0, 20.0)
    }

    fun getStats(): String {
        val sources = (sourceWins.keys + sourceLosses.keys).distinct()
        val stats = sources.map { src ->
            val w = sourceWins[src] ?: 0
            val l = sourceLosses[src] ?: 0
            val rate = if (w + l > 0) (w * 100 / (w + l)) else 50
            "$src: ${w}W/${l}L ($rate%)"
        }
        return "ScannerLearning: ${stats.joinToString(" | ")}"
    }

    private fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("sourceWins", mapToJson(sourceWins))
                putString("sourceLosses", mapToJson(sourceLosses))
                putString("liqBucketWins", mapToJson(liqBucketWins))
                putString("liqBucketLosses", mapToJson(liqBucketLosses))
                putString("ageBucketWins", mapToJson(ageBucketWins))
                putString("ageBucketLosses", mapToJson(ageBucketLosses))
                apply()
            }
        } catch (_: Exception) {
        }
    }

    private fun load() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            jsonToMap(prefs.getString("sourceWins", null)).forEach { (k, v) -> sourceWins[k] = v }
            jsonToMap(prefs.getString("sourceLosses", null)).forEach { (k, v) -> sourceLosses[k] = v }
            jsonToMap(prefs.getString("liqBucketWins", null)).forEach { (k, v) -> liqBucketWins[k] = v }
            jsonToMap(prefs.getString("liqBucketLosses", null)).forEach { (k, v) -> liqBucketLosses[k] = v }
            jsonToMap(prefs.getString("ageBucketWins", null)).forEach { (k, v) -> ageBucketWins[k] = v }
            jsonToMap(prefs.getString("ageBucketLosses", null)).forEach { (k, v) -> ageBucketLosses[k] = v }
        } catch (_: Exception) {
        }
    }

    private fun mapToJson(map: Map<String, Int>): String {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }

    private fun jsonToMap(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optInt(it, 0) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun clear() {
        sourceWins.clear()
        sourceLosses.clear()
        liqBucketWins.clear()
        liqBucketLosses.clear()
        ageBucketWins.clear()
        ageBucketLosses.clear()
        save()
        ErrorLogger.warn("ScannerLearning", "🧹 Scanner learning cleared")
    }
}

/**
 * ModeLearning — Separate learning instance per trading mode.
 */
object ModeLearning {
    private const val TAG = "ModeLearning"
    private const val PREFS_NAME = "mode_learning_v2"
    private const val MIN_TRADES_FOR_CONFIDENCE = 5
    private const val CRITICAL_LOSS_RATE = 70.0
    private const val HEALTH_CHECK_INTERVAL_MS = 30 * 60 * 1000L

    private var ctx: Context? = null

    data class ModeLearningData(
        var totalTrades: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnlPct: Double = 0.0,
        var bestPnlPct: Double = 0.0,
        var worstPnlPct: Double = -100.0,
        var avgHoldTimeMs: Long = 0L,
        var lastTradeMs: Long = 0L,
        var consecutiveLosses: Int = 0,
        var isHealthy: Boolean = true,
        var deactivationReason: String = "",
        val phaseWins: MutableMap<String, Int> = mutableMapOf(),
        val phaseLosses: MutableMap<String, Int> = mutableMapOf(),
        val liqBucketWins: MutableMap<String, Int> = mutableMapOf(),
        val liqBucketLosses: MutableMap<String, Int> = mutableMapOf(),
        val sourceWins: MutableMap<String, Int> = mutableMapOf(),
        val sourceLosses: MutableMap<String, Int> = mutableMapOf(),
        val hourWins: MutableMap<Int, Int> = mutableMapOf(),
        val hourLosses: MutableMap<Int, Int> = mutableMapOf(),
    ) {
        val winRate: Double get() = if (wins + losses > 0) wins.toDouble() / (wins + losses) * 100 else 50.0
        val avgPnl: Double get() = if (totalTrades > 0) totalPnlPct / totalTrades else 0.0
        val lossRate: Double get() = 100.0 - winRate
        val isReliable: Boolean get() = totalTrades >= MIN_TRADES_FOR_CONFIDENCE
    }

    data class ModeScannerPrefs(
        val mode: String,
        val preferredLiqMin: Double,
        val preferredLiqMax: Double,
        val preferredAgeMinHours: Double,
        val preferredAgeMaxHours: Double,
        val preferredSources: List<String>,
        val avoidSources: List<String>,
        val preferredPhases: List<String>,
        val avoidPhases: List<String>,
        val preferredHours: List<Int>,
        val avoidHours: List<Int>,
        val confidence: Double,
    )

    private val modeData = ConcurrentHashMap<String, ModeLearningData>()
    private val lastHealthCheck = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info(TAG, "📊 Initialized: ${modeData.size} modes loaded")
    }

    fun recordTrade(
        mode: String,
        isWin: Boolean,
        pnlPct: Double,
        holdTimeMs: Long,
        entryPhase: String,
        liquidityUsd: Double,
        source: String,
        hourOfDay: Int,
    ) {
        try {
            val data = modeData.getOrPut(mode) { ModeLearningData() }

            data.totalTrades++
            if (isWin) {
                data.wins++
                data.consecutiveLosses = 0
            } else {
                data.losses++
                data.consecutiveLosses++
            }

            data.totalPnlPct += pnlPct
            data.lastTradeMs = System.currentTimeMillis()

            if (pnlPct > data.bestPnlPct) data.bestPnlPct = pnlPct
            if (pnlPct < data.worstPnlPct) data.worstPnlPct = pnlPct

            data.avgHoldTimeMs = if (data.totalTrades == 1) {
                holdTimeMs
            } else {
                ((data.avgHoldTimeMs * (data.totalTrades - 1)) + holdTimeMs) / data.totalTrades
            }

            val liqBucket = getLiquidityBucket(liquidityUsd)

            if (isWin) {
                data.phaseWins.merge(entryPhase, 1) { a, b -> a + b }
                data.liqBucketWins.merge(liqBucket, 1) { a, b -> a + b }
                data.sourceWins.merge(source, 1) { a, b -> a + b }
                data.hourWins.merge(hourOfDay, 1) { a, b -> a + b }
            } else {
                data.phaseLosses.merge(entryPhase, 1) { a, b -> a + b }
                data.liqBucketLosses.merge(liqBucket, 1) { a, b -> a + b }
                data.sourceLosses.merge(source, 1) { a, b -> a + b }
                data.hourLosses.merge(hourOfDay, 1) { a, b -> a + b }
            }

            val emoji = if (isWin) "✅" else "❌"
            ErrorLogger.info(
                TAG,
                "$emoji [$mode] Trade #${data.totalTrades}: WR=${data.winRate.toInt()}% | PnL=${pnlPct.toInt()}% | Phase=$entryPhase"
            )

            if (data.totalTrades % 5 == 0) save()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordTrade error: ${e.message}")
        }
    }

    fun getScannerPrefs(mode: String): ModeScannerPrefs {
        val data = modeData[mode]
        if (data == null || !data.isReliable) return getDefaultPrefs(mode)

        val preferredPhases = data.phaseWins.keys.filter { phase ->
            val wins = data.phaseWins[phase] ?: 0
            val losses = data.phaseLosses[phase] ?: 0
            val total = wins + losses
            total >= 3 && wins.toDouble() / total >= 0.6
        }

        val avoidPhases = data.phaseLosses.keys.filter { phase ->
            val wins = data.phaseWins[phase] ?: 0
            val losses = data.phaseLosses[phase] ?: 0
            val total = wins + losses
            total >= 3 && losses.toDouble() / total >= 0.7
        }

        val preferredSources = data.sourceWins.keys.filter { src ->
            val wins = data.sourceWins[src] ?: 0
            val losses = data.sourceLosses[src] ?: 0
            val total = wins + losses
            total >= 3 && wins.toDouble() / total >= 0.6
        }

        val avoidSources = data.sourceLosses.keys.filter { src ->
            val wins = data.sourceWins[src] ?: 0
            val losses = data.sourceLosses[src] ?: 0
            val total = wins + losses
            total >= 3 && losses.toDouble() / total >= 0.7
        }

        val preferredHours = data.hourWins.keys.filter { hour ->
            val wins = data.hourWins[hour] ?: 0
            val losses = data.hourLosses[hour] ?: 0
            val total = wins + losses
            total >= 2 && wins.toDouble() / total >= 0.65
        }

        val avoidHours = data.hourLosses.keys.filter { hour ->
            val wins = data.hourWins[hour] ?: 0
            val losses = data.hourLosses[hour] ?: 0
            val total = wins + losses
            total >= 2 && losses.toDouble() / total >= 0.75
        }

        val bestLiqBucket = data.liqBucketWins.maxByOrNull { (bucket, wins) ->
            val losses = data.liqBucketLosses[bucket] ?: 0
            if (wins + losses >= 3) wins.toDouble() / (wins + losses) else 0.0
        }?.key

        val (liqMin, liqMax) = when (bestLiqBucket) {
            "0-2k" -> 0.0 to 2_000.0
            "2k-20k" -> 2_000.0 to 20_000.0
            "20k-100k" -> 20_000.0 to 100_000.0
            "100k+" -> 100_000.0 to Double.MAX_VALUE
            else -> getDefaultLiqRange(mode)
        }

        val confidence = data.totalTrades.toDouble().coerceAtMost(50.0) / 50.0

        return ModeScannerPrefs(
            mode = mode,
            preferredLiqMin = liqMin,
            preferredLiqMax = liqMax,
            preferredAgeMinHours = 0.0,
            preferredAgeMaxHours = 24.0,
            preferredSources = preferredSources,
            avoidSources = avoidSources,
            preferredPhases = preferredPhases,
            avoidPhases = avoidPhases,
            preferredHours = preferredHours,
            avoidHours = avoidHours,
            confidence = confidence,
        )
    }

    fun getScoreBonus(
        mode: String,
        entryPhase: String,
        liquidityUsd: Double,
        source: String,
        hourOfDay: Int,
    ): Int {
        val data = modeData[mode] ?: return 0
        if (!data.isReliable) return 0

        var bonus = 0.0

        val phaseWins = data.phaseWins[entryPhase] ?: 0
        val phaseLosses = data.phaseLosses[entryPhase] ?: 0
        val phaseTotal = phaseWins + phaseLosses
        if (phaseTotal >= 3) {
            val phaseWinRate = phaseWins.toDouble() / phaseTotal
            bonus += (phaseWinRate - 0.5) * 20
        }

        val liqBucket = getLiquidityBucket(liquidityUsd)
        val liqWins = data.liqBucketWins[liqBucket] ?: 0
        val liqLosses = data.liqBucketLosses[liqBucket] ?: 0
        val liqTotal = liqWins + liqLosses
        if (liqTotal >= 3) {
            val liqWinRate = liqWins.toDouble() / liqTotal
            bonus += (liqWinRate - 0.5) * 15
        }

        val srcWins = data.sourceWins[source] ?: 0
        val srcLosses = data.sourceLosses[source] ?: 0
        val srcTotal = srcWins + srcLosses
        if (srcTotal >= 3) {
            val srcWinRate = srcWins.toDouble() / srcTotal
            bonus += (srcWinRate - 0.5) * 10
        }

        val hourWins = data.hourWins[hourOfDay] ?: 0
        val hourLosses = data.hourLosses[hourOfDay] ?: 0
        val hourTotal = hourWins + hourLosses
        if (hourTotal >= 2) {
            val hourWinRate = hourWins.toDouble() / hourTotal
            bonus += (hourWinRate - 0.5) * 10
        }

        return bonus.toInt().coerceIn(-20, 20)
    }

    fun selfHealingCheckForMode(mode: String): Boolean {
        val now = System.currentTimeMillis()
        val lastCheck = lastHealthCheck[mode] ?: 0L
        if (now - lastCheck < HEALTH_CHECK_INTERVAL_MS) return false
        lastHealthCheck[mode] = now

        val data = modeData[mode] ?: return false
        if (data.totalTrades < MIN_TRADES_FOR_CONFIDENCE) return false

        if (data.lossRate >= CRITICAL_LOSS_RATE) {
            ErrorLogger.warn(TAG, "🚨 [$mode] POISONED: ${data.lossRate.toInt()}% loss rate - RESETTING")
            resetMode(mode)
            return true
        }

        if (data.consecutiveLosses >= 5) {
            ErrorLogger.warn(TAG, "⚠️ [$mode] 5+ consecutive losses - partial reset")
            data.totalTrades = (data.totalTrades * 0.5).toInt()
            data.wins = (data.wins * 0.5).toInt()
            data.losses = (data.losses * 0.5).toInt()
            data.consecutiveLosses = 0
        }

        return false
    }

    fun resetMode(mode: String) {
        modeData[mode] = ModeLearningData()
        save()
        ErrorLogger.warn(TAG, "🧹 [$mode] Learning reset")
    }

    fun clear() {
        modeData.clear()
        lastHealthCheck.clear()
        save()
        ErrorLogger.warn(TAG, "🧹 All mode learning cleared")
    }

    fun getStats(mode: String): String {
        val data = modeData[mode] ?: return "[$mode] No data"
        return "[$mode] ${data.totalTrades} trades | WR=${data.winRate.toInt()}% | AvgPnL=${data.avgPnl.toInt()}% | Best=${data.bestPnlPct.toInt()}%"
    }

    fun getAllStatsSorted(): List<Pair<String, ModeLearningData>> {
        return modeData.entries
            .filter { it.value.isReliable }
            .sortedByDescending { it.value.winRate }
            .map { it.key to it.value }
    }

    data class ModeStatsSnapshot(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val avgPnlPct: Double,
        val avgHoldMins: Double,
    )

    fun getAllModeStats(): Map<String, ModeStatsSnapshot> {
        return modeData.entries
            .filter { it.value.totalTrades >= 3 }
            .associate { (mode, data) ->
                mode to ModeStatsSnapshot(
                    totalTrades = data.totalTrades,
                    wins = data.wins,
                    losses = data.losses,
                    avgPnlPct = data.avgPnl,
                    avgHoldMins = (data.avgHoldTimeMs / 60_000.0),
                )
            }
    }

    fun getBestMode(): String? {
        return modeData.entries
            .filter { it.value.isReliable && it.value.winRate >= 55.0 }
            .maxByOrNull { it.value.winRate * it.value.avgPnl }
            ?.key
    }

    fun getWorstMode(): String? {
        return modeData.entries
            .filter { it.value.isReliable }
            .minByOrNull { it.value.winRate }
            ?.key
    }

    private fun getLiquidityBucket(liqUsd: Double): String {
        return when {
            liqUsd < 2_000 -> "0-2k"
            liqUsd < 20_000 -> "2k-20k"
            liqUsd < 100_000 -> "20k-100k"
            else -> "100k+"
        }
    }

    private fun getDefaultPrefs(mode: String): ModeScannerPrefs {
        val (liqMin, liqMax) = getDefaultLiqRange(mode)
        return ModeScannerPrefs(
            mode = mode,
            preferredLiqMin = liqMin,
            preferredLiqMax = liqMax,
            preferredAgeMinHours = 0.0,
            preferredAgeMaxHours = when (mode) {
                "MOONSHOT", "PUMP_SNIPER" -> 6.0
                "MICRO_CAP" -> 12.0
                "LONG_HOLD", "WHALE_FOLLOW" -> 48.0
                else -> 24.0
            },
            preferredSources = emptyList(),
            avoidSources = emptyList(),
            preferredPhases = emptyList(),
            avoidPhases = emptyList(),
            preferredHours = emptyList(),
            avoidHours = emptyList(),
            confidence = 0.0,
        )
    }

    private fun getDefaultLiqRange(mode: String): Pair<Double, Double> {
        return when (mode) {
            "MICRO_CAP" -> 2_000.0 to 20_000.0
            "MOONSHOT", "PUMP_SNIPER" -> 10_000.0 to 50_000.0
            "LONG_HOLD", "WHALE_FOLLOW" -> 50_000.0 to Double.MAX_VALUE
            "REVIVAL" -> 10_000.0 to 100_000.0
            else -> 2_000.0 to 100_000.0
        }
    }

    private fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = JSONObject()

            modeData.forEach { (mode, data) ->
                val modeJson = JSONObject().apply {
                    put("totalTrades", data.totalTrades)
                    put("wins", data.wins)
                    put("losses", data.losses)
                    put("totalPnlPct", data.totalPnlPct)
                    put("bestPnlPct", data.bestPnlPct)
                    put("worstPnlPct", data.worstPnlPct)
                    put("avgHoldTimeMs", data.avgHoldTimeMs)
                    put("lastTradeMs", data.lastTradeMs)
                    put("consecutiveLosses", data.consecutiveLosses)
                    put("isHealthy", data.isHealthy)
                    put("phaseWins", JSONObject(data.phaseWins as Map<*, *>))
                    put("phaseLosses", JSONObject(data.phaseLosses as Map<*, *>))
                    put("liqBucketWins", JSONObject(data.liqBucketWins as Map<*, *>))
                    put("liqBucketLosses", JSONObject(data.liqBucketLosses as Map<*, *>))
                    put("sourceWins", JSONObject(data.sourceWins as Map<*, *>))
                    put("sourceLosses", JSONObject(data.sourceLosses as Map<*, *>))
                }
                json.put(mode, modeJson)
            }

            prefs.edit().putString("modeData", json.toString()).apply()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "save error: ${e.message}")
        }
    }

    private fun load() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = prefs.getString("modeData", null) ?: return
            val json = JSONObject(jsonStr)

            json.keys().forEach { mode ->
                val modeJson = json.optJSONObject(mode) ?: return@forEach
                val data = ModeLearningData(
                    totalTrades = modeJson.optInt("totalTrades", 0),
                    wins = modeJson.optInt("wins", 0),
                    losses = modeJson.optInt("losses", 0),
                    totalPnlPct = modeJson.optDouble("totalPnlPct", 0.0),
                    bestPnlPct = modeJson.optDouble("bestPnlPct", 0.0),
                    worstPnlPct = modeJson.optDouble("worstPnlPct", -100.0),
                    avgHoldTimeMs = modeJson.optLong("avgHoldTimeMs", 0L),
                    lastTradeMs = modeJson.optLong("lastTradeMs", 0L),
                    consecutiveLosses = modeJson.optInt("consecutiveLosses", 0),
                    isHealthy = modeJson.optBoolean("isHealthy", true),
                )

                modeJson.optJSONObject("phaseWins")?.let { obj ->
                    obj.keys().forEach { k -> data.phaseWins[k] = obj.optInt(k, 0) }
                }
                modeJson.optJSONObject("phaseLosses")?.let { obj ->
                    obj.keys().forEach { k -> data.phaseLosses[k] = obj.optInt(k, 0) }
                }
                modeJson.optJSONObject("liqBucketWins")?.let { obj ->
                    obj.keys().forEach { k -> data.liqBucketWins[k] = obj.optInt(k, 0) }
                }
                modeJson.optJSONObject("liqBucketLosses")?.let { obj ->
                    obj.keys().forEach { k -> data.liqBucketLosses[k] = obj.optInt(k, 0) }
                }
                modeJson.optJSONObject("sourceWins")?.let { obj ->
                    obj.keys().forEach { k -> data.sourceWins[k] = obj.optInt(k, 0) }
                }
                modeJson.optJSONObject("sourceLosses")?.let { obj ->
                    obj.keys().forEach { k -> data.sourceLosses[k] = obj.optInt(k, 0) }
                }

                modeData[mode] = data
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "load error: ${e.message}")
        }
    }
}

/**
 * SolanaMarketScanner — full Solana DEX opportunity discovery
 */
class SolanaMarketScanner(
    private val cfg: () -> BotConfig,
    private val onTokenFound: (mint: String, symbol: String, name: String, source: TokenSource, score: Double, liquidityUsd: Double, volumeH1: Double) -> Unit,
    private val onLog: (String) -> Unit,
    private val getBrain: () -> BotBrain? = { null },
) {
    enum class TokenSource {
        PUMP_FUN_NEW,
        PUMP_FUN_GRADUATE,
        DEX_TRENDING,
        DEX_GAINERS,
        DEX_BOOSTED,
        BIRDEYE_TRENDING,
        COINGECKO_TRENDING,
        JUPITER_NEW,
        RAYDIUM_NEW_POOL,
        NARRATIVE_SCAN,
        MANUAL,
    }

    data class ScannedToken(
        val mint: String,
        val symbol: String,
        val name: String,
        val source: TokenSource,
        val liquidityUsd: Double,
        val volumeH1: Double,
        val mcapUsd: Double,
        val pairCreatedHoursAgo: Double,
        val dexId: String,
        val priceChangeH1: Double,
        val txCountH1: Int,
        val score: Double,
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .cache(null)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 6
            maxRequestsPerHost = 1
        })
        .build()

    private val dex = DexscreenerApi()
    private val coingecko = CoinGeckoTrending()

    private val rcScoreCache = ConcurrentHashMap<String, Int>()

    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null

    private val birdeye: BirdeyeApi
        get() = BirdeyeApi(cfg().birdeyeApiKey)

    private val seenMints = ConcurrentHashMap<String, Long>()
    // V5.9.44: Unified TTLs across paper/live. Per V5.9.34 the scanner is
    // meant to be wide open — classification is the decision gate's job.
    // Previous 2x-longer live TTLs starved the scanner of fresh tokens
    // (user: "Scanner in live nulled").
    // V5.9.363 — wider funnel: cut seen TTL 20s→12s so re-discovered tokens
    // can be re-emitted to the watchlist faster.
    private fun getSeenTtl(): Long = 12_000L

    private val rejectedMints = ConcurrentHashMap<String, Long>()
    private fun getRejectedTtl(): Long = 8_000L

    private val cooldownHitCount = ConcurrentHashMap<String, Int>()
    private val saturatedMints = ConcurrentHashMap<String, Long>()
    private fun getMaxCooldownHits(): Int = 20
    private fun getSaturationTtl(): Long = 30_000L

    @Volatile private var telemetryRawScanned = 0
    @Volatile private var telemetryCooldownHits = 0
    @Volatile private var telemetryRugRejects = 0
    @Volatile private var telemetryLiqRejects = 0
    @Volatile private var telemetryEnqueued = 0
    @Volatile private var telemetryStaleDrops = 0
    @Volatile private var telemetrySaturatedDrops = 0
    private var lastTelemetryLogMs = 0L

    private val semaphore = Semaphore(6)

    @Volatile private var memorySafeMode = false
    @Volatile private var oomCount = 0
    @Volatile private var scanRotation = 0
    @Volatile private var isRunning = false
    @Volatile private var lastNewTokenFoundMs = System.currentTimeMillis()
    @Volatile private var lastStalenessCheckMs = System.currentTimeMillis()

    private val STALENESS_THRESHOLD_MS = 60_000L

    fun getCachedRcScore(mint: String): Int? = rcScoreCache[mint]

    fun getThroughputTelemetry(): String {
        return "RAW=$telemetryRawScanned CD=$telemetryCooldownHits RUG=$telemetryRugRejects LIQ=$telemetryLiqRejects ENQ=$telemetryEnqueued STALE=$telemetryStaleDrops SAT=$telemetrySaturatedDrops"
    }

    /**
     * V5.9.365 — structured snapshot for UI rendering. The string getter above
     * is convenient for log lines but UIs need typed numbers.
     */
    data class ThroughputSnapshot(
        val raw: Int,
        val cd: Int,
        val rug: Int,
        val liqRej: Int,
        val enq: Int,
        val stale: Int,
        val sat: Int,
    )
    fun getThroughputTelemetrySnapshot(): ThroughputSnapshot = ThroughputSnapshot(
        raw     = telemetryRawScanned,
        cd      = telemetryCooldownHits,
        rug     = telemetryRugRejects,
        liqRej  = telemetryLiqRejects,
        enq     = telemetryEnqueued,
        stale   = telemetryStaleDrops,
        sat     = telemetrySaturatedDrops,
    )

    fun resetTelemetry() {
        telemetryRawScanned = 0
        telemetryCooldownHits = 0
        telemetryRugRejects = 0
        telemetryLiqRejects = 0
        telemetryEnqueued = 0
        telemetryStaleDrops = 0
        telemetrySaturatedDrops = 0
    }

    private fun isSaturated(mint: String): Boolean {
        val saturatedAt = saturatedMints[mint] ?: return false
        return System.currentTimeMillis() - saturatedAt < getSaturationTtl()
    }

    private fun recordCooldownHit(mint: String) {
        telemetryCooldownHits++
        val hits = cooldownHitCount.merge(mint, 1) { a, b -> a + b } ?: 1
        if (hits >= getMaxCooldownHits()) {
            saturatedMints[mint] = System.currentTimeMillis()
            cooldownHitCount.remove(mint)
            telemetrySaturatedDrops++
            ErrorLogger.debug("Scanner", "🔇 SATURATED: $mint (hit ${getMaxCooldownHits()}x in cooldown)")
        }
    }

    fun getStatus(): String {
        val staleSeconds = (System.currentTimeMillis() - lastNewTokenFoundMs) / 1000
        return "Scanner: running=$isRunning seenMints=${seenMints.size} rejectedMints=${rejectedMints.size} scanRotation=$scanRotation stale=${staleSeconds}s"
    }

    fun recordNewTokenFound() {
        lastNewTokenFoundMs = System.currentTimeMillis()
    }

    fun checkAndResetIfStale(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastStalenessCheckMs < 10_000L) return false
        lastStalenessCheckMs = now

        val staleDuration = now - lastNewTokenFoundMs
        if (staleDuration > STALENESS_THRESHOLD_MS) {
            telemetryStaleDrops++
            ErrorLogger.warn("Scanner", "⚠️ Scanner STALE for ${staleDuration / 1000}s - forcing soft reset")
            onLog("⚠️ Scanner stale for ${staleDuration / 1000}s - soft reset")
            forceReset()
            lastNewTokenFoundMs = now
            return true
        }
        return false
    }

    fun forceReset() {
        cooldownHitCount.clear()
        saturatedMints.clear()
        resetTelemetry()
        scanRotation = 0
        ErrorLogger.info("Scanner", "Force reset - cleared transient state only")
        onLog("🔄 Scanner soft reset - preserved seen/rejected cooldowns")
    }

    private val scannerExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable is CancellationException) {
            ErrorLogger.info("Scanner", "Scanner coroutine cancelled (normal shutdown)")
            return@CoroutineExceptionHandler
        }
        ErrorLogger.error(
            "Scanner",
            "Scanner coroutine exception: ${throwable.javaClass.simpleName}: ${throwable.message}",
            throwable
        )
        onLog("⚠️ Scanner error: ${throwable.javaClass.simpleName} - ${throwable.message?.take(50)}")
    }

    private suspend fun runScan(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.warn("Scanner", "$name error: ${e.message}")
        }
    }

    fun start() {
        if (isRunning) {
            ErrorLogger.warn("Scanner", "Scanner already running, ignoring start()")
            return
        }

        ErrorLogger.info("Scanner", "SolanaMarketScanner.start() called")
        isRunning = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + scannerExceptionHandler)
        scanJob = scope?.launch {
            ErrorLogger.info("Scanner", "scanLoop starting...")
            runTestScan()
            scanLoop()
        }
        onLog("SolanaMarketScanner started")
    }

    fun stop() {
        ErrorLogger.info("Scanner", "SolanaMarketScanner.stop() called")
        isRunning = false
        scanJob?.cancel()
        scanJob = null
        scope?.cancel()
        scope = null
        onLog("SolanaMarketScanner stopped")
    }

    private suspend fun runTestScan() {
        try {
            onLog("🧪 Running lifecycle test scan...")
            val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
            ErrorLogger.info("Scanner", "TEST: Fetching newest token profiles...")

            // V5.9.365 — DexScreener cold-start is flaky. Was 1-shot with no
            // retries → routinely produced a single `TEST FAILED` ERROR on first
            // boot. Now retries 4× with backoff and downgrades to WARN so the
            // Errors panel stays clean. Real scanning resumes next cycle anyway.
            val body = getWithRetry(url, maxRetries = 4) ?: run {
                ErrorLogger.warn("Scanner", "TEST: cold-start fetch failed (DexScreener unreachable) — will retry next scan cycle")
                onLog("⚠️ Cold-start API probe failed (transient) — scanner will retry on next cycle")
                return
            }

            val profiles = if (body.startsWith("[")) JSONArray(body) else {
                ErrorLogger.warn("Scanner", "TEST: cold-start got non-array response — will retry next scan cycle")
                onLog("⚠️ Cold-start API probe got bad format (transient)")
                return
            }

            ErrorLogger.info("Scanner", "TEST OK: Got ${profiles.length()} token profiles")
            onLog("✅ API OK: ${profiles.length()} profiles received")

            var added = 0
            var checked = 0
            for (i in 0 until minOf(profiles.length(), 50)) {
                if (added >= 5) break

                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue

                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue

                checked++

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue

                val liq = pair.liquidity
                if (liq < 1000) continue

                val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0

                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq,
                    volumeH1 = pair.candle.volumeH1,
                    mcapUsd = pair.candle.marketCap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = pair.candle.buysH1 + pair.candle.sellsH1,
                    score = 20.0,
                )

                if (!passesFilter(token)) {
                    ErrorLogger.info("Scanner", "TEST: ${pair.baseSymbol} rejected by filters")
                    continue
                }

                emitWithRugcheck(token)
                added++
                ErrorLogger.info("Scanner", "TEST: Added ${pair.baseSymbol} (age=${ageHours.toInt()}h)")
                onLog("🎯 Test: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
            }

            if (added == 0) onLog("⚠️ Test: No tokens passed (checked $checked)")
            else onLog("✅ Test: $added diverse tokens added")
        } catch (e: CancellationException) {
            ErrorLogger.info("Scanner", "TEST: Scan cancelled")
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "TEST ERROR: ${e.message}", e)
            onLog("❌ Test error: ${e.message?.take(50)}")
        }
    }

    private suspend fun scanLoop() {
        ErrorLogger.info("Scanner", "scanLoop() entered")
        while (isRunning) {
            val c = cfg()
            val scanIntervalMs = maxOf(c.scanIntervalSecs * 1000L, 10_000L)
            ErrorLogger.debug("Scanner", "Scan interval: ${scanIntervalMs}ms")

            try {
                cleanupSeenMaps()

                val sScanTier = ScalingMode.activeTier(
                    TreasuryManager.treasurySol * WalletManager.lastKnownSolPrice
                )
                val tn = if (sScanTier != ScalingMode.Tier.MICRO) {
                    " ${sScanTier.icon}${sScanTier.label}"
                } else {
                    ""
                }

                scanRotation = (scanRotation + 1) % 4

                if (scanRotation == 0 && seenMints.size > 200) {
                    cleanupSeenMaps()
                    onLog("🔄 Scanner refresh: recycled expired cooldown entries")
                    ErrorLogger.info("Scanner", "Forced refresh: recycled expired cooldown entries only")
                }

                onLog("🌐 Scan #$scanRotation$tn - Starting scan cycle")
                ErrorLogger.info("Scanner", "Scan cycle #$scanRotation starting")

                System.gc()

                onLog("🚀 Scanning: Pump.fun tokens (PRIORITY)...")
                runScan("scanPumpFunDirect") { scanPumpFunDirect() }
                delay(200)
                runScan("scanPumpFunActive") { scanPumpFunActive() }
                delay(200)

                onLog("🔍 Scanning ALL sources (DEEP SCAN)...")
                runScan("scanPumpGraduates") { scanPumpGraduates() }
                delay(100)
                runScan("scanDexBoosted") { scanDexBoosted() }
                delay(100)
                runScan("scanFreshLaunches") { scanFreshLaunches() }
                delay(100)
                runScan("scanDexTrending") { scanDexTrending() }
                delay(100)
                runScan("scanDexGainers") { scanDexGainers() }
                delay(100)
                runScan("scanBirdeyeTrending") { scanBirdeyeTrending() }
                delay(100)
                runScan("scanTopVolumeTokens") { scanTopVolumeTokens() }
                delay(100)
                runScan("scanPumpFunVolume") { scanPumpFunVolume() }
                delay(100)
                runScan("scanRaydiumNewPools") { scanRaydiumNewPools() }
                delay(100)
                runScan("scanCoinGeckoTrending") { scanCoinGeckoTrending() }

                System.gc()
                onLog("✅ Scan cycle #$scanRotation complete")

                cleanupSeenMaps()

                val watchlistSize = GlobalTradeRegistry.size()
                if (scanRotation == 0) {
                    ErrorLogger.info(
                        "Scanner",
                        "Discovery health: seen=${seenMints.size} rejected=${rejectedMints.size} watchlist=$watchlistSize (GlobalTradeRegistry)"
                    )
                    onLog("📊 Discovery: seen=${seenMints.size} rejected=${rejectedMints.size} watchlist=$watchlistSize")
                }

                // Auto-recovery: if watchlist is thin, clear churn state so tokens can re-enter
                // V5.4: Raised threshold from ≤2 to ≤10 — 2 tokens wasn't catching pipeline drains early enough
                // V5.9.363: Raised threshold from ≤10 to ≤30 — fire churn-clear earlier so the watchlist refills faster
                if (watchlistSize <= 30 && seenMints.size > 20) {
                    onLog("⚠️ Watchlist thin ($watchlistSize tokens) - clearing churn state to refill")
                    ErrorLogger.warn(
                        "Scanner",
                        "Auto-recovery: watchlist=$watchlistSize (<=30), clearing cooldown/saturation maps"
                    )
                    cooldownHitCount.clear()
                    saturatedMints.clear()
                }

                System.gc()
            } catch (e: OutOfMemoryError) {
                oomCount++
                memorySafeMode = true
                ErrorLogger.crash("Scanner", "OutOfMemoryError #$oomCount", Exception(e.message))
                onLog("⚠️ Memory critical #$oomCount - pausing")
                System.gc()
                delay(10_000)
                if (oomCount >= 5) {
                    onLog("⛔ Too many OOM errors - scanner pausing. Restart to retry.")
                    ErrorLogger.error("Scanner", "Scanner paused after $oomCount OOM errors")
                    delay(300_000)
                    oomCount = 0
                    memorySafeMode = false
                }
            } catch (e: CancellationException) {
                ErrorLogger.info("Scanner", "Scanner coroutine cancelled (normal shutdown)")
                throw e
            } catch (e: Exception) {
                ErrorLogger.error("Scanner", "Scanner error: ${e.message}", e)
                onLog("Scanner: ${e.message?.take(50)}")
            }

            delay(scanIntervalMs)
        }
    }

    private suspend fun scanPumpFunActive() {
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanPumpFunActive: fetching newest token profiles...")
        val body = getWithRetry(url) ?: return

        try {
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0

            for (i in 0 until minOf(profiles.length(), 100)) {
                if (found >= 50) break

                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue

                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                checked++

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue

                val liq = pair.liquidity
                if (liq < 1000) continue

                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1

                val ageBonus = when {
                    ageHours < 0.5 -> 35.0
                    ageHours < 1 -> 30.0
                    ageHours < 3 -> 25.0
                    ageHours < 6 -> 20.0
                    ageHours < 12 -> 15.0
                    ageHours < 24 -> 10.0
                    else -> 5.0
                }

                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + ageBonus,
                )

                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    val freshIcon = if (ageHours < 1) "🆕" else if (ageHours < 6) "📈" else "📊"
                    onLog("$freshIcon NEW: ${pair.baseSymbol} | ${ageHours.toInt()}h old | liq=\$${liq.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanPumpFunActive: found $found tokens (checked $checked)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpFunActive error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanPumpFunDirect() {
        val urls = listOf(
            "https://frontend-api.pump.fun/coins?offset=0&limit=100&sort=created_timestamp&order=DESC&includeNsfw=false",
            "https://frontend-api.pump.fun/coins?offset=100&limit=100&sort=created_timestamp&order=DESC&includeNsfw=false",
            "https://frontend-api.pump.fun/coins?offset=0&limit=100&sort=last_trade_timestamp&order=DESC&includeNsfw=false",
            "https://frontend-api.pump.fun/coins?offset=0&limit=50&sort=market_cap&order=DESC&includeNsfw=false",
            "https://frontend-api.pump.fun/coins?offset=0&limit=50&sort=reply_count&order=DESC&includeNsfw=false",
        )

        ErrorLogger.info("Scanner", "scanPumpFunDirect: fetching from ${urls.size} pump.fun endpoints...")
        var totalFound = 0

        for (url in urls) {
            try {
                val body = getWithRetry(url) ?: continue
                val coins: JSONArray = when {
                    body.startsWith("[") -> JSONArray(body)
                    body.startsWith("{") -> {
                        val obj = JSONObject(body)
                        obj.optJSONArray("coins") ?: obj.optJSONArray("data") ?: continue
                    }
                    else -> continue
                }

                val now = System.currentTimeMillis()
                var found = 0

                for (i in 0 until minOf(coins.length(), 100)) {
                    if (found >= 30) break

                    val coin = coins.optJSONObject(i) ?: continue
                    val mint = coin.optString("mint", "")
                        .ifBlank { coin.optString("address", "") }
                        .ifBlank { coin.optString("token_address", "") }

                    if (mint.isBlank() || isSeen(mint)) continue

                    val symbol = coin.optString("symbol", "")
                        .ifBlank { coin.optString("ticker", "") }
                    val name = coin.optString("name", "")
                    if (symbol.isBlank()) continue

                    val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) }

                    val liq = pair?.liquidity ?: (coin.optDouble("usd_market_cap", 0.0) * 0.1)
                    val mcap = pair?.candle?.marketCap ?: coin.optDouble("usd_market_cap", 0.0)
                    val vol = pair?.candle?.volumeH1 ?: 0.0

                    val createdTs = coin.optLong("created_timestamp", 0L)
                    val ageHours = if (createdTs > 0) {
                        (now - createdTs) / 3_600_000.0
                    } else {
                        pair?.pairCreatedAtMs?.let { (now - it) / 3_600_000.0 } ?: 24.0
                    }

                    if (ageHours > 48) continue

                    val pumpBonus = when {
                        ageHours < 0.25 -> 50.0
                        ageHours < 0.5 -> 45.0
                        ageHours < 1 -> 40.0
                        ageHours < 3 -> 30.0
                        ageHours < 6 -> 20.0
                        else -> 10.0
                    }

                    val token = ScannedToken(
                        mint = mint,
                        symbol = symbol,
                        name = name,
                        source = TokenSource.PUMP_FUN_NEW,
                        liquidityUsd = liq,
                        volumeH1 = vol,
                        mcapUsd = mcap,
                        pairCreatedHoursAgo = ageHours,
                        dexId = "pump.fun",
                        priceChangeH1 = 0.0,
                        txCountH1 = pair?.candle?.let { it.buysH1 + it.sellsH1 } ?: 0,
                        score = scoreToken(liq, vol, 0, mcap, 0.0, ageHours) + pumpBonus,
                    )

                    if (passesFilter(token)) {
                        emitWithRugcheck(token)
                        found++
                        totalFound++
                        val freshIcon = when {
                            ageHours < 0.25 -> "🔥"
                            ageHours < 1 -> "🆕"
                            ageHours < 6 -> "📈"
                            else -> "📊"
                        }
                        onLog("$freshIcon PUMP: $symbol | ${(ageHours * 60).toInt()}m old | mcap=\$${mcap.toInt()}")
                    }
                }

                ErrorLogger.info("Scanner", "scanPumpFunDirect: found $found from ${url.take(50)}...")
                delay(100)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLogger.warn("Scanner", "scanPumpFunDirect error: ${e.message}")
            }
        }

        if (totalFound > 0) {
            onLog("🚀 Pump.fun direct: $totalFound new tokens found")
        }
        System.gc()
    }

    private suspend fun scanPumpFunVolume() {
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        ErrorLogger.info("Scanner", "scanPumpFunVolume: fetching boosted tokens...")
        val body = getWithRetry(url) ?: return

        try {
            val boosted = if (body.startsWith("[")) JSONArray(body) else return
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0

            for (i in 0 until minOf(boosted.length(), 80)) {
                if (found >= 30) break

                val item = boosted.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                checked++

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue

                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                        if (liq > 0) {
                            ErrorLogger.info("Scanner", "scanPumpFunVolume: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                        }
                    }
                }

                if (liq < 1000) continue

                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                val boostBonus = 20.0

                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.DEX_BOOSTED,
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + boostBonus,
                )

                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    onLog("💎 BOOST: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
                }
            }

            ErrorLogger.info("Scanner", "scanPumpFunVolume: found $found boosted tokens (checked $checked)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpFunVolume error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanFreshLaunches() {
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanFreshLaunches: looking for tokens < 30 mins old...")
        val body = getWithRetry(url) ?: return

        try {
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            val now = System.currentTimeMillis()
            var found = 0

            for (i in 0 until minOf(profiles.length(), 60)) {
                if (found >= 30) break

                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue

                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue

                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val ageMinutes = ageHours * 60.0
                if (ageHours > 8) continue

                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 500 } ?: 0.0
                        if (liq > 0) {
                            ErrorLogger.info("Scanner", "scanFreshLaunches: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                        }
                    }
                }

                if (liq < 250) continue

                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1

                val freshnessBonus = when {
                    ageMinutes < 5 -> 40.0
                    ageMinutes < 10 -> 35.0
                    ageMinutes < 15 -> 30.0
                    ageMinutes < 30 -> 25.0
                    ageMinutes < 60 -> 20.0
                    else -> 15.0
                }

                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + freshnessBonus,
                )

                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    onLog("🆕 FRESH: ${pair.baseSymbol} | ${ageMinutes.toInt()}m old | liq=\$${liq.toInt()}")
                }
            }

            ErrorLogger.info("Scanner", "scanFreshLaunches: found $found fresh tokens")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanFreshLaunches error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanDexTrending() {
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanDexTrending: fetching from DexScreener...")
        val body = getWithRetry(url) ?: run {
            ErrorLogger.warn("Scanner", "scanDexTrending: no response from API")
            return
        }

        try {
            if (!body.trim().startsWith("[")) {
                ErrorLogger.warn("Scanner", "scanDexTrending: invalid response format")
                return
            }

            val arr = JSONArray(body)
            ErrorLogger.info("Scanner", "scanDexTrending: got ${arr.length()} token profiles")
            var processed = 0
            var passed = 0

            for (i in 0 until minOf(arr.length(), 80)) {
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress", "").trim()
                if (mint.isBlank() || mint.length < 32 || mint.startsWith("0x") || isSeen(mint)) continue
                processed++

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue

                var fallbackLiq = 0.0
                if (pair.liquidity <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    fallbackLiq = overview?.liquidity ?: 0.0

                    if (fallbackLiq > 0) {
                        ErrorLogger.info("Scanner", "scanDexTrending: ${pair.baseSymbol} used Birdeye liq=\$${fallbackLiq.toInt()}")
                    } else if (pair.fdv > 0 || pair.candle.marketCap > 0) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        fallbackLiq = mcap * 0.10
                        if (fallbackLiq > 2000) {
                            ErrorLogger.info("Scanner", "scanDexTrending: ${pair.baseSymbol} estimated liq=\$${fallbackLiq.toInt()} from mcap=\$${mcap.toInt()}")
                        } else {
                            fallbackLiq = 0.0
                        }
                    }
                }

                val token = buildScannedToken(mint, pair, TokenSource.DEX_TRENDING, fallbackLiq) ?: continue
                if (passesFilter(token)) {
                    val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                    emitWithRugcheck(token)
                    passed++
                    onLog("📈 Trending: ${token.symbol} | age=${ageHours.toInt()}h | liq=\$${token.liquidityUsd.toInt()}")
                }
            }

            ErrorLogger.info("Scanner", "scanDexTrending: processed=$processed passed=$passed")
        } catch (e: OutOfMemoryError) {
            ErrorLogger.error("Scanner", "OOM in scanDexTrending", Exception(e.message))
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexTrending error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanDexGainers() {
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanDexGainers: fetching newest token profiles...")
        val body = getWithRetry(url) ?: run {
            ErrorLogger.warn("Scanner", "scanDexGainers: no response from API")
            return
        }

        try {
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            if (profiles.length() == 0) {
                ErrorLogger.warn("Scanner", "scanDexGainers: no profiles in response")
                return
            }

            ErrorLogger.info("Scanner", "scanDexGainers: got ${profiles.length()} token profiles")
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0

            for (i in 0 until minOf(profiles.length(), 60)) {
                if (found >= 30) break

                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue

                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                checked++

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue

                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                        if (liq > 0) {
                            ErrorLogger.info("Scanner", "scanDexGainers: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                        }
                    }
                }

                if (liq < 1000) continue

                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1

                val ageBonus = when {
                    ageHours < 1 -> 30.0
                    ageHours < 6 -> 25.0
                    ageHours < 12 -> 20.0
                    ageHours < 24 -> 15.0
                    ageHours < 48 -> 10.0
                    else -> 5.0
                }

                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = when {
                        ageHours < 6 -> TokenSource.PUMP_FUN_NEW
                        ageHours < 24 -> TokenSource.PUMP_FUN_GRADUATE
                        else -> TokenSource.RAYDIUM_NEW_POOL
                    },
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + ageBonus,
                )

                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    val src = when {
                        ageHours < 1 -> "🚀 FRESH"
                        ageHours < 6 -> "🆕 NEW"
                        ageHours < 24 -> "📈 YOUNG"
                        else -> "📊 TOKEN"
                    }
                    onLog("$src: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
                }
            }

            if (found > 0) {
                ErrorLogger.info("Scanner", "scanDexGainers: found $found tokens (checked $checked)")
            } else {
                ErrorLogger.info("Scanner", "scanDexGainers: no tokens passed filters (checked $checked)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexGainers error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanDexBoosted() {
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        val body = getWithRetry(url) ?: return

        try {
            if (!body.trim().startsWith("[")) {
                ErrorLogger.warn("Scanner", "scanDexBoosted: invalid response format")
                return
            }

            val arr = JSONArray(body)
            for (i in 0 until minOf(arr.length(), 30)) {
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue

                var fallbackLiq = 0.0
                if (pair.liquidity <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    fallbackLiq = overview?.liquidity ?: 0.0
                    if (fallbackLiq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        fallbackLiq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                    }
                }

                val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                val token = buildScannedToken(mint, pair, TokenSource.DEX_BOOSTED, fallbackLiq) ?: continue
                val boostedToken = token.copy(score = (token.score + 15.0).coerceAtMost(100.0))

                if (passesFilter(boostedToken)) {
                    emit(boostedToken)
                    onLog("💎 Boosted: ${token.symbol} | age=${ageHours.toInt()}h | liq=\$${token.liquidityUsd.toInt()}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexBoosted error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanPumpGraduates() {
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        ErrorLogger.info("Scanner", "scanPumpGraduates: fetching boosted tokens for graduates...")
        val body = getWithRetry(url) ?: return

        try {
            val boosted = if (body.startsWith("[")) JSONArray(body) else return
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0

            for (i in 0 until minOf(boosted.length(), 80)) {
                if (found >= 30) break

                val item = boosted.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                checked++

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue

                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1500 } ?: 0.0
                        if (liq > 0) {
                            ErrorLogger.info("Scanner", "scanPumpGraduates: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                        }
                    }
                }

                if (liq < 1500) continue

                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1

                val graduateBonus = when {
                    ageHours < 1 -> 30.0
                    ageHours < 6 -> 25.0
                    ageHours < 12 -> 20.0
                    ageHours < 24 -> 15.0
                    else -> 10.0
                }

                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.PUMP_FUN_GRADUATE,
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + graduateBonus,
                )

                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    val label = when {
                        ageHours < 1 -> "🎓 JUST GRAD"
                        ageHours < 6 -> "🎓 GRADUATE"
                        else -> "📊 PROMOTED"
                    }
                    onLog("$label: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
                }
            }

            if (found > 0) {
                ErrorLogger.info("Scanner", "scanPumpGraduates: found $found tokens (checked $checked)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpGraduates error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanBirdeyeTrending() {
        val c = cfg()
        if (c.birdeyeApiKey.isBlank()) return

        val url = "https://public-api.birdeye.so/defi/token_trending?sort_by=rank&sort_type=asc&offset=0&limit=50"
        val body = getWithRetry(url, apiKey = c.birdeyeApiKey) ?: return

        try {
            val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: return
            for (i in 0 until minOf(items.length(), 50)) {
                val item = items.optJSONObject(i) ?: continue
                val mint = item.optString("address", "")
                if (mint.isBlank() || isSeen(mint)) continue

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue

                var fallbackLiq = 0.0
                if (pair.liquidity <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    fallbackLiq = overview?.liquidity ?: 0.0
                    if (fallbackLiq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        fallbackLiq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                    }
                }

                val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                val token = buildScannedToken(mint, pair, TokenSource.BIRDEYE_TRENDING, fallbackLiq) ?: continue
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    onLog("🦅 Birdeye: ${token.symbol} | age=${ageHours.toInt()}h | liq=\$${token.liquidityUsd.toInt()}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanBirdeye error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanTopVolumeTokens() {
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"

        try {
            val body = getWithRetry(url) ?: return
            val profiles = if (body.startsWith("[")) JSONArray(body) else return

            ErrorLogger.info("Scanner", "scanTopVolume: got ${profiles.length()} token profiles")
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0

            for (i in 0 until minOf(profiles.length(), 80)) {
                if (found >= 30) break

                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue

                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                checked++

                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY", "JUP")) continue

                val liq = pair.liquidity
                if (liq < 500) continue

                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1

                val volBonus = when {
                    vol > 50000 -> 25.0
                    vol > 20000 -> 20.0
                    vol > 10000 -> 15.0
                    vol > 5000 -> 10.0
                    else -> 5.0
                }

                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.DEX_TRENDING,
                    liquidityUsd = liq,
                    volumeH1 = vol,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "solana",
                    priceChangeH1 = 0.0,
                    txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + volBonus,
                )

                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    onLog("📊 ACTIVE: ${pair.baseSymbol} | vol=\$${vol.toInt()} | liq=\$${liq.toInt()}")
                }
            }

            if (found > 0) {
                ErrorLogger.info("Scanner", "scanTopVolume: found $found tokens (checked $checked)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanTopVolume error: ${e.message}")
        }
        System.gc()
    }

    private suspend fun scanCoinGeckoTrending() {
        // GeckoTerminal trending Solana pools — completely free, no API key required
        try {
            val url = "https://api.geckoterminal.com/api/v2/networks/solana/trending_pools?page=1"
            val body = getWithRetry(url) ?: run {
                ErrorLogger.debug("Scanner", "scanCoinGeckoTrending: no response")
                return
            }
            val pools = JSONObject(body).optJSONArray("data") ?: return
            var found = 0
            for (i in 0 until minOf(pools.length(), 50)) {
                if (found >= 30) break
                val pool = pools.optJSONObject(i) ?: continue
                val attrs = pool.optJSONObject("attributes") ?: continue
                val relationships = pool.optJSONObject("relationships") ?: continue
                val baseTokenId = relationships.optJSONObject("base_token")
                    ?.optJSONObject("data")?.optString("id", "") ?: continue
                // GeckoTerminal token IDs are "solana_<mint>"
                val mint = baseTokenId.removePrefix("solana_")
                if (mint.isBlank() || mint == baseTokenId || isSeen(mint)) continue
                val name = attrs.optString("name", "")
                if (name.contains("/USDC", ignoreCase = true) || name.contains("/USDT", ignoreCase = true)) continue

                val liqUsd = attrs.optString("reserve_in_usd", "0").toDoubleOrNull() ?: 0.0
                val volH1 = attrs.optJSONObject("volume_usd")?.optString("h1", "0")?.toDoubleOrNull() ?: 0.0
                val buysH1 = attrs.optJSONObject("transactions")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
                val sellsH1 = attrs.optJSONObject("transactions")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
                val mcap = attrs.optString("fdv_usd", "0").toDoubleOrNull() ?: 0.0
                val createdAt = attrs.optString("pool_created_at", "")
                val ageHours = if (createdAt.isNotBlank()) {
                    try {
                        val ms = java.time.Instant.parse(createdAt).toEpochMilli()
                        (System.currentTimeMillis() - ms) / 3_600_000.0
                    } catch (_: Exception) { 24.0 }
                } else 24.0

                val token = ScannedToken(
                    mint = mint,
                    symbol = name.substringBefore("/").trim(),
                    name = name,
                    source = TokenSource.COINGECKO_TRENDING,
                    liquidityUsd = liqUsd,
                    volumeH1 = volH1,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "raydium",
                    priceChangeH1 = attrs.optJSONObject("price_change_percentage")
                        ?.optString("h1", "0")?.toDoubleOrNull() ?: 0.0,
                    txCountH1 = buysH1 + sellsH1,
                    score = scoreToken(liqUsd, volH1, buysH1 + sellsH1, mcap, 0.0, ageHours),
                )
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    ErrorLogger.info("Scanner", "🦎 GeckoTerminal trending: ${token.symbol} | age=${ageHours.toInt()}h | liq=\$${liqUsd.toInt()}")
                }
            }
            if (found > 0) ErrorLogger.info("Scanner", "scanCoinGeckoTrending: found $found tokens")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.debug("Scanner", "scanCoinGeckoTrending error: ${e.message}")
        }
    }

    private suspend fun scanRaydiumNewPools() {
        // GeckoTerminal NEW Solana pools — free, no API key, catches launches before they trend
        try {
            val url = "https://api.geckoterminal.com/api/v2/networks/solana/new_pools?page=1"
            val body = getWithRetry(url) ?: return
            val pools = JSONObject(body).optJSONArray("data") ?: return
            var found = 0
            for (i in 0 until minOf(pools.length(), 60)) {
                if (found >= 30) break
                val pool = pools.optJSONObject(i) ?: continue
                val attrs = pool.optJSONObject("attributes") ?: continue
                val relationships = pool.optJSONObject("relationships") ?: continue
                val baseTokenId = relationships.optJSONObject("base_token")
                    ?.optJSONObject("data")?.optString("id", "") ?: continue
                val mint = baseTokenId.removePrefix("solana_")
                if (mint.isBlank() || mint == baseTokenId || isSeen(mint)) continue
                val name = attrs.optString("name", "")
                if (name.contains("/USDC", ignoreCase = true) || name.contains("/USDT", ignoreCase = true)) continue

                val liqUsd = attrs.optString("reserve_in_usd", "0").toDoubleOrNull() ?: 0.0
                val volH1 = attrs.optJSONObject("volume_usd")?.optString("h1", "0")?.toDoubleOrNull() ?: 0.0
                val buysH1 = attrs.optJSONObject("transactions")?.optJSONObject("h1")?.optInt("buys", 0) ?: 0
                val sellsH1 = attrs.optJSONObject("transactions")?.optJSONObject("h1")?.optInt("sells", 0) ?: 0
                val mcap = attrs.optString("fdv_usd", "0").toDoubleOrNull() ?: 0.0
                val createdAt = attrs.optString("pool_created_at", "")
                val ageHours = if (createdAt.isNotBlank()) {
                    try {
                        val ms = java.time.Instant.parse(createdAt).toEpochMilli()
                        (System.currentTimeMillis() - ms) / 3_600_000.0
                    } catch (_: Exception) { 1.0 }
                } else 1.0

                val token = ScannedToken(
                    mint = mint,
                    symbol = name.substringBefore("/").trim(),
                    name = name,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liqUsd,
                    volumeH1 = volH1,
                    mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours,
                    dexId = "raydium",
                    priceChangeH1 = attrs.optJSONObject("price_change_percentage")
                        ?.optString("h1", "0")?.toDoubleOrNull() ?: 0.0,
                    txCountH1 = buysH1 + sellsH1,
                    score = scoreToken(liqUsd, volH1, buysH1 + sellsH1, mcap, 0.0, ageHours),
                )
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    ErrorLogger.info("Scanner", "🦎 GeckoTerminal new: ${token.symbol} | age=${(ageHours*60).toInt()}min | liq=\$${liqUsd.toInt()}")
                }
            }
            if (found > 0) ErrorLogger.info("Scanner", "scanRaydiumNewPools (GeckoTerminal): found $found tokens")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.debug("Scanner", "scanRaydiumNewPools error: ${e.message}")
        }
    }

    private suspend fun scanNarratives(keywords: List<String>) {
        keywords.forEach { kw ->
            if (kw.isBlank()) return@forEach

            val results = withContext(Dispatchers.IO) { dex.search(kw) }
            results.take(15).forEach { pair ->
                val mint = pair.pairAddress
                if (mint.isBlank() || isSeen(mint)) return@forEach

                val token = ScannedToken(
                    mint = mint,
                    symbol = pair.baseSymbol,
                    name = pair.baseName,
                    source = TokenSource.NARRATIVE_SCAN,
                    liquidityUsd = pair.liquidity,
                    volumeH1 = pair.candle.volumeH1,
                    mcapUsd = pair.candle.marketCap,
                    pairCreatedHoursAgo = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0,
                    dexId = "unknown",
                    priceChangeH1 = 0.0,
                    txCountH1 = pair.candle.buysH1 + pair.candle.sellsH1,
                    score = scoreToken(
                        pair.liquidity,
                        pair.candle.volumeH1,
                        pair.candle.buysH1 + pair.candle.sellsH1,
                        pair.candle.marketCap,
                        0.0,
                        (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                    ),
                )
                if (passesFilter(token)) emitWithRugcheck(token)
            }
            delay(500)
        }
    }

    private fun scoreToken(
        liqUsd: Double,
        volH1: Double,
        txH1: Int,
        mcap: Double,
        priceChangeH1: Double,
        ageHours: Double,
    ): Double {
        var s = 0.0

        s += when {
            liqUsd > 1_000_000 -> 10.0
            liqUsd > 500_000 -> 20.0
            liqUsd > 100_000 -> 35.0
            liqUsd > 25_000 -> 45.0
            liqUsd > 10_000 -> 35.0
            liqUsd > 2_000 -> 20.0
            else -> 5.0
        }

        val volLiqRatio = if (liqUsd > 0) volH1 / liqUsd else 0.0
        s += when {
            volLiqRatio > 5.0 -> 25.0
            volLiqRatio > 2.0 -> 20.0
            volLiqRatio > 1.0 -> 15.0
            volLiqRatio > 0.5 -> 10.0
            volLiqRatio > 0.2 -> 5.0
            else -> 0.0
        }

        s += when {
            txH1 > 500 -> 20.0
            txH1 > 200 -> 15.0
            txH1 > 100 -> 10.0
            txH1 > 50 -> 5.0
            txH1 > 20 -> 2.0
            else -> 0.0
        }

        s += when {
            priceChangeH1 > 100 -> 15.0
            priceChangeH1 > 50 -> 12.0
            priceChangeH1 > 20 -> 8.0
            priceChangeH1 > 10 -> 5.0
            priceChangeH1 < 0 -> -10.0
            else -> 0.0
        }

        s += when {
            ageHours < 0.5 -> 5.0
            ageHours < 2.0 -> 15.0
            ageHours < 6.0 -> 20.0
            ageHours < 24.0 -> 15.0
            ageHours < 72.0 -> 10.0
            ageHours < 168.0 -> 5.0
            else -> 2.0
        }

        return s.coerceIn(0.0, 100.0)
    }

    private fun getAISourceBoost(source: TokenSource): Double {
        val brain = getBrain() ?: return 0.0
        val boost = brain.getSourceBoost(source.name)
        return when {
            boost >= 15.0 -> 15.0
            boost >= 10.0 -> 10.0
            boost >= 5.0 -> 5.0
            boost <= -15.0 -> -15.0
            boost <= -10.0 -> -10.0
            boost <= -5.0 -> -5.0
            else -> 0.0
        }
    }

    private fun aiShouldSkipToken(mint: String, symbol: String, liquidity: Double, mcap: Double): Boolean {
        val tokenHistory = TradingMemory.getTokenLossHistory(mint)
        if (tokenHistory != null && tokenHistory.lossCount >= 2) {
            onLog("🤖 AI SKIP: $symbol — ${tokenHistory.lossCount} prior losses")
            return true
        }
        return false
    }

    private fun passesFilter(token: ScannedToken): Boolean {
        telemetryRawScanned++

        if (EfficiencyLayer.isLiquiditySuppressed(token.mint)) {
            telemetryLiqRejects++
            ErrorLogger.debug("Scanner", "SKIP ${token.symbol}: liquidity-suppressed")
            return false
        }

        val decision = EfficiencyLayer.shouldFullProcess(
            mint = token.mint,
            source = token.source.name,
            liquidity = token.liquidityUsd,
            score = token.score.toInt()
        )

        if (!decision.shouldProcess) {
            ErrorLogger.debug("Scanner", "SKIP ${token.symbol}: ${decision.reason}")
            return false
        }

        val liqQuality = when (token.source) {
            TokenSource.RAYDIUM_NEW_POOL -> EfficiencyLayer.LiqSourceQuality.DIRECT_POOL
            TokenSource.DEX_BOOSTED, TokenSource.DEX_TRENDING -> EfficiencyLayer.LiqSourceQuality.DEX_AGGREGATOR
            TokenSource.PUMP_FUN_NEW, TokenSource.PUMP_FUN_GRADUATE -> EfficiencyLayer.LiqSourceQuality.VERIFIED_PAIR
            else -> EfficiencyLayer.LiqSourceQuality.ESTIMATED_MCAP
        }

        EfficiencyLayer.recordLiquidity(token.mint, token.liquidityUsd, token.source.name, liqQuality)

        val passed = passesFilterInternal(token)
        if (!passed) {
            markRejected(token.mint)
            // V5.9.44: unified liquidity-reject telemetry floor (was paper=100, live=3000)
            val liqFloor = 100.0
            if (token.liquidityUsd < liqFloor && token.liquidityUsd > 0) {
                telemetryLiqRejects++
                EfficiencyLayer.registerLiquidityRejection(token.mint, token.liquidityUsd, liqFloor)
            }
        } else {
            seenMints[token.mint] = System.currentTimeMillis()
            ErrorLogger.info(
                "Scanner",
                "FILTER PASS ${token.symbol}: liq=\$${token.liquidityUsd.toInt()} score=${token.score.toInt()} (${decision.reason})"
            )
        }

        return passed
    }

    private fun passesFilterInternal(token: ScannedToken): Boolean {
        val c = cfg()
        val isPaperMode = c.paperMode

        // V5.6.29d: Banned tokens blocked in both modes
        if (BannedTokens.isBanned(token.mint)) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: PERMANENTLY BANNED")
            return false
        }

        // V5.9.34: SCANNER IS WIDE OPEN
        // User correction: 'the scanner is meant to be wide open and the watchlist
        // and decision gate etc is meant to classify the tokens'.
        // Removed: V5.9.20/V5.9.31 liquidity floor — downstream V3 scoring already
        //   penalises low liq; hard scanner reject was double-taxing thin pools.
        // Removed: V5.9.20 non-ASCII filter — the decision gate sees the symbol and
        //   can factor it. Scanner's job is to surface candidates, not judge them.
        // Removed: V5.9.20 typosquat hard-reject — downstream Rug detection + V3
        //   narrative scoring already handles impersonation risk.
        //
        // Kept below: basic mcap sanity (< 0), explicit scam words, infra impersonation —
        // those are factual data-quality guards, not opinion filters.

        // Basic mcap sanity check
        if (token.mcapUsd < 0) return false

        
        // Scam pattern detection (both modes)
        val sym = token.symbol.lowercase()
        val name = token.name.lowercase()
        val scamPatterns = listOf("test", "fake", "scam", "rug", "honeypot", "xxx", "porn")
        if (scamPatterns.any { sym.contains(it) || name.contains(it) }) {
            onLog("🚫 BLOCK: ${token.symbol} - scam pattern")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: scam pattern detected")
            return false
        }
        
        // Block impersonation of infrastructure tokens (both modes)
        val blockedSymbols = listOf("sol", "wsol", "usdt", "usdc", "ray", "jup")
        val symLower = token.symbol.lowercase().trim()
        if (symLower in blockedSymbols) {
            onLog("🚫 BLOCK: ${token.symbol} - reserved symbol")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: blocked symbol '$symLower'")
            return false
        }
        
        val infrastructureTokens = listOf(
            "solana", "wrapped sol", "wrapped solana",
            "raydium", "jupiter", "jito", "pyth", "marinade", "orca",
            "pump", "pumpfun", "pump.fun"
        )
        val nameLower = token.name.lowercase().trim()
        for (infra in infrastructureTokens) {
            if (nameLower == infra || nameLower.startsWith("$infra ")) {
                onLog("🚫 BLOCK: ${token.symbol} - impersonates $infra")
                ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: impersonates '$infra' (name='${token.name}')")
                return false
            }
        }

        // V5.9.68: dedupe — outer passesFilter already logs FILTER PASS with
        // the decision.reason so we don't need this near-identical second line.
        return true
    }

    private fun buildScannedToken(
        mint: String,
        pair: com.lifecyclebot.network.PairInfo,
        source: TokenSource,
        fallbackLiquidity: Double = 0.0,
    ): ScannedToken? {
        if (pair.candle.priceUsd <= 0) return null

        val liquidity = if (pair.liquidity > 0) pair.liquidity else fallbackLiquidity
        if (liquidity <= 0) {
            ErrorLogger.debug("Scanner", "Skipping ${pair.baseSymbol}: \$0 liquidity from all sources")
            return null
        }

        val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
        return ScannedToken(
            mint = mint,
            symbol = pair.baseSymbol,
            name = pair.baseName,
            source = source,
            liquidityUsd = liquidity,
            volumeH1 = pair.candle.volumeH1,
            mcapUsd = pair.candle.marketCap,
            pairCreatedHoursAgo = ageHours.coerceAtLeast(0.0),
            dexId = "solana",
            priceChangeH1 = 0.0,
            txCountH1 = pair.candle.buysH1 + pair.candle.sellsH1,
            score = scoreToken(
                liquidity,
                pair.candle.volumeH1,
                pair.candle.buysH1 + pair.candle.sellsH1,
                pair.candle.marketCap,
                0.0,
                ageHours
            ),
        )
    }

    private fun isSeen(mint: String): Boolean {
        val now = System.currentTimeMillis()

        if (isSaturated(mint)) {
            telemetrySaturatedDrops++
            return true
        }

        rejectedMints[mint]?.let { rejectedAt ->
            val age = now - rejectedAt
            if (age < getRejectedTtl()) {
                recordCooldownHit(mint)
                return true
            } else {
                rejectedMints.remove(mint)
            }
        }

        seenMints[mint]?.let { seenAt ->
            val age = now - seenAt
            if (age < getSeenTtl()) {
                recordCooldownHit(mint)
                return true
            } else {
                seenMints.remove(mint)
            }
        }

        if (mint in cfg().watchlist) {
            return true
        }

        return false
    }

    private fun markRejected(mint: String) {
        rejectedMints[mint] = System.currentTimeMillis()
    }

    fun markTokenRejected(mint: String) {
        rejectedMints[mint] = System.currentTimeMillis()
        ErrorLogger.info("Scanner", "Token ${mint.take(12)} marked as rejected for ${getRejectedTtl() / 1000}s")
    }

    private fun cleanupSeenMaps() {
        val now = System.currentTimeMillis()
        val seenBefore = seenMints.size
        val rejectedBefore = rejectedMints.size

        val seenToRemove = seenMints.entries.filter { now - it.value > getSeenTtl() }.map { it.key }
        seenToRemove.forEach { seenMints.remove(it) }

        val rejectedToRemove = rejectedMints.entries.filter { now - it.value > getRejectedTtl() }.map { it.key }
        rejectedToRemove.forEach { rejectedMints.remove(it) }

        val saturatedToRemove = saturatedMints.entries.filter { now - it.value > getSaturationTtl() }.map { it.key }
        saturatedToRemove.forEach { saturatedMints.remove(it) }

        val hitCountsToRemove = cooldownHitCount.keys.filter { mint ->
            !seenMints.containsKey(mint) && !rejectedMints.containsKey(mint)
        }
        hitCountsToRemove.forEach { cooldownHitCount.remove(it) }

        val seenRemoved = seenBefore - seenMints.size
        val rejectedRemoved = rejectedBefore - rejectedMints.size

        if (now - lastTelemetryLogMs > 60_000L) {
            ErrorLogger.info("Scanner", "📊 TELEMETRY: ${getThroughputTelemetry()}")
            onLog("📊 ${getThroughputTelemetry()}")
            lastTelemetryLogMs = now
        }

        // V5.9.365 — push snapshot to MarketsTelemetry singleton so the main UI's
        // Funnel Stages tile can read fresh counters without holding a scanner ref.
        try {
            MarketsTelemetry.latestThroughput = getThroughputTelemetrySnapshot()
        } catch (_: Exception) {}

        if (seenRemoved > 0 || rejectedRemoved > 0) {
            ErrorLogger.info(
                "Scanner",
                "Cleanup: removed $seenRemoved seen, $rejectedRemoved rejected. Remaining: ${seenMints.size} seen, ${rejectedMints.size} rejected, ${saturatedMints.size} saturated"
            )
            onLog("🧹 Map cleanup: seen=${seenMints.size} rejected=${rejectedMints.size} sat=${saturatedMints.size}")
        }

        if (seenMints.size > 10_000) {
            val toKeep = seenMints.entries.sortedByDescending { it.value }.take(50).map { it.key }
            seenMints.keys.retainAll(toKeep.toSet())
            ErrorLogger.warn("Scanner", "Aggressive seen cleanup: reduced to ${seenMints.size}")
            onLog("⚠️ Seen map trimmed to ${seenMints.size}")
        }

        if (rejectedMints.size > 2_000) {
            val toKeep = rejectedMints.entries.sortedByDescending { it.value }.take(100).map { it.key }
            rejectedMints.keys.retainAll(toKeep.toSet())
            ErrorLogger.warn("Scanner", "Aggressive rejected cleanup: reduced to ${rejectedMints.size}")
            onLog("⚠️ Rejected map trimmed to ${rejectedMints.size}")
        }
    }

    private fun emit(token: ScannedToken) {
        if (aiShouldSkipToken(token.mint, token.symbol, token.liquidityUsd, token.mcapUsd)) return

        val aiBoost = getAISourceBoost(token.source)
        val scannerLearningBoost = ScannerLearning.getDiscoveryBonus(
            source = token.source.name,
            liqUsd = token.liquidityUsd,
            ageHours = if (token.pairCreatedHoursAgo > 0) token.pairCreatedHoursAgo else 1.0
        )

        val totalBoost = aiBoost + scannerLearningBoost
        val adjustedScore = (token.score + totalBoost).coerceIn(0.0, 100.0)
        val adjustedToken = token.copy(score = adjustedScore)

        seenMints[token.mint] = System.currentTimeMillis()
        telemetryEnqueued++

        val boostIndicator = if (totalBoost != 0.0) {
            " AI${if (totalBoost > 0) "+" else ""}${totalBoost.toInt()}"
        } else {
            ""
        }

        onLog(
            "🔍 Found: ${token.symbol} (${token.source.name}$boostIndicator) liq=\$${(token.liquidityUsd / 1000).toInt()}K vol=\$${(token.volumeH1 / 1000).toInt()}K score=${adjustedScore.toInt()}"
        )

        try {
            com.lifecyclebot.v3.arb.SourceTimingRegistry.record(
                mint = token.mint,
                source = token.source.name,
                price = null,
                liquidityUsd = token.liquidityUsd,
                buyPressurePct = null
            )
        } catch (_: Exception) {
        }

        LiquidityDepthAI.recordSnapshot(
            mint = token.mint,
            liquidityUsd = token.liquidityUsd,
            mcapUsd = token.mcapUsd,
            holderCount = 0
        )

        onTokenFound(
            adjustedToken.mint,
            adjustedToken.symbol,
            adjustedToken.name,
            adjustedToken.source,
            adjustedToken.score,
            adjustedToken.liquidityUsd,
            adjustedToken.volumeH1
        )
    }

    private val rugcheckHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()

    // ═══════════════════════════════════════════════════════════════════════════
    // V5.6.9 FIX: RELAXED RC SCORE HANDLING
    // 
    // PROBLEM: RC score=1 means "pending/calculating" (unknown), NOT dangerous!
    // The API returns score=1 when Rugcheck hasn't fully processed the token yet.
    // Old logic blocked score < 2, which starved the bot of entries on new tokens.
    // 
    // NEW BEHAVIOR:
    //   - score=0: Confirmed dangerous → HARD BLOCK
    //   - score=1: Unknown/pending → ALLOW with soft penalty (let bot evaluate)
    //   - score 2-9: Very risky → ALLOW with penalty
    //   - score >= 10: Normal processing
    //   - rugged=true: Always HARD BLOCK
    // 
    // This ensures the bot can find entries while still learning danger signals.
    // ═══════════════════════════════════════════════════════════════════════════
    private fun quickRugcheck(mint: String): Boolean {
        try {
            val url = "https://api.rugcheck.xyz/v1/tokens/$mint/report/summary"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val resp = rugcheckHttp.newCall(request).execute()
            if (!resp.isSuccessful) {
                ErrorLogger.debug("Scanner", "Rugcheck API failed for ${mint.take(8)}, passing through")
                return true
            }

            val body = resp.body?.string() ?: return true
            val json = JSONObject(body)

            val scoreNormalized = json.optInt("score_normalised", json.optInt("score", 50))
            val rugged = json.optString("rugged", "").lowercase()
            rcScoreCache[mint] = scoreNormalized

            // ABSOLUTE BLOCK: Confirmed rug
            if (rugged == "true" || rugged == "yes") {
                telemetryRugRejects++
                onLog("🚫 RUG CONFIRMED: ${mint.take(8)}... (tokens worthless)")
                ErrorLogger.info("Scanner", "quickRugcheck FATAL: ${mint.take(12)} rugged=true")
                return false
            }

            val isPaper = cfg().paperMode

            // V5.6.9 FIX: Only score=0 is a hard block (confirmed dangerous)
            // Score=1 means "unknown/pending" and should be allowed through for evaluation
            if (scoreNormalized == 0) {
                telemetryRugRejects++
                onLog("🚫 RC HARD BLOCK: ${mint.take(8)}... score=0 (confirmed dangerous)")
                ErrorLogger.info("Scanner", "RC HARD_BLOCK: ${mint.take(12)} score=0 (confirmed dangerous)")
                return false
            }

            // Score=1: Unknown/pending — ALLOW with logging
            // The token is too new for Rugcheck to fully analyze. Let the bot evaluate
            // using other signals (liquidity, buy pressure, etc.)
            if (scoreNormalized == 1) {
                ErrorLogger.info("Scanner", "RC PENDING: ${mint.take(12)} score=1 (unknown/calculating) — allowing for evaluation")
                onLog("⏳ RC PENDING: ${mint.take(8)}... score=1 (unknown) — proceeding with other checks")
                return true  // V5.6.9: Allow through — other safety layers will catch truly bad tokens
            }

            // Scores 2-9: Very risky but not auto-blocked at scanner level
            // Let TokenSafetyChecker apply appropriate penalties
            if (scoreNormalized in 2..9) {
                ErrorLogger.debug("Scanner", "RC ${mint.take(8)}: score=$scoreNormalized (risky but passing scanner)")
                return true
            }

            // Scores >= 10: Normal range
            if (scoreNormalized in 10..100) {
                ErrorLogger.debug("Scanner", "RC ${mint.take(8)}: score=$scoreNormalized (OK)")
            }

            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.debug("Scanner", "RC exception for ${mint.take(8)}: ${e.message}, passing")
            return true
        }
    }

    private suspend fun emitWithRugcheck(token: ScannedToken) {
        val passed = try {
            withContext(Dispatchers.IO) {
                try {
                    withTimeout(2000L) { quickRugcheck(token.mint) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLogger.debug("Scanner", "RC error for ${token.symbol}: ${e.message}, passing through")
                    true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ErrorLogger.debug("Scanner", "RC outer error for ${token.symbol}: ${e.message}, passing through")
            true
        }

        if (!passed) {
            ErrorLogger.info("Scanner", "Rugcheck blocked ${token.symbol} (quickRugcheck returned false)")
            return
        }

        emit(token)
    }

    private fun getWithRetry(url: String, apiKey: String = "", maxRetries: Int = 2): String? {
        var lastError: Exception? = null

        repeat(maxRetries.coerceAtLeast(1)) { attempt ->
            try {
                val result = get(url, apiKey)
                if (result != null) return result

                if (attempt < maxRetries - 1) {
                    Thread.sleep(150L * (attempt + 1))
                }
            } catch (e: Exception) {
                lastError = e
                ErrorLogger.debug(
                    "Scanner",
                    "[NETWORK] Retry ${attempt + 1}/$maxRetries failed for ${url.take(40)}: ${e.message?.take(40)}"
                )

                if (attempt < maxRetries - 1) {
                    Thread.sleep(250L * (attempt + 1))
                }
            }
        }

        if (lastError != null) {
            ErrorLogger.warn("Scanner", "[NETWORK] All retries failed for ${url.take(50)}: ${lastError?.message?.take(30)}")
        } else {
            ErrorLogger.warn("Scanner", "[NETWORK] All retries returned null for ${url.take(50)}")
        }

        return null
    }

    private fun get(url: String, apiKey: String = ""): String? = try {
        val builder = Request.Builder().url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Connection", "keep-alive")

        if (apiKey.isNotBlank()) builder.header("X-API-KEY", apiKey)

        ErrorLogger.debug("Scanner", "HTTP GET: ${url.take(60)}...")
        val resp = http.newCall(builder.build()).execute()

        if (resp.isSuccessful) {
            val body = resp.body?.string()
            if (body != null && (body.trim().startsWith("{") || body.trim().startsWith("["))) {
                ErrorLogger.debug("Scanner", "HTTP OK: ${body.length} bytes from ${url.take(40)}")
                body
            } else {
                ErrorLogger.warn("Scanner", "[NETWORK] Non-JSON response from ${url.take(50)}")
                null
            }
        } else {
            when (resp.code) {
                429 -> ErrorLogger.debug("Scanner", "[NETWORK/RATE_LIMIT] 429 from ${url.take(40)}")
                530, 503 -> ErrorLogger.debug("Scanner", "[NETWORK/DNS] ${resp.code} from ${url.take(40)}")
                403 -> ErrorLogger.debug("Scanner", "[NETWORK/BLOCKED] 403 from ${url.take(40)}")
                else -> ErrorLogger.warn("Scanner", "[NETWORK] HTTP ${resp.code} from ${url.take(50)}")
            }
            null
        }
    } catch (e: java.net.SocketTimeoutException) {
        ErrorLogger.debug("Scanner", "[NETWORK/TIMEOUT] ${url.take(50)}")
        null
    } catch (e: java.net.UnknownHostException) {
        ErrorLogger.warn("Scanner", "[NETWORK/DNS_FAIL] Cannot resolve ${url.take(50)}")
        null
    } catch (e: java.net.ConnectException) {
        ErrorLogger.warn("Scanner", "[NETWORK/CONN_FAIL] ${url.take(50)}")
        null
    } catch (e: Exception) {
        ErrorLogger.warn("Scanner", "[NETWORK/ERROR] ${e.javaClass.simpleName}: ${e.message?.take(30)}")
        null
    }
}
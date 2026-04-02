package com.lifecyclebot.engine

import android.content.Context
import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.Candle
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.BirdeyeApi
import com.lifecyclebot.network.CoinGeckoTrending
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * ScannerLearning — Tracks which sources/characteristics produce winners
 * ═══════════════════════════════════════════════════════════════════════
 * 
 * Learns over time:
 *  - Which TokenSource produces most winners
 *  - Which liquidity ranges perform best
 *  - Which age profiles (fresh vs established) win more
 *  - Which mcap ranges are optimal
 *
 * Used to boost/penalize discovery scores based on historical performance.
 */
object ScannerLearning {
    private const val PREFS_NAME = "scanner_learning"
    private var ctx: Context? = null
    
    // Track wins/losses by source
    private val sourceWins = ConcurrentHashMap<String, Int>()
    private val sourceLosses = ConcurrentHashMap<String, Int>()
    
    // Track by liquidity bucket (0-5k, 5k-20k, 20k-100k, 100k+)
    private val liqBucketWins = ConcurrentHashMap<String, Int>()
    private val liqBucketLosses = ConcurrentHashMap<String, Int>()
    
    // Track by age bucket (0-1h, 1-6h, 6-24h, 24h+)
    private val ageBucketWins = ConcurrentHashMap<String, Int>()
    private val ageBucketLosses = ConcurrentHashMap<String, Int>()
    
    // Initialize with context and load saved state
    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info("ScannerLearning", "📊 Loaded state: ${getStats()}")
    }
    
    fun recordTrade(source: String, liqUsd: Double, ageHours: Double, isWin: Boolean) {
        // Source tracking
        if (isWin) sourceWins.merge(source, 1) { a, b -> a + b }
        else sourceLosses.merge(source, 1) { a, b -> a + b }
        
        // Liquidity bucket tracking
        val liqBucket = when {
            liqUsd < 5_000 -> "liq_0_5k"
            liqUsd < 20_000 -> "liq_5k_20k"
            liqUsd < 100_000 -> "liq_20k_100k"
            else -> "liq_100k_plus"
        }
        if (isWin) liqBucketWins.merge(liqBucket, 1) { a, b -> a + b }
        else liqBucketLosses.merge(liqBucket, 1) { a, b -> a + b }
        
        // Age bucket tracking
        val ageBucket = when {
            ageHours < 1 -> "age_0_1h"
            ageHours < 6 -> "age_1_6h"
            ageHours < 24 -> "age_6_24h"
            else -> "age_24h_plus"
        }
        if (isWin) ageBucketWins.merge(ageBucket, 1) { a, b -> a + b }
        else ageBucketLosses.merge(ageBucket, 1) { a, b -> a + b }
        
        // Save after each trade
        save()
        
        ErrorLogger.info("ScannerLearning", "📊 Recorded ${if (isWin) "WIN" else "LOSS"}: src=$source liq=$liqBucket age=$ageBucket")
    }
    
    // Get win rate for a source (0.0 to 1.0)
    fun getSourceWinRate(source: String): Double {
        val wins = sourceWins[source] ?: 0
        val losses = sourceLosses[source] ?: 0
        val total = wins + losses
        return if (total >= 5) wins.toDouble() / total else 0.5  // Default 50% until enough data
    }
    
    // Get score bonus/penalty based on historical performance
    fun getDiscoveryBonus(source: String, liqUsd: Double, ageHours: Double): Double {
        var bonus = 0.0
        
        // Source-based bonus (-10 to +10)
        val srcRate = getSourceWinRate(source)
        bonus += (srcRate - 0.5) * 20.0  // 60% win rate = +2, 40% = -2
        
        // Liquidity-based bonus
        val liqBucket = when {
            liqUsd < 5_000 -> "liq_0_5k"
            liqUsd < 20_000 -> "liq_5k_20k"
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
        
        // Age-based bonus
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
    
    // ── Persistence ─────────────────────────────────────────────────────
    
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
        } catch (_: Exception) {}
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
        } catch (_: Exception) {}
    }
    
    private fun mapToJson(map: Map<String, Int>): String {
        val obj = org.json.JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        return obj.toString()
    }
    
    private fun jsonToMap(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optInt(it, 0) }
        } catch (_: Exception) { emptyMap() }
    }
    
    /**
     * Clear all learned data - used by SelfHealingDiagnostics.
     */
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

// ═══════════════════════════════════════════════════════════════════════════════
// MODE-SPECIFIC LEARNING SYSTEM
// Each trading mode has its own learning instance with tailored scanner filters
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * ModeLearning — Separate learning instance per trading mode.
 * 
 * Each of the 18 trading modes learns independently what works best for its strategy.
 * For example:
 * - MOONSHOT mode learns which early-stage patterns lead to 10x+
 * - PUMP_SNIPER learns which viral signals actually pump
 * - WHALE_FOLLOW learns which whale entry patterns are smart money vs dumps
 * 
 * Self-healing per mode: If a mode's learning becomes poisoned, only that mode resets.
 */
object ModeLearning {
    
    private const val TAG = "ModeLearning"
    private const val PREFS_NAME = "mode_learning_v2"
    private const val MIN_TRADES_FOR_CONFIDENCE = 5
    private const val CRITICAL_LOSS_RATE = 70.0  // 70%+ losses = poisoned
    private const val HEALTH_CHECK_INTERVAL_MS = 30 * 60 * 1000L
    
    private var ctx: Context? = null
    
    /**
     * Per-mode learning data structure.
     */
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
        
        // Pattern-specific wins/losses for this mode
        val phaseWins: MutableMap<String, Int> = mutableMapOf(),
        val phaseLosses: MutableMap<String, Int> = mutableMapOf(),
        val liqBucketWins: MutableMap<String, Int> = mutableMapOf(),
        val liqBucketLosses: MutableMap<String, Int> = mutableMapOf(),
        val sourceWins: MutableMap<String, Int> = mutableMapOf(),
        val sourceLosses: MutableMap<String, Int> = mutableMapOf(),
        val hourWins: MutableMap<Int, Int> = mutableMapOf(),
        val hourLosses: MutableMap<Int, Int> = mutableMapOf(),
    ) {
        val winRate: Double get() = if (totalTrades > 0) wins.toDouble() / totalTrades * 100 else 50.0
        val avgPnl: Double get() = if (totalTrades > 0) totalPnlPct / totalTrades else 0.0
        val lossRate: Double get() = 100.0 - winRate
        val isReliable: Boolean get() = totalTrades >= MIN_TRADES_FOR_CONFIDENCE
    }
    
    /**
     * Mode-specific scanner filter preferences.
     */
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
        val confidence: Double,  // How confident we are in these preferences
    )
    
    // Per-mode learning data
    private val modeData = ConcurrentHashMap<String, ModeLearningData>()
    
    // Health check tracking
    private val lastHealthCheck = ConcurrentHashMap<String, Long>()
    
    /**
     * Initialize with context.
     */
    fun init(context: Context) {
        ctx = context.applicationContext
        load()
        ErrorLogger.info(TAG, "📊 Initialized: ${modeData.size} modes loaded")
    }
    
    /**
     * Record a trade outcome for a specific mode.
     */
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
            
            // Update basic stats
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
            
            // Update best/worst
            if (pnlPct > data.bestPnlPct) data.bestPnlPct = pnlPct
            if (pnlPct < data.worstPnlPct) data.worstPnlPct = pnlPct
            
            // Update rolling avg hold time
            data.avgHoldTimeMs = if (data.totalTrades == 1) {
                holdTimeMs
            } else {
                ((data.avgHoldTimeMs * (data.totalTrades - 1)) + holdTimeMs) / data.totalTrades
            }
            
            // Record pattern-specific wins/losses
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
            
            // Log progress
            val emoji = if (isWin) "✅" else "❌"
            ErrorLogger.info(TAG, "$emoji [$mode] Trade #${data.totalTrades}: " +
                "WR=${data.winRate.toInt()}% | PnL=${pnlPct.toInt()}% | Phase=$entryPhase")
            
            // Save periodically (every 5 trades)
            if (data.totalTrades % 5 == 0) save()
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordTrade error: ${e.message}")
        }
    }
    
    /**
     * Get learned scanner preferences for a mode.
     * Used to tailor scanner filters per mode.
     */
    fun getScannerPrefs(mode: String): ModeScannerPrefs {
        val data = modeData[mode]
        
        if (data == null || !data.isReliable) {
            // Return default prefs if not enough data
            return getDefaultPrefs(mode)
        }
        
        // Calculate preferred/avoid based on win rates
        val preferredPhases = data.phaseWins.keys.filter { phase ->
            val wins = data.phaseWins[phase] ?: 0
            val losses = data.phaseLosses[phase] ?: 0
            val total = wins + losses
            total >= 3 && wins.toDouble() / total >= 0.6  // 60%+ win rate
        }
        
        val avoidPhases = data.phaseLosses.keys.filter { phase ->
            val wins = data.phaseWins[phase] ?: 0
            val losses = data.phaseLosses[phase] ?: 0
            val total = wins + losses
            total >= 3 && losses.toDouble() / total >= 0.7  // 70%+ loss rate
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
        
        // Find best performing liquidity bucket
        val bestLiqBucket = data.liqBucketWins.maxByOrNull { (bucket, wins) ->
            val losses = data.liqBucketLosses[bucket] ?: 0
            if (wins + losses >= 3) wins.toDouble() / (wins + losses) else 0.0
        }?.key
        
        val (liqMin, liqMax) = when (bestLiqBucket) {
            "0-5k" -> 0.0 to 5_000.0
            "5k-20k" -> 5_000.0 to 20_000.0
            "20k-100k" -> 20_000.0 to 100_000.0
            "100k+" -> 100_000.0 to Double.MAX_VALUE
            else -> getDefaultLiqRange(mode)
        }
        
        val confidence = data.totalTrades.toDouble().coerceAtMost(50.0) / 50.0  // Max at 50 trades
        
        return ModeScannerPrefs(
            mode = mode,
            preferredLiqMin = liqMin,
            preferredLiqMax = liqMax,
            preferredAgeMinHours = 0.0,  // Could enhance with age learning
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
    
    /**
     * Get score bonus for a setup based on mode-specific learning.
     * Returns -20 to +20 adjustment.
     */
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
        
        // Phase bonus/penalty
        val phaseWins = data.phaseWins[entryPhase] ?: 0
        val phaseLosses = data.phaseLosses[entryPhase] ?: 0
        val phaseTotal = phaseWins + phaseLosses
        if (phaseTotal >= 3) {
            val phaseWinRate = phaseWins.toDouble() / phaseTotal
            bonus += (phaseWinRate - 0.5) * 20  // -10 to +10
        }
        
        // Liquidity bucket bonus/penalty
        val liqBucket = getLiquidityBucket(liquidityUsd)
        val liqWins = data.liqBucketWins[liqBucket] ?: 0
        val liqLosses = data.liqBucketLosses[liqBucket] ?: 0
        val liqTotal = liqWins + liqLosses
        if (liqTotal >= 3) {
            val liqWinRate = liqWins.toDouble() / liqTotal
            bonus += (liqWinRate - 0.5) * 15  // -7.5 to +7.5
        }
        
        // Source bonus/penalty
        val srcWins = data.sourceWins[source] ?: 0
        val srcLosses = data.sourceLosses[source] ?: 0
        val srcTotal = srcWins + srcLosses
        if (srcTotal >= 3) {
            val srcWinRate = srcWins.toDouble() / srcTotal
            bonus += (srcWinRate - 0.5) * 10  // -5 to +5
        }
        
        // Hour bonus/penalty
        val hourWins = data.hourWins[hourOfDay] ?: 0
        val hourLosses = data.hourLosses[hourOfDay] ?: 0
        val hourTotal = hourWins + hourLosses
        if (hourTotal >= 2) {
            val hourWinRate = hourWins.toDouble() / hourTotal
            bonus += (hourWinRate - 0.5) * 10  // -5 to +5
        }
        
        return bonus.toInt().coerceIn(-20, 20)
    }
    
    /**
     * Self-healing check for a specific mode.
     * Returns true if mode was reset.
     */
    fun selfHealingCheckForMode(mode: String): Boolean {
        val now = System.currentTimeMillis()
        val lastCheck = lastHealthCheck[mode] ?: 0L
        
        if (now - lastCheck < HEALTH_CHECK_INTERVAL_MS) return false
        lastHealthCheck[mode] = now
        
        val data = modeData[mode] ?: return false
        
        // Not enough data to judge
        if (data.totalTrades < MIN_TRADES_FOR_CONFIDENCE) return false
        
        // Check if mode is poisoned
        if (data.lossRate >= CRITICAL_LOSS_RATE) {
            ErrorLogger.warn(TAG, "🚨 [$mode] POISONED: ${data.lossRate.toInt()}% loss rate - RESETTING")
            resetMode(mode)
            return true
        }
        
        // Check for consecutive loss streak
        if (data.consecutiveLosses >= 5) {
            ErrorLogger.warn(TAG, "⚠️ [$mode] 5+ consecutive losses - partial reset")
            // Partial reset: decay weights but don't clear completely
            data.totalTrades = (data.totalTrades * 0.5).toInt()
            data.wins = (data.wins * 0.5).toInt()
            data.losses = (data.losses * 0.5).toInt()
            data.consecutiveLosses = 0
            return false
        }
        
        return false
    }
    
    /**
     * Reset learning for a specific mode.
     */
    fun resetMode(mode: String) {
        modeData[mode] = ModeLearningData()
        save()
        ErrorLogger.warn(TAG, "🧹 [$mode] Learning reset")
    }
    
    /**
     * Clear all mode learning data.
     */
    fun clear() {
        modeData.clear()
        lastHealthCheck.clear()
        save()
        ErrorLogger.warn(TAG, "🧹 All mode learning cleared")
    }
    
    /**
     * Get stats summary for a mode.
     */
    fun getStats(mode: String): String {
        val data = modeData[mode] ?: return "[$mode] No data"
        return "[$mode] ${data.totalTrades} trades | WR=${data.winRate.toInt()}% | " +
            "AvgPnL=${data.avgPnl.toInt()}% | Best=${data.bestPnlPct.toInt()}%"
    }
    
    /**
     * Get all mode stats sorted by performance.
     */
    fun getAllStatsSorted(): List<Pair<String, ModeLearningData>> {
        return modeData.entries
            .filter { it.value.isReliable }
            .sortedByDescending { it.value.winRate }
            .map { it.key to it.value }
    }
    
    /**
     * Get all mode stats as a map (for Turso collective sync).
     */
    data class ModeStatsSnapshot(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val avgPnlPct: Double,
        val avgHoldMins: Double,
    )
    
    fun getAllModeStats(): Map<String, ModeStatsSnapshot> {
        return modeData.entries
            .filter { it.value.totalTrades >= 3 }  // At least 3 trades to report
            .associate { (mode, data) ->
                mode to ModeStatsSnapshot(
                    totalTrades = data.totalTrades,
                    wins = data.wins,
                    losses = data.losses,
                    avgPnlPct = data.avgPnl,
                    avgHoldMins = (data.avgHoldTimeMs / 60_000.0)
                )
            }
    }
    
    /**
     * Get best performing mode.
     */
    fun getBestMode(): String? {
        return modeData.entries
            .filter { it.value.isReliable && it.value.winRate >= 55.0 }
            .maxByOrNull { it.value.winRate * it.value.avgPnl }
            ?.key
    }
    
    /**
     * Get worst performing mode.
     */
    fun getWorstMode(): String? {
        return modeData.entries
            .filter { it.value.isReliable }
            .minByOrNull { it.value.winRate }
            ?.key
    }
    
    // ── Helpers ─────────────────────────────────────────────────────────
    
    private fun getLiquidityBucket(liqUsd: Double): String {
        return when {
            liqUsd < 5_000 -> "0-5k"
            liqUsd < 20_000 -> "5k-20k"
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
            "MICRO_CAP" -> 1_000.0 to 10_000.0
            "MOONSHOT", "PUMP_SNIPER" -> 5_000.0 to 50_000.0
            "LONG_HOLD", "WHALE_FOLLOW" -> 50_000.0 to Double.MAX_VALUE
            "REVIVAL" -> 10_000.0 to 100_000.0
            else -> 5_000.0 to 100_000.0
        }
    }
    
    // ── Persistence ─────────────────────────────────────────────────────
    
    private fun save() {
        val c = ctx ?: return
        try {
            val prefs = c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = org.json.JSONObject()
            
            modeData.forEach { (mode, data) ->
                val modeJson = org.json.JSONObject().apply {
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
                    put("phaseWins", org.json.JSONObject(data.phaseWins as Map<*, *>))
                    put("phaseLosses", org.json.JSONObject(data.phaseLosses as Map<*, *>))
                    put("liqBucketWins", org.json.JSONObject(data.liqBucketWins as Map<*, *>))
                    put("liqBucketLosses", org.json.JSONObject(data.liqBucketLosses as Map<*, *>))
                    put("sourceWins", org.json.JSONObject(data.sourceWins as Map<*, *>))
                    put("sourceLosses", org.json.JSONObject(data.sourceLosses as Map<*, *>))
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
            val json = org.json.JSONObject(jsonStr)
            
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
                
                // Load pattern maps
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
 * ═══════════════════════════════════════════════════════════════
 *
 * Expands the bot beyond pump.fun to the entire Solana ecosystem:
 *
 * SOURCES (polled on different schedules):
 * ─────────────────────────────────────────
 *  1. Pump.fun WebSocket        — new launches (existing, unchanged)
 *  2. Pump.fun graduates        — tokens migrating to Raydium (high signal)
 *  3. Dexscreener /boosted      — tokens with paid boosts (attention signal)
 *  4. Dexscreener /trending     — top movers by volume/tx last 1h on Solana
 *  5. Dexscreener /gainers      — biggest % gainers last 1h on Solana
 *  6. Birdeye trending          — Birdeye's 24h trending list (free tier)
 *  7. CoinGecko trending        — mainstream attention proxy (already wired)
 *  8. Jupiter new listings      — newly listed on Jupiter aggregator
 *  9. Raydium new pools         — new liquidity pools (Raydium API)
 * 10. Dexscreener search        — keyword scanning for narrative plays
 *
 * FILTERING (before adding to watchlist):
 * ─────────────────────────────────────────
 *  • Liquidity ≥ minLiquidityUsd (default $8K)
 *  • Volume/liquidity ratio ≥ 0.3 (active market)
 *  • Not already in watchlist or blacklist
 *  • Not a known scam pattern (name/symbol checks)
 *  • Passes Dexscreener pair score threshold
 *  • DEX filter — configurable (all/raydium/orca/meteora/pump)
 *
 * MARKET MODES:
 */

class SolanaMarketScanner(
    private val cfg: () -> BotConfig,
    private val onTokenFound: (mint: String, symbol: String, name: String,
                               source: TokenSource, score: Double, liquidityUsd: Double) -> Unit,
    private val onLog: (String) -> Unit,
    private val getBrain: () -> BotBrain? = { null },  // AI learning integration
) {
    enum class TokenSource {
        PUMP_FUN_NEW,       // brand new pump.fun launch
        PUMP_FUN_GRADUATE,  // migrated from pump.fun to Raydium
        DEX_TRENDING,       // dexscreener trending on Solana
        DEX_GAINERS,        // top % gainers last 1h
        DEX_BOOSTED,        // paid boost = attention incoming
        BIRDEYE_TRENDING,   // birdeye 24h trending
        COINGECKO_TRENDING, // CoinGecko trending
        JUPITER_NEW,        // new Jupiter listing
        RAYDIUM_NEW_POOL,   // new Raydium liquidity pool
        NARRATIVE_SCAN,     // keyword-matched narrative play
        MANUAL,             // manually added by user
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
        val dexId: String,          // raydium / orca / meteora / pump
        val priceChangeH1: Double,
        val txCountH1: Int,
        val score: Double,          // composite discovery score 0-100
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)  // Increased from 8s
        .readTimeout(15, TimeUnit.SECONDS)     // Increased from 10s for slow APIs
        // AGGRESSIVE Memory optimization
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .cache(null)
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 2  // Only 2 concurrent requests max
            maxRequestsPerHost = 1
        })
        .build()

    private val dex        = DexscreenerApi()
    private val coingecko  = CoinGeckoTrending()
    
    // V5.0: RC score cache for eligibility checks
    // Key = mint, Value = rugcheck score (0-100)
    private val rcScoreCache = ConcurrentHashMap<String, Int>()
    
    // Use a mutable scope that can be recreated when starting
    private var scope: CoroutineScope? = null
    private var scanJob: Job? = null
    
    // Create birdeye lazily with API key from config
    private val birdeye: BirdeyeApi
        get() = BirdeyeApi(cfg().birdeyeApiKey)

    // Track which mints we've already surfaced to avoid duplicates
    private val seenMints  = ConcurrentHashMap<String, Long>()
    // V5.2.8: Dynamic TTL based on mode - faster refresh in paper mode
    private fun getSeenTtl(): Long = if (cfg().paperMode) 10_000L else 15_000L  // 10s paper, 15s live
    
    // Track rejected tokens separately - very short cooldown for paper mode learning
    private val rejectedMints = ConcurrentHashMap<String, Long>()
    // V5.2.8: Dynamic rejected TTL - faster retry in paper mode
    private fun getRejectedTtl(): Long = if (cfg().paperMode) 15_000L else 30_000L  // 15s paper, 30s live
    
    // ═══════════════════════════════════════════════════════════════════════
    // V5.2 FIX: SATURATION SUPPRESSION - Stop processing same tokens 60-90x
    // Tracks how many times we've seen a token in cooldown state
    // After MAX_COOLDOWN_HITS, we suppress it for a longer period
    // V5.2.8: More lenient in paper mode for learning
    // ═══════════════════════════════════════════════════════════════════════
    private val cooldownHitCount = ConcurrentHashMap<String, Int>()
    private val saturatedMints = ConcurrentHashMap<String, Long>()
    private fun getMaxCooldownHits(): Int = if (cfg().paperMode) 10 else 5  // 10 hits paper, 5 live
    private fun getSaturationTtl(): Long = if (cfg().paperMode) 60_000L else 120_000L  // 1min paper, 2min live
    
    // ═══════════════════════════════════════════════════════════════════════
    // V5.2 FIX: THROUGHPUT TELEMETRY - Track pipeline health metrics
    // ═══════════════════════════════════════════════════════════════════════
    @Volatile private var telemetryRawScanned = 0
    @Volatile private var telemetryCooldownHits = 0
    @Volatile private var telemetryRugRejects = 0
    @Volatile private var telemetryLiqRejects = 0
    @Volatile private var telemetryEnqueued = 0
    @Volatile private var telemetryStaleDrops = 0
    @Volatile private var telemetrySaturatedDrops = 0
    private var lastTelemetryLogMs = 0L
    
    fun getThroughputTelemetry(): String {
        return "RAW=$telemetryRawScanned CD=$telemetryCooldownHits RUG=$telemetryRugRejects " +
               "LIQ=$telemetryLiqRejects ENQ=$telemetryEnqueued STALE=$telemetryStaleDrops SAT=$telemetrySaturatedDrops"
    }
    
    fun resetTelemetry() {
        telemetryRawScanned = 0
        telemetryCooldownHits = 0
        telemetryRugRejects = 0
        telemetryLiqRejects = 0
        telemetryEnqueued = 0
        telemetryStaleDrops = 0
        telemetrySaturatedDrops = 0
    }
    
    // Check if a token is saturated (seen too many times in cooldown)
    private fun isSaturated(mint: String): Boolean {
        val saturatedAt = saturatedMints[mint] ?: return false
        return System.currentTimeMillis() - saturatedAt < getSaturationTtl()
    }
    
    // Record a cooldown hit and potentially saturate the token
    private fun recordCooldownHit(mint: String) {
        telemetryCooldownHits++
        val hits = cooldownHitCount.merge(mint, 1) { a, b -> a + b } ?: 1
        if (hits >= getMaxCooldownHits()) {
            saturatedMints[mint] = System.currentTimeMillis()
            cooldownHitCount.remove(mint)  // Reset counter
            telemetrySaturatedDrops++
            ErrorLogger.debug("Scanner", "🔇 SATURATED: $mint (hit ${getMaxCooldownHits()}x in cooldown)")
        }
    }
    
    // Memory protection: limit concurrent operations
    private val semaphore = kotlinx.coroutines.sync.Semaphore(3)  // max 3 concurrent scans
    
    // Memory-safe mode - enables when OOM detected
    @Volatile private var memorySafeMode = false
    @Volatile private var oomCount = 0
    
    // Scan rotation - alternate between different scan sources for variety
    @Volatile private var scanRotation = 0
    
    /**
     * V5.0: Get cached RC score for a mint
     * Returns null if not cached
     */
    fun getCachedRcScore(mint: String): Int? = rcScoreCache[mint]
    
    // Running state
    @Volatile private var isRunning = false
    
    // Staleness detection - track last time we found a new token
    @Volatile private var lastNewTokenFoundMs = System.currentTimeMillis()
    @Volatile private var lastStalenessCheckMs = System.currentTimeMillis()
    private val STALENESS_THRESHOLD_MS = 120_000L  // 2 minutes without new tokens = stale
    
    // Public status for debugging
    fun getStatus(): String {
        val staleSeconds = (System.currentTimeMillis() - lastNewTokenFoundMs) / 1000
        return "Scanner: running=$isRunning seenMints=${seenMints.size} rejectedMints=${rejectedMints.size} scanRotation=$scanRotation stale=${staleSeconds}s"
    }
    
    // Called when a new token passes filters and gets added to watchlist
    fun recordNewTokenFound() {
        lastNewTokenFoundMs = System.currentTimeMillis()
    }
    
    // Check if scanner is stuck and needs reset
    fun checkAndResetIfStale(): Boolean {
        val now = System.currentTimeMillis()
        
        // Only check staleness every 30 seconds to avoid spam
        if (now - lastStalenessCheckMs < 30_000L) return false
        lastStalenessCheckMs = now
        
        val staleDuration = now - lastNewTokenFoundMs
        if (staleDuration > STALENESS_THRESHOLD_MS) {
            ErrorLogger.warn("Scanner", "⚠️ Scanner STALE for ${staleDuration/1000}s - forcing reset")
            onLog("⚠️ Scanner stuck for ${staleDuration/1000}s - resetting...")
            forceReset()
            lastNewTokenFoundMs = now  // Reset timer after clearing
            return true
        }
        return false
    }
    
    // Force clear all maps (emergency reset)
    fun forceReset() {
        seenMints.clear()
        rejectedMints.clear()
        cooldownHitCount.clear()
        saturatedMints.clear()
        resetTelemetry()
        scanRotation = 0
        ErrorLogger.info("Scanner", "Force reset - cleared all maps")
        onLog("🔄 Scanner reset - maps cleared")
    }
    
    // Coroutine exception handler for scanner - logs errors without crashing
    private val scannerExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // Don't log cancellation as error - it's normal when scanner stops
        if (throwable is kotlinx.coroutines.CancellationException) {
            ErrorLogger.info("Scanner", "Scanner coroutine cancelled (normal shutdown)")
            return@CoroutineExceptionHandler
        }
        ErrorLogger.error("Scanner", 
            "Scanner coroutine exception: ${throwable.javaClass.simpleName}: ${throwable.message}", 
            throwable
        )
        onLog("⚠️ Scanner error: ${throwable.javaClass.simpleName} - ${throwable.message?.take(50)}")
        // Don't crash - just log and the scan loop will continue
    }
    
    // Helper to run scan functions with proper cancellation handling
    private suspend fun runScan(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Normal cancellation - re-throw to stop properly
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.warn("Scanner", "$name error: ${e.message}")
        }
    }

    // ── Start / Stop ─────────────────────────────────────────────────

    fun start() {
        if (isRunning) {
            ErrorLogger.warn("Scanner", "Scanner already running, ignoring start()")
            return
        }
        
        ErrorLogger.info("Scanner", "SolanaMarketScanner.start() called")
        isRunning = true
        
        // Create a fresh scope each time we start - with exception handler
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + scannerExceptionHandler)
        scanJob = scope?.launch { 
            ErrorLogger.info("Scanner", "scanLoop starting...")
            
            // IMMEDIATE TEST SCAN - verify API works before main loop
            runTestScan()
            
            scanLoop() 
        }
        onLog("SolanaMarketScanner started")
    }
    
    private suspend fun runTestScan() {
        // LIFECYCLE-BASED test scan - finds newest tokens by profile, not by keyword
        // Uses token-profiles API which returns the most recently created tokens
        try {
            onLog("🧪 Running lifecycle test scan...")
            
            // Use token-profiles API - returns newest Solana tokens (no keywords needed)
            val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
            ErrorLogger.info("Scanner", "TEST: Fetching newest token profiles...")
            
            val body = get(url)
            if (body == null) {
                ErrorLogger.error("Scanner", "TEST FAILED: No response from DexScreener API")
                onLog("❌ API test failed - no response")
                return
            }
            
            val profiles = if (body.startsWith("[")) JSONArray(body) else {
                ErrorLogger.error("Scanner", "TEST FAILED: Invalid response format")
                onLog("❌ API test failed - bad format")
                return
            }
            
            ErrorLogger.info("Scanner", "TEST OK: Got ${profiles.length()} token profiles")
            onLog("✅ API OK: ${profiles.length()} profiles received")
            
            // For each token profile, get full pair data
            var added = 0
            var checked = 0
            for (i in 0 until minOf(profiles.length(), 50)) {
                if (added >= 5) break
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data for this token
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) }
                if (pair == null) {
                    ErrorLogger.debug("Scanner", "TEST: No pair data for ${mint.take(12)}...")
                    continue
                }
                
                // Skip stablecoins
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
                    score = 60.0
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
            
            if (added == 0) {
                onLog("⚠️ Test: No tokens passed (checked $checked)")
            } else {
                onLog("✅ Test: $added diverse tokens added")
            }
            
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Coroutine was cancelled - this is normal when scanner is stopped
            ErrorLogger.info("Scanner", "TEST: Scan cancelled")
            throw e  // Re-throw to properly cancel
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "TEST ERROR: ${e.message}", e)
            onLog("❌ Test error: ${e.message?.take(50)}")
        }
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

    // ── Main scan loop ────────────────────────────────────────────────

    private suspend fun scanLoop() {
        ErrorLogger.info("Scanner", "scanLoop() entered")
        while (isRunning) {
            val c = cfg()
            // Use configured interval, minimum 10 seconds for fast scanning
            val scanIntervalMs = maxOf((c.scanIntervalSecs * 1000L).toLong(), 10_000L)
            ErrorLogger.debug("Scanner", "Scan interval: ${scanIntervalMs}ms")

            try {
                // Clean expired seen entries - use safe iteration
                val now = System.currentTimeMillis()
                val expiredKeys = seenMints.entries
                    .filter { now - it.value > getSeenTtl() }
                    .map { it.key }
                expiredKeys.forEach { seenMints.remove(it) }

                // ScalingMode tier logging
                val sScanTier = ScalingMode.activeTier(
                    TreasuryManager.treasurySol * WalletManager.lastKnownSolPrice)
                val _tn = if (sScanTier != ScalingMode.Tier.MICRO)
                    " ${sScanTier.icon}${sScanTier.label}" else ""
                
                // PUMP.FUN PRIORITY: Scan pump.fun EVERY cycle, plus rotate secondary sources
                scanRotation = (scanRotation + 1) % 4  // 4 rotations for more variety
                
                // FORCE RESET every 20 cycles to prevent scanner staleness
                if (scanRotation == 0 && seenMints.size > 50) {
                    val resetCount = seenMints.size
                    seenMints.clear()
                    rejectedMints.entries.removeIf { System.currentTimeMillis() - it.value > 60_000 }  // Keep only recent rejects
                    onLog("🔄 Scanner refresh: cleared $resetCount seen tokens")
                    ErrorLogger.info("Scanner", "Forced refresh: cleared $resetCount seen mints")
                }
                
                onLog("🌐 Scan #$scanRotation${_tn} - Starting scan cycle")
                ErrorLogger.info("Scanner", "Scan cycle #$scanRotation starting")
                
                // GC before scan
                System.gc()
                
                var tokensFoundThisCycle = 0
                
                // ALWAYS scan pump.fun first (priority) - BOTH direct API and profiles
                onLog("🚀 Scanning: Pump.fun tokens (PRIORITY)...")
                runScan("scanPumpFunDirect") { scanPumpFunDirect() }  // Direct pump.fun API
                delay(200)
                runScan("scanPumpFunActive") { scanPumpFunActive() }  // DexScreener profiles
                delay(200)
                
                // SCAN ALL SOURCES - Same coverage for both paper and live modes
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
                
                // GC after scan
                System.gc()
                onLog("✅ Scan cycle #$scanRotation complete")
                
                // Clean up old seen/rejected entries every cycle
                cleanupSeenMaps()
                
                // V4.0: Use GlobalTradeRegistry for authoritative watchlist count
                val watchlistSize = GlobalTradeRegistry.size()
                if (scanRotation == 0) {
                    ErrorLogger.info("Scanner", "Discovery health: seen=${seenMints.size} rejected=${rejectedMints.size} watchlist=$watchlistSize (GlobalTradeRegistry)")
                    onLog("📊 Discovery: seen=${seenMints.size} rejected=${rejectedMints.size} watchlist=$watchlistSize")
                }
                
                // AUTO-RECOVER: If watchlist is empty or very small, force scanner refresh
                if (watchlistSize <= 2 && seenMints.size > 30) {
                    onLog("⚠️ Watchlist depleted - forcing scanner refresh")
                    ErrorLogger.warn("Scanner", "Auto-recovery: watchlist=$watchlistSize, clearing ${seenMints.size} seen mints")
                    seenMints.clear()
                    rejectedMints.clear()
                }
                
                // GC after scan
                System.gc()

            } catch (e: OutOfMemoryError) {
                oomCount++
                ErrorLogger.crash("Scanner", "OutOfMemoryError #$oomCount", Exception(e.message))
                onLog("⚠️ Memory critical #$oomCount - pausing 30s")
                System.gc()
                delay(30_000)  // 30 second pause
                if (oomCount >= 5) {
                    onLog("⛔ Too many OOM errors - scanner pausing. Restart to retry.")
                    ErrorLogger.error("Scanner", "Scanner paused after $oomCount OOM errors")
                    delay(300_000)  // 5 minute pause, then continue
                    oomCount = 0  // Reset counter to try again
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation - don't log as error
                ErrorLogger.info("Scanner", "Scanner coroutine cancelled (normal shutdown)")
                throw e  // Re-throw to properly cancel
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                ErrorLogger.error("Scanner", "Scanner error: ${e.message}", e)
                onLog("Scanner: ${e.message?.take(50)}")
            }

            delay(scanIntervalMs)
        }
    }


    // ══════════════════════════════════════════════════════════════════════════
    // PUMP.FUN FOCUSED SCANNING - Finding best profit opportunities
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * LIFECYCLE-BASED: Scan for newest token profiles.
     * Gets the most recently created tokens from DexScreener's token-profiles API.
     * No keywords - pure lifecycle discovery.
     */
    private suspend fun scanPumpFunActive() {
        // Use token-profiles API - returns newest tokens (no keywords)
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanPumpFunActive: fetching newest token profiles...")
        val body = get(url) ?: return
        
        try {
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(profiles.length(), 100)) {
                if (found >= 40) break  // INCREASED from 15 to 40
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data for this token
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                val liq = pair.liquidity
                if (liq < 1000) continue
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Lifecycle scoring - newer tokens get bonus
                val ageBonus = when {
                    ageHours < 0.5 -> 35.0  // < 30 mins = very fresh
                    ageHours < 1 -> 30.0    // < 1 hour
                    ageHours < 3 -> 25.0    // < 3 hours
                    ageHours < 6 -> 20.0    // < 6 hours
                    ageHours < 12 -> 15.0   // < 12 hours
                    ageHours < 24 -> 10.0   // < 24 hours
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
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + ageBonus
                )
                
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    val freshIcon = if (ageHours < 1) "🆕" else if (ageHours < 6) "📈" else "📊"
                    onLog("$freshIcon NEW: ${pair.baseSymbol} | ${ageHours.toInt()}h old | liq=\$${liq.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanPumpFunActive: found $found tokens (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpFunActive error: ${e.message}")
        }
        System.gc()
    }

    /**
     * DIRECT PUMP.FUN SCAN - Fetch newest tokens directly from pump.fun API
     * This is the PRIMARY source for early pump.fun entries
     * EXPANDED: More endpoints, higher limits, deeper scanning
     */
    private suspend fun scanPumpFunDirect() {
        // Pump.fun API endpoints - EXPANDED for wider coverage
        val urls = listOf(
            // Primary: newest coins sorted by creation - INCREASED LIMIT
            "https://frontend-api.pump.fun/coins?offset=0&limit=100&sort=created_timestamp&order=DESC&includeNsfw=false",
            // Page 2 of newest
            "https://frontend-api.pump.fun/coins?offset=100&limit=100&sort=created_timestamp&order=DESC&includeNsfw=false",
            // Recently active/traded
            "https://frontend-api.pump.fun/coins?offset=0&limit=100&sort=last_trade_timestamp&order=DESC&includeNsfw=false",
            // Top by market cap (graduates)
            "https://frontend-api.pump.fun/coins?offset=0&limit=50&sort=market_cap&order=DESC&includeNsfw=false",
            // Top by reply count (engagement)
            "https://frontend-api.pump.fun/coins?offset=0&limit=50&sort=reply_count&order=DESC&includeNsfw=false",
        )
        
        ErrorLogger.info("Scanner", "scanPumpFunDirect: fetching from ${urls.size} pump.fun endpoints...")
        
        var totalFound = 0
        
        for (url in urls) {
            try {
                val body = get(url) ?: continue
                
                // Parse response - could be array or object with coins property
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
                    if (found >= 30) break  // INCREASED from 10 to 30 per endpoint
                    
                    val coin = coins.optJSONObject(i) ?: continue
                    
                    // Pump.fun coin structure
                    val mint = coin.optString("mint", "")
                        .ifBlank { coin.optString("address", "") }
                        .ifBlank { coin.optString("token_address", "") }
                    
                    if (mint.isBlank() || isSeen(mint)) continue
                    
                    val symbol = coin.optString("symbol", "")
                        .ifBlank { coin.optString("ticker", "") }
                    val name = coin.optString("name", "")
                    
                    if (symbol.isBlank()) continue
                    
                    // Get additional data from DexScreener for liquidity/volume
                    val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) }
                    
                    val liq = pair?.liquidity ?: coin.optDouble("usd_market_cap", 0.0) * 0.1
                    val mcap = pair?.candle?.marketCap ?: coin.optDouble("usd_market_cap", 0.0)
                    val vol = pair?.candle?.volumeH1 ?: 0.0
                    
                    // Calculate age from created_timestamp
                    val createdTs = coin.optLong("created_timestamp", 0L)
                    val ageHours = if (createdTs > 0) {
                        (now - createdTs) / 3_600_000.0
                    } else {
                        (pair?.pairCreatedAtMs?.let { (now - it) / 3_600_000.0 }) ?: 24.0
                    }
                    
                    // Skip very old tokens (focus on fresh ones)
                    if (ageHours > 48) continue
                    
                    // Pump.fun specific bonus - these are the tokens we want!
                    val pumpBonus = when {
                        ageHours < 0.25 -> 50.0  // < 15 mins = VERY fresh
                        ageHours < 0.5 -> 45.0   // < 30 mins
                        ageHours < 1 -> 40.0     // < 1 hour
                        ageHours < 3 -> 30.0     // < 3 hours
                        ageHours < 6 -> 20.0     // < 6 hours
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
                        score = scoreToken(liq, vol, 0, mcap, 0.0, ageHours) + pumpBonus
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
                delay(100)  // Small delay between API calls
                
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                ErrorLogger.warn("Scanner", "scanPumpFunDirect error: ${e.message}")
            }
        }
        
        if (totalFound > 0) {
            onLog("🚀 Pump.fun direct: $totalFound new tokens found")
        }
        System.gc()
    }

    /**
     * LIFECYCLE-BASED: Scan for BOOSTED tokens.
     * Tokens that are being promoted = attention incoming.
     * No keywords - pure discovery based on which tokens are being boosted.
     */
    private suspend fun scanPumpFunVolume() {
        // Use boosted tokens API - tokens being promoted (no keywords)
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        ErrorLogger.info("Scanner", "scanPumpFunVolume: fetching boosted tokens...")
        val body = get(url) ?: return
        
        try {
            val boosted = if (body.startsWith("[")) JSONArray(body) else return
            
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(boosted.length(), 50)) {
                if (found >= 15) break
                
                val item = boosted.optJSONObject(i) ?: continue
                
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                        if (liq > 0) ErrorLogger.info("Scanner", "scanPumpFunVolume: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                    }
                }
                
                if (liq < 1000) continue  // Boosted tokens should have some liquidity
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Boosted tokens get attention bonus
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
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + boostBonus
                )
                
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    onLog("💎 BOOST: ${pair.baseSymbol} | age=${ageHours.toInt()}h | liq=\$${liq.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanPumpFunVolume: found $found boosted tokens (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpFunVolume error: ${e.message}")
        }
        System.gc()
    }

    /**
     * Scan for FRESH LAUNCHES - tokens created in the last 30 minutes.
     * Early entry = maximum profit potential (but also maximum risk).
     * Uses token-profiles API which returns newest tokens.
     */
    private suspend fun scanFreshLaunches() {
        // Use token-profiles API to find fresh Solana tokens
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanFreshLaunches: looking for tokens < 30 mins old...")
        val body = get(url) ?: return
        
        try {
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            
            val now = System.currentTimeMillis()
            var found = 0
            
            for (i in 0 until minOf(profiles.length(), 30)) {
                if (found >= 15) break
                val profile = profiles.optJSONObject(i) ?: continue
                
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "") 
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                // Get pair data for this token
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val ageMinutes = ageHours * 60.0
                
                // Focus on fresh tokens (< 2 hours for more results)
                if (ageHours > 2) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 500 } ?: 0.0
                        if (liq > 0) ErrorLogger.info("Scanner", "scanFreshLaunches: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                    }
                }
                
                // Fresh tokens - lower liquidity threshold
                if (liq < 500) continue
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val name = pair.baseName
                val symbol = pair.baseSymbol
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                val dexId = "solana"
                
                // Fresh launch bonus - the newer, the better
                val freshnessBonus = when {
                    ageMinutes < 5 -> 40.0   // < 5 mins = ultra fresh
                    ageMinutes < 10 -> 35.0  // < 10 mins
                    ageMinutes < 15 -> 30.0  // < 15 mins
                    ageMinutes < 30 -> 25.0  // < 30 mins
                    ageMinutes < 60 -> 20.0  // < 1 hour
                    else -> 15.0             // < 2 hours
                }
                
                val token = ScannedToken(
                    mint = mint, symbol = symbol, name = name,
                    source = TokenSource.RAYDIUM_NEW_POOL,
                    liquidityUsd = liq, volumeH1 = vol, mcapUsd = mcap,
                    pairCreatedHoursAgo = ageHours, dexId = dexId,
                    priceChangeH1 = 0.0, txCountH1 = buys + sells,
                    score = scoreToken(liq, vol, buys + sells, mcap, 0.0, ageHours) + freshnessBonus
                )
                
                if (passesFilter(token)) {
                    emitWithRugcheck(token)
                    found++
                    onLog("🆕 FRESH: $symbol | ${ageMinutes.toInt()}m old | liq=\$${liq.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanFreshLaunches: found $found fresh tokens")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanFreshLaunches error: ${e.message}")
        }
        System.gc()
    }


    // ── Source 1: Dexscreener trending ───────────────────────────────

    private suspend fun scanDexTrending() {
        // Look for trending Solana tokens
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanDexTrending: fetching from DexScreener...")
        val body = get(url)
        if (body == null) {
            ErrorLogger.warn("Scanner", "scanDexTrending: no response from API")
            return
        }
        try {
            // Validate JSON array format
            if (!body.trim().startsWith("[")) {
                ErrorLogger.warn("Scanner", "scanDexTrending: invalid response format")
                return
            }
            val arr = JSONArray(body)
            ErrorLogger.info("Scanner", "scanDexTrending: got ${arr.length()} token profiles")
            var processed = 0
            var passed = 0
            for (i in 0 until minOf(arr.length(), 50)) {  // INCREASED from 15 to 50
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress", "").trim()
                if (mint.isBlank() || mint.length < 32 || mint.startsWith("0x") || isSeen(mint)) continue
                processed++
                
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) }
                if (pair == null) {
                    ErrorLogger.debug("Scanner", "scanDexTrending: no pair for ${mint.take(12)}")
                    continue
                }
                
                // Skip major stablecoins only
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye as fallback
                // Then try using FDV/mcap as a proxy (typical ratio is 10-20% of mcap)
                var fallbackLiq = 0.0
                if (pair.liquidity <= 0) {
                    // Try Birdeye first
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    fallbackLiq = overview?.liquidity ?: 0.0
                    
                    if (fallbackLiq > 0) {
                        ErrorLogger.info("Scanner", "scanDexTrending: ${pair.baseSymbol} used Birdeye liq=\$${fallbackLiq.toInt()}")
                    } else if (pair.fdv > 0 || pair.candle.marketCap > 0) {
                        // Estimate liquidity as ~10% of FDV/mcap (conservative estimate)
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        fallbackLiq = mcap * 0.10
                        if (fallbackLiq > 1000) {  // Only use if meaningful
                            ErrorLogger.info("Scanner", "scanDexTrending: ${pair.baseSymbol} estimated liq=\$${fallbackLiq.toInt()} from mcap=\$${mcap.toInt()}")
                        } else {
                            fallbackLiq = 0.0  // Too small, skip
                        }
                    }
                }
                
                val token = buildScannedToken(mint, pair, TokenSource.DEX_TRENDING, fallbackLiq) ?: continue
                if (passesFilter(token)) {
                    val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
                    emitWithRugcheck(token)
                    passed++
                    onLog("📈 Trending: ${token.symbol} | age=${ageHours.toInt()}h | liq=$${token.liquidityUsd.toInt()}")
                }
            }
            ErrorLogger.info("Scanner", "scanDexTrending: processed=$processed passed=$passed")
        } catch (e: OutOfMemoryError) {
            ErrorLogger.error("Scanner", "OOM in scanDexTrending", Exception(e.message))
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexTrending error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 2: LIFECYCLE-BASED new token discovery ─────────

    private suspend fun scanDexGainers() {
        // LIFECYCLE-BASED: Use token-profiles API for diverse new token discovery
        // No keywords - finds tokens purely by when they were created
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        ErrorLogger.info("Scanner", "scanDexGainers: fetching newest token profiles...")
        val body = get(url)
        if (body == null) {
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
            
            for (i in 0 until minOf(profiles.length(), 30)) {
                if (found >= 15) break
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1000 } ?: 0.0
                        if (liq > 0) ErrorLogger.info("Scanner", "scanDexGainers: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                    }
                }
                
                if (liq < 1000) continue  // Min $1K liquidity
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Lifecycle-based scoring - new tokens get bonus
                val ageBonus = when {
                    ageHours < 1 -> 30.0   // < 1 hour = very fresh
                    ageHours < 6 -> 25.0   // < 6 hours = fresh
                    ageHours < 12 -> 20.0  // < 12 hours
                    ageHours < 24 -> 15.0  // < 24 hours = new
                    ageHours < 48 -> 10.0  // < 48 hours
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
            if (found > 0) ErrorLogger.info("Scanner", "scanDexGainers: found $found tokens (checked $checked)")
            else ErrorLogger.info("Scanner", "scanDexGainers: no tokens passed filters (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexGainers error: ${e.message}")
        }
        System.gc()
    }
    // ── Source 3: Dexscreener boosted tokens ─────────────────────────

    private suspend fun scanDexBoosted() {
        // Boosted = paid promotion = attention arriving
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        val body = get(url) ?: return
        try {
            // Validate JSON array format
            if (!body.trim().startsWith("[")) {
                ErrorLogger.warn("Scanner", "scanDexBoosted: invalid response format")
                return
            }
            val arr = JSONArray(body)
            for (i in 0 until minOf(arr.length(), 12)) {
                val item = arr.optJSONObject(i) ?: continue
                val mint = item.optString("tokenAddress","")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip only stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
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
                // Boosted tokens get score bump
                val boostedToken = token.copy(score = (token.score + 15.0).coerceAtMost(100.0))
                if (passesFilter(boostedToken)) {
                    emit(boostedToken)
                    onLog("💎 Boosted: ${token.symbol} | age=${ageHours.toInt()}h | liq=$${token.liquidityUsd.toInt()}")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanDexBoosted error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 4: LIFECYCLE-BASED Pump.fun graduates ────────────

    private suspend fun scanPumpGraduates() {
        // LIFECYCLE-BASED: Use boosted tokens API which often contains recently graduated tokens
        // Boosted tokens are typically new and attention-worthy
        val url = "https://api.dexscreener.com/token-boosts/top/v1?chainIds=solana"
        ErrorLogger.info("Scanner", "scanPumpGraduates: fetching boosted tokens for graduates...")
        val body = get(url) ?: return
        try {
            val boosted = if (body.startsWith("[")) JSONArray(body) else return
            
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(boosted.length(), 50)) {
                if (found >= 15) break
                
                val item = boosted.optJSONObject(i) ?: continue
                
                val mint = item.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
                var liq = pair.liquidity
                if (liq <= 0) {
                    val overview = withContext(Dispatchers.IO) { birdeye.getTokenOverview(mint) }
                    liq = overview?.liquidity ?: 0.0
                    
                    if (liq <= 0 && (pair.fdv > 0 || pair.candle.marketCap > 0)) {
                        val mcap = if (pair.fdv > 0) pair.fdv else pair.candle.marketCap
                        liq = (mcap * 0.10).takeIf { it > 1500 } ?: 0.0
                        if (liq > 0) ErrorLogger.info("Scanner", "scanPumpGraduates: ${pair.baseSymbol} estimated liq=\$${liq.toInt()} from mcap")
                    }
                }
                
                if (liq < 1500) continue  // Graduates should have decent liquidity
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Graduate/attention bonus scoring
                val graduateBonus = when {
                    ageHours < 1 -> 30.0   // Just launched
                    ageHours < 6 -> 25.0   // Fresh
                    ageHours < 12 -> 20.0  // Recent
                    ageHours < 24 -> 15.0  // New
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
            if (found > 0) ErrorLogger.info("Scanner", "scanPumpGraduates: found $found tokens (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanPumpGraduates error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 5: Birdeye trending ────────────────────────────────────

    private suspend fun scanBirdeyeTrending() {
        val c = cfg()
        if (c.birdeyeApiKey.isBlank()) return  // needs key
        val url = "https://public-api.birdeye.so/defi/token_trending?sort_by=rank&sort_type=asc&offset=0&limit=50"  // INCREASED
        val body = get(url, apiKey = c.birdeyeApiKey) ?: return
        try {
            val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: return
            for (i in 0 until minOf(items.length(), 40)) {  // INCREASED from 12 to 40
                val item = items.optJSONObject(i) ?: continue
                val mint = item.optString("address","")
                if (mint.isBlank() || isSeen(mint)) continue
                
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip only stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT")) continue
                
                // If DexScreener returned $0 liquidity, try Birdeye then estimate from mcap
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
                    onLog("🦅 Birdeye: ${token.symbol} | age=${ageHours.toInt()}h | liq=$${token.liquidityUsd.toInt()}")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanBirdeye error: ${e.message}")
        }
        System.gc()
    }

    // ── Source 5b: Top Volume Tokens (new diverse source) ─────────────

    private suspend fun scanTopVolumeTokens() {
        // LIFECYCLE-BASED: Use token-profiles API for diverse discovery
        // Gets the most recent token profiles - no keywords
        val url = "https://api.dexscreener.com/token-profiles/latest/v1?chainId=solana"
        
        try {
            val body = get(url) ?: return
            val profiles = if (body.startsWith("[")) JSONArray(body) else return
            
            ErrorLogger.info("Scanner", "scanTopVolume: got ${profiles.length()} token profiles")
            val now = System.currentTimeMillis()
            var found = 0
            var checked = 0
            
            for (i in 0 until minOf(profiles.length(), 50)) {
                if (found >= 15) break
                
                val profile = profiles.optJSONObject(i) ?: continue
                if (profile.optString("chainId", "") != "solana") continue
                
                val mint = profile.optString("tokenAddress", "")
                if (mint.isBlank() || mint.startsWith("0x") || isSeen(mint)) continue
                
                checked++
                
                // Get full pair data
                val pair = withContext(Dispatchers.IO) { dex.getBestPair(mint) } ?: continue
                
                // Skip stablecoins
                if (pair.baseSymbol.uppercase() in listOf("SOL", "WSOL", "USDC", "USDT", "RAY", "JUP")) continue
                
                val liq = pair.liquidity
                if (liq < 1000) continue
                
                val vol = pair.candle.volumeH1
                val mcap = pair.candle.marketCap
                val ageHours = (now - pair.pairCreatedAtMs) / 3_600_000.0
                val buys = pair.candle.buysH1
                val sells = pair.candle.sellsH1
                
                // Volume activity bonus
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
            
            if (found > 0) ErrorLogger.info("Scanner", "scanTopVolume: found $found tokens (checked $checked)")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            ErrorLogger.error("Scanner", "scanTopVolume error: ${e.message}")
        }
        System.gc()
    }


    // ── Source 6: CoinGecko trending ─────────────────────────────────

    private suspend fun scanCoinGeckoTrending() {
        // Skip CoinGecko - it requires extra API calls to resolve tokens
        ErrorLogger.debug("Scanner", "Skipping CoinGecko scan (memory optimization)")
        return
    }

    // ── Source 7: Raydium new pools ───────────────────────────────────

    private suspend fun scanRaydiumNewPools() {
        // Skip Raydium pools scan - returns huge JSON responses
        ErrorLogger.debug("Scanner", "Skipping Raydium scan (memory optimization)")
        return
    }

    // ── Source 8: Narrative scanning ─────────────────────────────────

    private suspend fun scanNarratives(keywords: List<String>) {
        keywords.forEach { kw ->
            if (kw.isBlank()) return@forEach
            val results = withContext(Dispatchers.IO) { dex.search(kw) }
            results.take(5).forEach { pair ->
                val mint = pair.pairAddress
                if (mint.isBlank() || isSeen(mint)) return@forEach
                val token = ScannedToken(
                    mint               = mint,
                    symbol             = pair.baseSymbol,
                    name               = pair.baseName,
                    source             = TokenSource.NARRATIVE_SCAN,
                    liquidityUsd       = pair.liquidity,
                    volumeH1           = pair.candle.volumeH1,
                    mcapUsd            = pair.candle.marketCap,
                    pairCreatedHoursAgo = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0,
                    dexId              = "unknown",
                    priceChangeH1      = 0.0,
                    txCountH1          = pair.candle.buysH1 + pair.candle.sellsH1,
                    score              = scoreToken(pair.liquidity, pair.candle.volumeH1,
                                                    pair.candle.buysH1 + pair.candle.sellsH1,
                                                    pair.candle.marketCap, 0.0,
                                                    (System.currentTimeMillis() - pair.pairCreatedAtMs)/3_600_000.0),
                )
                if (passesFilter(token)) emitWithRugcheck(token)
            }
            delay(500)
        }
    }

    // ── Scoring ───────────────────────────────────────────────────────

    /**
     * Composite discovery score for ranking tokens before adding to watchlist.
     * Higher = more interesting opportunity.
     */
    private fun scoreToken(
        liqUsd: Double, volH1: Double, txH1: Int,
        mcap: Double, priceChangeH1: Double, ageHours: Double,
    ): Double {
        var s = 0.0

        // Liquidity — sweet spot is $10K-$500K
        // Too low = exit risk, too high = slow mover
        s += when {
            liqUsd > 1_000_000 -> 10.0   // large — stable but slower
            liqUsd >   500_000 -> 20.0
            liqUsd >   100_000 -> 35.0   // ideal range
            liqUsd >    50_000 -> 45.0   // sweet spot
            liqUsd >    10_000 -> 35.0
            liqUsd >     5_000 -> 20.0
            else               -> 5.0
        }

        // Volume/liquidity ratio — high ratio = real activity vs just parked liquidity
        val volLiqRatio = if (liqUsd > 0) volH1 / liqUsd else 0.0
        s += when {
            volLiqRatio > 5.0  -> 25.0   // extremely active
            volLiqRatio > 2.0  -> 20.0
            volLiqRatio > 1.0  -> 15.0
            volLiqRatio > 0.5  -> 10.0
            volLiqRatio > 0.2  -> 5.0
            else               -> 0.0
        }

        // Transaction count — real buyers not just big whale trades
        s += when {
            txH1 > 500 -> 20.0
            txH1 > 200 -> 15.0
            txH1 > 100 -> 10.0
            txH1 >  50 -> 5.0
            txH1 >  20 -> 2.0
            else       -> 0.0
        }

        // Price momentum
        s += when {
            priceChangeH1 > 100 -> 15.0
            priceChangeH1 >  50 -> 12.0
            priceChangeH1 >  20 -> 8.0
            priceChangeH1 >  10 -> 5.0
            priceChangeH1 <   0 -> -10.0  // already falling
            else                -> 0.0
        }

        // Age sweet spot — too new = rug risk, too old = might be dead
        s += when {
            ageHours < 0.5  -> 5.0    // very fresh — high risk/reward
            ageHours < 2.0  -> 15.0   // sweet spot for launch snipe
            ageHours < 6.0  -> 20.0   // ideal for range/reclaim
            ageHours < 24.0 -> 15.0
            ageHours < 72.0 -> 10.0
            ageHours < 168.0 -> 5.0   // 1 week
            else            -> 2.0
        }

        return s.coerceIn(0.0, 100.0)
    }
    
    /**
     * Get AI-driven boost for a source based on historical win rates.
     * Returns a score adjustment (-20 to +20) based on how well this source has performed.
     */
    private fun getAISourceBoost(source: TokenSource): Double {
        val brain = getBrain() ?: return 0.0
        val sourceName = source.name
        val boost = brain.getSourceBoost(sourceName)
        
        // Scale the boost for discovery scoring
        return when {
            boost >= 15.0 -> 15.0   // Highly profitable source — prioritize
            boost >= 10.0 -> 10.0
            boost >= 5.0 -> 5.0
            boost <= -15.0 -> -15.0  // Consistently losing source — deprioritize
            boost <= -10.0 -> -10.0
            boost <= -5.0 -> -5.0
            else -> 0.0
        }
    }
    
    /**
     * Get AI-driven risk check for a token based on TradingMemory.
     * Returns true if the token should be skipped.
     */
    private fun aiShouldSkipToken(mint: String, symbol: String, liquidity: Double, mcap: Double): Boolean {
        // Check TradingMemory for past losses on this token
        val tokenHistory = TradingMemory.getTokenLossHistory(mint)
        if (tokenHistory != null && tokenHistory.lossCount >= 2) {
            onLog("🤖 AI SKIP: $symbol — ${tokenHistory.lossCount} prior losses")
            return true
        }
        
        // Check for creator blacklist (if available via TradingMemory)
        // Note: Would need creator wallet to check, but we don't have it in scanner
        
        return false
    }

    // ── Filtering ─────────────────────────────────────────────────────

    private fun passesFilter(token: ScannedToken): Boolean {
        // V4.20: Check if suppressed due to low liquidity (efficiency optimization)
        if (EfficiencyLayer.isLiquiditySuppressed(token.mint)) {
            ErrorLogger.debug("Scanner", "SKIP ${token.symbol}: liquidity-suppressed")
            return false
        }
        
        // V4.20: Check discovery cooldown - prevents duplicate processing across venues
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
        
        // V4.20: Record liquidity snapshot for fusion
        val liqQuality = when (token.source) {
            TokenSource.RAYDIUM_NEW_POOL -> EfficiencyLayer.LiqSourceQuality.DIRECT_POOL
            TokenSource.DEX_BOOSTED, TokenSource.DEX_TRENDING -> EfficiencyLayer.LiqSourceQuality.DEX_AGGREGATOR
            TokenSource.PUMP_FUN_NEW, TokenSource.PUMP_FUN_GRADUATE -> EfficiencyLayer.LiqSourceQuality.VERIFIED_PAIR
            else -> EfficiencyLayer.LiqSourceQuality.ESTIMATED_MCAP
        }
        EfficiencyLayer.recordLiquidity(token.mint, token.liquidityUsd, token.source.name, liqQuality)
        
        val passed = passesFilterInternal(token)
        if (!passed) {
            // Mark rejected tokens so we don't keep re-scanning them
            markRejected(token.mint)
            
            // V4.20: If rejected due to low liquidity, register for suppression
            val c = cfg()
            val liqFloor = if (c.paperMode) 100.0 else 3000.0
            if (token.liquidityUsd < liqFloor && token.liquidityUsd > 0) {
                EfficiencyLayer.registerLiquidityRejection(token.mint, token.liquidityUsd, liqFloor)
            }
        } else {
            // Mark PASSING tokens as seen immediately to prevent duplicate processing
            // This prevents the same token from being evaluated multiple times
            // while waiting for rugcheck or other async operations
            seenMints[token.mint] = System.currentTimeMillis()
            ErrorLogger.info("Scanner", "FILTER PASS ${token.symbol}: liq=\$${token.liquidityUsd.toInt()} score=${token.score.toInt()} (${decision.reason})")
        }
        return passed
    }
    
    private fun passesFilterInternal(token: ScannedToken): Boolean {
        val c = cfg()
        val isPaperMode = c.paperMode

        // ═══════════════════════════════════════════════════════════════════
        // PAPER MODE: Skip permanent ban check - we want to trade everything
        // This allows learning from tokens that were previously banned
        // ═══════════════════════════════════════════════════════════════════
        if (!isPaperMode && BannedTokens.isBanned(token.mint)) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: PERMANENTLY BANNED")
            return false
        }

        // Check if this is a pump.fun token - they get special treatment
        val isPumpFunToken = token.source == TokenSource.PUMP_FUN_NEW || 
                             token.dexId == "pump.fun" ||
                             token.pairCreatedHoursAgo < 1.0  // Very new = likely pump.fun
        
        // V5.0: PAPER MODE still needs minimum liquidity to be tradeable
        // Without liquidity, we can't learn anything meaningful
        if (isPaperMode) {
            // Paper mode floor: $1000 liquidity minimum
            // This filters out truly untradeable tokens while still allowing learning
            val PAPER_LIQ_FLOOR = 1000.0
            if (token.liquidityUsd > 0 && token.liquidityUsd < PAPER_LIQ_FLOOR) {
                ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: paper liq \$${token.liquidityUsd.toInt()} < \$$PAPER_LIQ_FLOOR")
                return false
            }
            // Only reject if literally zero or negative values
            if (token.mcapUsd < 0) return false
            // Allow through if liquidity is OK
            return true
        }
        
        // REAL MODE: Apply filters as normal
        // HARD MINIMUM MCAP - LOWER for pump.fun tokens
        val HARD_MIN_MCAP = if (isPumpFunToken) 500.0 else 2_000.0
        if (token.mcapUsd > 0 && token.mcapUsd < HARD_MIN_MCAP) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: mcap $${token.mcapUsd.toInt()} < hard min $${HARD_MIN_MCAP.toInt()}")
            return false
        }

        // Minimum liquidity - MUCH LOWER for pump.fun to catch early entries
        val HARD_MIN_LIQ = if (isPumpFunToken) 100.0 else 500.0
        if (token.liquidityUsd < HARD_MIN_LIQ && token.liquidityUsd > 0) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: liq $${token.liquidityUsd.toInt()} < min $${HARD_MIN_LIQ.toInt()}")
            return false
        }

        // DEX filter — user can restrict to specific DEXs
        if (c.allowedDexes.isNotEmpty() && token.dexId !in c.allowedDexes) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: DEX ${token.dexId} not in allowed list")
            return false
        }

        // MC range filter (user configurable, in addition to hard minimum)
        if (c.scanMinMcapUsd > 0 && token.mcapUsd < c.scanMinMcapUsd) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: mcap $${token.mcapUsd.toInt()} < min $${c.scanMinMcapUsd.toInt()}")
            return false
        }
        if (c.scanMaxMcapUsd > 0 && token.mcapUsd > c.scanMaxMcapUsd) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: mcap $${token.mcapUsd.toInt()} > max $${c.scanMaxMcapUsd.toInt()}")
            return false
        }

        // Source filter — user can disable specific sources
        if (token.source == TokenSource.PUMP_FUN_NEW && !c.scanPumpFunNew) return false
        if (token.source == TokenSource.PUMP_FUN_GRADUATE && !c.scanPumpGraduates) return false
        if (token.source == TokenSource.DEX_TRENDING && !c.scanDexTrending) return false
        if (token.source == TokenSource.RAYDIUM_NEW_POOL && !c.scanRaydiumNew) return false

        // Minimum discovery score - VERY LOW for maximum discovery
        // We want to discover as many tokens as possible - let strategy filter
        val MIN_SCORE = 5.0  // Lowered from 10 - let more through
        if (token.score < MIN_SCORE) {
            ErrorLogger.debug("Scanner", "FILTER REJECT ${token.symbol}: score ${token.score.toInt()} < min ${MIN_SCORE.toInt()}")
            return false
        }

        // Name/symbol scam heuristics
        val sym = token.symbol.lowercase()
        val name = token.name.lowercase()
        val scamPatterns = listOf("test","fake","scam","rug","honeypot","xxx","porn")
        if (scamPatterns.any { sym.contains(it) || name.contains(it) }) {
            onLog("🚫 BLOCK: ${token.symbol} - scam pattern")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: scam pattern detected")
            return false
        }
        
        // Block tokens impersonating ONLY the actual major infrastructure tokens
        // We want meme coins like "Baby Doge" or "Shiba Moon" - those are fine
        // We only block tokens pretending to BE Solana/Raydium/Jupiter etc.
        val infrastructureTokens = listOf(
            "solana", "wrapped sol", "wrapped solana",  // Core chain token
            "raydium", "jupiter", "jito", "pyth", "marinade", "orca",  // Solana infrastructure
            "pump", "pumpfun", "pump.fun"  // Pump.fun itself
        )
        
        val symLower = token.symbol.lowercase().trim()
        val nameLower = token.name.lowercase().trim()
        
        // Block exact matches of major token symbols only
        val blockedSymbols = listOf("sol", "wsol", "usdt", "usdc", "ray", "jup")
        if (symLower in blockedSymbols) {
            onLog("🚫 BLOCK: ${token.symbol} - reserved symbol")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: blocked symbol '$symLower'")
            return false
        }
        
        for (infra in infrastructureTokens) {
            // Only block exact name matches for infrastructure tokens
            if (nameLower == infra || nameLower.startsWith("$infra ")) {
                onLog("🚫 BLOCK: ${token.symbol} - impersonates $infra")
                ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: impersonates '$infra' (name='${token.name}')")
                return false
            }
        }
        
        // Block tokens with suspicious market caps (over $500M is likely fake data)
        if (token.mcapUsd > 500_000_000) {
            onLog("🚫 BLOCK: ${token.symbol} - fake mcap \$${(token.mcapUsd/1_000_000).toInt()}M")
            ErrorLogger.info("Scanner", "FILTER REJECT ${token.symbol}: suspicious mcap $${(token.mcapUsd/1_000_000).toInt()}M")
            return false
        }
        
        // Token passed all filters!
        ErrorLogger.info("Scanner", "FILTER PASS ${token.symbol}: liq=$${token.liquidityUsd.toInt()} score=${token.score.toInt()}")
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /**
     * Build a ScannedToken from pair data.
     * @param fallbackLiquidity Optional liquidity from another source (e.g., Birdeye) 
     *                          to use if DexScreener returns $0
     */
    private fun buildScannedToken(
        mint: String,
        pair: com.lifecyclebot.network.PairInfo,
        source: TokenSource,
        fallbackLiquidity: Double = 0.0,
    ): ScannedToken? {
        if (pair.candle.priceUsd <= 0) return null
        
        // Use DexScreener liquidity, or fallback if DexScreener returned $0
        val liquidity = if (pair.liquidity > 0) pair.liquidity else fallbackLiquidity
        
        // Skip only if BOTH sources returned $0 liquidity
        if (liquidity <= 0) {
            ErrorLogger.debug("Scanner", "Skipping ${pair.baseSymbol}: $0 liquidity from all sources")
            return null
        }
        
        val ageHours = (System.currentTimeMillis() - pair.pairCreatedAtMs) / 3_600_000.0
        return ScannedToken(
            mint               = mint,
            symbol             = pair.baseSymbol,
            name               = pair.baseName,
            source             = source,
            liquidityUsd       = liquidity,
            volumeH1           = pair.candle.volumeH1,
            mcapUsd            = pair.candle.marketCap,
            pairCreatedHoursAgo = ageHours.coerceAtLeast(0.0),
            dexId              = "solana",
            priceChangeH1      = 0.0,
            txCountH1          = pair.candle.buysH1 + pair.candle.sellsH1,
            score              = scoreToken(liquidity, pair.candle.volumeH1,
                                            pair.candle.buysH1 + pair.candle.sellsH1,
                                            pair.candle.marketCap, 0.0, ageHours),
        )
    }

    private fun isSeen(mint: String): Boolean {
        val now = System.currentTimeMillis()
        
        // V5.2 FIX: Check if saturated FIRST (most aggressive filter)
        if (isSaturated(mint)) {
            telemetrySaturatedDrops++
            return true  // Suppressed due to excessive cooldown churn
        }
        
        // Check if rejected (1 hour cooldown)
        val rejectedAt = rejectedMints[mint]
        if (rejectedAt != null && now - rejectedAt < getRejectedTtl()) {
            recordCooldownHit(mint)  // Track for saturation
            return true  // Still in cooldown from rejection
        }
        
        // Check if recently seen (30 min cooldown)
        val seenAt = seenMints[mint]
        if (seenAt != null && now - seenAt < getSeenTtl()) {
            recordCooldownHit(mint)  // Track for saturation
            return true
        }
        
        // Check if already in watchlist (via config)
        val watchlist = cfg().watchlist
        if (mint in watchlist) {
            return true  // Already being tracked
        }
        
        return false
    }
    
    // Mark a token as rejected (longer cooldown than just "seen")
    private fun markRejected(mint: String) {
        rejectedMints[mint] = System.currentTimeMillis()
    }
    
    // Public function for BotService to mark tokens as rejected
    fun markTokenRejected(mint: String) {
        rejectedMints[mint] = System.currentTimeMillis()
        ErrorLogger.info("Scanner", "Token ${mint.take(12)} marked as rejected for ${getRejectedTtl()/1000}s")
    }
    
    // Clean up old entries from seen/rejected maps periodically
    private fun cleanupSeenMaps() {
        val now = System.currentTimeMillis()
        val seenBefore = seenMints.size
        val rejectedBefore = rejectedMints.size
        
        // Use safer cleanup - iterate and remove explicitly
        val seenToRemove = seenMints.entries.filter { now - it.value > getSeenTtl() }.map { it.key }
        seenToRemove.forEach { seenMints.remove(it) }
        
        val rejectedToRemove = rejectedMints.entries.filter { now - it.value > getRejectedTtl() }.map { it.key }
        rejectedToRemove.forEach { rejectedMints.remove(it) }
        
        // V5.2: Also cleanup saturated mints
        val saturatedToRemove = saturatedMints.entries.filter { now - it.value > getSaturationTtl() }.map { it.key }
        saturatedToRemove.forEach { saturatedMints.remove(it) }
        
        // Clear old cooldown hit counts (if token hasn't been seen in a while)
        val hitCountsToRemove = cooldownHitCount.keys.filter { mint ->
            !seenMints.containsKey(mint) && !rejectedMints.containsKey(mint)
        }
        hitCountsToRemove.forEach { cooldownHitCount.remove(it) }
        
        val seenRemoved = seenBefore - seenMints.size
        val rejectedRemoved = rejectedBefore - rejectedMints.size
        
        // Log telemetry periodically (every 60 seconds)
        if (now - lastTelemetryLogMs > 60_000L) {
            ErrorLogger.info("Scanner", "📊 TELEMETRY: ${getThroughputTelemetry()}")
            onLog("📊 ${getThroughputTelemetry()}")
            lastTelemetryLogMs = now
        }
        
        if (seenRemoved > 0 || rejectedRemoved > 0) {
            ErrorLogger.info("Scanner", "Cleanup: removed $seenRemoved seen, $rejectedRemoved rejected. " +
                "Remaining: ${seenMints.size} seen, ${rejectedMints.size} rejected, ${saturatedMints.size} saturated")
            onLog("🧹 Map cleanup: seen=${seenMints.size} rejected=${rejectedMints.size} sat=${saturatedMints.size}")
        }
        
        // AGGRESSIVE cleanup if maps are getting large - keep fewer entries
        if (seenMints.size > 100) {
            val toKeep = seenMints.entries.sortedByDescending { it.value }.take(50).map { it.key }
            seenMints.keys.retainAll(toKeep.toSet())
            ErrorLogger.warn("Scanner", "Aggressive seen cleanup: reduced to ${seenMints.size}")
            onLog("⚠️ Seen map trimmed to ${seenMints.size}")
        }
        
        if (rejectedMints.size > 200) {
            val toKeep = rejectedMints.entries.sortedByDescending { it.value }.take(100).map { it.key }
            rejectedMints.keys.retainAll(toKeep.toSet())
            ErrorLogger.warn("Scanner", "Aggressive rejected cleanup: reduced to ${rejectedMints.size}")
            onLog("⚠️ Rejected map trimmed to ${rejectedMints.size}")
        }
    }

    private fun emit(token: ScannedToken) {
        // AI check - skip tokens that have failed repeatedly
        if (aiShouldSkipToken(token.mint, token.symbol, token.liquidityUsd, token.mcapUsd)) {
            return  // Don't emit - AI says this token is bad
        }
        
        // Apply AI-driven source boost to score
        val aiBoost = getAISourceBoost(token.source)
        
        // Apply ScannerLearning boost (from historical trade outcomes)
        val scannerLearningBoost = ScannerLearning.getDiscoveryBonus(
            source = token.source.name,
            liqUsd = token.liquidityUsd,
            ageHours = if (token.pairCreatedHoursAgo > 0) token.pairCreatedHoursAgo else 1.0
        )
        
        val totalBoost = aiBoost + scannerLearningBoost
        val adjustedScore = (token.score + totalBoost).coerceIn(0.0, 100.0)
        val adjustedToken = token.copy(score = adjustedScore)
        
        seenMints[token.mint] = System.currentTimeMillis()
        val boostIndicator = if (totalBoost != 0.0) " AI${if(totalBoost>0) "+" else ""}${totalBoost.toInt()}" else ""
        onLog("🔍 Found: ${token.symbol} (${token.source.name}$boostIndicator) " +
              "liq=$${(token.liquidityUsd/1000).toInt()}K " +
              "vol=$${(token.volumeH1/1000).toInt()}K " +
              "score=${adjustedScore.toInt()}")
        
        // ═══════════════════════════════════════════════════════════════════
        // ARB INTEGRATION: Record source timing for venue lag detection
        // This powers the ArbScannerAI's ability to detect cross-source arbitrage
        // ═══════════════════════════════════════════════════════════════════
        try {
            com.lifecyclebot.v3.arb.SourceTimingRegistry.record(
                mint = token.mint,
                source = token.source.name,
                price = null,  // Price not available at this stage
                liquidityUsd = token.liquidityUsd,
                buyPressurePct = null  // Will be populated later
            )
        } catch (e: Exception) {
            // Silently ignore - arb feature is optional
        }
        
        // Record liquidity snapshot for LiquidityDepthAI
        LiquidityDepthAI.recordSnapshot(
            mint = token.mint,
            liquidityUsd = token.liquidityUsd,
            mcapUsd = token.mcapUsd,
            holderCount = 0  // Holder count not available from scanner
        )
        
        onTokenFound(adjustedToken.mint, adjustedToken.symbol, adjustedToken.name, adjustedToken.source, adjustedToken.score, adjustedToken.liquidityUsd)
    }
    
    // Separate HTTP client for rugcheck with SHORT timeout
    private val rugcheckHttp = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.SECONDS)
        .build()
    
    /**
     * V3 MIGRATION: Quick rugcheck is now MUCH more lenient
     * 
     * Old behavior: Block tokens with score < 10 (paper) or score < 5 (live)
     * New behavior: Only block CONFIRMED rugs. Everything else passes through.
     * 
     * Why? Most meme coins have low rugcheck scores but are still tradeable.
     * V3 engine handles rug risk as a score penalty, not a hard block.
     * The real protection comes from V3's unified scoring + distribution detection + exit strategies.
     */
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
                return true  // Pass if API error
            }
            
            val body = resp.body?.string() ?: return true
            val json = JSONObject(body)
            
            val scoreNormalized = json.optInt("score_normalised", json.optInt("score", 50))
            val rugged = json.optString("rugged", "").lowercase()
            
            // V5.0: Cache the RC score for eligibility checks
            rcScoreCache[mint] = scoreNormalized
            
            // ═══════════════════════════════════════════════════════════════════
            // V5.0 MIGRATION: RC IS NOW A HARD GATE, not advisory!
            // 
            // - rugged=true → FATAL BLOCK (confirmed rug pull)
            // - RC <= 3 → HARD BLOCK (catastrophic safety)
            // - RC 4-10 → BLOCK (dangerous) - let FDG handle as shadow
            // - RC > 10 → PASS (V3 will score it)
            // ═══════════════════════════════════════════════════════════════════
            
            // CONFIRMED RUG = always block (token is worthless)
            if (rugged == "true" || rugged == "yes") {
                onLog("🚫 RUG CONFIRMED: ${mint.take(8)}... (tokens worthless)")
                ErrorLogger.info("Scanner", "quickRugcheck FATAL: ${mint.take(12)} rugged=true")
                return false
            }
            
            // V5.2 FIX: RC >= 6 should PASS (most good solana tokens score 6-20)
            // RC <= 5 = BLOCK (dangerous/rug territory)
            // RC <= 2 = HARD BLOCK (catastrophic) - V5.2: Lowered from 3
            // V5.2.8: Paper mode allows RC 4-5 for learning
            // V5.2.9: Paper mode allows ALL RC scores for maximum learning
            
            val c = cfg()
            val isPaper = c.paperMode
            
            // V5.2.9: In Paper Mode, allow EVERYTHING for learning (it's fake money!)
            if (isPaper) {
                if (scoreNormalized <= 2) {
                    onLog("⚠️ RC DANGER [PAPER]: ${mint.take(8)}... score=$scoreNormalized (allowed for learning)")
                    ErrorLogger.info("Scanner", "RC PASS [PAPER]: ${mint.take(12)} score=$scoreNormalized (DANGER - learning mode)")
                } else if (scoreNormalized in 3..5) {
                    onLog("⚠️ RC WARN [PAPER]: ${mint.take(8)}... score=$scoreNormalized (allowed for learning)")
                    ErrorLogger.info("Scanner", "RC PASS [PAPER]: ${mint.take(12)} score=$scoreNormalized (learning mode)")
                }
                return true  // Paper mode: PASS everything for learning
            }
            
            // LIVE MODE: Strict filtering for capital protection
            if (scoreNormalized <= 2) {
                onLog("🚫 RC HARD BLOCK: ${mint.take(8)}... score=$scoreNormalized (catastrophic)")
                ErrorLogger.info("Scanner", "RC HARD_BLOCK: ${mint.take(12)} score=$scoreNormalized <= 2")
                return false
            }
            
            if (scoreNormalized in 3..5) {
                onLog("🚫 RC BLOCK: ${mint.take(8)}... score=$scoreNormalized (dangerous)")
                ErrorLogger.info("Scanner", "RC BLOCK: ${mint.take(12)} score=$scoreNormalized (3-5)")
                return false
            }
            
            // V5.2: RC >= 6 = PASS - this is realistic for solana tokens
            // Most good tokens score 6-20, blocking above 6 kills all trading
            if (scoreNormalized in 6..15) {
                ErrorLogger.debug("Scanner", "RC ${mint.take(8)}: score=$scoreNormalized (OK - normal range)")
            }
            
            return true  // Pass - RC >= 6
            
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            // Timeout or error - pass through (don't block on API issues)
            ErrorLogger.debug("Scanner", "RC exception for ${mint.take(8)}: ${e.message}, passing")
            return true
        }
    }
    
    /**
     * Emit with optional quick rugcheck
     * VERY LENIENT - Only block truly dangerous rugs
     */
    private suspend fun emitWithRugcheck(token: ScannedToken) {
        // Quick check with 2-second timeout - always pass on any error
        val passed = try {
            withContext(Dispatchers.IO) { 
                try {
                    withTimeout(2000L) { quickRugcheck(token.mint) }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    ErrorLogger.debug("Scanner", "RC timeout for ${token.symbol}, passing through")
                    true  // Timeout = pass through
                } catch (e: Exception) {
                    ErrorLogger.debug("Scanner", "RC error for ${token.symbol}: ${e.message}, passing through")
                    true  // Any error = pass through
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e  // Re-throw job cancellation
        } catch (e: Exception) {
            ErrorLogger.debug("Scanner", "RC outer error for ${token.symbol}: ${e.message}, passing through")
            true  // Any other error = pass through
        }
        
        if (!passed) {
            ErrorLogger.info("Scanner", "Rugcheck blocked ${token.symbol} (quickRugcheck returned false)")
            return
        }
        
        emit(token)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // V5.2.9: NETWORK STABILITY LAYER
    // - Retry logic with exponential backoff
    // - DNS fallback handling
    // - Graceful degradation on failures
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun getWithRetry(url: String, apiKey: String = "", maxRetries: Int = 2): String? {
        var lastError: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                val result = get(url, apiKey)
                if (result != null) return result
                // Non-null but empty = API issue, retry
                if (attempt < maxRetries - 1) {
                    Thread.sleep((100 * (attempt + 1)).toLong())  // Simple backoff
                }
            } catch (e: Exception) {
                lastError = e
                ErrorLogger.debug("Scanner", "[NETWORK] Retry ${attempt + 1}/$maxRetries for ${url.take(40)}")
                if (attempt < maxRetries - 1) {
                    Thread.sleep((200 * (attempt + 1)).toLong())
                }
            }
        }
        if (lastError != null) {
            ErrorLogger.warn("Scanner", "[NETWORK] All retries failed for ${url.take(50)}: ${lastError?.message?.take(30)}")
        }
        return null
    }

    private fun get(url: String, apiKey: String = ""): String? = try {
        val builder = Request.Builder().url(url)
            // Use browser-like headers to avoid Cloudflare blocks
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Connection", "keep-alive")
        if (apiKey.isNotBlank()) builder.header("X-API-KEY", apiKey)
        ErrorLogger.debug("Scanner", "HTTP GET: ${url.take(60)}...")
        val resp = http.newCall(builder.build()).execute()
        if (resp.isSuccessful) {
            val body = resp.body?.string()
            // Validate it looks like JSON before returning
            if (body != null && (body.trim().startsWith("{") || body.trim().startsWith("["))) {
                ErrorLogger.debug("Scanner", "HTTP OK: ${body.length} bytes from ${url.take(40)}")
                body
            } else {
                ErrorLogger.warn("Scanner", "[NETWORK] Non-JSON response from ${url.take(50)}")
                null
            }
        } else {
            // Classify errors for observability
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

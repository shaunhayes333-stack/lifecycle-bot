package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * TimeModeScheduler — Auto-switch trading modes based on time patterns
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Learns and applies optimal mode selection based on:
 * - Hour of day (0-23)
 * - Day of week (1-7, Sunday=1)
 * - Historical performance data from ModeLearning
 * - Collective insights
 * 
 * Features:
 * - Auto-switch modes based on learned patterns
 * - User-defined schedule overrides
 * - Learn best hours for each mode from collective
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TimeModeScheduler {
    
    private const val TAG = "TimeModeScheduler"
    private const val PREFS_NAME = "time_mode_scheduler"
    
    private var prefs: SharedPreferences? = null
    
    // Auto-switching enabled
    private var autoSwitchEnabled = false
    
    // User-defined schedule overrides (hour -> mode)
    private val userSchedule = ConcurrentHashMap<Int, String>()
    
    // Learned performance by hour (hour -> mode -> stats)
    private val hourlyStats = ConcurrentHashMap<Int, MutableMap<String, HourlyModeStats>>()
    
    // Learned performance by day (dayOfWeek -> mode -> stats)
    private val dailyStats = ConcurrentHashMap<Int, MutableMap<String, DailyModeStats>>()
    
    // Current recommended mode
    private var currentRecommendedMode: String? = null
    private var lastRecommendationTime = 0L
    
    data class HourlyModeStats(
        var totalTrades: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnl: Double = 0.0,
    ) {
        val winRate: Double
            get() = if (totalTrades > 0) (wins.toDouble() / totalTrades * 100) else 0.0
        
        val avgPnl: Double
            get() = if (totalTrades > 0) totalPnl / totalTrades else 0.0
        
        val isReliable: Boolean
            get() = totalTrades >= 5
    }
    
    data class DailyModeStats(
        var totalTrades: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnl: Double = 0.0,
    ) {
        val winRate: Double
            get() = if (totalTrades > 0) (wins.toDouble() / totalTrades * 100) else 0.0
        
        val avgPnl: Double
            get() = if (totalTrades > 0) totalPnl / totalTrades else 0.0
        
        val isReliable: Boolean
            get() = totalTrades >= 3
    }
    
    data class TimeSlotRecommendation(
        val hour: Int,
        val dayOfWeek: Int,
        val recommendedMode: String?,
        val confidence: Int,           // 0-100
        val reason: String,
        val alternatives: List<String>,
    )
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }
    
    private fun loadFromPrefs() {
        prefs?.let {
            autoSwitchEnabled = it.getBoolean("auto_switch_enabled", false)
            
            // Load user schedule
            val scheduleJson = it.getString("user_schedule", null)
            if (scheduleJson != null) {
                try {
                    val obj = JSONObject(scheduleJson)
                    obj.keys().forEach { key ->
                        userSchedule[key.toInt()] = obj.getString(key)
                    }
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Failed to load schedule: ${e.message}")
                }
            }
            
            // Load hourly stats
            val hourlyJson = it.getString("hourly_stats", null)
            if (hourlyJson != null) {
                try {
                    val arr = JSONArray(hourlyJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val hour = obj.getInt("hour")
                        val mode = obj.getString("mode")
                        val stats = HourlyModeStats(
                            totalTrades = obj.getInt("trades"),
                            wins = obj.getInt("wins"),
                            losses = obj.getInt("losses"),
                            totalPnl = obj.getDouble("pnl"),
                        )
                        hourlyStats.getOrPut(hour) { mutableMapOf() }[mode] = stats
                    }
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Failed to load hourly stats: ${e.message}")
                }
            }
        }
    }
    
    private fun saveToPrefs() {
        prefs?.edit()?.apply {
            putBoolean("auto_switch_enabled", autoSwitchEnabled)
            
            // Save user schedule
            val scheduleObj = JSONObject()
            userSchedule.forEach { (hour, mode) ->
                scheduleObj.put(hour.toString(), mode)
            }
            putString("user_schedule", scheduleObj.toString())
            
            // Save hourly stats
            val hourlyArr = JSONArray()
            hourlyStats.forEach { (hour, modes) ->
                modes.forEach { (mode, stats) ->
                    hourlyArr.put(JSONObject().apply {
                        put("hour", hour)
                        put("mode", mode)
                        put("trades", stats.totalTrades)
                        put("wins", stats.wins)
                        put("losses", stats.losses)
                        put("pnl", stats.totalPnl)
                    })
                }
            }
            putString("hourly_stats", hourlyArr.toString())
            
            apply()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING - Record trade outcomes by time
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a trade outcome for time-based learning.
     */
    fun recordTradeOutcome(
        mode: String,
        pnlPct: Double,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        
        // Update hourly stats
        val hourlyModes = hourlyStats.getOrPut(hour) { mutableMapOf() }
        val hourlyMode = hourlyModes.getOrPut(mode) { HourlyModeStats() }
        hourlyMode.totalTrades++
        hourlyMode.totalPnl += pnlPct
        if (pnlPct > 5.0) {
            hourlyMode.wins++
        } else if (pnlPct < -5.0) {
            hourlyMode.losses++
        }
        
        // Update daily stats
        val dailyModes = dailyStats.getOrPut(dayOfWeek) { mutableMapOf() }
        val dailyMode = dailyModes.getOrPut(mode) { DailyModeStats() }
        dailyMode.totalTrades++
        dailyMode.totalPnl += pnlPct
        if (pnlPct > 5.0) {
            dailyMode.wins++
        } else if (pnlPct < -5.0) {
            dailyMode.losses++
        }
        
        saveToPrefs()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECOMMENDATIONS - Get best mode for current time
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get the recommended mode for the current time.
     */
    fun getRecommendation(): TimeSlotRecommendation {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        
        // Check user override first
        val userOverride = userSchedule[hour]
        if (userOverride != null) {
            return TimeSlotRecommendation(
                hour = hour,
                dayOfWeek = dayOfWeek,
                recommendedMode = userOverride,
                confidence = 100,
                reason = "User scheduled",
                alternatives = emptyList(),
            )
        }
        
        // Check learned patterns
        val hourlyModes = hourlyStats[hour] ?: emptyMap()
        val reliableModes = hourlyModes.filter { it.value.isReliable }
        
        if (reliableModes.isEmpty()) {
            // Check collective recommendation
            val collectiveRec = getCollectiveRecommendation(hour)
            if (collectiveRec != null) {
                return TimeSlotRecommendation(
                    hour = hour,
                    dayOfWeek = dayOfWeek,
                    recommendedMode = collectiveRec,
                    confidence = 50,
                    reason = "Collective suggestion",
                    alternatives = emptyList(),
                )
            }
            
            return TimeSlotRecommendation(
                hour = hour,
                dayOfWeek = dayOfWeek,
                recommendedMode = null,
                confidence = 0,
                reason = "Not enough data",
                alternatives = emptyList(),
            )
        }
        
        // Find best performing mode for this hour
        val bestMode = reliableModes.entries
            .sortedByDescending { it.value.winRate * 0.6 + it.value.avgPnl * 0.4 }
            .first()
        
        val alternatives = reliableModes.entries
            .filter { it.key != bestMode.key }
            .sortedByDescending { it.value.winRate }
            .take(3)
            .map { it.key }
        
        val confidence = when {
            bestMode.value.totalTrades >= 20 && bestMode.value.winRate >= 60 -> 90
            bestMode.value.totalTrades >= 10 && bestMode.value.winRate >= 55 -> 70
            bestMode.value.totalTrades >= 5 -> 50
            else -> 30
        }
        
        return TimeSlotRecommendation(
            hour = hour,
            dayOfWeek = dayOfWeek,
            recommendedMode = bestMode.key,
            confidence = confidence,
            reason = "Best WR: ${bestMode.value.winRate.toInt()}% (${bestMode.value.totalTrades} trades)",
            alternatives = alternatives,
        )
    }
    
    /**
     * Get recommended mode from collective learning.
     */
    private fun getCollectiveRecommendation(hour: Int): String? {
        if (!com.lifecyclebot.collective.CollectiveLearning.isEnabled()) return null
        
        // Map hours to market conditions
        val marketCondition = when (hour) {
            in 8..12 -> "MORNING"   // US morning
            in 13..17 -> "AFTERNOON" // US afternoon
            in 18..23 -> "EVENING"  // US evening / Asia morning
            else -> "OVERNIGHT"     // Low activity
        }
        
        return try {
            com.lifecyclebot.collective.CollectiveLearning.getRecommendedMode(
                liquidityUsd = 50000.0,  // Medium liquidity default
                emaTrend = "NEUTRAL"
            )
        } catch (e: Exception) {
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO-SWITCHING - Apply recommended mode
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if mode should be switched and return new mode if so.
     * Returns null if no switch needed.
     */
    fun checkAndGetModeSwitch(currentMode: String): String? {
        if (!autoSwitchEnabled) return null
        
        val now = System.currentTimeMillis()
        
        // Only check every 5 minutes
        if (now - lastRecommendationTime < 5 * 60 * 1000L && currentRecommendedMode != null) {
            return if (currentRecommendedMode != currentMode) currentRecommendedMode else null
        }
        
        val rec = getRecommendation()
        lastRecommendationTime = now
        currentRecommendedMode = rec.recommendedMode
        
        // Only switch if confidence is high enough and different from current
        if (rec.confidence >= 60 && rec.recommendedMode != null && rec.recommendedMode != currentMode) {
            ErrorLogger.info(TAG, "Auto-switch: $currentMode → ${rec.recommendedMode} (${rec.reason})")
            return rec.recommendedMode
        }
        
        return null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // USER CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun setAutoSwitchEnabled(enabled: Boolean) {
        autoSwitchEnabled = enabled
        saveToPrefs()
        ErrorLogger.info(TAG, "Auto-switch ${if (enabled) "enabled" else "disabled"}")
    }
    
    fun isAutoSwitchEnabled(): Boolean = autoSwitchEnabled
    
    fun setScheduleOverride(hour: Int, mode: String?) {
        if (mode == null) {
            userSchedule.remove(hour)
        } else {
            userSchedule[hour] = mode
        }
        saveToPrefs()
    }
    
    fun clearSchedule() {
        userSchedule.clear()
        saveToPrefs()
    }
    
    fun getSchedule(): Map<Int, String> = userSchedule.toMap()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYTICS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get best hours for a specific mode.
     */
    fun getBestHoursForMode(mode: String, limit: Int = 5): List<Pair<Int, HourlyModeStats>> {
        return hourlyStats.entries
            .mapNotNull { (hour, modes) ->
                modes[mode]?.let { stats ->
                    if (stats.isReliable) hour to stats else null
                }
            }
            .sortedByDescending { it.second.winRate }
            .take(limit)
    }
    
    /**
     * Get worst hours for a specific mode (times to avoid).
     */
    fun getWorstHoursForMode(mode: String, limit: Int = 5): List<Pair<Int, HourlyModeStats>> {
        return hourlyStats.entries
            .mapNotNull { (hour, modes) ->
                modes[mode]?.let { stats ->
                    if (stats.isReliable && stats.winRate < 45) hour to stats else null
                }
            }
            .sortedBy { it.second.winRate }
            .take(limit)
    }
    
    fun getFormattedSummary(): String {
        val rec = getRecommendation()
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        
        return buildString {
            appendLine("═══ TIME MODE SCHEDULER ═══")
            appendLine()
            appendLine("Auto-switch: ${if (autoSwitchEnabled) "ENABLED" else "DISABLED"}")
            appendLine("Current hour: $hour:00")
            appendLine()
            appendLine("RECOMMENDATION:")
            if (rec.recommendedMode != null) {
                appendLine("  Mode: ${rec.recommendedMode}")
                appendLine("  Confidence: ${rec.confidence}%")
                appendLine("  Reason: ${rec.reason}")
                if (rec.alternatives.isNotEmpty()) {
                    appendLine("  Alternatives: ${rec.alternatives.joinToString(", ")}")
                }
            } else {
                appendLine("  No recommendation (${rec.reason})")
            }
            appendLine()
            appendLine("USER SCHEDULE:")
            if (userSchedule.isEmpty()) {
                appendLine("  No custom schedule set")
            } else {
                userSchedule.entries.sortedBy { it.key }.forEach { (h, m) ->
                    appendLine("  ${h}:00 → $m")
                }
            }
        }
    }
}

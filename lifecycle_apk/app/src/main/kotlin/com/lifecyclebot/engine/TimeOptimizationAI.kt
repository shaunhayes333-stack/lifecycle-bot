package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TIME OPTIMIZATION AI
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Learns optimal trading times based on historical performance. Crypto markets
 * have distinct patterns:
 * 
 * - US market hours (9:30 AM - 4 PM ET) = high volume, good liquidity
 * - Asian market hours (9 PM - 4 AM ET) = different dynamics
 * - Weekends = lower volume, potentially higher volatility
 * - Early morning US = often sees big moves as traders wake up
 * 
 * The AI tracks win rates and average PnL by:
 * - Hour of day (0-23 UTC)
 * - Day of week (1-7)
 * - Combined time slots
 * 
 * Over time, it learns which hours/days are most profitable and adjusts
 * entry scores accordingly.
 * 
 * LEARNING:
 * - Records trade outcomes with timestamp
 * - Calculates hourly/daily statistics
 * - Identifies "golden hours" (high win rate + good avg PnL)
 * - Identifies "danger zones" (low win rate or negative PnL)
 * - Adjusts entry scores: +8 for golden hours, -5 for danger zones
 */
object TimeOptimizationAI {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Minimum trades needed to trust statistics
    private const val MIN_TRADES_FOR_CONFIDENCE = 10
    
    // Thresholds for golden/danger classification
    private const val GOLDEN_WIN_RATE = 55.0      // % win rate for golden hour
    private const val GOLDEN_AVG_PNL = 15.0       // % avg PnL for golden hour
    private const val DANGER_WIN_RATE = 40.0      // % below = danger zone
    private const val DANGER_AVG_PNL = -10.0      // % avg PnL below = danger
    
    // Time zones for market awareness
    private val UTC = TimeZone.getTimeZone("UTC")
    private val EST = TimeZone.getTimeZone("America/New_York")
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class TradeTimeRecord(
        val hourUtc: Int,        // 0-23
        val dayOfWeek: Int,      // 1=Sunday, 7=Saturday
        val pnlPct: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class TimeSlotStats(
        val slot: String,        // e.g., "H12" for hour 12, "D3" for Tuesday
        val tradeCount: Int,
        val winRate: Double,     // %
        val avgPnl: Double,      // %
        val isGolden: Boolean,   // High performance
        val isDanger: Boolean,   // Low performance
        val entryAdjustment: Double
    )
    
    enum class MarketSession(val label: String, val startHourUtc: Int, val endHourUtc: Int) {
        US_PREMARKET("US Pre-Market", 12, 14),      // 8-10 AM ET
        US_MORNING("US Morning", 14, 17),           // 10 AM - 1 PM ET
        US_AFTERNOON("US Afternoon", 17, 21),       // 1-5 PM ET
        US_AFTERHOURS("US After-Hours", 21, 24),    // 5-8 PM ET
        ASIA_MORNING("Asia Morning", 0, 4),         // 8 PM - 12 AM ET
        ASIA_AFTERNOON("Asia Afternoon", 4, 8),     // 12-4 AM ET
        EUROPE_MORNING("Europe Morning", 8, 12),    // 4-8 AM ET
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // All recorded trades
    private val tradeRecords = java.util.concurrent.ConcurrentLinkedDeque<TradeTimeRecord>()
    private const val MAX_RECORDS = 5000  // Keep last 5000 trades
    
    // Cached stats by hour (0-23)
    private val hourlyStats = ConcurrentHashMap<Int, TimeSlotStats>()
    
    // Cached stats by day (1-7)
    private val dailyStats = ConcurrentHashMap<Int, TimeSlotStats>()
    
    // Cached stats by session
    private val sessionStats = ConcurrentHashMap<MarketSession, TimeSlotStats>()
    
    // Last refresh timestamp
    @Volatile private var lastStatsRefresh = 0L
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TIME UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get current hour in UTC (0-23).
     */
    fun getCurrentHourUtc(): Int {
        val cal = Calendar.getInstance(UTC)
        return cal.get(Calendar.HOUR_OF_DAY)
    }
    
    /**
     * Get current day of week (1=Sunday, 7=Saturday).
     */
    fun getCurrentDayOfWeek(): Int {
        val cal = Calendar.getInstance(UTC)
        return cal.get(Calendar.DAY_OF_WEEK)
    }
    
    /**
     * Get current market session.
     */
    fun getCurrentSession(): MarketSession {
        val hour = getCurrentHourUtc()
        return MarketSession.values().find { session ->
            if (session.startHourUtc < session.endHourUtc) {
                hour >= session.startHourUtc && hour < session.endHourUtc
            } else {
                hour >= session.startHourUtc || hour < session.endHourUtc
            }
        } ?: MarketSession.US_AFTERHOURS
    }
    
    /**
     * Check if currently weekend (Saturday or Sunday).
     */
    fun isWeekend(): Boolean {
        val day = getCurrentDayOfWeek()
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }
    
    /**
     * Get day name from day number.
     */
    private fun dayName(day: Int): String = when (day) {
        Calendar.SUNDAY -> "Sun"
        Calendar.MONDAY -> "Mon"
        Calendar.TUESDAY -> "Tue"
        Calendar.WEDNESDAY -> "Wed"
        Calendar.THURSDAY -> "Thu"
        Calendar.FRIDAY -> "Fri"
        Calendar.SATURDAY -> "Sat"
        else -> "?"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Refresh all time-based statistics.
     * Called periodically from BotService.
     */
    fun refreshStats() {
        // Trim old records
        while (tradeRecords.size > MAX_RECORDS) {
            tradeRecords.pollFirst()
        }
        
        // Calculate hourly stats
        for (hour in 0..23) {
            val trades = tradeRecords.filter { it.hourUtc == hour }
            hourlyStats[hour] = calculateSlotStats("H$hour", trades)
        }
        
        // Calculate daily stats
        for (day in 1..7) {
            val trades = tradeRecords.filter { it.dayOfWeek == day }
            dailyStats[day] = calculateSlotStats("D$day", trades)
        }
        
        // Calculate session stats
        for (session in MarketSession.values()) {
            val trades = tradeRecords.filter { record ->
                if (session.startHourUtc < session.endHourUtc) {
                    record.hourUtc >= session.startHourUtc && record.hourUtc < session.endHourUtc
                } else {
                    record.hourUtc >= session.startHourUtc || record.hourUtc < session.endHourUtc
                }
            }
            sessionStats[session] = calculateSlotStats(session.name, trades)
        }
        
        lastStatsRefresh = System.currentTimeMillis()
    }
    
    private fun calculateSlotStats(slot: String, trades: List<TradeTimeRecord>): TimeSlotStats {
        if (trades.isEmpty()) {
            return TimeSlotStats(slot, 0, 0.0, 0.0, false, false, 0.0)
        }
        
        val winRate = trades.count { it.pnlPct > 0 }.toDouble() / trades.size * 100
        val avgPnl = trades.map { it.pnlPct }.average()
        
        val hasEnoughData = trades.size >= MIN_TRADES_FOR_CONFIDENCE
        
        val isGolden = hasEnoughData && winRate >= GOLDEN_WIN_RATE && avgPnl >= GOLDEN_AVG_PNL
        val isDanger = hasEnoughData && (winRate <= DANGER_WIN_RATE || avgPnl <= DANGER_AVG_PNL)
        
        // Calculate entry adjustment
        val adjustment = when {
            !hasEnoughData -> 0.0  // Not enough data to trust
            isGolden -> {
                // Scale bonus by how much better than threshold
                val winBonus = (winRate - GOLDEN_WIN_RATE) * 0.2
                val pnlBonus = (avgPnl - GOLDEN_AVG_PNL) * 0.1
                minOf(winBonus + pnlBonus + 5.0, 12.0)  // Max +12
            }
            isDanger -> {
                // Scale penalty by how much worse than threshold
                val winPenalty = (DANGER_WIN_RATE - winRate) * 0.2
                val pnlPenalty = (DANGER_AVG_PNL - avgPnl) * 0.1
                maxOf(-(winPenalty + pnlPenalty + 3.0), -10.0)  // Max -10
            }
            avgPnl > 5.0 -> avgPnl * 0.15  // Slight boost for positive
            avgPnl < -5.0 -> avgPnl * 0.2  // Slight penalty for negative
            else -> 0.0
        }
        
        return TimeSlotStats(slot, trades.size, winRate, avgPnl, isGolden, isDanger, adjustment)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ENTRY SCORE ADJUSTMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get entry score adjustment for current time.
     * Combines hourly, daily, and session adjustments.
     */
    fun getEntryScoreAdjustment(): Double {
        // Refresh if stale (> 10 min)
        if (System.currentTimeMillis() - lastStatsRefresh > 10 * 60 * 1000) {
            refreshStats()
        }
        
        val hour = getCurrentHourUtc()
        val day = getCurrentDayOfWeek()
        val session = getCurrentSession()
        
        val hourlyAdj = hourlyStats[hour]?.entryAdjustment ?: 0.0
        val dailyAdj = dailyStats[day]?.entryAdjustment ?: 0.0
        val sessionAdj = sessionStats[session]?.entryAdjustment ?: 0.0
        
        // Combine with weights: hourly most important, then session, then day
        val combined = (hourlyAdj * 0.5) + (sessionAdj * 0.3) + (dailyAdj * 0.2)
        
        return combined.coerceIn(-10.0, 12.0)
    }
    
    /**
     * Check if current time is a "golden hour" (historically profitable).
     */
    fun isGoldenHour(): Boolean {
        if (System.currentTimeMillis() - lastStatsRefresh > 10 * 60 * 1000) {
            refreshStats()
        }
        val hour = getCurrentHourUtc()
        return hourlyStats[hour]?.isGolden == true
    }
    
    /**
     * Check if current time is a "danger zone" (historically unprofitable).
     */
    fun isDangerZone(): Boolean {
        if (System.currentTimeMillis() - lastStatsRefresh > 10 * 60 * 1000) {
            refreshStats()
        }
        val hour = getCurrentHourUtc()
        return hourlyStats[hour]?.isDanger == true
    }
    
    /**
     * Get list of golden hours.
     */
    fun getGoldenHours(): List<Int> {
        return hourlyStats.entries.filter { it.value.isGolden }.map { it.key }
    }
    
    /**
     * Get list of danger hours.
     */
    fun getDangerHours(): List<Int> {
        return hourlyStats.entries.filter { it.value.isDanger }.map { it.key }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record trade outcome for time learning.
     */
    fun recordOutcome(pnlPct: Double) {
        val cal = Calendar.getInstance(UTC)
        val record = TradeTimeRecord(
            hourUtc = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
            pnlPct = pnlPct
        )
        tradeRecords.addLast(record)
        
        // Log significant time-based insights
        if (kotlin.math.abs(pnlPct) > 30.0) {
            val session = getCurrentSession()
            val hourStats = hourlyStats[record.hourUtc]
            ErrorLogger.debug("TimeAI", "⏰ Trade ${pnlPct.toInt()}% @ H${record.hourUtc} " +
                "[${dayName(record.dayOfWeek)}] ${session.label} | " +
                "hour_avg=${hourStats?.avgPnl?.toInt() ?: 0}%")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS & PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStats(): String {
        val currentHour = getCurrentHourUtc()
        val currentDay = getCurrentDayOfWeek()
        val session = getCurrentSession()
        val adjustment = getEntryScoreAdjustment()
        
        val hourStats = hourlyStats[currentHour]
        val status = when {
            hourStats?.isGolden == true -> "🌟GOLDEN"
            hourStats?.isDanger == true -> "⚠️DANGER"
            else -> "➖"
        }
        
        return buildString {
            append("TimeAI: H$currentHour ${dayName(currentDay)} [${session.label}] $status ")
            append("adj=${if (adjustment >= 0) "+" else ""}${adjustment.toInt()} ")
            append("trades=${tradeRecords.size}")
        }
    }
    
    fun getDetailedStats(): String {
        return buildString {
            appendLine("═══ HOURLY PERFORMANCE ═══")
            for (hour in 0..23) {
                val stats = hourlyStats[hour] ?: continue
                if (stats.tradeCount == 0) continue
                val status = when {
                    stats.isGolden -> "🌟"
                    stats.isDanger -> "⚠️"
                    else -> "  "
                }
                appendLine("H${hour.toString().padStart(2, '0')}: " +
                    "wr=${stats.winRate.toInt()}% " +
                    "avg=${stats.avgPnl.toInt()}% " +
                    "n=${stats.tradeCount} $status")
            }
            
            appendLine("\n═══ DAILY PERFORMANCE ═══")
            for (day in 1..7) {
                val stats = dailyStats[day] ?: continue
                if (stats.tradeCount == 0) continue
                val status = when {
                    stats.isGolden -> "🌟"
                    stats.isDanger -> "⚠️"
                    else -> "  "
                }
                appendLine("${dayName(day)}: " +
                    "wr=${stats.winRate.toInt()}% " +
                    "avg=${stats.avgPnl.toInt()}% " +
                    "n=${stats.tradeCount} $status")
            }
            
            appendLine("\n═══ SESSION PERFORMANCE ═══")
            for (session in MarketSession.values()) {
                val stats = sessionStats[session] ?: continue
                if (stats.tradeCount == 0) continue
                val status = when {
                    stats.isGolden -> "🌟"
                    stats.isDanger -> "⚠️"
                    else -> "  "
                }
                appendLine("${session.label}: " +
                    "wr=${stats.winRate.toInt()}% " +
                    "avg=${stats.avgPnl.toInt()}% " +
                    "n=${stats.tradeCount} $status")
            }
        }
    }
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            // Save trade records
            val recordsArray = JSONArray()
            tradeRecords.forEach { record ->
                recordsArray.put(JSONObject().apply {
                    put("hour", record.hourUtc)
                    put("day", record.dayOfWeek)
                    put("pnl", record.pnlPct)
                    put("ts", record.timestamp)
                })
            }
            put("records", recordsArray)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            // Load records
            tradeRecords.clear()
            val recordsArray = json.optJSONArray("records")
            if (recordsArray != null) {
                for (i in 0 until recordsArray.length()) {
                    val obj = recordsArray.getJSONObject(i)
                    tradeRecords.addLast(TradeTimeRecord(
                        hourUtc = obj.getInt("hour"),
                        dayOfWeek = obj.getInt("day"),
                        pnlPct = obj.getDouble("pnl"),
                        timestamp = obj.optLong("ts", System.currentTimeMillis())
                    ))
                }
            }
            
            // Refresh stats after loading
            refreshStats()
            
            ErrorLogger.info("TimeAI", "Loaded ${tradeRecords.size} time records")
        } catch (e: Exception) {
            ErrorLogger.error("TimeAI", "Failed to load: ${e.message}", e)
        }
    }
    
    fun cleanup() {
        // Trim to max records
        while (tradeRecords.size > MAX_RECORDS) {
            tradeRecords.pollFirst()
        }
    }
}

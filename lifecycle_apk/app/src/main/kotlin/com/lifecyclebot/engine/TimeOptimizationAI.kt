package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * TIME OPTIMIZATION AI
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * Learns optimal trading times based on historical performance.
 *
 * Decisive-trade rules used everywhere here:
 * - WIN    = pnlPct >= 0.5
 * - LOSS   = pnlPct <= -2.0
 * - SCRATCH = between those thresholds, ignored as noise
 */
object TimeOptimizationAI {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════

    private const val WIN_THRESHOLD_PCT = 0.5
    private const val LOSS_THRESHOLD_PCT = -2.0

    private const val MIN_TRADES_FOR_CONFIDENCE = 10

    private const val GOLDEN_WIN_RATE = 55.0
    private const val GOLDEN_AVG_PNL = 15.0
    private const val DANGER_WIN_RATE = 40.0
    private const val DANGER_AVG_PNL = -10.0

    private const val STATS_REFRESH_STALE_MS = 10 * 60 * 1000L
    private const val MAX_RECORDS = 5000

    private val UTC = TimeZone.getTimeZone("UTC")

    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════

    data class TradeTimeRecord(
        val hourUtc: Int,
        val dayOfWeek: Int,
        val pnlPct: Double,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class TimeSlotStats(
        val slot: String,
        val tradeCount: Int,
        val winCount: Int,
        val lossCount: Int,
        val winRate: Double,
        val avgPnl: Double,
        val isGolden: Boolean,
        val isDanger: Boolean,
        val entryAdjustment: Double
    )

    enum class MarketSession(val label: String, val startHourUtc: Int, val endHourUtc: Int) {
        US_PREMARKET("US Pre-Market", 12, 14),
        US_MORNING("US Morning", 14, 17),
        US_AFTERNOON("US Afternoon", 17, 21),
        US_AFTERHOURS("US After-Hours", 21, 24),
        ASIA_MORNING("Asia Morning", 0, 4),
        ASIA_AFTERNOON("Asia Afternoon", 4, 8),
        EUROPE_MORNING("Europe Morning", 8, 12),
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val tradeRecords = ConcurrentLinkedDeque<TradeTimeRecord>()

    private val hourlyStats = ConcurrentHashMap<Int, TimeSlotStats>()
    private val dailyStats = ConcurrentHashMap<Int, TimeSlotStats>()
    private val sessionStats = ConcurrentHashMap<MarketSession, TimeSlotStats>()

    @Volatile
    private var lastStatsRefresh = 0L

    // ═══════════════════════════════════════════════════════════════════════════
    // TIME UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════

    fun getCurrentHourUtc(): Int {
        val cal = Calendar.getInstance(UTC)
        return cal.get(Calendar.HOUR_OF_DAY)
    }

    fun getCurrentDayOfWeek(): Int {
        val cal = Calendar.getInstance(UTC)
        return cal.get(Calendar.DAY_OF_WEEK)
    }

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

    fun isWeekend(): Boolean {
        val day = getCurrentDayOfWeek()
        return day == Calendar.SATURDAY || day == Calendar.SUNDAY
    }

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

    fun refreshStats() {
        while (tradeRecords.size > MAX_RECORDS) {
            tradeRecords.pollFirst()
        }

        val snapshot = tradeRecords.toList()

        for (hour in 0..23) {
            val trades = snapshot.filter { it.hourUtc == hour }
            hourlyStats[hour] = calculateSlotStats("H$hour", trades)
        }

        for (day in 1..7) {
            val trades = snapshot.filter { it.dayOfWeek == day }
            dailyStats[day] = calculateSlotStats("D$day", trades)
        }

        for (session in MarketSession.values()) {
            val trades = snapshot.filter { record ->
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
            return TimeSlotStats(
                slot = slot,
                tradeCount = 0,
                winCount = 0,
                lossCount = 0,
                winRate = 0.0,
                avgPnl = 0.0,
                isGolden = false,
                isDanger = false,
                entryAdjustment = 0.0
            )
        }

        val wins = trades.count { isWin(it.pnlPct) }
        val losses = trades.count { isLoss(it.pnlPct) }
        val decisiveTrades = wins + losses
        val avgPnl = trades.map { it.pnlPct }.average()

        val winRate = if (decisiveTrades > 0) {
            wins.toDouble() / decisiveTrades.toDouble() * 100.0
        } else {
            0.0
        }

        val hasEnoughData = decisiveTrades >= MIN_TRADES_FOR_CONFIDENCE

        val isGolden = hasEnoughData &&
            winRate >= GOLDEN_WIN_RATE &&
            avgPnl >= GOLDEN_AVG_PNL

        val isDanger = hasEnoughData &&
            (winRate <= DANGER_WIN_RATE || avgPnl <= DANGER_AVG_PNL)

        val adjustment = when {
            !hasEnoughData -> 0.0
            isGolden -> {
                val winBonus = (winRate - GOLDEN_WIN_RATE) * 0.2
                val pnlBonus = (avgPnl - GOLDEN_AVG_PNL) * 0.1
                minOf(winBonus + pnlBonus + 5.0, 12.0)
            }
            isDanger -> {
                val winPenalty = (DANGER_WIN_RATE - winRate) * 0.15
                val pnlPenalty = (DANGER_AVG_PNL - avgPnl) * 0.08
                maxOf(-(winPenalty + pnlPenalty + 2.0), -6.0)
            }
            avgPnl > 5.0 -> (avgPnl * 0.15).coerceAtMost(4.0)
            avgPnl < -5.0 -> (avgPnl * 0.2).coerceAtLeast(-4.0)
            else -> 0.0
        }

        return TimeSlotStats(
            slot = slot,
            tradeCount = decisiveTrades,
            winCount = wins,
            lossCount = losses,
            winRate = winRate,
            avgPnl = avgPnl,
            isGolden = isGolden,
            isDanger = isDanger,
            entryAdjustment = adjustment
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTRY SCORE ADJUSTMENT
    // ═══════════════════════════════════════════════════════════════════════════

    fun getEntryScoreAdjustment(): Double {
        ensureFreshStats()

        val hour = getCurrentHourUtc()
        val day = getCurrentDayOfWeek()
        val session = getCurrentSession()

        val hourlyAdj = hourlyStats[hour]?.entryAdjustment ?: 0.0
        val dailyAdj = dailyStats[day]?.entryAdjustment ?: 0.0
        val sessionAdj = sessionStats[session]?.entryAdjustment ?: 0.0

        val combined = (hourlyAdj * 0.5) + (sessionAdj * 0.3) + (dailyAdj * 0.2)
        return combined.coerceIn(-10.0, 12.0)
    }

    fun isGoldenHour(): Boolean {
        ensureFreshStats()
        val hour = getCurrentHourUtc()
        return hourlyStats[hour]?.isGolden == true
    }

    fun isDangerZone(): Boolean {
        ensureFreshStats()
        val hour = getCurrentHourUtc()
        return hourlyStats[hour]?.isDanger == true
    }

    fun getGoldenHours(): List<Int> {
        ensureFreshStats()
        return hourlyStats.entries
            .filter { it.value.isGolden }
            .map { it.key }
            .sorted()
    }

    fun getDangerHours(): List<Int> {
        ensureFreshStats()
        return hourlyStats.entries
            .filter { it.value.isDanger }
            .map { it.key }
            .sorted()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Record trade outcome for time learning.
     * Scratch trades are ignored.
     */
    fun recordOutcome(pnlPct: Double) {
        if (!isWin(pnlPct) && !isLoss(pnlPct)) {
            return
        }

        val cal = Calendar.getInstance(UTC)
        val record = TradeTimeRecord(
            hourUtc = cal.get(Calendar.HOUR_OF_DAY),
            dayOfWeek = cal.get(Calendar.DAY_OF_WEEK),
            pnlPct = pnlPct
        )
        tradeRecords.addLast(record)

        while (tradeRecords.size > MAX_RECORDS) {
            tradeRecords.pollFirst()
        }

        if (kotlin.math.abs(pnlPct) > 30.0) {
            val session = getCurrentSession()
            val hourStats = hourlyStats[record.hourUtc]
            ErrorLogger.debug(
                "TimeAI",
                "⏰ Trade ${pnlPct.toInt()}% @ H${record.hourUtc} [${dayName(record.dayOfWeek)}] ${session.label} | " +
                    "hour_avg=${hourStats?.avgPnl?.toInt() ?: 0}%"
            )
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
        ensureFreshStats()

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
                appendLine(
                    "H${hour.toString().padStart(2, '0')}: " +
                        "wr=${stats.winRate.toInt()}% " +
                        "avg=${stats.avgPnl.toInt()}% " +
                        "n=${stats.tradeCount} $status"
                )
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
                appendLine(
                    "${dayName(day)}: " +
                        "wr=${stats.winRate.toInt()}% " +
                        "avg=${stats.avgPnl.toInt()}% " +
                        "n=${stats.tradeCount} $status"
                )
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
                appendLine(
                    "${session.label}: " +
                        "wr=${stats.winRate.toInt()}% " +
                        "avg=${stats.avgPnl.toInt()}% " +
                        "n=${stats.tradeCount} $status"
                )
            }
        }
    }

    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            val recordsArray = JSONArray()
            tradeRecords.forEach { record ->
                recordsArray.put(
                    JSONObject().apply {
                        put("hour", record.hourUtc)
                        put("day", record.dayOfWeek)
                        put("pnl", record.pnlPct)
                        put("ts", record.timestamp)
                    }
                )
            }
            put("records", recordsArray)
        }
    }

    fun loadFromJson(json: JSONObject) {
        try {
            tradeRecords.clear()

            val recordsArray = json.optJSONArray("records")
            if (recordsArray != null) {
                for (i in 0 until recordsArray.length()) {
                    val obj = recordsArray.getJSONObject(i)
                    val pnlPct = obj.getDouble("pnl")

                    if (!isWin(pnlPct) && !isLoss(pnlPct)) {
                        continue
                    }

                    tradeRecords.addLast(
                        TradeTimeRecord(
                            hourUtc = obj.getInt("hour"),
                            dayOfWeek = obj.getInt("day"),
                            pnlPct = pnlPct,
                            timestamp = obj.optLong("ts", System.currentTimeMillis())
                        )
                    )
                }
            }

            while (tradeRecords.size > MAX_RECORDS) {
                tradeRecords.pollFirst()
            }

            refreshStats()
            ErrorLogger.info("TimeAI", "Loaded ${tradeRecords.size} time records")
        } catch (e: Exception) {
            ErrorLogger.error("TimeAI", "Failed to load: ${e.message}", e)
        }
    }

    fun cleanup() {
        while (tradeRecords.size > MAX_RECORDS) {
            tradeRecords.pollFirst()
        }
    }

    private fun ensureFreshStats() {
        if (System.currentTimeMillis() - lastStatsRefresh > STATS_REFRESH_STALE_MS) {
            refreshStats()
        }
    }

    private fun isWin(pnlPct: Double): Boolean = pnlPct >= WIN_THRESHOLD_PCT

    private fun isLoss(pnlPct: Double): Boolean = pnlPct <= LOSS_THRESHOLD_PCT
}
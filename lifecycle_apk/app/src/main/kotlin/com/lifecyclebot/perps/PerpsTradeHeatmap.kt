package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🔥 PERPS TRADE HEATMAP - V5.7.2
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Visual heatmap showing performance across markets and directions.
 * Helps identify which setups are working best.
 * 
 * DIMENSIONS:
 * ─────────────────────────────────────────────────────────────────────────────
 *   📊 BY MARKET         - SOL, AAPL, TSLA, NVDA, etc.
 *   📈 BY DIRECTION      - LONG vs SHORT
 *   ⚡ BY LEVERAGE TIER  - Sniper, Tactical, Assault, Nuclear
 *   🕐 BY TIME OF DAY    - Morning, Afternoon, Evening, Night
 *   📅 BY DAY OF WEEK    - Mon-Sun patterns
 *   🎯 BY SCANNER        - Which scanner produced best results
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsTradeHeatmap {
    
    private const val TAG = "🔥PerpsHeatmap"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HEATMAP CELLS
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Market x Direction heatmap
    private val marketDirectionStats = ConcurrentHashMap<String, HeatmapCell>()
    
    // Market x Leverage Tier heatmap
    private val marketTierStats = ConcurrentHashMap<String, HeatmapCell>()
    
    // Time of day stats
    private val timeOfDayStats = ConcurrentHashMap<String, HeatmapCell>()
    
    // Day of week stats
    private val dayOfWeekStats = ConcurrentHashMap<String, HeatmapCell>()
    
    // Scanner performance
    private val scannerStats = ConcurrentHashMap<String, HeatmapCell>()
    
    data class HeatmapCell(
        var trades: Int = 0,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnlPct: Double = 0.0,
        var bestTrade: Double = 0.0,
        var worstTrade: Double = 0.0,
        var avgHoldMinutes: Double = 0.0,
        var lastUpdate: Long = 0,
    ) {
        fun getWinRate(): Double = if (trades > 0) wins.toDouble() / trades * 100 else 0.0
        fun getAvgPnl(): Double = if (trades > 0) totalPnlPct / trades else 0.0
        fun getHeatScore(): Int {
            // Combine win rate and avg P&L into a single score (0-100)
            val wrScore = getWinRate()
            val pnlScore = (getAvgPnl() + 50).coerceIn(0.0, 100.0)  // Normalize P&L
            return ((wrScore * 0.6 + pnlScore * 0.4)).toInt().coerceIn(0, 100)
        }
        fun getColor(): String {
            val score = getHeatScore()
            return when {
                score >= 70 -> "#22C55E"  // Green - hot
                score >= 55 -> "#84CC16"  // Lime
                score >= 45 -> "#F59E0B"  // Amber
                score >= 35 -> "#F97316"  // Orange
                else -> "#EF4444"         // Red - cold
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class HeatmapData(
        val marketDirection: Map<Pair<PerpsMarket, PerpsDirection>, HeatmapCell>,
        val marketTier: Map<Pair<PerpsMarket, PerpsRiskTier>, HeatmapCell>,
        val timeOfDay: Map<TimeSlot, HeatmapCell>,
        val dayOfWeek: Map<Int, HeatmapCell>,  // 1-7 (Sunday = 1)
        val scanners: Map<String, HeatmapCell>,
        val topSetups: List<SetupRanking>,
        val worstSetups: List<SetupRanking>,
    )
    
    data class SetupRanking(
        val description: String,
        val winRate: Double,
        val avgPnl: Double,
        val trades: Int,
        val emoji: String,
    )
    
    enum class TimeSlot(val hours: IntRange, val emoji: String, val label: String) {
        EARLY_MORNING(0..5, "🌙", "Early AM"),
        MORNING(6..11, "🌅", "Morning"),
        AFTERNOON(12..17, "☀️", "Afternoon"),
        EVENING(18..23, "🌆", "Evening"),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDING TRADES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a completed trade to all heatmaps
     */
    fun recordTrade(trade: PerpsTrade, scanner: String? = null) {
        val isWin = trade.pnlPct > 0
        val holdMinutes = ((trade.closeTime - trade.openTime) / 60_000).toDouble()
        
        // Market x Direction
        val mdKey = "${trade.market.symbol}_${trade.direction.symbol}"
        updateCell(marketDirectionStats, mdKey, trade.pnlPct, isWin, holdMinutes)
        
        // Market x Tier
        val mtKey = "${trade.market.symbol}_${trade.riskTier.name}"
        updateCell(marketTierStats, mtKey, trade.pnlPct, isWin, holdMinutes)
        
        // Time of day
        val hour = java.util.Calendar.getInstance().apply { 
            timeInMillis = trade.openTime 
        }.get(java.util.Calendar.HOUR_OF_DAY)
        val timeSlot = TimeSlot.values().find { hour in it.hours } ?: TimeSlot.AFTERNOON
        updateCell(timeOfDayStats, timeSlot.name, trade.pnlPct, isWin, holdMinutes)
        
        // Day of week
        val dayOfWeek = java.util.Calendar.getInstance().apply { 
            timeInMillis = trade.openTime 
        }.get(java.util.Calendar.DAY_OF_WEEK)
        updateCell(dayOfWeekStats, dayOfWeek.toString(), trade.pnlPct, isWin, holdMinutes)
        
        // Scanner
        scanner?.let {
            updateCell(scannerStats, it, trade.pnlPct, isWin, holdMinutes)
        }
        
        ErrorLogger.debug(TAG, "🔥 Recorded: ${trade.market.symbol} ${trade.direction.symbol} " +
            "${trade.riskTier.emoji} | pnl=${trade.pnlPct.fmt(1)}%")
    }
    
    private fun updateCell(
        map: ConcurrentHashMap<String, HeatmapCell>,
        key: String,
        pnlPct: Double,
        isWin: Boolean,
        holdMinutes: Double,
    ) {
        val cell = map.getOrPut(key) { HeatmapCell() }
        
        cell.trades++
        if (isWin) cell.wins++ else cell.losses++
        cell.totalPnlPct += pnlPct
        
        if (pnlPct > cell.bestTrade) cell.bestTrade = pnlPct
        if (pnlPct < cell.worstTrade) cell.worstTrade = pnlPct
        
        // Running average of hold time
        cell.avgHoldMinutes = (cell.avgHoldMinutes * (cell.trades - 1) + holdMinutes) / cell.trades
        cell.lastUpdate = System.currentTimeMillis()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HEATMAP QUERIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get complete heatmap data for UI rendering
     */
    fun getHeatmapData(): HeatmapData {
        // Convert to typed maps
        val mdMap = mutableMapOf<Pair<PerpsMarket, PerpsDirection>, HeatmapCell>()
        marketDirectionStats.forEach { (key, cell) ->
            val parts = key.split("_")
            if (parts.size == 2) {
                val market = PerpsMarket.values().find { it.symbol == parts[0] }
                val direction = PerpsDirection.values().find { it.symbol == parts[1] }
                if (market != null && direction != null) {
                    mdMap[Pair(market, direction)] = cell
                }
            }
        }
        
        val mtMap = mutableMapOf<Pair<PerpsMarket, PerpsRiskTier>, HeatmapCell>()
        marketTierStats.forEach { (key, cell) ->
            val parts = key.split("_")
            if (parts.size == 2) {
                val market = PerpsMarket.values().find { it.symbol == parts[0] }
                val tier = PerpsRiskTier.values().find { it.name == parts[1] }
                if (market != null && tier != null) {
                    mtMap[Pair(market, tier)] = cell
                }
            }
        }
        
        val todMap = mutableMapOf<TimeSlot, HeatmapCell>()
        timeOfDayStats.forEach { (key, cell) ->
            TimeSlot.values().find { it.name == key }?.let { slot ->
                todMap[slot] = cell
            }
        }
        
        val dowMap = mutableMapOf<Int, HeatmapCell>()
        dayOfWeekStats.forEach { (key, cell) ->
            key.toIntOrNull()?.let { day ->
                dowMap[day] = cell
            }
        }
        
        // Find top and worst setups
        val allSetups = marketDirectionStats.map { (key, cell) ->
            val parts = key.split("_")
            val desc = "${parts.getOrNull(0) ?: "?"} ${parts.getOrNull(1) ?: "?"}"
            SetupRanking(
                description = desc,
                winRate = cell.getWinRate(),
                avgPnl = cell.getAvgPnl(),
                trades = cell.trades,
                emoji = if (cell.getWinRate() >= 50) "🔥" else "❄️",
            )
        }.filter { it.trades >= 3 }  // Min 3 trades to rank
        
        val topSetups = allSetups.sortedByDescending { it.winRate }.take(5)
        val worstSetups = allSetups.sortedBy { it.winRate }.take(5)
        
        return HeatmapData(
            marketDirection = mdMap,
            marketTier = mtMap,
            timeOfDay = todMap,
            dayOfWeek = dowMap,
            scanners = scannerStats.toMap(),
            topSetups = topSetups,
            worstSetups = worstSetups,
        )
    }
    
    /**
     * Get best market/direction combo
     */
    fun getBestSetup(): Pair<PerpsMarket, PerpsDirection>? {
        val best = marketDirectionStats.entries
            .filter { it.value.trades >= 3 }
            .maxByOrNull { it.value.getHeatScore() }
        
        best?.let {
            val parts = it.key.split("_")
            val market = PerpsMarket.values().find { m -> m.symbol == parts.getOrNull(0) }
            val direction = PerpsDirection.values().find { d -> d.symbol == parts.getOrNull(1) }
            if (market != null && direction != null) {
                return Pair(market, direction)
            }
        }
        
        return null
    }
    
    /**
     * Get best risk tier for a market
     */
    fun getBestTierForMarket(market: PerpsMarket): PerpsRiskTier? {
        return marketTierStats.entries
            .filter { it.key.startsWith(market.symbol) && it.value.trades >= 2 }
            .maxByOrNull { it.value.getHeatScore() }
            ?.let {
                val tierName = it.key.split("_").getOrNull(1)
                PerpsRiskTier.values().find { t -> t.name == tierName }
            }
    }
    
    /**
     * Get best time to trade
     */
    fun getBestTimeSlot(): TimeSlot? {
        return timeOfDayStats.entries
            .filter { it.value.trades >= 3 }
            .maxByOrNull { it.value.getHeatScore() }
            ?.let { TimeSlot.values().find { slot -> slot.name == it.key } }
    }
    
    /**
     * Get AI recommendation based on heatmap
     */
    fun getAIRecommendation(): String {
        val bestSetup = getBestSetup()
        val bestTime = getBestTimeSlot()
        
        val sb = StringBuilder()
        sb.append("🔥 HEATMAP INSIGHTS:\n")
        
        bestSetup?.let { (market, dir) ->
            val cell = marketDirectionStats["${market.symbol}_${dir.symbol}"]
            sb.append("📈 Best Setup: ${market.emoji} ${market.symbol} ${dir.emoji} ${dir.symbol}\n")
            sb.append("   Win Rate: ${cell?.getWinRate()?.toInt()}% | Avg P&L: ${cell?.getAvgPnl()?.fmt(1)}%\n")
        }
        
        bestTime?.let { slot ->
            val cell = timeOfDayStats[slot.name]
            sb.append("🕐 Best Time: ${slot.emoji} ${slot.label}\n")
            sb.append("   Win Rate: ${cell?.getWinRate()?.toInt()}%\n")
        }
        
        // Worst to avoid
        val worstSetup = marketDirectionStats.entries
            .filter { it.value.trades >= 3 }
            .minByOrNull { it.value.getHeatScore() }
        
        worstSetup?.let {
            val parts = it.key.split("_")
            sb.append("❄️ Avoid: ${parts[0]} ${parts[1]} (${it.value.getWinRate().toInt()}% WR)\n")
        }
        
        return sb.toString()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var prefs: android.content.SharedPreferences? = null
    
    fun init(context: android.content.Context) {
        prefs = context.getSharedPreferences("perps_heatmap", android.content.Context.MODE_PRIVATE)
        restore()
        ErrorLogger.info(TAG, "🔥 PerpsTradeHeatmap initialized")
    }
    
    private fun restore() {
        val p = prefs ?: return
        
        // Restore market direction stats
        p.all.filter { it.key.startsWith("md_") }.forEach { (key, value) ->
            val cellKey = key.removePrefix("md_")
            try {
                val parts = (value as String).split("|")
                if (parts.size >= 6) {
                    marketDirectionStats[cellKey] = HeatmapCell(
                        trades = parts[0].toInt(),
                        wins = parts[1].toInt(),
                        losses = parts[2].toInt(),
                        totalPnlPct = parts[3].toDouble(),
                        bestTrade = parts[4].toDouble(),
                        worstTrade = parts[5].toDouble(),
                    )
                }
            } catch (_: Exception) {}
        }
    }
    
    fun save() {
        val p = prefs ?: return
        
        p.edit().apply {
            marketDirectionStats.forEach { (key, cell) ->
                putString("md_$key", "${cell.trades}|${cell.wins}|${cell.losses}|${cell.totalPnlPct}|${cell.bestTrade}|${cell.worstTrade}")
            }
            apply()
        }
    }
    
    fun reset() {
        marketDirectionStats.clear()
        marketTierStats.clear()
        timeOfDayStats.clear()
        dayOfWeekStats.clear()
        scannerStats.clear()
        prefs?.edit()?.clear()?.apply()
        ErrorLogger.info(TAG, "🔥 Heatmap reset")
    }
    
    private fun Double.fmt(decimals: Int): String = String.format("%.${decimals}f", this)
}

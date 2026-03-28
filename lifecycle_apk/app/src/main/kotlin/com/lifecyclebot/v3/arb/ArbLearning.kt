package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * ArbLearning - Outcome learning for arb trades
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Records arb trade outcomes and learns optimal thresholds.
 * 
 * LEARNING TARGETS:
 * 1. Which arb types perform best
 * 2. Optimal score thresholds per type
 * 3. Optimal hold times per type
 * 4. Token-specific patterns (avoid repeated losers)
 */
object ArbLearning {
    
    private const val TAG = "ArbLearning"
    private const val MAX_OUTCOMES = 500
    private const val MAX_PER_TOKEN = 10
    
    // Outcome storage
    private val outcomes = ConcurrentLinkedDeque<ArbOutcome>()
    
    // Per-token tracking (for recent loss detection)
    private val tokenOutcomes = ConcurrentHashMap<String, MutableList<ArbOutcome>>()
    
    // Learned thresholds (start with defaults)
    @Volatile var venueLagMinScore = 55
    @Volatile var flowImbalanceMinScore = 55
    @Volatile var panicReversionMinScore = 55
    
    // Performance stats by type
    data class TypeStats(
        var trades: Int = 0,
        var wins: Int = 0,
        var totalPnl: Double = 0.0,
        var avgHoldTime: Double = 0.0
    ) {
        val winRate: Double get() = if (trades > 0) (wins.toDouble() / trades) * 100 else 0.0
        val avgPnl: Double get() = if (trades > 0) totalPnl / trades else 0.0
    }
    
    private val typeStats = ConcurrentHashMap<ArbType, TypeStats>().apply {
        ArbType.values().forEach { put(it, TypeStats()) }
    }
    
    /**
     * Record an arb trade outcome.
     */
    fun recordOutcome(outcome: ArbOutcome) {
        try {
            // Add to main list
            outcomes.addLast(outcome)
            
            // Trim if too many
            while (outcomes.size > MAX_OUTCOMES) {
                outcomes.pollFirst()
            }
            
            // Add to per-token tracking
            val tokenList = tokenOutcomes.getOrPut(outcome.mint) { mutableListOf() }
            synchronized(tokenList) {
                tokenList.add(outcome)
                if (tokenList.size > MAX_PER_TOKEN) {
                    tokenList.removeAt(0)
                }
            }
            
            // Update type stats
            typeStats[outcome.arbType]?.let { stats ->
                stats.trades++
                if (outcome.isWin) stats.wins++
                stats.totalPnl += outcome.pnlPct
                stats.avgHoldTime = ((stats.avgHoldTime * (stats.trades - 1)) + outcome.holdSeconds) / stats.trades
            }
            
            // Learn from outcome
            learnFromOutcome(outcome)
            
            ErrorLogger.debug(TAG, "[ARB_LEARN] ${outcome.symbol} | ${outcome.arbType} | " +
                "pnl=${String.format("%.1f", outcome.pnlPct)}% | ${if (outcome.isWin) "WIN" else "LOSS"}")
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordOutcome error: ${e.message}")
        }
    }
    
    /**
     * Learn from an outcome and adjust thresholds.
     */
    private fun learnFromOutcome(outcome: ArbOutcome) {
        val stats = typeStats[outcome.arbType] ?: return
        
        // Only adjust after enough data
        if (stats.trades < 10) return
        
        when (outcome.arbType) {
            ArbType.VENUE_LAG -> {
                // If win rate drops below 50%, raise threshold
                if (stats.winRate < 50 && venueLagMinScore < 70) {
                    venueLagMinScore++
                    ErrorLogger.debug(TAG, "[ARB_LEARN] VenueLag threshold raised to $venueLagMinScore")
                }
                // If win rate is high, can lower threshold
                else if (stats.winRate > 65 && stats.trades >= 20 && venueLagMinScore > 45) {
                    venueLagMinScore--
                }
            }
            ArbType.FLOW_IMBALANCE -> {
                if (stats.winRate < 50 && flowImbalanceMinScore < 70) {
                    flowImbalanceMinScore++
                    ErrorLogger.debug(TAG, "[ARB_LEARN] FlowImbalance threshold raised to $flowImbalanceMinScore")
                }
                else if (stats.winRate > 65 && stats.trades >= 20 && flowImbalanceMinScore > 45) {
                    flowImbalanceMinScore--
                }
            }
            ArbType.PANIC_REVERSION -> {
                // Panic reversion needs higher bar due to risk
                if (stats.winRate < 55 && panicReversionMinScore < 75) {
                    panicReversionMinScore++
                    ErrorLogger.debug(TAG, "[ARB_LEARN] PanicReversion threshold raised to $panicReversionMinScore")
                }
                else if (stats.winRate > 70 && stats.trades >= 20 && panicReversionMinScore > 50) {
                    panicReversionMinScore--
                }
            }
        }
    }
    
    /**
     * Get recent loss count for a token.
     * Used to avoid repeated losses on same token.
     */
    fun getRecentLossCount(mint: String): Int {
        return try {
            val list = tokenOutcomes[mint] ?: return 0
            val now = System.currentTimeMillis()
            val oneHourAgo = now - (60 * 60 * 1000)
            
            synchronized(list) {
                list.count { !it.isWin && it.timestampMs >= oneHourAgo }
            }
        } catch (e: Exception) {
            0
        }
    }
    
    /**
     * Get performance stats for all arb types.
     */
    fun getTypeStats(): Map<ArbType, TypeStats> {
        return typeStats.toMap()
    }
    
    /**
     * Get overall stats.
     */
    fun getOverallStats(): OverallStats {
        val all = outcomes.toList()
        val wins = all.count { it.isWin }
        val totalPnl = all.sumOf { it.pnlPct }
        
        return OverallStats(
            totalTrades = all.size,
            wins = wins,
            losses = all.size - wins,
            winRate = if (all.isNotEmpty()) (wins.toDouble() / all.size) * 100 else 0.0,
            totalPnl = totalPnl,
            avgPnl = if (all.isNotEmpty()) totalPnl / all.size else 0.0,
            avgHoldSeconds = if (all.isNotEmpty()) all.sumOf { it.holdSeconds } / all.size else 0
        )
    }
    
    data class OverallStats(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val totalPnl: Double,
        val avgPnl: Double,
        val avgHoldSeconds: Int
    )
    
    /**
     * Get stats string for logging.
     */
    fun getStats(): String {
        val overall = getOverallStats()
        return "ArbLearning: ${overall.totalTrades} trades | " +
               "${String.format("%.0f", overall.winRate)}% WR | " +
               "${String.format("%.1f", overall.avgPnl)}% avg | " +
               "thresholds: VL=$venueLagMinScore FI=$flowImbalanceMinScore PR=$panicReversionMinScore"
    }
    
    /**
     * Save to JSON.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("venueLagMinScore", venueLagMinScore)
            put("flowImbalanceMinScore", flowImbalanceMinScore)
            put("panicReversionMinScore", panicReversionMinScore)
            
            val overall = getOverallStats()
            put("totalTrades", overall.totalTrades)
            put("winRate", overall.winRate)
            put("avgPnl", overall.avgPnl)
            
            val typeStatsJson = JSONObject()
            typeStats.forEach { (type, stats) ->
                typeStatsJson.put(type.name, JSONObject().apply {
                    put("trades", stats.trades)
                    put("wins", stats.wins)
                    put("winRate", stats.winRate)
                    put("avgPnl", stats.avgPnl)
                    put("avgHoldTime", stats.avgHoldTime)
                })
            }
            put("typeStats", typeStatsJson)
            
            // Recent outcomes
            val recentOutcomes = JSONArray()
            outcomes.takeLast(20).forEach { outcome ->
                recentOutcomes.put(JSONObject().apply {
                    put("symbol", outcome.symbol)
                    put("type", outcome.arbType.name)
                    put("pnl", outcome.pnlPct)
                    put("win", outcome.isWin)
                    put("hold", outcome.holdSeconds)
                })
            }
            put("recentOutcomes", recentOutcomes)
        }
    }
    
    /**
     * Load from JSON.
     */
    fun loadFromJson(json: JSONObject) {
        try {
            venueLagMinScore = json.optInt("venueLagMinScore", 55)
            flowImbalanceMinScore = json.optInt("flowImbalanceMinScore", 55)
            panicReversionMinScore = json.optInt("panicReversionMinScore", 55)
            
            json.optJSONObject("typeStats")?.let { stats ->
                ArbType.values().forEach { type ->
                    stats.optJSONObject(type.name)?.let { typeJson ->
                        typeStats[type]?.apply {
                            trades = typeJson.optInt("trades", 0)
                            wins = typeJson.optInt("wins", 0)
                            totalPnl = typeJson.optDouble("avgPnl", 0.0) * trades
                            avgHoldTime = typeJson.optDouble("avgHoldTime", 0.0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "loadFromJson error: ${e.message}")
        }
    }
    
    /**
     * Reset all learning data.
     */
    fun reset() {
        outcomes.clear()
        tokenOutcomes.clear()
        typeStats.values.forEach { it.trades = 0; it.wins = 0; it.totalPnl = 0.0; it.avgHoldTime = 0.0 }
        venueLagMinScore = 55
        flowImbalanceMinScore = 55
        panicReversionMinScore = 55
    }
}

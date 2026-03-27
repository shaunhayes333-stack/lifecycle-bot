package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * SuperBrainEnhancements — Additional market intelligence layer.
 * 
 * Extends BotBrain with:
 * - Chart pattern insights recording
 * - Market breadth tracking
 * - Cross-signal aggregation
 * - Collective learning preparation
 * 
 * DEFENSIVE DESIGN:
 * - Static object with no initialization
 * - All methods wrapped in try/catch
 * - Thread-safe data structures
 * - No blocking operations
 */
object SuperBrainEnhancements {
    
    private const val TAG = "SuperBrain+"
    private const val MAX_INSIGHTS = 500
    private const val MAX_BREADTH_HISTORY = 100
    
    // ═══════════════════════════════════════════════════════════════════
    // CHART PATTERN INSIGHTS
    // Records patterns detected for later analysis
    // ═══════════════════════════════════════════════════════════════════
    
    data class ChartInsight(
        val mint: String,
        val symbol: String,
        val pattern: String,           // e.g., "HAMMER", "DOUBLE_BOTTOM", "BREAKOUT"
        val timeframe: String,         // e.g., "1m", "5m", "1h"
        val confidence: Double,        // 0-100
        val priceAtDetection: Double,
        val timestampMs: Long,
        val outcome: String? = null,   // Filled in later: "WIN", "LOSS", "PENDING"
        val pnlPct: Double? = null,    // Filled in after trade closes
    )
    
    private val chartInsights = ConcurrentHashMap<String, MutableList<ChartInsight>>()
    private val insightCounter = AtomicLong(0)
    
    /**
     * Record a detected chart pattern.
     */
    fun recordChartInsight(
        mint: String,
        symbol: String,
        pattern: String,
        timeframe: String,
        confidence: Double,
        priceAtDetection: Double,
    ) {
        try {
            val insight = ChartInsight(
                mint = mint,
                symbol = symbol,
                pattern = pattern,
                timeframe = timeframe,
                confidence = confidence,
                priceAtDetection = priceAtDetection,
                timestampMs = System.currentTimeMillis(),
            )
            
            val list = chartInsights.getOrPut(mint) { mutableListOf() }
            synchronized(list) {
                list.add(insight)
                // Keep only recent insights per token
                if (list.size > 50) {
                    list.removeAt(0)
                }
            }
            
            insightCounter.incrementAndGet()
            
            // Log significant patterns
            if (confidence >= 70) {
                ErrorLogger.info(TAG, "📊 Pattern: $pattern on $symbol ($timeframe) conf=${confidence.toInt()}%")
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordChartInsight error: ${e.message}")
        }
    }
    
    /**
     * Update insight outcome after trade completes.
     */
    fun updateInsightOutcome(mint: String, outcome: String, pnlPct: Double) {
        try {
            chartInsights[mint]?.let { list ->
                synchronized(list) {
                    // Update most recent pending insight
                    list.lastOrNull { it.outcome == null }?.let { insight ->
                        val index = list.indexOf(insight)
                        list[index] = insight.copy(outcome = outcome, pnlPct = pnlPct)
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "updateInsightOutcome error: ${e.message}")
        }
    }
    
    /**
     * Get pattern performance statistics.
     */
    fun getPatternStats(): Map<String, PatternStat> {
        return try {
            val stats = mutableMapOf<String, PatternStat>()
            
            chartInsights.values.flatten()
                .filter { it.outcome != null }
                .groupBy { it.pattern }
                .forEach { (pattern, insights) ->
                    val wins = insights.count { it.outcome == "WIN" }
                    val total = insights.size
                    val avgPnl = insights.mapNotNull { it.pnlPct }.average().takeIf { !it.isNaN() } ?: 0.0
                    
                    stats[pattern] = PatternStat(
                        pattern = pattern,
                        trades = total,
                        winRate = if (total > 0) wins.toDouble() / total * 100 else 0.0,
                        avgPnlPct = avgPnl,
                    )
                }
            
            stats
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    data class PatternStat(
        val pattern: String,
        val trades: Int,
        val winRate: Double,
        val avgPnlPct: Double,
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // MARKET BREADTH TRACKING
    // Tracks overall market health indicators
    // ═══════════════════════════════════════════════════════════════════
    
    data class BreadthSnapshot(
        val timestampMs: Long,
        val advancingCount: Int,       // Tokens going up
        val decliningCount: Int,       // Tokens going down
        val newHighsCount: Int,        // Tokens at 24h high
        val newLowsCount: Int,         // Tokens at 24h low
        val avgVolumeChange: Double,   // Average volume change %
        val topGainerPct: Double,      // Best performer %
        val topLoserPct: Double,       // Worst performer %
    ) {
        val breadthRatio: Double get() = if (decliningCount > 0) {
            advancingCount.toDouble() / decliningCount
        } else {
            advancingCount.toDouble()
        }
        
        val sentiment: String get() = when {
            breadthRatio >= 2.0 -> "STRONG_BULL"
            breadthRatio >= 1.2 -> "BULL"
            breadthRatio >= 0.8 -> "NEUTRAL"
            breadthRatio >= 0.5 -> "BEAR"
            else -> "STRONG_BEAR"
        }
    }
    
    private val breadthHistory = mutableListOf<BreadthSnapshot>()
    @Volatile private var latestBreadth: BreadthSnapshot? = null
    
    /**
     * Record market breadth snapshot.
     */
    fun recordBreadth(
        advancingCount: Int,
        decliningCount: Int,
        newHighsCount: Int = 0,
        newLowsCount: Int = 0,
        avgVolumeChange: Double = 0.0,
        topGainerPct: Double = 0.0,
        topLoserPct: Double = 0.0,
    ) {
        try {
            val snapshot = BreadthSnapshot(
                timestampMs = System.currentTimeMillis(),
                advancingCount = advancingCount,
                decliningCount = decliningCount,
                newHighsCount = newHighsCount,
                newLowsCount = newLowsCount,
                avgVolumeChange = avgVolumeChange,
                topGainerPct = topGainerPct,
                topLoserPct = topLoserPct,
            )
            
            latestBreadth = snapshot
            
            synchronized(breadthHistory) {
                breadthHistory.add(snapshot)
                if (breadthHistory.size > MAX_BREADTH_HISTORY) {
                    breadthHistory.removeAt(0)
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordBreadth error: ${e.message}")
        }
    }
    
    /**
     * Get current market sentiment from breadth.
     */
    fun getCurrentSentiment(): String {
        return latestBreadth?.sentiment ?: "UNKNOWN"
    }
    
    /**
     * Get breadth trend (improving/declining).
     */
    fun getBreadthTrend(): String {
        return try {
            synchronized(breadthHistory) {
                if (breadthHistory.size < 3) return "INSUFFICIENT_DATA"
                
                val recent = breadthHistory.takeLast(3)
                val ratios = recent.map { it.breadthRatio }
                
                when {
                    ratios[2] > ratios[1] && ratios[1] > ratios[0] -> "IMPROVING"
                    ratios[2] < ratios[1] && ratios[1] < ratios[0] -> "DECLINING"
                    else -> "MIXED"
                }
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // CROSS-SIGNAL AGGREGATION
    // Combines signals from multiple sources
    // ═══════════════════════════════════════════════════════════════════
    
    data class AggregatedSignal(
        val mint: String,
        val symbol: String,
        val bullishSignals: Int,
        val bearishSignals: Int,
        val neutralSignals: Int,
        val sources: List<String>,
        val overallBias: String,       // "BULLISH", "BEARISH", "NEUTRAL"
        val confidence: Double,
        val timestampMs: Long,
    )
    
    private val aggregatedSignals = ConcurrentHashMap<String, AggregatedSignal>()
    
    /**
     * Record a signal from a specific source.
     */
    fun recordSignal(
        mint: String,
        symbol: String,
        source: String,
        signalType: String,  // "BULLISH", "BEARISH", "NEUTRAL"
    ) {
        try {
            val existing = aggregatedSignals[mint]
            val now = System.currentTimeMillis()
            
            // Reset if too old (> 5 minutes)
            val shouldReset = existing == null || (now - existing.timestampMs) > 300_000
            
            val newSignal = if (shouldReset) {
                AggregatedSignal(
                    mint = mint,
                    symbol = symbol,
                    bullishSignals = if (signalType == "BULLISH") 1 else 0,
                    bearishSignals = if (signalType == "BEARISH") 1 else 0,
                    neutralSignals = if (signalType == "NEUTRAL") 1 else 0,
                    sources = listOf(source),
                    overallBias = signalType,
                    confidence = 50.0,
                    timestampMs = now,
                )
            } else {
                existing!!.copy(
                    bullishSignals = existing.bullishSignals + if (signalType == "BULLISH") 1 else 0,
                    bearishSignals = existing.bearishSignals + if (signalType == "BEARISH") 1 else 0,
                    neutralSignals = existing.neutralSignals + if (signalType == "NEUTRAL") 1 else 0,
                    sources = (existing.sources + source).distinct(),
                    timestampMs = now,
                ).let { updated ->
                    // Recalculate bias
                    val total = updated.bullishSignals + updated.bearishSignals + updated.neutralSignals
                    val bias = when {
                        updated.bullishSignals > updated.bearishSignals * 1.5 -> "BULLISH"
                        updated.bearishSignals > updated.bullishSignals * 1.5 -> "BEARISH"
                        else -> "NEUTRAL"
                    }
                    val conf = if (total > 0) {
                        maxOf(updated.bullishSignals, updated.bearishSignals).toDouble() / total * 100
                    } else 50.0
                    
                    updated.copy(overallBias = bias, confidence = conf)
                }
            }
            
            aggregatedSignals[mint] = newSignal
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordSignal error: ${e.message}")
        }
    }
    
    /**
     * Get aggregated signal for a token.
     */
    fun getAggregatedSignal(mint: String): AggregatedSignal? {
        return try {
            aggregatedSignals[mint]?.takeIf { 
                System.currentTimeMillis() - it.timestampMs < 300_000 
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // DASHBOARD DATA
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Get summary for SuperBrain dashboard.
     */
    fun getDashboardData(): SuperBrainDashboard {
        return try {
            val patternStats = getPatternStats()
            val topPatterns = patternStats.values
                .filter { it.trades >= 3 }
                .sortedByDescending { it.winRate }
                .take(5)
            
            SuperBrainDashboard(
                totalInsights = insightCounter.get().toInt(),
                marketSentiment = getCurrentSentiment(),
                breadthTrend = getBreadthTrend(),
                topPatterns = topPatterns,
                activeSignals = aggregatedSignals.size,
                latestBreadth = latestBreadth,
            )
        } catch (e: Exception) {
            SuperBrainDashboard()
        }
    }
    
    data class SuperBrainDashboard(
        val totalInsights: Int = 0,
        val marketSentiment: String = "UNKNOWN",
        val breadthTrend: String = "UNKNOWN",
        val topPatterns: List<PatternStat> = emptyList(),
        val activeSignals: Int = 0,
        val latestBreadth: BreadthSnapshot? = null,
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // SERIALIZATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Export state to JSON.
     */
    fun toJson(): JSONObject {
        return try {
            JSONObject().apply {
                put("insightCount", insightCounter.get())
                put("sentiment", getCurrentSentiment())
                put("breadthTrend", getBreadthTrend())
                
                // Pattern stats
                val statsArray = JSONArray()
                getPatternStats().values.forEach { stat ->
                    statsArray.put(JSONObject().apply {
                        put("pattern", stat.pattern)
                        put("trades", stat.trades)
                        put("winRate", stat.winRate)
                        put("avgPnl", stat.avgPnlPct)
                    })
                }
                put("patternStats", statsArray)
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }
    
    /**
     * Clear all data (for testing/reset).
     */
    fun clear() {
        try {
            chartInsights.clear()
            breadthHistory.clear()
            aggregatedSignals.clear()
            latestBreadth = null
            insightCounter.set(0)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "clear error: ${e.message}")
        }
    }
}

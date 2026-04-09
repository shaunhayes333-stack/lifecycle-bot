package com.lifecyclebot.perps

import com.lifecyclebot.collective.CollectiveLearning
import com.lifecyclebot.collective.PerpsInsightRecord
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🧠 PERPS LEARNING INSIGHTS PANEL - V5.7.3
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Generates and displays AI-powered learning insights from the Perps trading system.
 * 
 * INSIGHT TYPES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🎯 PATTERN_DISCOVERED - New winning/losing pattern identified
 *   📈 LAYER_IMPROVEMENT  - AI layer showing improvement
 *   📉 LAYER_DECLINE      - AI layer showing decline
 *   🔥 HOT_SETUP          - Frequently winning setup detected
 *   ⚠️  RISK_WARNING       - Risk threshold breach detected
 *   💡 OPTIMIZATION       - Suggested parameter optimization
 *   🏆 MILESTONE          - Achievement unlocked
 *   🔄 CORRELATION        - Cross-layer correlation discovered
 * 
 * DISPLAY MODES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Real-time feed (last 10 insights)
 *   • Daily summary (key learnings)
 *   • Layer performance rankings
 *   • Market-specific insights
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsLearningInsightsPanel {
    
    private const val TAG = "🧠InsightsPanel"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INSIGHT TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class InsightType(val emoji: String, val displayName: String, val priority: Int) {
        PATTERN_DISCOVERED("🎯", "Pattern Discovered", 1),
        LAYER_IMPROVEMENT("📈", "Layer Improving", 2),
        LAYER_DECLINE("📉", "Layer Declining", 3),
        HOT_SETUP("🔥", "Hot Setup", 1),
        RISK_WARNING("⚠️", "Risk Warning", 0),
        OPTIMIZATION("💡", "Optimization", 4),
        MILESTONE("🏆", "Milestone", 2),
        CORRELATION("🔄", "Correlation Found", 3),
        AUTO_BUY("📡", "Auto-Buy Executed", 2),
        MARKET_SHIFT("🌊", "Market Shift", 2),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INSIGHT DATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class DisplayInsight(
        val id: Long,
        val type: InsightType,
        val title: String,
        val description: String,
        val market: String?,
        val layerName: String?,
        val impactScore: Double,
        val timestamp: Long,
        val actionable: Boolean,
        val actionText: String?,
    ) {
        fun getTimeAgo(): String {
            val mins = (System.currentTimeMillis() - timestamp) / 60_000
            return when {
                mins < 1 -> "just now"
                mins < 60 -> "${mins}m ago"
                mins < 1440 -> "${mins / 60}h ago"
                else -> "${mins / 1440}d ago"
            }
        }
        
        fun getDisplayText(): String {
            return "${type.emoji} $title\n$description"
        }
    }
    
    // Cache for recent insights
    private val insightsCache = mutableListOf<DisplayInsight>()
    private val lastRefresh = AtomicLong(0)
    private const val CACHE_TTL_MS = 30_000L  // 30 second cache
    
    // Stats
    private val insightsGenerated = AtomicInteger(0)
    private val patternsIdentified = AtomicInteger(0)
    private val optimizationsApplied = AtomicInteger(0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INSIGHT GENERATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Generate a new insight and persist it
     */
    suspend fun generateInsight(
        type: InsightType,
        title: String,
        description: String,
        market: String? = null,
        layerName: String? = null,
        impactScore: Double = 0.0,
        actionable: Boolean = false,
        actionText: String? = null,
    ): DisplayInsight {
        val insight = DisplayInsight(
            id = System.currentTimeMillis(),
            type = type,
            title = title,
            description = description,
            market = market,
            layerName = layerName,
            impactScore = impactScore,
            timestamp = System.currentTimeMillis(),
            actionable = actionable,
            actionText = actionText,
        )
        
        // Add to cache
        synchronized(insightsCache) {
            insightsCache.add(0, insight)
            if (insightsCache.size > 50) {
                insightsCache.removeAt(insightsCache.size - 1)
            }
        }
        
        insightsGenerated.incrementAndGet()
        
        // Persist to Turso
        try {
            val record = PerpsInsightRecord(
                instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: "",
                insightType = type.name,
                layerName = layerName,
                market = market,
                direction = null,
                insight = "$title: $description",
                actionTaken = actionText ?: "",
                impactScore = impactScore,
                timestamp = System.currentTimeMillis(),
            )
            CollectiveLearning.getClient()?.savePerpsInsight(record)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Failed to persist insight: ${e.message}")
        }
        
        return insight
    }
    
    /**
     * Generate insights from recent trading activity
     */
    suspend fun analyzeAndGenerateInsights() {
        try {
            // Get layer stats
            val layerStats = PerpsLearningBridge.getLayerPerpsStats()
            
            // Check for layer improvements/declines
            layerStats.forEach { (layerName, stats) ->
                val (trust, accuracy) = stats
                
                if (accuracy >= 70 && trust >= 0.8) {
                    generateInsight(
                        type = InsightType.LAYER_IMPROVEMENT,
                        title = "$layerName Performing Well",
                        description = "Accuracy: ${accuracy.toInt()}% | Trust: ${(trust * 100).toInt()}%",
                        layerName = layerName,
                        impactScore = accuracy,
                    )
                } else if (accuracy < 40 && trust < 0.4) {
                    generateInsight(
                        type = InsightType.LAYER_DECLINE,
                        title = "$layerName Needs Attention",
                        description = "Accuracy dropped to ${accuracy.toInt()}%",
                        layerName = layerName,
                        impactScore = -accuracy,
                        actionable = true,
                        actionText = "Review layer parameters",
                    )
                }
            }
            
            // Check perps trader state
            val perpsState = PerpsTraderAI.getState()
            
            // Milestone checks
            if (perpsState.lifetimeTrades == 10) {
                generateInsight(
                    type = InsightType.MILESTONE,
                    title = "First 10 Perps Trades!",
                    description = "Your perps AI has completed 10 trades",
                    impactScore = 10.0,
                )
            }
            
            if (perpsState.lifetimeTrades == 100) {
                generateInsight(
                    type = InsightType.MILESTONE,
                    title = "100 Perps Trades!",
                    description = "Perps AI now has significant learning data",
                    impactScore = 50.0,
                )
            }
            
            // Win rate insights
            if (perpsState.lifetimeTrades >= 20) {
                val winRate = perpsState.lifetimeWins.toDouble() / perpsState.lifetimeTrades * 100
                
                if (winRate >= 60) {
                    generateInsight(
                        type = InsightType.HOT_SETUP,
                        title = "Strong Win Rate",
                        description = "Perps win rate at ${winRate.toInt()}% over ${perpsState.lifetimeTrades} trades",
                        impactScore = winRate,
                    )
                } else if (winRate < 40) {
                    generateInsight(
                        type = InsightType.RISK_WARNING,
                        title = "Low Win Rate Alert",
                        description = "Win rate at ${winRate.toInt()}% - consider reducing leverage",
                        impactScore = -winRate,
                        actionable = true,
                        actionText = "Reduce max leverage",
                    )
                }
            }
            
            // Check replay learner patterns
            val patterns = PerpsAutoReplayLearner.getPatternsIdentified()
            if (patterns > patternsIdentified.get()) {
                val newPatterns = patterns - patternsIdentified.get()
                patternsIdentified.set(patterns)
                
                generateInsight(
                    type = InsightType.PATTERN_DISCOVERED,
                    title = "$newPatterns New Patterns Found",
                    description = "Auto-replay identified new trading patterns",
                    impactScore = newPatterns.toDouble() * 5,
                )
            }
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "analyzeAndGenerateInsights error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // RETRIEVAL
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get recent insights for display
     */
    fun getRecentInsights(limit: Int = 10): List<DisplayInsight> {
        return synchronized(insightsCache) {
            insightsCache.take(limit)
        }
    }
    
    /**
     * Get insights by type
     */
    fun getInsightsByType(type: InsightType, limit: Int = 10): List<DisplayInsight> {
        return synchronized(insightsCache) {
            insightsCache.filter { it.type == type }.take(limit)
        }
    }
    
    /**
     * Get insights for a specific market
     */
    fun getMarketInsights(market: String, limit: Int = 10): List<DisplayInsight> {
        return synchronized(insightsCache) {
            insightsCache.filter { it.market == market }.take(limit)
        }
    }
    
    /**
     * Get high-priority insights (warnings and hot setups)
     */
    fun getHighPriorityInsights(): List<DisplayInsight> {
        return synchronized(insightsCache) {
            insightsCache.filter { 
                it.type == InsightType.RISK_WARNING || 
                it.type == InsightType.HOT_SETUP ||
                it.type.priority <= 1
            }.take(5)
        }
    }
    
    /**
     * Get actionable insights
     */
    fun getActionableInsights(): List<DisplayInsight> {
        return synchronized(insightsCache) {
            insightsCache.filter { it.actionable }.take(5)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PANEL DATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get complete panel data for UI display
     */
    data class InsightsPanelData(
        val recentInsights: List<DisplayInsight>,
        val highPriority: List<DisplayInsight>,
        val actionable: List<DisplayInsight>,
        val topPerformingLayers: List<Pair<String, Double>>,
        val totalInsights: Int,
        val patternsIdentified: Int,
        val lastAnalysis: Long,
    )
    
    suspend fun getPanelData(): InsightsPanelData {
        // Refresh if stale
        if (System.currentTimeMillis() - lastRefresh.get() > CACHE_TTL_MS) {
            analyzeAndGenerateInsights()
            lastRefresh.set(System.currentTimeMillis())
        }
        
        // Get layer rankings
        val layerStats = PerpsLearningBridge.getLayerPerpsStats()
        val topLayers = layerStats.entries
            .sortedByDescending { it.value.first * it.value.second }
            .take(5)
            .map { it.key to (it.value.second) }
        
        return InsightsPanelData(
            recentInsights = getRecentInsights(10),
            highPriority = getHighPriorityInsights(),
            actionable = getActionableInsights(),
            topPerformingLayers = topLayers,
            totalInsights = insightsGenerated.get(),
            patternsIdentified = patternsIdentified.get(),
            lastAnalysis = lastRefresh.get(),
        )
    }
    
    /**
     * Generate formatted text for display in UI
     */
    fun getFormattedInsightsText(): String {
        val insights = getRecentInsights(5)
        
        if (insights.isEmpty()) {
            return "No insights yet. Keep trading to generate learning insights!"
        }
        
        return buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("🧠 LEARNING INSIGHTS")
            appendLine("═══════════════════════════════════════════")
            appendLine()
            
            insights.forEach { insight ->
                appendLine("${insight.type.emoji} ${insight.title}")
                appendLine("   ${insight.description}")
                appendLine("   ${insight.getTimeAgo()}")
                if (insight.actionable && insight.actionText != null) {
                    appendLine("   💡 Action: ${insight.actionText}")
                }
                appendLine()
            }
            
            appendLine("═══════════════════════════════════════════")
            appendLine("Patterns: ${patternsIdentified.get()} | Total: ${insightsGenerated.get()}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getTotalInsights(): Int = insightsGenerated.get()
    fun getPatternsCount(): Int = patternsIdentified.get()
    fun getOptimizationsCount(): Int = optimizationsApplied.get()
    
    /**
     * Clear all cached insights
     */
    fun clearCache() {
        synchronized(insightsCache) {
            insightsCache.clear()
        }
        lastRefresh.set(0)
    }
}

package com.lifecyclebot.engine

import org.json.JSONObject

/**
 * DashboardDataProvider — Centralized data provider for UI dashboards.
 * 
 * Aggregates data from:
 * - UnifiedModeOrchestrator (trading modes)
 * - SuperBrainEnhancements (market intelligence)
 * - BotBrain (learning metrics)
 * - TreasuryManager (profit tracking)
 * 
 * DEFENSIVE DESIGN:
 * - All methods return safe defaults on error
 * - No initialization required
 * - Thread-safe reads
 */
object DashboardDataProvider {
    
    private const val TAG = "DashboardData"
    
    // ═══════════════════════════════════════════════════════════════════
    // UNIFIED DASHBOARD DATA
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Complete dashboard data for the main intelligence view.
     */
    data class IntelligenceDashboard(
        // Mode Orchestrator
        val currentMode: String = "Standard",
        val activeModes: Int = 0,
        val totalModes: Int = 18,
        val topPerformingMode: String = "N/A",
        val topModeWinRate: Double = 0.0,
        
        // SuperBrain
        val marketSentiment: String = "UNKNOWN",
        val breadthTrend: String = "UNKNOWN",
        val totalInsights: Int = 0,
        val activeSignals: Int = 0,
        
        // Pattern Stats
        val topPatterns: List<PatternDisplay> = emptyList(),
        
        // Brain Learning
        val totalTradesAnalyzed: Int = 0,
        val currentRegime: String = "UNKNOWN",
        val entryThresholdDelta: Double = 0.0,
        
        // Treasury
        val treasuryBalance: Double = 0.0,
        val treasuryPnl: Double = 0.0,
        
        // Timestamps
        val lastUpdateMs: Long = 0,
    )
    
    data class PatternDisplay(
        val name: String,
        val emoji: String,
        val winRate: Double,
        val trades: Int,
    )
    
    /**
     * Get complete intelligence dashboard data.
     */
    fun getIntelligenceDashboard(
        brain: BotBrain? = null,
    ): IntelligenceDashboard {
        return try {
            // Mode Orchestrator data
            val modeData = try {
                UnifiedModeOrchestrator.ensureInitialized()
                val stats = UnifiedModeOrchestrator.getAllStatsSorted()
                val topMode = stats.firstOrNull { it.trades >= 5 }
                
                Triple(
                    UnifiedModeOrchestrator.getModeDisplay(UnifiedModeOrchestrator.getCurrentPrimaryMode()),
                    stats.count { it.isActive },
                    topMode
                )
            } catch (e: Exception) {
                Triple("Standard", 0, null)
            }
            
            // SuperBrain data
            val brainData = try {
                SuperBrainEnhancements.getDashboardData()
            } catch (e: Exception) {
                SuperBrainEnhancements.SuperBrainDashboard()
            }
            
            // Pattern displays
            val patterns = brainData.topPatterns.map { stat ->
                PatternDisplay(
                    name = stat.pattern,
                    emoji = getPatternEmoji(stat.pattern),
                    winRate = stat.winRate,
                    trades = stat.trades,
                )
            }
            
            // Treasury data
            val treasuryData = try {
                Pair(
                    TreasuryManager.treasurySol,
                    TreasuryManager.lifetimeLocked
                )
            } catch (e: Exception) {
                Pair(0.0, 0.0)
            }
            
            IntelligenceDashboard(
                currentMode = modeData.first,
                activeModes = modeData.second,
                totalModes = UnifiedModeOrchestrator.ExtendedMode.values().size,
                topPerformingMode = modeData.third?.let { 
                    UnifiedModeOrchestrator.getModeDisplay(it.mode) 
                } ?: "N/A",
                topModeWinRate = modeData.third?.winRate ?: 0.0,
                
                marketSentiment = brainData.marketSentiment,
                breadthTrend = brainData.breadthTrend,
                totalInsights = brainData.totalInsights,
                activeSignals = brainData.activeSignals,
                
                topPatterns = patterns,
                
                totalTradesAnalyzed = brain?.totalTradesAnalysed ?: 0,
                currentRegime = brain?.currentRegime ?: "UNKNOWN",
                entryThresholdDelta = brain?.entryThresholdDelta ?: 0.0,
                
                treasuryBalance = treasuryData.first,
                treasuryPnl = treasuryData.second,
                
                lastUpdateMs = System.currentTimeMillis(),
            )
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "getIntelligenceDashboard error: ${e.message}")
            IntelligenceDashboard()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // MODE PERFORMANCE CARD
    // ═══════════════════════════════════════════════════════════════════
    
    data class ModePerformanceCard(
        val emoji: String,
        val name: String,
        val trades: Int,
        val winRate: Double,
        val avgPnl: Double,
        val isActive: Boolean,
        val riskLevel: Int,
    )
    
    /**
     * Get mode performance cards for display.
     */
    fun getModePerformanceCards(): List<ModePerformanceCard> {
        return try {
            UnifiedModeOrchestrator.ensureInitialized()
            UnifiedModeOrchestrator.getAllStatsSorted().map { stats ->
                ModePerformanceCard(
                    emoji = stats.mode.emoji,
                    name = stats.mode.label,
                    trades = stats.trades,
                    winRate = stats.winRate,
                    avgPnl = stats.avgPnlPct,
                    isActive = stats.isActive,
                    riskLevel = stats.mode.riskLevel,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // TREASURY DASHBOARD
    // ═══════════════════════════════════════════════════════════════════
    
    data class TreasuryDashboard(
        val currentBalance: Double = 0.0,
        val lifetimePnl: Double = 0.0,
        val totalWithdrawn: Double = 0.0,
        val lastDepositMs: Long = 0,
        val isLocked: Boolean = false,
        val lockReason: String = "",
    )
    
    /**
     * Get treasury dashboard data.
     */
    fun getTreasuryDashboard(): TreasuryDashboard {
        return try {
            TreasuryDashboard(
                currentBalance = TreasuryManager.treasurySol,
                lifetimePnl = TreasuryManager.lifetimeLocked,
                totalWithdrawn = TreasuryManager.lifetimeWithdrawn,
                lastDepositMs = 0,  // Not tracked separately
                isLocked = false,   // Treasury is always accessible
                lockReason = "",
            )
        } catch (e: Exception) {
            TreasuryDashboard()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // QUICK STATS
    // ═══════════════════════════════════════════════════════════════════
    
    data class QuickStats(
        val sentiment: String,
        val sentimentEmoji: String,
        val activeMode: String,
        val modeEmoji: String,
        val treasurySol: Double,
        val insightCount: Int,
    )
    
    /**
     * Get quick stats for status bar display.
     */
    fun getQuickStats(): QuickStats {
        return try {
            val sentiment = SuperBrainEnhancements.getCurrentSentiment()
            val mode = UnifiedModeOrchestrator.getCurrentPrimaryMode()
            
            QuickStats(
                sentiment = sentiment,
                sentimentEmoji = getSentimentEmoji(sentiment),
                activeMode = mode.label,
                modeEmoji = mode.emoji,
                treasurySol = TreasuryManager.treasurySol,
                insightCount = SuperBrainEnhancements.getDashboardData().totalInsights,
            )
        } catch (e: Exception) {
            QuickStats(
                sentiment = "UNKNOWN",
                sentimentEmoji = "❓",
                activeMode = "Standard",
                modeEmoji = "📈",
                treasurySol = 0.0,
                insightCount = 0,
            )
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SERIALIZATION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Export all dashboard data to JSON.
     */
    fun toJson(brain: BotBrain? = null): JSONObject {
        return try {
            val dashboard = getIntelligenceDashboard(brain)
            
            JSONObject().apply {
                put("currentMode", dashboard.currentMode)
                put("activeModes", dashboard.activeModes)
                put("marketSentiment", dashboard.marketSentiment)
                put("breadthTrend", dashboard.breadthTrend)
                put("totalInsights", dashboard.totalInsights)
                put("treasuryBalance", dashboard.treasuryBalance)
                put("lastUpdate", dashboard.lastUpdateMs)
                
                // Include mode orchestrator state
                put("modeOrchestrator", UnifiedModeOrchestrator.toJson())
                
                // Include superbrain state
                put("superBrain", SuperBrainEnhancements.toJson())
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════
    
    private fun getPatternEmoji(pattern: String): String {
        return when (pattern.uppercase()) {
            "HAMMER" -> "🔨"
            "DOJI" -> "✝️"
            "ENGULFING" -> "🌊"
            "DOUBLE_BOTTOM" -> "W"
            "DOUBLE_TOP" -> "M"
            "BREAKOUT" -> "🚀"
            "BREAKDOWN" -> "📉"
            "MORNING_STAR" -> "⭐"
            "EVENING_STAR" -> "🌙"
            "BULLISH_FLAG" -> "🏁"
            "BEARISH_FLAG" -> "🚩"
            "CUP_HANDLE" -> "☕"
            "HEAD_SHOULDERS" -> "👤"
            "TRIANGLE" -> "△"
            "WEDGE" -> "◢"
            else -> "📊"
        }
    }
    
    private fun getSentimentEmoji(sentiment: String): String {
        return when (sentiment.uppercase()) {
            "STRONG_BULL" -> "🟢🟢"
            "BULL" -> "🟢"
            "NEUTRAL" -> "🟡"
            "BEAR" -> "🔴"
            "STRONG_BEAR" -> "🔴🔴"
            else -> "❓"
        }
    }
}

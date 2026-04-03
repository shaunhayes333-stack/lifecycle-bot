package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * DashboardDataProvider — Centralized data provider for UI dashboards.
 *
 * Aggregates safe read-only dashboard data from:
 * - UnifiedModeOrchestrator
 * - SuperBrainEnhancements
 * - BotBrain
 * - TreasuryManager
 *
 * Defensive goals:
 * - Never throw to caller
 * - Safe defaults everywhere
 * - Isolate failures by subsystem
 */
object DashboardDataProvider {

    private const val TAG = "DashboardData"

    // ═══════════════════════════════════════════════════════════════════
    // UNIFIED DASHBOARD DATA
    // ═══════════════════════════════════════════════════════════════════

    data class IntelligenceDashboard(
        val currentMode: String = "Standard",
        val activeModes: Int = 0,
        val totalModes: Int = 0,
        val topPerformingMode: String = "N/A",
        val topModeWinRate: Double = 0.0,

        val marketSentiment: String = "UNKNOWN",
        val breadthTrend: String = "UNKNOWN",
        val totalInsights: Int = 0,
        val activeSignals: Int = 0,

        val topPatterns: List<PatternDisplay> = emptyList(),

        val totalTradesAnalyzed: Int = 0,
        val currentRegime: String = "UNKNOWN",
        val entryThresholdDelta: Double = 0.0,

        val treasuryBalance: Double = 0.0,
        val treasuryPnl: Double = 0.0,

        val lastUpdateMs: Long = 0L,
    )

    data class PatternDisplay(
        val name: String,
        val emoji: String,
        val winRate: Double,
        val trades: Int,
    )

    fun getIntelligenceDashboard(brain: BotBrain? = null): IntelligenceDashboard {
        return try {
            val modeSummary = getSafeModeSummary()
            val brainData = getSafeSuperBrainDashboard()
            val treasuryData = getSafeTreasuryPair()

            val patterns = brainData.topPatterns.map { stat ->
                PatternDisplay(
                    name = stat.pattern,
                    emoji = getPatternEmoji(stat.pattern),
                    winRate = stat.winRate,
                    trades = stat.trades,
                )
            }

            IntelligenceDashboard(
                currentMode = modeSummary.currentMode,
                activeModes = modeSummary.activeModes,
                totalModes = modeSummary.totalModes,
                topPerformingMode = modeSummary.topModeName,
                topModeWinRate = modeSummary.topModeWinRate,

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
            ErrorLogger.debug(TAG, "getModePerformanceCards error: ${e.message}")
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
        val lastDepositMs: Long = 0L,
        val isLocked: Boolean = false,
        val lockReason: String = "",
    )

    fun getTreasuryDashboard(): TreasuryDashboard {
        return try {
            TreasuryDashboard(
                currentBalance = TreasuryManager.treasurySol,
                lifetimePnl = TreasuryManager.lifetimeLocked,
                totalWithdrawn = TreasuryManager.lifetimeWithdrawn,
                lastDepositMs = 0L,
                isLocked = false,
                lockReason = "",
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "getTreasuryDashboard error: ${e.message}")
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

    fun getQuickStats(): QuickStats {
        return try {
            val sentiment = try {
                SuperBrainEnhancements.getCurrentSentiment()
            } catch (_: Exception) {
                "UNKNOWN"
            }

            val mode = try {
                UnifiedModeOrchestrator.ensureInitialized()
                UnifiedModeOrchestrator.getCurrentPrimaryMode()
            } catch (_: Exception) {
                null
            }

            val insights = try {
                SuperBrainEnhancements.getDashboardData().totalInsights
            } catch (_: Exception) {
                0
            }

            val treasury = try {
                TreasuryManager.treasurySol
            } catch (_: Exception) {
                0.0
            }

            QuickStats(
                sentiment = sentiment,
                sentimentEmoji = getSentimentEmoji(sentiment),
                activeMode = mode?.label ?: "Standard",
                modeEmoji = mode?.emoji ?: "📈",
                treasurySol = treasury,
                insightCount = insights,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "getQuickStats error: ${e.message}")
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

    fun toJson(brain: BotBrain? = null): JSONObject {
        val dashboard = getIntelligenceDashboard(brain)

        val root = JSONObject()
        root.put("currentMode", dashboard.currentMode)
        root.put("activeModes", dashboard.activeModes)
        root.put("totalModes", dashboard.totalModes)
        root.put("topPerformingMode", dashboard.topPerformingMode)
        root.put("topModeWinRate", dashboard.topModeWinRate)

        root.put("marketSentiment", dashboard.marketSentiment)
        root.put("breadthTrend", dashboard.breadthTrend)
        root.put("totalInsights", dashboard.totalInsights)
        root.put("activeSignals", dashboard.activeSignals)

        root.put("totalTradesAnalyzed", dashboard.totalTradesAnalyzed)
        root.put("currentRegime", dashboard.currentRegime)
        root.put("entryThresholdDelta", dashboard.entryThresholdDelta)

        root.put("treasuryBalance", dashboard.treasuryBalance)
        root.put("treasuryPnl", dashboard.treasuryPnl)
        root.put("lastUpdate", dashboard.lastUpdateMs)

        val patternArray = JSONArray()
        dashboard.topPatterns.forEach { p ->
            patternArray.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("emoji", p.emoji)
                    put("winRate", p.winRate)
                    put("trades", p.trades)
                }
            )
        }
        root.put("topPatterns", patternArray)

        try {
            root.put("modeOrchestrator", UnifiedModeOrchestrator.toJson())
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "modeOrchestrator toJson error: ${e.message}")
            root.put("modeOrchestrator", JSONObject())
        }

        try {
            root.put("superBrain", SuperBrainEnhancements.toJson())
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "superBrain toJson error: ${e.message}")
            root.put("superBrain", JSONObject())
        }

        return root
    }

    // ═══════════════════════════════════════════════════════════════════
    // INTERNAL SAFE HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private data class SafeModeSummary(
        val currentMode: String = "Standard",
        val activeModes: Int = 0,
        val totalModes: Int = 0,
        val topModeName: String = "N/A",
        val topModeWinRate: Double = 0.0,
    )

    private fun getSafeModeSummary(): SafeModeSummary {
        return try {
            UnifiedModeOrchestrator.ensureInitialized()
            val stats = UnifiedModeOrchestrator.getAllStatsSorted()
            val topMode = stats.firstOrNull { it.trades >= 5 }

            val totalModes = try {
                UnifiedModeOrchestrator.ExtendedMode.values().size
            } catch (_: Exception) {
                stats.size
            }

            SafeModeSummary(
                currentMode = UnifiedModeOrchestrator.getModeDisplay(
                    UnifiedModeOrchestrator.getCurrentPrimaryMode()
                ),
                activeModes = stats.count { it.isActive },
                totalModes = totalModes,
                topModeName = topMode?.let { UnifiedModeOrchestrator.getModeDisplay(it.mode) } ?: "N/A",
                topModeWinRate = topMode?.winRate ?: 0.0,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "getSafeModeSummary error: ${e.message}")
            SafeModeSummary()
        }
    }

    private fun getSafeSuperBrainDashboard(): SuperBrainEnhancements.SuperBrainDashboard {
        return try {
            SuperBrainEnhancements.getDashboardData()
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "getSafeSuperBrainDashboard error: ${e.message}")
            SuperBrainEnhancements.SuperBrainDashboard()
        }
    }

    private fun getSafeTreasuryPair(): Pair<Double, Double> {
        return try {
            Pair(
                TreasuryManager.treasurySol,
                TreasuryManager.lifetimeLocked,
            )
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "getSafeTreasuryPair error: ${e.message}")
            Pair(0.0, 0.0)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun getPatternEmoji(pattern: String): String {
        val p = pattern.uppercase()

        return when {
            "HAMMER" in p -> "🔨"
            "DOJI" in p -> "✝️"
            "ENGULF" in p -> "🌊"
            "DOUBLE_BOTTOM" in p -> "W"
            "DOUBLE_TOP" in p -> "M"
            "BREAKOUT" in p -> "🚀"
            "BREAKDOWN" in p -> "📉"
            "MORNING_STAR" in p -> "⭐"
            "EVENING_STAR" in p -> "🌙"
            "BULLISH_FLAG" in p || "BULL_FLAG" in p -> "🏁"
            "BEARISH_FLAG" in p || "BEAR_FLAG" in p -> "🚩"
            "CUP_HANDLE" in p || "CUP" in p -> "☕"
            "HEAD_SHOULDERS" in p -> "👤"
            "TRIANGLE" in p -> "△"
            "WEDGE" in p -> "◢"
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
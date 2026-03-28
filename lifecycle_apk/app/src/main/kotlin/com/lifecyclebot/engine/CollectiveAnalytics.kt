package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * CollectiveAnalytics — Dashboard data for Collective Learning
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Tracks and provides analytics for the Collective Hive Mind:
 * - Total patterns learned from collective
 * - Your contribution count
 * - Global blacklist size
 * - Best/worst patterns from collective
 * - Whale wallet success rates
 * 
 * Used by the UI to display the Collective Intelligence dashboard.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CollectiveAnalytics {
    
    private const val TAG = "CollectiveAnalytics"
    private const val PREFS_NAME = "collective_analytics"
    
    private var prefs: SharedPreferences? = null
    
    // Local contribution tracking
    private var patternsUploaded = 0
    private var blacklistReported = 0
    private var modeStatsSynced = 0
    private var whaleTradesReported = 0
    
    // Cached collective stats
    private var cachedCollectivePatterns = 0
    private var cachedGlobalBlacklist = 0
    private var cachedActiveInstances = 0
    private var lastSyncTime = 0L
    
    // Best/worst patterns from collective
    private val bestPatterns = mutableListOf<PatternStat>()
    private val worstPatterns = mutableListOf<PatternStat>()
    
    data class PatternStat(
        val patternType: String,
        val winRate: Double,
        val totalTrades: Int,
        val avgPnl: Double,
    )
    
    data class AnalyticsSummary(
        val yourContributions: Int,
        val patternsUploaded: Int,
        val blacklistReported: Int,
        val modeStatsSynced: Int,
        val whaleTradesReported: Int,
        val collectivePatterns: Int,
        val globalBlacklist: Int,
        val estimatedInstances: Int,
        val bestPatterns: List<PatternStat>,
        val worstPatterns: List<PatternStat>,
        val lastSyncTime: Long,
    )
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }
    
    private fun loadFromPrefs() {
        prefs?.let {
            patternsUploaded = it.getInt("patterns_uploaded", 0)
            blacklistReported = it.getInt("blacklist_reported", 0)
            modeStatsSynced = it.getInt("mode_stats_synced", 0)
            whaleTradesReported = it.getInt("whale_trades_reported", 0)
            cachedCollectivePatterns = it.getInt("collective_patterns", 0)
            cachedGlobalBlacklist = it.getInt("global_blacklist", 0)
            lastSyncTime = it.getLong("last_sync_time", 0L)
        }
    }
    
    private fun saveToPrefs() {
        prefs?.edit()?.apply {
            putInt("patterns_uploaded", patternsUploaded)
            putInt("blacklist_reported", blacklistReported)
            putInt("mode_stats_synced", modeStatsSynced)
            putInt("whale_trades_reported", whaleTradesReported)
            putInt("collective_patterns", cachedCollectivePatterns)
            putInt("global_blacklist", cachedGlobalBlacklist)
            putLong("last_sync_time", lastSyncTime)
            apply()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONTRIBUTION TRACKING - Called when we upload to collective
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun recordPatternUpload() {
        patternsUploaded++
        saveToPrefs()
    }
    
    fun recordBlacklistReport() {
        blacklistReported++
        saveToPrefs()
    }
    
    fun recordModeSyncUpload(count: Int) {
        modeStatsSynced += count
        saveToPrefs()
    }
    
    fun recordWhaleTradeReport() {
        whaleTradesReported++
        saveToPrefs()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COLLECTIVE STATS - Updated from CollectiveLearning downloads
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun updateCollectiveStats(
        totalPatterns: Int,
        blacklistSize: Int,
        estimatedInstances: Int = 0
    ) {
        cachedCollectivePatterns = totalPatterns
        cachedGlobalBlacklist = blacklistSize
        cachedActiveInstances = estimatedInstances
        lastSyncTime = System.currentTimeMillis()
        saveToPrefs()
    }
    
    fun updateBestPatterns(patterns: List<PatternStat>) {
        bestPatterns.clear()
        bestPatterns.addAll(patterns.take(5))
    }
    
    fun updateWorstPatterns(patterns: List<PatternStat>) {
        worstPatterns.clear()
        worstPatterns.addAll(patterns.take(5))
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS - For UI display
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getSummary(): AnalyticsSummary {
        return AnalyticsSummary(
            yourContributions = patternsUploaded + blacklistReported + whaleTradesReported,
            patternsUploaded = patternsUploaded,
            blacklistReported = blacklistReported,
            modeStatsSynced = modeStatsSynced,
            whaleTradesReported = whaleTradesReported,
            collectivePatterns = cachedCollectivePatterns,
            globalBlacklist = cachedGlobalBlacklist,
            estimatedInstances = cachedActiveInstances,
            bestPatterns = bestPatterns.toList(),
            worstPatterns = worstPatterns.toList(),
            lastSyncTime = lastSyncTime,
        )
    }
    
    fun getFormattedSummary(): String {
        val summary = getSummary()
        return buildString {
            appendLine("═══ COLLECTIVE HIVE MIND ═══")
            appendLine()
            appendLine("YOUR CONTRIBUTIONS:")
            appendLine("  Patterns uploaded: ${summary.patternsUploaded}")
            appendLine("  Blacklist reports: ${summary.blacklistReported}")
            appendLine("  Whale trades: ${summary.whaleTradesReported}")
            appendLine("  Mode syncs: ${summary.modeStatsSynced}")
            appendLine()
            appendLine("COLLECTIVE INTELLIGENCE:")
            appendLine("  Total patterns: ${summary.collectivePatterns}")
            appendLine("  Global blacklist: ${summary.globalBlacklist} tokens")
            if (summary.estimatedInstances > 0) {
                appendLine("  Active instances: ~${summary.estimatedInstances}")
            }
            appendLine()
            if (summary.bestPatterns.isNotEmpty()) {
                appendLine("TOP WINNING PATTERNS:")
                summary.bestPatterns.forEach { p ->
                    appendLine("  ${p.patternType}: ${p.winRate.toInt()}% WR (${p.totalTrades} trades)")
                }
            }
            if (summary.worstPatterns.isNotEmpty()) {
                appendLine()
                appendLine("AVOID THESE PATTERNS:")
                summary.worstPatterns.forEach { p ->
                    appendLine("  ${p.patternType}: ${p.winRate.toInt()}% WR (${p.totalTrades} trades)")
                }
            }
        }
    }
}

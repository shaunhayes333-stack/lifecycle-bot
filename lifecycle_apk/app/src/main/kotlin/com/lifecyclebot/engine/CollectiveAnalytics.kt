package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * CollectiveAnalytics — Dashboard data for Collective Learning
 *
 * Thread-safe local analytics store for the Collective Intelligence dashboard.
 */
object CollectiveAnalytics {

    private const val TAG = "CollectiveAnalytics"
    private const val PREFS_NAME = "collective_analytics"

    private const val KEY_PATTERNS_UPLOADED = "patterns_uploaded"
    private const val KEY_BLACKLIST_REPORTED = "blacklist_reported"
    private const val KEY_MODE_STATS_SYNCED = "mode_stats_synced"
    private const val KEY_WHALE_TRADES_REPORTED = "whale_trades_reported"
    private const val KEY_COLLECTIVE_PATTERNS = "collective_patterns"
    private const val KEY_GLOBAL_BLACKLIST = "global_blacklist"
    private const val KEY_ACTIVE_INSTANCES = "active_instances"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_BEST_PATTERNS_JSON = "best_patterns_json"
    private const val KEY_WORST_PATTERNS_JSON = "worst_patterns_json"

    private val lock = Any()

    @Volatile
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
        val avgPnl: Double
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
        val lastSyncTime: Long
    )

    fun init(context: Context) {
        synchronized(lock) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefsLocked()
        }
    }

    private fun loadFromPrefsLocked() {
        val p = prefs ?: return

        patternsUploaded = p.getInt(KEY_PATTERNS_UPLOADED, 0)
        blacklistReported = p.getInt(KEY_BLACKLIST_REPORTED, 0)
        modeStatsSynced = p.getInt(KEY_MODE_STATS_SYNCED, 0)
        whaleTradesReported = p.getInt(KEY_WHALE_TRADES_REPORTED, 0)

        cachedCollectivePatterns = p.getInt(KEY_COLLECTIVE_PATTERNS, 0)
        cachedGlobalBlacklist = p.getInt(KEY_GLOBAL_BLACKLIST, 0)
        cachedActiveInstances = p.getInt(KEY_ACTIVE_INSTANCES, 0)
        lastSyncTime = p.getLong(KEY_LAST_SYNC_TIME, 0L)

        bestPatterns.clear()
        bestPatterns.addAll(parsePatternList(p.getString(KEY_BEST_PATTERNS_JSON, null)))

        worstPatterns.clear()
        worstPatterns.addAll(parsePatternList(p.getString(KEY_WORST_PATTERNS_JSON, null)))
    }

    private fun saveToPrefsLocked() {
        val p = prefs ?: return

        val bestJson = patternListToJson(bestPatterns)
        val worstJson = patternListToJson(worstPatterns)

        p.edit()
            .putInt(KEY_PATTERNS_UPLOADED, patternsUploaded)
            .putInt(KEY_BLACKLIST_REPORTED, blacklistReported)
            .putInt(KEY_MODE_STATS_SYNCED, modeStatsSynced)
            .putInt(KEY_WHALE_TRADES_REPORTED, whaleTradesReported)
            .putInt(KEY_COLLECTIVE_PATTERNS, cachedCollectivePatterns)
            .putInt(KEY_GLOBAL_BLACKLIST, cachedGlobalBlacklist)
            .putInt(KEY_ACTIVE_INSTANCES, cachedActiveInstances)
            .putLong(KEY_LAST_SYNC_TIME, lastSyncTime)
            .putString(KEY_BEST_PATTERNS_JSON, bestJson)
            .putString(KEY_WORST_PATTERNS_JSON, worstJson)
            .apply()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONTRIBUTION TRACKING
    // ═══════════════════════════════════════════════════════════════════════

    fun recordPatternUpload() {
        synchronized(lock) {
            patternsUploaded += 1
            saveToPrefsLocked()
        }
    }

    fun recordBlacklistReport() {
        synchronized(lock) {
            blacklistReported += 1
            saveToPrefsLocked()
        }
    }

    fun recordModeSyncUpload(count: Int) {
        if (count <= 0) return

        synchronized(lock) {
            modeStatsSynced += count
            saveToPrefsLocked()
        }
    }

    fun recordWhaleTradeReport() {
        synchronized(lock) {
            whaleTradesReported += 1
            saveToPrefsLocked()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COLLECTIVE STATS
    // ═══════════════════════════════════════════════════════════════════════

    fun updateCollectiveStats(
        totalPatterns: Int,
        blacklistSize: Int,
        estimatedInstances: Int = 0
    ) {
        synchronized(lock) {
            cachedCollectivePatterns = totalPatterns.coerceAtLeast(0)
            cachedGlobalBlacklist = blacklistSize.coerceAtLeast(0)
            cachedActiveInstances = estimatedInstances.coerceAtLeast(0)
            lastSyncTime = System.currentTimeMillis()
            saveToPrefsLocked()
        }
    }

    fun updateBestPatterns(patterns: List<PatternStat>) {
        synchronized(lock) {
            bestPatterns.clear()
            bestPatterns.addAll(
                patterns
                    .sortedWith(
                        compareByDescending<PatternStat> { sanitizeDouble(it.winRate) }
                            .thenByDescending { it.totalTrades }
                            .thenByDescending { sanitizeDouble(it.avgPnl) }
                    )
                    .take(5)
            )
            saveToPrefsLocked()
        }
    }

    fun updateWorstPatterns(patterns: List<PatternStat>) {
        synchronized(lock) {
            worstPatterns.clear()
            worstPatterns.addAll(
                patterns
                    .sortedWith(
                        compareBy<PatternStat> { sanitizeDouble(it.winRate) }
                            .thenByDescending { it.totalTrades }
                            .thenBy { sanitizeDouble(it.avgPnl) }
                    )
                    .take(5)
            )
            saveToPrefsLocked()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════

    fun getSummary(): AnalyticsSummary {
        synchronized(lock) {
            return AnalyticsSummary(
                yourContributions = patternsUploaded + blacklistReported + whaleTradesReported + modeStatsSynced,
                patternsUploaded = patternsUploaded,
                blacklistReported = blacklistReported,
                modeStatsSynced = modeStatsSynced,
                whaleTradesReported = whaleTradesReported,
                collectivePatterns = cachedCollectivePatterns,
                globalBlacklist = cachedGlobalBlacklist,
                estimatedInstances = cachedActiveInstances,
                bestPatterns = ArrayList(bestPatterns),
                worstPatterns = ArrayList(worstPatterns),
                lastSyncTime = lastSyncTime
            )
        }
    }

    fun getFormattedSummary(): String {
        val summary = getSummary()
        val sb = StringBuilder()

        sb.append("═══ COLLECTIVE HIVE MIND ═══\n\n")
        sb.append("YOUR CONTRIBUTIONS:\n")
        sb.append("  Patterns uploaded: ").append(summary.patternsUploaded).append('\n')
        sb.append("  Blacklist reports: ").append(summary.blacklistReported).append('\n')
        sb.append("  Whale trades: ").append(summary.whaleTradesReported).append('\n')
        sb.append("  Mode syncs: ").append(summary.modeStatsSynced).append('\n')
        sb.append("  Total contributions: ").append(summary.yourContributions).append("\n\n")

        sb.append("COLLECTIVE INTELLIGENCE:\n")
        sb.append("  Total patterns: ").append(summary.collectivePatterns).append('\n')
        sb.append("  Global blacklist: ").append(summary.globalBlacklist).append(" tokens\n")

        if (summary.estimatedInstances > 0) {
            sb.append("  Active instances: ~").append(summary.estimatedInstances).append('\n')
        }

        if (summary.lastSyncTime > 0L) {
            sb.append("  Last sync: ").append(summary.lastSyncTime).append('\n')
        }

        if (summary.bestPatterns.isNotEmpty()) {
            sb.append("\nTOP WINNING PATTERNS:\n")
            for (p in summary.bestPatterns) {
                sb.append("  ")
                    .append(p.patternType)
                    .append(": ")
                    .append(p.winRate.toInt())
                    .append("% WR | ")
                    .append(p.totalTrades)
                    .append(" trades | avg ")
                    .append(formatDouble(p.avgPnl))
                    .append("%\n")
            }
        }

        if (summary.worstPatterns.isNotEmpty()) {
            sb.append("\nAVOID THESE PATTERNS:\n")
            for (p in summary.worstPatterns) {
                sb.append("  ")
                    .append(p.patternType)
                    .append(": ")
                    .append(p.winRate.toInt())
                    .append("% WR | ")
                    .append(p.totalTrades)
                    .append(" trades | avg ")
                    .append(formatDouble(p.avgPnl))
                    .append("%\n")
            }
        }

        return sb.toString()
    }

    fun clearForTesting() {
        synchronized(lock) {
            patternsUploaded = 0
            blacklistReported = 0
            modeStatsSynced = 0
            whaleTradesReported = 0

            cachedCollectivePatterns = 0
            cachedGlobalBlacklist = 0
            cachedActiveInstances = 0
            lastSyncTime = 0L

            bestPatterns.clear()
            worstPatterns.clear()

            saveToPrefsLocked()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // JSON HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun patternListToJson(patterns: List<PatternStat>): String {
        val arr = JSONArray()

        for (p in patterns) {
            arr.put(
                JSONObject().apply {
                    put("patternType", p.patternType)
                    put("winRate", sanitizeDouble(p.winRate))
                    put("totalTrades", p.totalTrades.coerceAtLeast(0))
                    put("avgPnl", sanitizeDouble(p.avgPnl))
                }
            )
        }

        return arr.toString()
    }

    private fun parsePatternList(json: String?): List<PatternStat> {
        if (json.isNullOrBlank()) return emptyList()

        return try {
            val arr = JSONArray(json)
            val result = ArrayList<PatternStat>(arr.length())

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue

                result.add(
                    PatternStat(
                        patternType = obj.optString("patternType", "UNKNOWN"),
                        winRate = sanitizeDouble(obj.optDouble("winRate", 0.0)),
                        totalTrades = obj.optInt("totalTrades", 0).coerceAtLeast(0),
                        avgPnl = sanitizeDouble(obj.optDouble("avgPnl", 0.0))
                    )
                )
            }

            result
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "Failed to parse pattern list: ${e.message}")
            emptyList()
        }
    }

    private fun sanitizeDouble(value: Double, default: Double = 0.0): Double {
        return if (value.isNaN() || value.isInfinite()) default else value
    }

    private fun formatDouble(value: Double): String {
        return String.format(Locale.US, "%.2f", sanitizeDouble(value))
    }
}
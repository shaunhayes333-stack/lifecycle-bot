package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/**
 * CloudLearningSync — Shared Community Learning Database
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Connects all bot instances to a shared Supabase database for collective learning.
 * Many users = smarter bot. Each instance contributes learned patterns and draws
 * from the community's collective knowledge.
 * 
 * PRIVACY: Only aggregated weights and patterns are shared. Never individual trades,
 * wallet addresses, or P&L amounts.
 * 
 * HOW IT WORKS:
 * 1. Each instance generates a unique anonymous ID (not tied to wallet)
 * 2. Every 4 hours, uploads learned feature weights and pattern stats
 * 3. On startup, downloads community-aggregated weights
 * 4. Blends community knowledge with local learning
 * 
 * FREE TIER: Supabase provides 500MB free, plenty for millions of weight records
 */
object CloudLearningSync {

    private const val PREFS_NAME = "cloud_learning_sync"
    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_LAST_UPLOAD = "last_upload_ts"
    private const val KEY_LAST_DOWNLOAD = "last_download_ts"
    private const val KEY_OPT_IN = "opt_in_sharing"
    private const val KEY_USE_COMMUNITY = "use_community_weights"
    private const val KEY_COMMUNITY_WEIGHTS = "cached_community_weights"

    // ═══════════════════════════════════════════════════════════════════
    // SUPABASE CONFIGURATION - COMMUNITY SHARED DATABASE
    // ═══════════════════════════════════════════════════════════════════
    // All bot instances share this database for collective learning
    // More users = smarter bots for everyone!
    
    private const val SUPABASE_URL = "https://rnhjixrfdjxggvejgjhz.supabase.co"
    private const val SUPABASE_ANON_KEY = "sb_publishable_LNM7cOm7XjEHmQYVY_QZzw_Y_sFqrkd"
    
    // Minimum trades required to contribute (prevents noisy data)
    private const val MIN_TRADES_TO_CONTRIBUTE = 20
    
    // Sync intervals
    private const val UPLOAD_INTERVAL_MS = 4 * 60 * 60 * 1000L  // 4 hours
    private const val DOWNLOAD_INTERVAL_MS = 60 * 60 * 1000L    // 1 hour

    private var prefs: SharedPreferences? = null
    private var instanceId: String = ""
    private var isOptedIn: Boolean = true
    private var useCommunityWeights: Boolean = true
    private var lastUploadTs: Long = 0
    private var lastDownloadTs: Long = 0

    // Cached community weights
    private var communityWeights: CommunityWeights? = null

    // ═══════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * What each instance contributes to the community.
     * Privacy-safe: No wallet addresses, no individual trades, no P&L.
     */
    data class ContributionPayload(
        val instanceId: String,
        val tradeCount: Int,
        val winRate: Double,
        val featureWeights: Map<String, Double>,
        val patternStats: List<PatternStat>,
        val appVersion: String,
        val timestamp: Long,
    )

    data class PatternStat(
        val name: String,
        val winRate: Double,
        val profitFactor: Double,
        val sampleCount: Int,
    )

    /**
     * Aggregated community weights downloaded from cloud.
     */
    data class CommunityWeights(
        val featureWeights: Map<String, Double>,
        val patternMultipliers: Map<String, Double>,
        val totalContributors: Int,
        val totalTrades: Int,
        val lastUpdated: Long,
    )

    // ═══════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()
        
        // Generate instance ID if not exists (anonymous, not tied to wallet)
        if (instanceId.isBlank()) {
            instanceId = generateAnonymousId()
            saveState()
        }
        
        ErrorLogger.info("CloudSync", "Initialized: id=${instanceId.take(8)}... " +
            "optIn=$isOptedIn useCommunity=$useCommunityWeights")
    }

    private fun loadState() {
        val p = prefs ?: return
        instanceId = p.getString(KEY_INSTANCE_ID, "") ?: ""
        lastUploadTs = p.getLong(KEY_LAST_UPLOAD, 0)
        lastDownloadTs = p.getLong(KEY_LAST_DOWNLOAD, 0)
        isOptedIn = p.getBoolean(KEY_OPT_IN, true)
        useCommunityWeights = p.getBoolean(KEY_USE_COMMUNITY, true)
        
        // Load cached community weights
        try {
            val json = p.getString(KEY_COMMUNITY_WEIGHTS, null)
            if (json != null) {
                communityWeights = parseCommunityWeights(JSONObject(json))
            }
        } catch (e: Exception) {
            ErrorLogger.debug("CloudSync", "No cached community weights")
        }
    }

    private fun saveState() {
        val p = prefs ?: return
        p.edit().apply {
            putString(KEY_INSTANCE_ID, instanceId)
            putLong(KEY_LAST_UPLOAD, lastUploadTs)
            putLong(KEY_LAST_DOWNLOAD, lastDownloadTs)
            putBoolean(KEY_OPT_IN, isOptedIn)
            putBoolean(KEY_USE_COMMUNITY, useCommunityWeights)
            
            // Cache community weights
            communityWeights?.let { cw ->
                val json = JSONObject().apply {
                    put("featureWeights", JSONObject(cw.featureWeights))
                    put("patternMultipliers", JSONObject(cw.patternMultipliers))
                    put("totalContributors", cw.totalContributors)
                    put("totalTrades", cw.totalTrades)
                    put("lastUpdated", cw.lastUpdated)
                }
                putString(KEY_COMMUNITY_WEIGHTS, json.toString())
            }
            
            apply()
        }
    }

    /**
     * Generate anonymous instance ID (not tied to wallet or device).
     */
    private fun generateAnonymousId(): String {
        val uuid = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis().toString()
        val combined = "$uuid-$timestamp"
        
        // Hash it for extra anonymity
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    // ═══════════════════════════════════════════════════════════════════
    // UPLOAD (Contribute to Community)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if cloud sync is configured (has valid Supabase credentials)
     */
    private fun isConfigured(): Boolean {
        return SUPABASE_URL.isNotBlank() && SUPABASE_ANON_KEY.isNotBlank()
    }

    /**
     * Upload learned weights to community database.
     * Called periodically by BotService.
     */
    suspend fun uploadLearnings(
        tradeCount: Int,
        winRate: Double,
        featureWeights: Map<String, Double>,
        patternStats: List<PatternBacktester.PatternStats>,
    ): Boolean {
        if (!isConfigured()) {
            ErrorLogger.debug("CloudSync", "Upload skipped: not configured (see docs/CLOUD_SETUP.md)")
            return false
        }
        
        if (!isOptedIn) {
            ErrorLogger.debug("CloudSync", "Upload skipped: opted out")
            return false
        }
        
        if (tradeCount < MIN_TRADES_TO_CONTRIBUTE) {
            ErrorLogger.debug("CloudSync", "Upload skipped: need $MIN_TRADES_TO_CONTRIBUTE trades (have $tradeCount)")
            return false
        }
        
        val now = System.currentTimeMillis()
        if (now - lastUploadTs < UPLOAD_INTERVAL_MS) {
            ErrorLogger.debug("CloudSync", "Upload skipped: too soon")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                // Convert external PatternStats to our local format
                val localPatternStats = patternStats.map { 
                    PatternStat(it.patternName, it.winRate, it.profitFactor, it.totalTrades) 
                }
                
                val payload = ContributionPayload(
                    instanceId = instanceId,
                    tradeCount = tradeCount,
                    winRate = winRate,
                    featureWeights = featureWeights,
                    patternStats = localPatternStats,
                    appVersion = "1.0.0",
                    timestamp = now,
                )
                
                val success = postToSupabase("contributions", payload.toJson())
                
                if (success) {
                    lastUploadTs = now
                    saveState()
                    ErrorLogger.info("CloudSync", "☁️ Uploaded learnings: $tradeCount trades, ${winRate.toInt()}% win rate")
                }
                
                success
            } catch (e: Exception) {
                ErrorLogger.error("CloudSync", "Upload failed: ${e.message}")
                false
            }
        }
    }

    private fun ContributionPayload.toJson(): JSONObject {
        return JSONObject().apply {
            put("instance_id", instanceId)
            put("trade_count", tradeCount)
            put("win_rate", winRate)
            put("feature_weights", JSONObject(featureWeights))
            put("pattern_stats", JSONArray(patternStats.map { 
                JSONObject().apply {
                    put("name", it.name)
                    put("win_rate", it.winRate)
                    put("profit_factor", it.profitFactor)
                    put("sample_count", it.sampleCount)
                }
            }))
            put("app_version", appVersion)
            put("created_at", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(java.util.Date(timestamp)))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DOWNLOAD (Get Community Knowledge)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Download aggregated community weights.
     * Called on app start and periodically.
     */
    suspend fun downloadCommunityWeights(): CommunityWeights? {
        if (!isConfigured()) {
            ErrorLogger.debug("CloudSync", "Download skipped: not configured")
            return communityWeights  // Return cached
        }
        
        if (!useCommunityWeights) {
            ErrorLogger.debug("CloudSync", "Download skipped: community weights disabled")
            return communityWeights  // Return cached
        }
        
        val now = System.currentTimeMillis()
        if (now - lastDownloadTs < DOWNLOAD_INTERVAL_MS && communityWeights != null) {
            ErrorLogger.debug("CloudSync", "Using cached community weights")
            return communityWeights
        }

        return withContext(Dispatchers.IO) {
            try {
                val response = getFromSupabase("aggregated_weights?select=*&order=created_at.desc&limit=1")
                
                if (response != null) {
                    val arr = JSONArray(response)
                    if (arr.length() > 0) {
                        val obj = arr.getJSONObject(0)
                        communityWeights = parseCommunityWeights(obj)
                        lastDownloadTs = now
                        saveState()
                        
                        ErrorLogger.info("CloudSync", "☁️ Downloaded community weights: " +
                            "${communityWeights?.totalContributors} contributors, " +
                            "${communityWeights?.totalTrades} total trades")
                    }
                }
                
                communityWeights
            } catch (e: Exception) {
                ErrorLogger.error("CloudSync", "Download failed: ${e.message}")
                communityWeights  // Return cached on failure
            }
        }
    }

    private fun parseCommunityWeights(obj: JSONObject): CommunityWeights {
        val weightsObj = obj.optJSONObject("feature_weights") ?: JSONObject()
        val weights = mutableMapOf<String, Double>()
        weightsObj.keys().forEach { weights[it] = weightsObj.getDouble(it) }
        
        val multipliersObj = obj.optJSONObject("pattern_multipliers") ?: JSONObject()
        val multipliers = mutableMapOf<String, Double>()
        multipliersObj.keys().forEach { multipliers[it] = multipliersObj.getDouble(it) }
        
        return CommunityWeights(
            featureWeights = weights,
            patternMultipliers = multipliers,
            totalContributors = obj.optInt("total_contributors", 0),
            totalTrades = obj.optInt("total_trades", 0),
            lastUpdated = System.currentTimeMillis(),
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // BLEND COMMUNITY + LOCAL
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Blend community weights with local learned weights.
     * More local trades = more local weight.
     */
    fun blendWeights(
        localWeights: Map<String, Double>,
        localTradeCount: Int,
    ): Map<String, Double> {
        val community = communityWeights ?: return localWeights
        
        if (!useCommunityWeights || community.featureWeights.isEmpty()) {
            return localWeights
        }
        
        // Blend ratio based on local trade count
        // More local trades = trust local more
        val localRatio = when {
            localTradeCount >= 100 -> 0.6  // Experienced: 60% local
            localTradeCount >= 50 -> 0.4   // Medium: 40% local
            localTradeCount >= 20 -> 0.3   // Some experience: 30% local
            else -> 0.1                     // New: 10% local, 90% community
        }
        val communityRatio = 1.0 - localRatio
        
        val blended = mutableMapOf<String, Double>()
        
        // Get all keys from both maps
        val allKeys = localWeights.keys + community.featureWeights.keys
        
        for (key in allKeys) {
            val local = localWeights[key] ?: 1.0
            val comm = community.featureWeights[key] ?: 1.0
            blended[key] = (local * localRatio) + (comm * communityRatio)
        }
        
        return blended
    }

    /**
     * Get community pattern multiplier.
     */
    fun getCommunityPatternMultiplier(patternName: String): Double {
        if (!useCommunityWeights) return 1.0
        return communityWeights?.patternMultipliers?.get(patternName) ?: 1.0
    }

    // ═══════════════════════════════════════════════════════════════════
    // SUPABASE HTTP CLIENT
    // ═══════════════════════════════════════════════════════════════════

    private fun postToSupabase(table: String, data: JSONObject): Boolean {
        val url = URL("$SUPABASE_URL/rest/v1/$table")
        val conn = url.openConnection() as HttpURLConnection
        
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            conn.setRequestProperty("Prefer", "return=minimal")
            conn.doOutput = true
            
            conn.outputStream.bufferedWriter().use { it.write(data.toString()) }
            
            val responseCode = conn.responseCode
            responseCode in 200..299
        } catch (e: Exception) {
            ErrorLogger.error("CloudSync", "POST error: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun getFromSupabase(query: String): String? {
        val url = URL("$SUPABASE_URL/rest/v1/$query")
        val conn = url.openConnection() as HttpURLConnection
        
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("apikey", SUPABASE_ANON_KEY)
            conn.setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                null
            }
        } catch (e: Exception) {
            ErrorLogger.error("CloudSync", "GET error: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SETTINGS
    // ═══════════════════════════════════════════════════════════════════

    fun setOptIn(enabled: Boolean) {
        isOptedIn = enabled
        saveState()
        ErrorLogger.info("CloudSync", "Opt-in sharing: $enabled")
    }

    fun setUseCommunityWeights(enabled: Boolean) {
        useCommunityWeights = enabled
        saveState()
        ErrorLogger.info("CloudSync", "Use community weights: $enabled")
    }

    fun isOptedIn() = isOptedIn
    fun isUsingCommunityWeights() = useCommunityWeights

    fun getStatus(): String {
        if (!isConfigured()) {
            return "CloudSync: Not configured (see docs/CLOUD_SETUP.md)"
        }
        val community = communityWeights
        return if (community != null) {
            "CloudSync: ${community.totalContributors} contributors, ${community.totalTrades} trades | " +
                "optIn=$isOptedIn useCommunity=$useCommunityWeights"
        } else {
            "CloudSync: No community data yet | optIn=$isOptedIn useCommunity=$useCommunityWeights"
        }
    }

    fun getCommunityStats(): String {
        val community = communityWeights ?: return "No community data"
        return "🌐 Community: ${community.totalContributors} bots, ${community.totalTrades} trades learned"
    }
}

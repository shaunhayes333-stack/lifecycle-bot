package com.lifecyclebot.collective

import android.content.Context
import android.util.Log
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.security.MessageDigest

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * COLLECTIVE LEARNING
 * 
 * Main orchestrator for the shared knowledge base.
 * Handles uploading local learnings and downloading collective intelligence.
 * 
 * PRIVACY GUARANTEES:
 *   - No wallet addresses are uploaded
 *   - No trade sizes or amounts
 *   - No personal settings
 *   - Only anonymized patterns and aggregated stats
 * 
 * SYNC BEHAVIOR:
 *   - Downloads on app start
 *   - Uploads after each completed trade
 *   - Full sync every 15 minutes while active
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CollectiveLearning {
    
    private const val TAG = "CollectiveLearning"
    
    // Turso connection (set via init)
    private var client: TursoClient? = null
    private var isInitialized = false
    private var lastSyncTime = 0L
    
    // V5.6.11: Store context for reconnection attempts
    private var appContext: Context? = null
    private var lastReconnectAttempt = 0L
    private const val RECONNECT_COOLDOWN_MS = 60_000L  // 1 minute between reconnect attempts
    
    // V3.3: Instance ID for this app installation (hashed for privacy)
    private var instanceId: String = ""
    
    // Sync interval (15 minutes)
    private const val SYNC_INTERVAL_MS = 15 * 60 * 1000L
    
    // V5.6.12: Session upload stats for debugging
    private var totalUploadAttemptsThisSession = 0
    private var totalUploadSuccessThisSession = 0
    private var totalUploadSkippedThisSession = 0
    
    // V3.3: SharedPreferences for PERSISTENT cache across restarts
    private const val PREFS_NAME = "collective_learning_cache"
    private const val KEY_BLACKLIST = "cached_blacklist_json"
    private const val KEY_PATTERNS = "cached_patterns_json"
    private const val KEY_MODE_STATS = "cached_mode_stats_json"
    private const val KEY_LAST_SYNC = "last_sync_time"
    private const val KEY_INSTANCE_ID = "instance_id"
    private var prefs: android.content.SharedPreferences? = null
    
    // Local cache of collective data (loaded from SharedPreferences on init)
    private val cachedBlacklist = mutableSetOf<String>()
    private val cachedPatterns = mutableMapOf<String, CollectivePattern>()
    private val cachedModeStats = mutableMapOf<String, ModePerformance>()
    private val cachedWhaleStats = mutableMapOf<String, WhaleEffectiveness>()
    
    // Background sync job
    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Initialize collective learning with Turso credentials.
     * Call this on app startup.
     */
    suspend fun init(ctx: Context): Boolean {
        // V5.6.12: Log at very start of init to confirm it's being called
        Log.i(TAG, "🚀 INIT CALLED | alreadyInit=$isInitialized | clientExists=${client != null}")
        
        if (isInitialized && client != null) {
            Log.d(TAG, "Already initialized and connected")
            return true
        }
        
        // V5.6.11: Store context for potential reinit
        appContext = ctx.applicationContext
        
        try {
            // V3.3: Initialize SharedPreferences for persistent cache
            prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // V3.3: Generate or load instance ID (persistent across app launches)
            instanceId = prefs?.getString(KEY_INSTANCE_ID, null) ?: run {
                val newId = sha256("${ctx.packageName}|${System.currentTimeMillis()}|${android.os.Build.FINGERPRINT}").take(16)
                prefs?.edit()?.putString(KEY_INSTANCE_ID, newId)?.apply()
                newId
            }
            Log.i(TAG, "📱 Instance ID: $instanceId")
            
            // V3.3: IMMEDIATELY load cached data from SharedPreferences
            // This ensures new instances get prior learning BEFORE network sync
            loadCacheFromPrefs()
            Log.i(TAG, "📥 Loaded ${cachedBlacklist.size} blacklist + ${cachedPatterns.size} patterns from LOCAL cache")
            
            val config = ConfigStore.load(ctx)
            val dbUrl = config.tursoDbUrl
            val authToken = config.tursoAuthToken
            
            // V5.2: Better logging for debugging connection issues
            Log.i(TAG, "🔧 INIT: dbUrl=${dbUrl.take(50)}... authToken=${authToken.take(20)}...")
            
            if (dbUrl.isBlank() || authToken.isBlank()) {
                Log.w(TAG, "❌ Turso credentials not configured - using LOCAL CACHE ONLY")
                // V3.3: Even without Turso, we have local cache!
                if (cachedBlacklist.isNotEmpty() || cachedPatterns.isNotEmpty()) {
                    Log.i(TAG, "✅ Local collective cache available (${cachedBlacklist.size} blacklist, ${cachedPatterns.size} patterns)")
                }
                return false
            }
            
            client = TursoClient(dbUrl, authToken)
            Log.i(TAG, "🔧 TursoClient created, testing connection...")
            
            // V5.2: Retry connection up to 3 times with exponential backoff
            var connectionSuccess = false
            for (attempt in 1..3) {
                try {
                    if (client!!.testConnection()) {
                        connectionSuccess = true
                        break
                    }
                    Log.w(TAG, "⚠️ Connection attempt $attempt/3 failed, retrying...")
                    if (attempt < 3) {
                        kotlinx.coroutines.delay(attempt * 1000L)  // 1s, 2s backoff
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "⚠️ Connection attempt $attempt error: ${e.message}")
                }
            }
            
            if (!connectionSuccess) {
                Log.e(TAG, "❌ TURSO CONNECTION FAILED after 3 attempts - using LOCAL CACHE")
                client = null
                return false
            }
            Log.i(TAG, "✅ Turso connection successful!")
            
            // Initialize schema
            Log.i(TAG, "🔧 Initializing schema...")
            if (!client!!.initSchema()) {
                Log.e(TAG, "❌ Failed to initialize schema")
                client = null
                return false
            }
            Log.i(TAG, "✅ Schema initialized!")
            
            isInitialized = true
            Log.i(TAG, "✅ COLLECTIVE LEARNING ONLINE - HIVE MIND ACTIVE")
            
            // Initial sync - download collective data (and SAVE to local cache)
            downloadAll()
            
            // FIX: IMMEDIATELY refresh CollectiveIntelligenceAI caches
            // This ensures new users see shared data right away, not just local data
            try {
                Log.i(TAG, "🧠 Triggering CollectiveIntelligenceAI refresh for new user...")
                com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.refresh()
                Log.i(TAG, "✅ CollectiveIntelligenceAI caches populated")
            } catch (e: Exception) {
                Log.w(TAG, "CollectiveIntelligenceAI refresh warning: ${e.message}")
            }
            
            // Start background sync
            startBackgroundSync()
            
            // V3.3: Register this instance in the database for legal compliance
            registerInstance(ctx)
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            return false
        }
    }
    
    /**
     * V3.3: Register this app instance in the database.
     * Creates a legal record of the installation with timestamp.
     */
    private suspend fun registerInstance(ctx: Context) {
        if (!isEnabled()) return
        
        try {
            val now = System.currentTimeMillis()
            val isoTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(now))
            
            val deviceInfo = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            val appVersion = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) { "unknown" }
            
            val sql = """
                INSERT INTO instance_registry 
                    (instance_id, install_timestamp, install_timestamp_iso, device_info, 
                     app_version, total_trades, total_pnl_sol, last_active, is_active)
                VALUES (?, ?, ?, ?, ?, 0, 0.0, ?, 1)
                ON CONFLICT(instance_id) DO UPDATE SET 
                    last_active = ?,
                    app_version = ?,
                    is_active = 1
            """.trimIndent()
            
            val result = client?.execute(sql, listOf(
                instanceId, now, isoTimestamp, deviceInfo, appVersion, now, now, appVersion
            ))
            
            if (result?.success == true) {
                Log.i(TAG, "📋 Instance registered: $instanceId (installed: $isoTimestamp)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instance registration warning: ${e.message}")
        }
    }
    
    /**
     * Check if collective learning is enabled and connected.
     */
    fun isEnabled(): Boolean {
        val enabled = isInitialized && client != null
        // V5.6.12: Debug logging to track why collective may be disabled
        if (!enabled) {
            Log.d(TAG, "🔍 isEnabled=false | init=$isInitialized | client=${client != null}")
        }
        return enabled
    }
    
    /**
     * V5.6.11: Try to reconnect if disconnected.
     * Returns true if connected (either was already or reconnected).
     */
    suspend fun ensureConnected(): Boolean {
        // Already connected
        if (isEnabled()) return true
        
        // Check cooldown
        val now = System.currentTimeMillis()
        if (now - lastReconnectAttempt < RECONNECT_COOLDOWN_MS) {
            return false
        }
        lastReconnectAttempt = now
        
        // Try to reconnect
        val ctx = appContext ?: return false
        Log.i(TAG, "🔄 Attempting to reconnect to Turso...")
        
        // Reset state for reinit
        isInitialized = false
        client = null
        
        return try {
            init(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Reconnect failed: ${e.message}")
            false
        }
    }
    
    /**
     * Shutdown and cleanup.
     */
    fun shutdown() {
        syncJob?.cancel()
        syncJob = null
        client = null
        isInitialized = false
        Log.i(TAG, "Collective learning shutdown")
    }
    
    /**
     * V5.6.11: Get diagnostics for debugging collective connection issues.
     */
    suspend fun getDiagnostics(): CollectiveDiagnostics {
        val connected = isEnabled()
        var canQuery = false
        var tableCount = 0
        var tradeCount = 0
        var errorMessage: String? = null
        
        if (connected) {
            try {
                // Test basic query
                val testResult = client?.query("SELECT 1 as test")
                canQuery = testResult?.success == true
                
                // Count tables
                val tablesResult = client?.query("SELECT name FROM sqlite_master WHERE type='table'")
                tableCount = tablesResult?.rows?.size ?: 0
                
                // Count trades
                val tradesResult = client?.query("SELECT COUNT(*) as cnt FROM collective_trades")
                tradeCount = (tradesResult?.rows?.firstOrNull()?.get("cnt") as? Number)?.toInt() ?: 0
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }
        
        return CollectiveDiagnostics(
            isConnected = connected,
            canQuery = canQuery,
            tableCount = tableCount,
            tradeCount = tradeCount,
            instanceId = instanceId.take(8) + "...",
            errorMessage = errorMessage,
            lastReconnectAttempt = lastReconnectAttempt
        )
    }
    
    data class CollectiveDiagnostics(
        val isConnected: Boolean,
        val canQuery: Boolean,
        val tableCount: Int,
        val tradeCount: Int,
        val instanceId: String,
        val errorMessage: String?,
        val lastReconnectAttempt: Long
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // UPLOAD METHODS (Share local learnings)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Upload a pattern outcome after a trade completes.
     * Call this from BehaviorLearning when a trade is classified.
     */
    suspend fun uploadPatternOutcome(
        patternType: String,
        discoverySource: String,
        liquidityBucket: String,
        emaTrend: String,
        isWin: Boolean,
        pnlPct: Double,
        holdMins: Double,
    ) {
        if (!isEnabled()) return
        
        try {
            val patternHash = hashPattern(patternType, discoverySource, liquidityBucket, emaTrend)
            val now = System.currentTimeMillis()
            
            // Upsert pattern with incremented stats
            val sql = """
                INSERT INTO collective_patterns 
                    (pattern_hash, pattern_type, discovery_source, liquidity_bucket, ema_trend,
                     total_trades, wins, losses, avg_pnl_pct, avg_hold_mins, last_updated)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                ON CONFLICT(pattern_hash) DO UPDATE SET
                    total_trades = total_trades + 1,
                    wins = wins + ?,
                    losses = losses + ?,
                    avg_pnl_pct = (avg_pnl_pct * total_trades + ?) / (total_trades + 1),
                    avg_hold_mins = (avg_hold_mins * total_trades + ?) / (total_trades + 1),
                    last_updated = ?
            """.trimIndent()
            
            val winInc = if (isWin) 1 else 0
            val lossInc = if (isWin) 0 else 1
            
            val result = client!!.execute(sql, listOf(
                patternHash, patternType, discoverySource, liquidityBucket, emaTrend,
                winInc, lossInc, pnlPct, holdMins, now,
                winInc, lossInc, pnlPct, holdMins, now
            ))
            
            if (result.success) {
                Log.d(TAG, "📤 Uploaded pattern: $patternType (${if (isWin) "WIN" else "LOSS"})")
            } else {
                Log.w(TAG, "Failed to upload pattern: ${result.error}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload pattern error: ${e.message}")
        }
    }
    
    /**
     * V3.3: Upload EVERY trade to the collective knowledge base.
     * This captures all trading activity for the hive mind.
     * 
     * Call this IMMEDIATELY after every BUY or SELL execution.
     */
    suspend fun uploadTrade(
        side: String,           // "BUY" or "SELL"
        symbol: String,         // Token symbol
        mode: String,           // Trading mode (e.g., "PUMP_SNIPER", "WHALE_FOLLOW")
        source: String,         // Discovery source
        liquidityUsd: Double,   // Liquidity at time of trade
        marketSentiment: String, // EMA trend / sentiment
        entryScore: Int,        // V3 score at entry
        confidence: Int,        // Confidence percentage
        pnlPct: Double = 0.0,   // PnL % (0 for BUY, actual for SELL)
        holdMins: Double = 0.0, // Hold time (0 for BUY, actual for SELL)
        isWin: Boolean = false, // Only relevant for SELL
        paperMode: Boolean = true,
    ) {
        // V5.6.22: Track upload attempts
        totalUploadAttemptsThisSession++
        
        // V5.6.22: Use ErrorLogger so logs appear in the app's Error Log export
        ErrorLogger.info("CollectiveTrade", "📤 ATTEMPT #$totalUploadAttemptsThisSession: $side $symbol | enabled=${isEnabled()} | init=$isInitialized | client=${client != null} | inst=${instanceId.take(8)}")
        
        // V5.6.20: Validate instanceId BEFORE attempting upload
        if (instanceId.isBlank()) {
            totalUploadSkippedThisSession++
            ErrorLogger.error("CollectiveTrade", "❌ SKIPPED: $side $symbol - instanceId BLANK! init() not completed")
            return
        }
        
        // V5.6.11: Try to reconnect if disconnected
        if (!isEnabled()) {
            ErrorLogger.warn("CollectiveTrade", "🔄 Reconnecting for $side $symbol...")
            ensureConnected()
            if (!isEnabled()) {
                totalUploadSkippedThisSession++
                ErrorLogger.error("CollectiveTrade", "❌ SKIPPED: $side $symbol - DISABLED (client=${client != null}, init=$isInitialized)")
                return
            }
        }
        
        try {
            val now = System.currentTimeMillis()
            
            // Create unique hash for this trade (privacy: no wallet, no mint)
            val tradeHash = sha256("$now|$side|$symbol|$mode|${System.nanoTime()}").take(24)
            
            val liquidityBucket = when {
                liquidityUsd < 5_000 -> "MICRO"
                liquidityUsd < 25_000 -> "SMALL"
                liquidityUsd < 100_000 -> "MID"
                else -> "LARGE"
            }
            
            ErrorLogger.info("CollectiveTrade", "📤 INSERT: $side $symbol | hash=${tradeHash.take(8)} | inst=${instanceId.take(8)} | liq=$liquidityBucket")
            
            // V3.3: Include instance_id for legal compliance and per-user audit trail
            val sql = """
                INSERT INTO collective_trades 
                    (trade_hash, instance_id, timestamp, side, symbol, mode, source, liquidity_bucket,
                     market_sentiment, entry_score, confidence, pnl_pct, hold_mins, is_win, paper_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            
            val result = client!!.execute(sql, listOf(
                tradeHash, instanceId, now, side, symbol, mode, source, liquidityBucket,
                marketSentiment, entryScore, confidence, pnlPct, holdMins,
                if (isWin) 1 else 0, if (paperMode) 1 else 0
            ))
            
            // V5.6.22: Log the actual Turso response for debugging
            ErrorLogger.info("CollectiveTrade", "📤 RESULT: $side $symbol | success=${result.success} | rows=${result.rowsAffected} | lastId=${result.lastInsertId} | err=${result.error?.take(100)}")
            
            if (result.success) {
                // V5.6.20: Turso HTTP API may return success=true with no rowsAffected for INSERTs
                // Treat as success if no error is reported
                if (result.error.isNullOrBlank()) {
                    totalUploadSuccessThisSession++
                    ErrorLogger.info("CollectiveTrade", "✅ UPLOADED: $side $symbol ($mode) ${if (side == "SELL") "${pnlPct.toInt()}%" else ""} [${totalUploadSuccessThisSession}/${totalUploadAttemptsThisSession}]")
                    totalUploadsThisSession++
                    
                    // V3.3: Update instance registry trade count
                    updateInstanceTradeCount()
                } else {
                    ErrorLogger.warn("CollectiveTrade", "⚠️ SUCCESS but error msg: $side $symbol | ${result.error}")
                }
            } else {
                ErrorLogger.error("CollectiveTrade", "❌ FAILED: $side $symbol | err=${result.error} | rows=${result.rowsAffected}")
            }
        } catch (e: Exception) {
            ErrorLogger.error("CollectiveTrade", "❌ EXCEPTION: $side $symbol | ${e.message}")
        }
    }
    
    /**
     * V3.3: Update instance registry trade count.
     * Called after each successful trade upload.
     */
    private suspend fun updateInstanceTradeCount() {
        try {
            val now = System.currentTimeMillis()
            client?.execute("""
                UPDATE instance_registry 
                SET total_trades = total_trades + 1, last_active = ?
                WHERE instance_id = ?
            """.trimIndent(), listOf(now, instanceId))
        } catch (_: Exception) {}
    }
    
    /**
     * Get total count of all trades in the collective database.
     */
    suspend fun getCollectiveTotalTradeCount(): Int {
        if (!isEnabled()) return 0
        
        return try {
            val result = client?.execute("SELECT COUNT(*) as cnt FROM collective_trades")
            if (result?.success == true && result.rows.isNotEmpty()) {
                // V5.2.10: Handle multiple data types from Turso
                val cnt = result.rows[0]["cnt"]
                when (cnt) {
                    is Number -> cnt.toInt()
                    is String -> cnt.toIntOrNull() ?: 0
                    else -> {
                        Log.w(TAG, "getCollectiveTotalTradeCount: unexpected type ${cnt?.javaClass?.simpleName}")
                        0
                    }
                }
            } else 0
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get trade count: ${e.message}")
            0
        }
    }
    
    /**
     * Report a blacklisted token (rug, honeypot, scam).
     * Call this when a token is detected as malicious.
     */
    suspend fun reportBlacklistedToken(
        mint: String,
        symbol: String,
        reason: String,
        severity: Int = 3,
    ) {
        if (!isEnabled()) return
        
        try {
            val now = System.currentTimeMillis()
            
            val sql = """
                INSERT INTO token_blacklist 
                    (mint, symbol, reason, report_count, first_reported, last_reported, severity)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                ON CONFLICT(mint) DO UPDATE SET
                    report_count = report_count + 1,
                    last_reported = ?,
                    severity = MAX(severity, ?)
            """.trimIndent()
            
            val result = client!!.execute(sql, listOf(
                mint, symbol, reason, now, now, severity,
                now, severity
            ))
            
            if (result.success) {
                cachedBlacklist.add(mint)
                Log.i(TAG, "🚫 Reported blacklist: $symbol ($reason)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Report blacklist error: ${e.message}")
        }
    }
    
    /**
     * Upload mode performance stats.
     * Call this periodically from ModeLearning.
     */
    suspend fun uploadModePerformance(
        modeName: String,
        marketCondition: String,
        liquidityBucket: String,
        totalTrades: Int,
        wins: Int,
        losses: Int,
        avgPnlPct: Double,
        avgHoldMins: Double,
    ) {
        if (!isEnabled()) return
        
        try {
            val now = System.currentTimeMillis()
            
            val sql = """
                INSERT INTO mode_performance 
                    (mode_name, market_condition, liquidity_bucket, total_trades, wins, losses,
                     avg_pnl_pct, avg_hold_mins, last_updated)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(mode_name, market_condition, liquidity_bucket) DO UPDATE SET
                    total_trades = total_trades + ?,
                    wins = wins + ?,
                    losses = losses + ?,
                    avg_pnl_pct = (avg_pnl_pct * total_trades + ? * ?) / (total_trades + ?),
                    avg_hold_mins = (avg_hold_mins * total_trades + ? * ?) / (total_trades + ?),
                    last_updated = ?
            """.trimIndent()
            
            client!!.execute(sql, listOf(
                modeName, marketCondition, liquidityBucket, totalTrades, wins, losses,
                avgPnlPct, avgHoldMins, now,
                totalTrades, wins, losses,
                avgPnlPct, totalTrades, totalTrades,
                avgHoldMins, totalTrades, totalTrades,
                now
            ))
            
            Log.d(TAG, "📤 Uploaded mode stats: $modeName ($marketCondition)")
        } catch (e: Exception) {
            Log.e(TAG, "Upload mode stats error: ${e.message}")
        }
    }
    
    /**
     * Upload whale effectiveness data.
     * Call this after a whale-follow trade completes.
     */
    suspend fun uploadWhaleEffectiveness(
        walletAddress: String,  // Will be hashed for privacy
        isProfitable: Boolean,
        pnlPct: Double,
        leadTimeSec: Int,
    ) {
        if (!isEnabled()) return
        
        try {
            val walletHash = hashWallet(walletAddress)
            val now = System.currentTimeMillis()
            
            val sql = """
                INSERT INTO whale_effectiveness 
                    (wallet_hash, total_follows, profitable_follows, avg_pnl_pct, avg_lead_time_sec, last_updated)
                VALUES (?, 1, ?, ?, ?, ?)
                ON CONFLICT(wallet_hash) DO UPDATE SET
                    total_follows = total_follows + 1,
                    profitable_follows = profitable_follows + ?,
                    avg_pnl_pct = (avg_pnl_pct * total_follows + ?) / (total_follows + 1),
                    avg_lead_time_sec = (avg_lead_time_sec * total_follows + ?) / (total_follows + 1),
                    last_updated = ?
            """.trimIndent()
            
            val profitInc = if (isProfitable) 1 else 0
            
            client!!.execute(sql, listOf(
                walletHash, profitInc, pnlPct, leadTimeSec, now,
                profitInc, pnlPct, leadTimeSec, now
            ))
            
            Log.d(TAG, "📤 Uploaded whale stats: ${walletHash.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Upload whale stats error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INSTANCE HEARTBEAT (V3.2: Track active instances)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Upload a heartbeat to track this instance as active.
     * Call this periodically (every 5 minutes) from BotService.
     */
    suspend fun uploadHeartbeat(
        instanceId: String,
        appVersion: String,
        paperMode: Boolean,
        trades24h: Int,
        pnl24hPct: Double
    ) {
        if (!isEnabled()) return
        
        try {
            val now = System.currentTimeMillis()
            
            val sql = """
                INSERT INTO instance_heartbeats 
                    (instance_id, last_heartbeat, app_version, paper_mode, trades_24h, pnl_24h_pct)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(instance_id) DO UPDATE SET
                    last_heartbeat = excluded.last_heartbeat,
                    app_version = excluded.app_version,
                    paper_mode = excluded.paper_mode,
                    trades_24h = excluded.trades_24h,
                    pnl_24h_pct = excluded.pnl_24h_pct
            """
            
            client!!.execute(sql, listOf(
                instanceId,
                now,
                appVersion,
                if (paperMode) 1 else 0,
                trades24h,
                pnl24hPct
            ))
            
            Log.d(TAG, "💓 Heartbeat sent: $instanceId")
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat error: ${e.message}")
        }
    }
    
    /**
     * Count active instances (heartbeat within last 15 minutes).
     */
    suspend fun countActiveInstances(): Int {
        if (!isEnabled()) return 1  // At least this instance
        
        try {
            // V3.3: Count "Active Users" - users who have traded in last 24h
            // This is more meaningful than heartbeat-based instance count
            val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
            
            // First try to count from instance_registry (users who traded recently)
            val registryResult = client!!.query(
                "SELECT COUNT(*) as count FROM instance_registry WHERE last_active > ? AND total_trades > 0",
                listOf(oneDayAgo)
            )
            
            if (registryResult.success && registryResult.rows.isNotEmpty()) {
                val activeTraders = (registryResult.rows[0]["count"] as? Number)?.toInt() ?: 0
                if (activeTraders > 0) {
                    Log.d(TAG, "📊 Active trading users (24h): $activeTraders")
                    return activeTraders.coerceAtLeast(1)
                }
            }
            
            // Fallback: count from heartbeats (15 min window)
            val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000L)
            val heartbeatResult = client!!.query(
                "SELECT COUNT(*) as count FROM instance_heartbeats WHERE last_heartbeat > ?",
                listOf(fifteenMinsAgo)
            )
            
            if (heartbeatResult.success && heartbeatResult.rows.isNotEmpty()) {
                val count = (heartbeatResult.rows[0]["count"] as? Number)?.toInt() ?: 1
                Log.d(TAG, "📊 Active instances (heartbeat): $count")
                return count.coerceAtLeast(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Count instances error: ${e.message}")
        }
        
        return 1  // Fallback to at least this instance
    }
    
    /**
     * V3.3: Get count of active trading users (users who traded in last 24h).
     * More meaningful metric than just instance count.
     */
    suspend fun getActiveUsersCount(): Int {
        if (!isEnabled()) return 1
        
        try {
            val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
            
            // V5.6.11: First try counting all distinct instance_ids (no time filter for debugging)
            val totalResult = client!!.query(
                "SELECT COUNT(DISTINCT instance_id) as total, COUNT(*) as trades FROM collective_trades"
            )
            
            if (totalResult.success && totalResult.rows.isNotEmpty()) {
                val totalUsers = (totalResult.rows[0]["total"] as? Number)?.toInt() ?: 0
                val totalTrades = (totalResult.rows[0]["trades"] as? Number)?.toInt() ?: 0
                Log.d(TAG, "📊 TOTAL in collective_trades: $totalUsers users, $totalTrades trades")
            }
            
            // Now try with time filter
            val result = client!!.query(
                "SELECT COUNT(DISTINCT instance_id) as count FROM collective_trades WHERE timestamp > ?",
                listOf(oneDayAgo)
            )
            
            if (result.success && result.rows.isNotEmpty()) {
                val count = (result.rows[0]["count"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
                Log.d(TAG, "📊 Active users (24h): $count")
                return count
            }
        } catch (e: Exception) {
            Log.w(TAG, "getActiveUsersCount error: ${e.message}")
        }
        
        return 1
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DOWNLOAD METHODS (Get collective intelligence)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Download all collective data.
     */
    suspend fun downloadAll() {
        if (!isEnabled()) return
        
        try {
            downloadBlacklist()
            downloadPatterns()
            downloadModeStats()
            downloadWhaleStats()
            lastSyncTime = System.currentTimeMillis()
            
            // V3.3: PERSIST downloaded data to SharedPreferences
            // This ensures new app instances inherit the hive mind immediately!
            saveCacheToPrefs()
            
            // V3.2: Count active instances
            val activeInstances = countActiveInstances()
            
            Log.i(TAG, "📥 Full sync completed (${activeInstances} active instances) | CACHED TO LOCAL STORAGE")
            
            // Update CollectiveAnalytics with downloaded stats
            try {
                com.lifecyclebot.engine.CollectiveAnalytics.updateCollectiveStats(
                    totalPatterns = cachedPatterns.size,
                    blacklistSize = cachedBlacklist.size,
                    estimatedInstances = activeInstances
                )
                
                // Update best/worst patterns
                val sortedPatterns = cachedPatterns.values.filter { it.isReliable }
                val bestPatterns = sortedPatterns
                    .sortedByDescending { it.winRate }
                    .take(5)
                    .map { p ->
                        com.lifecyclebot.engine.CollectiveAnalytics.PatternStat(
                            patternType = "${p.patternType}_${p.discoverySource}",
                            winRate = p.winRate,
                            totalTrades = p.totalTrades,
                            avgPnl = p.avgPnlPct
                        )
                    }
                val worstPatterns = sortedPatterns
                    .sortedBy { it.winRate }
                    .take(5)
                    .map { p ->
                        com.lifecyclebot.engine.CollectiveAnalytics.PatternStat(
                            patternType = "${p.patternType}_${p.discoverySource}",
                            winRate = p.winRate,
                            totalTrades = p.totalTrades,
                            avgPnl = p.avgPnlPct
                        )
                    }
                
                com.lifecyclebot.engine.CollectiveAnalytics.updateBestPatterns(bestPatterns)
                com.lifecyclebot.engine.CollectiveAnalytics.updateWorstPatterns(worstPatterns)
            } catch (e: Exception) {
                Log.d(TAG, "CollectiveAnalytics update error: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download all error: ${e.message}")
        }
    }
    
    /**
     * Download token blacklist.
     */
    private suspend fun downloadBlacklist() {
        try {
            val result = client!!.query(
                "SELECT mint FROM token_blacklist WHERE report_count >= 3"
            )
            
            if (result.success) {
                cachedBlacklist.clear()
                for (row in result.rows) {
                    val mint = row["mint"] as? String
                    if (mint != null) {
                        cachedBlacklist.add(mint)
                    }
                }
                Log.i(TAG, "📥 Downloaded ${cachedBlacklist.size} blacklisted tokens")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download blacklist error: ${e.message}")
        }
    }
    
    /**
     * Download collective patterns.
     */
    private suspend fun downloadPatterns() {
        try {
            val result = client!!.query(
                "SELECT * FROM collective_patterns WHERE total_trades >= 10"
            )
            
            if (result.success) {
                cachedPatterns.clear()
                for (row in result.rows) {
                    val pattern = CollectivePattern(
                        id = (row["id"] as? Long) ?: 0,
                        patternHash = row["pattern_hash"] as? String ?: "",
                        patternType = row["pattern_type"] as? String ?: "",
                        discoverySource = row["discovery_source"] as? String ?: "",
                        liquidityBucket = row["liquidity_bucket"] as? String ?: "",
                        emaTrend = row["ema_trend"] as? String ?: "",
                        totalTrades = (row["total_trades"] as? Long)?.toInt() ?: 0,
                        wins = (row["wins"] as? Long)?.toInt() ?: 0,
                        losses = (row["losses"] as? Long)?.toInt() ?: 0,
                        avgPnlPct = row["avg_pnl_pct"] as? Double ?: 0.0,
                        avgHoldMins = row["avg_hold_mins"] as? Double ?: 0.0,
                        lastUpdated = (row["last_updated"] as? Long) ?: 0,
                    )
                    cachedPatterns[pattern.patternHash] = pattern
                }
                Log.i(TAG, "📥 Downloaded ${cachedPatterns.size} patterns")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download patterns error: ${e.message}")
        }
    }
    
    /**
     * Download mode performance stats.
     */
    private suspend fun downloadModeStats() {
        try {
            val result = client!!.query(
                "SELECT * FROM mode_performance WHERE total_trades >= 20"
            )
            
            if (result.success) {
                cachedModeStats.clear()
                for (row in result.rows) {
                    val key = "${row["mode_name"]}_${row["market_condition"]}_${row["liquidity_bucket"]}"
                    val stat = ModePerformance(
                        id = (row["id"] as? Long) ?: 0,
                        modeName = row["mode_name"] as? String ?: "",
                        marketCondition = row["market_condition"] as? String ?: "",
                        liquidityBucket = row["liquidity_bucket"] as? String ?: "",
                        totalTrades = (row["total_trades"] as? Long)?.toInt() ?: 0,
                        wins = (row["wins"] as? Long)?.toInt() ?: 0,
                        losses = (row["losses"] as? Long)?.toInt() ?: 0,
                        avgPnlPct = row["avg_pnl_pct"] as? Double ?: 0.0,
                        avgHoldMins = row["avg_hold_mins"] as? Double ?: 0.0,
                        lastUpdated = (row["last_updated"] as? Long) ?: 0,
                    )
                    cachedModeStats[key] = stat
                }
                Log.i(TAG, "📥 Downloaded ${cachedModeStats.size} mode stats")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download mode stats error: ${e.message}")
        }
    }
    
    /**
     * Download whale effectiveness stats.
     */
    private suspend fun downloadWhaleStats() {
        try {
            val result = client!!.query(
                "SELECT * FROM whale_effectiveness WHERE total_follows >= 5"
            )
            
            if (result.success) {
                cachedWhaleStats.clear()
                for (row in result.rows) {
                    val stat = WhaleEffectiveness(
                        id = (row["id"] as? Long) ?: 0,
                        walletHash = row["wallet_hash"] as? String ?: "",
                        totalFollows = (row["total_follows"] as? Long)?.toInt() ?: 0,
                        profitableFollows = (row["profitable_follows"] as? Long)?.toInt() ?: 0,
                        avgPnlPct = row["avg_pnl_pct"] as? Double ?: 0.0,
                        avgLeadTimeSec = (row["avg_lead_time_sec"] as? Long)?.toInt() ?: 0,
                        lastUpdated = (row["last_updated"] as? Long) ?: 0,
                    )
                    cachedWhaleStats[stat.walletHash] = stat
                }
                Log.i(TAG, "📥 Downloaded ${cachedWhaleStats.size} whale stats")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download whale stats error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUERY METHODS (Use collective intelligence)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Check if a token is blacklisted by the collective.
     */
    fun isBlacklisted(mint: String): Boolean {
        return cachedBlacklist.contains(mint)
    }
    
    /**
     * Get collective pattern stats for a given pattern.
     */
    fun getPatternStats(
        patternType: String,
        discoverySource: String,
        liquidityBucket: String,
        emaTrend: String,
    ): CollectivePattern? {
        val hash = hashPattern(patternType, discoverySource, liquidityBucket, emaTrend)
        return cachedPatterns[hash]
    }
    
    /**
     * Get collective mode performance.
     */
    fun getModePerformance(
        modeName: String,
        marketCondition: String,
        liquidityBucket: String,
    ): ModePerformance? {
        val key = "${modeName}_${marketCondition}_$liquidityBucket"
        return cachedModeStats[key]
    }
    
    /**
     * Get whale effectiveness for a wallet.
     */
    fun getWhaleEffectiveness(walletAddress: String): WhaleEffectiveness? {
        val hash = hashWallet(walletAddress)
        return cachedWhaleStats[hash]
    }
    
    /**
     * Get all patterns with high loss rate (>70% losses).
     * Useful for blocking known bad setups.
     */
    fun getHighLossPatterns(): List<CollectivePattern> {
        return cachedPatterns.values.filter { 
            it.isReliable && it.winRate < 30.0 
        }
    }
    
    /**
     * Get all patterns with high win rate (>60% wins).
     * Useful for boosting confidence on good setups.
     */
    fun getHighWinPatterns(): List<CollectivePattern> {
        return cachedPatterns.values.filter { 
            it.isReliable && it.winRate > 60.0 
        }
    }
    
    /**
     * Get best mode for current conditions.
     */
    fun getBestMode(marketCondition: String, liquidityBucket: String): String? {
        return cachedModeStats.values
            .filter { 
                it.marketCondition == marketCondition && 
                it.liquidityBucket == liquidityBucket &&
                it.totalTrades >= 20
            }
            .maxByOrNull { it.winRate }
            ?.modeName
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // BACKGROUND SYNC
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun startBackgroundSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                if (isEnabled()) {
                    try {
                        downloadAll()
                    } catch (e: Exception) {
                        Log.e(TAG, "Background sync error: ${e.message}")
                    }
                }
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVACY HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Hash pattern characteristics for anonymization.
     */
    private fun hashPattern(
        patternType: String,
        discoverySource: String,
        liquidityBucket: String,
        emaTrend: String,
    ): String {
        val input = "$patternType|$discoverySource|$liquidityBucket|$emaTrend"
        return sha256(input).take(16)
    }
    
    /**
     * Hash wallet address for privacy.
     */
    private fun hashWallet(walletAddress: String): String {
        return sha256("AATE_WHALE_$walletAddress").take(16)
    }
    
    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStats(): Map<String, Any> {
        return mapOf(
            "enabled" to isEnabled(),
            "isInitialized" to isInitialized,
            "clientExists" to (client != null),
            "blacklistedTokens" to cachedBlacklist.size,
            "patterns" to cachedPatterns.size,
            "modeStats" to cachedModeStats.size,
            "whaleStats" to cachedWhaleStats.size,
            "lastSyncTime" to lastSyncTime,
            // V5.6.12: Debug stats
            "uploadAttempts" to totalUploadAttemptsThisSession,
            "uploadSuccess" to totalUploadSuccessThisSession,
            "uploadSkipped" to totalUploadSkippedThisSession,
        )
    }
    
    /**
     * Get the total number of collective trades from all patterns in Turso.
     * This represents the aggregate trade count across the entire hive mind.
     */
    suspend fun getCollectiveTradeCount(): Int {
        if (!isEnabled()) return 0
        
        return try {
            // First try to get actual trade count from collective_trades table
            val tradeCount = getCollectiveTotalTradeCount()
            if (tradeCount > 0) return tradeCount
            
            // Fallback: Sum all trades from cached patterns (already downloaded from Turso)
            val fromPatterns = cachedPatterns.values.sumOf { it.totalTrades }
            
            // Also sum from mode performance stats
            val fromModes = cachedModeStats.values.sumOf { it.totalTrades }
            
            // Return the higher count (they may overlap)
            maxOf(fromPatterns, fromModes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get collective trade count: ${e.message}")
            0
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORE ADJUSTMENTS - Use collective intelligence to adjust local decisions
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get a score adjustment based on collective pattern data.
     * Returns a value between -30 (known loser) and +30 (proven winner).
     */
    fun getPatternScoreAdjustment(
        entryPhase: String,
        tradingMode: String,
        discoverySource: String,
        liquidityUsd: Double,
        emaTrend: String,
    ): Int {
        if (!isEnabled()) return 0
        
        val liquidityBucket = when {
            liquidityUsd < 5_000 -> "MICRO"
            liquidityUsd < 25_000 -> "SMALL"
            liquidityUsd < 100_000 -> "MID"
            else -> "LARGE"
        }
        
        val patternType = "${entryPhase}_$tradingMode"
        val stats = getPatternStats(patternType, discoverySource, liquidityBucket, emaTrend)
        
        if (stats == null || !stats.isReliable) return 0
        
        // Score adjustment based on collective win rate
        return when {
            stats.winRate >= 70.0 -> +30  // Proven winner
            stats.winRate >= 60.0 -> +20  // Good pattern
            stats.winRate >= 50.0 -> +10  // Slightly positive
            stats.winRate >= 40.0 -> 0    // Neutral
            stats.winRate >= 30.0 -> -10  // Slightly negative
            stats.winRate >= 20.0 -> -20  // Known loser
            else -> -30                   // Toxic pattern
        }
    }
    
    /**
     * Check if the collective has a recommended mode for current conditions.
     */
    fun getRecommendedMode(
        liquidityUsd: Double,
        emaTrend: String,
    ): String? {
        if (!isEnabled()) return null
        
        val liquidityBucket = when {
            liquidityUsd < 5_000 -> "MICRO"
            liquidityUsd < 25_000 -> "SMALL"
            liquidityUsd < 100_000 -> "MID"
            else -> "LARGE"
        }
        
        val marketCondition = when (emaTrend.uppercase()) {
            "BULL", "BULLISH" -> "BULL"
            "BEAR", "BEARISH" -> "BEAR"
            else -> "SIDEWAYS"
        }
        
        return getBestMode(marketCondition, liquidityBucket)
    }
    
    /**
     * Bulk sync local mode learning data to collective.
     * Call this periodically (e.g., every 15 minutes).
     */
    suspend fun syncModeLearning(modeStats: Map<String, ModeStatSnapshot>) {
        if (!isEnabled()) return
        
        try {
            for ((modeName, stats) in modeStats) {
                if (stats.totalTrades < 5) continue  // Not enough data
                
                uploadModePerformance(
                    modeName = modeName,
                    marketCondition = stats.marketCondition,
                    liquidityBucket = stats.liquidityBucket,
                    totalTrades = stats.totalTrades,
                    wins = stats.wins,
                    losses = stats.losses,
                    avgPnlPct = stats.avgPnlPct,
                    avgHoldMins = stats.avgHoldMins
                )
            }
            Log.i(TAG, "📤 Synced ${modeStats.size} mode stats to collective")
        } catch (e: Exception) {
            Log.e(TAG, "Mode sync error: ${e.message}")
        }
    }
    
    /**
     * Data class for mode stat snapshots from local ModeLearning.
     */
    data class ModeStatSnapshot(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val avgPnlPct: Double,
        val avgHoldMins: Double,
        val marketCondition: String = "NEUTRAL",
        val liquidityBucket: String = "MID",
    )
    
    /**
     * Get collective insights summary for logging.
     */
    fun getInsightsSummary(): String {
        if (!isEnabled()) return "Collective: DISABLED"
        
        val highWin = getHighWinPatterns().size
        val highLoss = getHighLossPatterns().size
        val blacklisted = cachedBlacklist.size
        
        return "Collective: $blacklisted blacklisted | $highWin winning patterns | $highLoss losing patterns"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // LEGAL AGREEMENT RECORDING
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a legal agreement acceptance in the collective database.
     * Required for legal compliance and audit trail.
     */
    suspend fun recordLegalAgreement(record: LegalAgreementRecord): Boolean {
        if (!isEnabled()) {
            Log.w(TAG, "Collective disabled - storing legal agreement locally only")
            return true  // Still return true so local storage works
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val sql = """
                    INSERT INTO legal_agreements (
                        instance_id, agreement_version, agreement_type,
                        accepted_at, accepted_at_iso, device_info,
                        app_version, ip_country, consent_checksum
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(instance_id, agreement_type, agreement_version) 
                    DO UPDATE SET
                        accepted_at = excluded.accepted_at,
                        accepted_at_iso = excluded.accepted_at_iso,
                        device_info = excluded.device_info,
                        app_version = excluded.app_version
                """
                
                client?.execute(
                    sql,
                    listOf(
                        record.instanceId,
                        record.agreementVersion,
                        record.agreementType,
                        record.acceptedAt,
                        record.acceptedAtIso,
                        record.deviceInfo,
                        record.appVersion,
                        record.ipCountry,
                        record.consentChecksum
                    )
                )
                
                Log.i(TAG, "✅ Legal agreement recorded: ${record.agreementType} v${record.agreementVersion}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record legal agreement: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Get count of agreement acceptances (for analytics).
     */
    suspend fun getLegalAgreementCount(): Int {
        if (!isEnabled()) return 0
        
        return withContext(Dispatchers.IO) {
            try {
                val result = client?.execute("SELECT COUNT(*) as cnt FROM legal_agreements")
                result?.rows?.firstOrNull()?.get("cnt")?.toString()?.toIntOrNull() ?: 0
            } catch (e: Exception) {
                0
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COLLECTIVE INTELLIGENCE AI SUPPORT METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Data class for pattern data returned to CollectiveIntelligenceAI
     * (Different from CollectiveSchema.CollectivePattern)
     */
    data class AIPattern(
        val patternKey: String,
        val totalTrades: Int,
        val wins: Int,
        val avgPnlPct: Double,
        val instanceCount: Int,
        val lastUpdated: Long,
    )
    
    /**
     * Data class for mode stats returned to CollectiveIntelligenceAI
     */
    data class CollectiveModeStats(
        val modeName: String,
        val totalTrades: Int,
        val wins: Int,
        val avgPnlPct: Double,
        val instanceCount: Int,
    )
    
    /**
     * Data class for signal data returned to CollectiveIntelligenceAI
     */
    data class CollectiveSignal(
        val mint: String,
        val signal: String,
        val instanceId: String,
        val timestamp: Long,
    )
    
    /**
     * Download all patterns for CollectiveIntelligenceAI analysis.
     */
    suspend fun downloadAllPatterns(): List<AIPattern> {
        if (!isEnabled()) return emptyList()
        
        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query(
                    "SELECT pattern_hash as pattern_key, total_trades, wins, avg_pnl_pct, " +
                    "1 as instance_count, last_updated " +
                    "FROM collective_patterns"
                )

                if (result.success) {
                    result.rows.map { row ->
                        AIPattern(
                            patternKey = row["pattern_key"] as? String ?: "",
                            totalTrades = (row["total_trades"] as? Long)?.toInt() ?: 0,
                            wins = (row["wins"] as? Long)?.toInt() ?: 0,
                            avgPnlPct = row["avg_pnl_pct"] as? Double ?: 0.0,
                            instanceCount = (row["instance_count"] as? Long)?.toInt() ?: 1,
                            lastUpdated = (row["last_updated"] as? Long) ?: 0
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadAllPatterns error: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * Download mode stats for CollectiveIntelligenceAI analysis.
     */
    suspend fun downloadModeStatsForAI(): Map<String, CollectiveModeStats> {
        if (!isEnabled()) return emptyMap()
        
        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query(
                    "SELECT mode_name, SUM(total_trades) as total_trades, SUM(wins) as wins, " +
                    "AVG(avg_pnl_pct) as avg_pnl_pct, COUNT(DISTINCT id) as instance_count " +
                    "FROM mode_performance GROUP BY mode_name"
                )
                
                if (result.success) {
                    result.rows.associate { row ->
                        val modeName = row["mode_name"] as? String ?: ""
                        modeName to CollectiveModeStats(
                            modeName = modeName,
                            totalTrades = (row["total_trades"] as? Long)?.toInt() ?: 0,
                            wins = (row["wins"] as? Long)?.toInt() ?: 0,
                            avgPnlPct = row["avg_pnl_pct"] as? Double ?: 0.0,
                            instanceCount = (row["instance_count"] as? Long)?.toInt() ?: 1
                        )
                    }
                } else {
                    emptyMap()
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadModeStatsForAI error: ${e.message}")
                emptyMap()
            }
        }
    }
    
    /**
     * Download recent signals for consensus analysis.
     */
    suspend fun downloadRecentSignals(): List<CollectiveSignal> {
        if (!isEnabled()) return emptyList()
        
        return withContext(Dispatchers.IO) {
            try {
                // Get signals from last 24 hours
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
                val result = client!!.query(
                    "SELECT mint, signal_type as signal, broadcaster_id as instance_id, timestamp FROM network_signals WHERE timestamp > $cutoff AND expires_at > $cutoff"
                )
                
                if (result.success) {
                    result.rows.map { row ->
                        CollectiveSignal(
                            mint = row["mint"] as? String ?: "",
                            signal = row["signal"] as? String ?: "",
                            instanceId = row["instance_id"] as? String ?: "",
                            timestamp = (row["timestamp"] as? Long) ?: 0
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadRecentSignals error: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * Prune patterns older than cutoffTime.
     */
    suspend fun pruneOldPatterns(cutoffTime: Long): Int {
        if (!isEnabled()) return 0
        
        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.execute(
                    "DELETE FROM collective_patterns WHERE last_updated < $cutoffTime"
                )
                
                if (result.success) {
                    val deleted = result.rowsAffected?.toInt() ?: 0
                    Log.i(TAG, "🧹 Pruned $deleted old patterns")
                    deleted
                } else {
                    0
                }
            } catch (e: Exception) {
                Log.e(TAG, "pruneOldPatterns error: ${e.message}")
                0
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════════
    // V4.0: ENHANCED COLLECTIVE INTELLIGENCE - HIVE MIND NETWORK
    // 
    // These methods enable 500,000 bots to work together:
    // 1. Query aggregated stats from all trades in the network
    // 2. Broadcast hot tokens when a big winner is found
    // 3. Get network signals (hot tokens from other bots)
    // 4. Compare local performance vs swarm
    // ═══════════════════════════════════════════════════════════════════════════════
    
    /**
     * V4.0: Get aggregated collective stats from ALL trades in the network.
     * This is the TRUE collective performance - not just local device.
     */
    data class CollectiveStats(
        val totalTrades: Int,
        val totalBuys: Int,
        val totalSells: Int,
        val totalWins: Int,
        val totalLosses: Int,
        val winRate: Double,
        val avgPnlPct: Double,
        val totalProfitPct: Double,
        val totalLossPct: Double,
        val activeUsers24h: Int,
        val lastUpdated: Long
    )
    
    suspend fun getCollectiveStats(): CollectiveStats {
        if (!isEnabled()) {
            Log.w(TAG, "📊 getCollectiveStats: DISABLED (client=${client != null}, init=$isInitialized)")
            return CollectiveStats(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 1, 0)
        }
        
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "📊 Querying collective_trades...")
                
                // Query aggregated stats from collective_trades
                val result = client!!.query("""
                    SELECT 
                        COUNT(*) as total_trades,
                        SUM(CASE WHEN side = 'BUY' THEN 1 ELSE 0 END) as total_buys,
                        SUM(CASE WHEN side = 'SELL' THEN 1 ELSE 0 END) as total_sells,
                        SUM(CASE WHEN side = 'SELL' AND is_win = 1 THEN 1 ELSE 0 END) as total_wins,
                        SUM(CASE WHEN side = 'SELL' AND is_win = 0 THEN 1 ELSE 0 END) as total_losses,
                        AVG(CASE WHEN side = 'SELL' THEN pnl_pct ELSE NULL END) as avg_pnl,
                        SUM(CASE WHEN side = 'SELL' AND pnl_pct > 0 THEN pnl_pct ELSE 0 END) as total_profit,
                        SUM(CASE WHEN side = 'SELL' AND pnl_pct < 0 THEN pnl_pct ELSE 0 END) as total_loss
                    FROM collective_trades
                """.trimIndent())
                
                Log.d(TAG, "📊 Query result: success=${result.success}, rows=${result.rows.size}, error=${result.error}")
                
                // V5.2.10: Debug log raw row data
                if (result.rows.isNotEmpty()) {
                    Log.d(TAG, "📊 RAW ROW DATA: ${result.rows[0]}")
                }
                
                if (result.success && result.rows.isNotEmpty()) {
                    val row = result.rows[0]
                    // V5.2.10: Try multiple casting approaches for Turso data
                    val totalTrades = row["total_trades"]?.let {
                        when (it) {
                            is Number -> it.toInt()
                            is String -> it.toIntOrNull() ?: 0
                            else -> {
                                Log.w(TAG, "📊 total_trades unexpected type: ${it::class.simpleName}")
                                0
                            }
                        }
                    } ?: 0
                    val totalSells = (row["total_sells"] as? Number)?.toInt() 
                        ?: (row["total_sells"] as? String)?.toIntOrNull() ?: 0
                    val totalWins = (row["total_wins"] as? Number)?.toInt()
                        ?: (row["total_wins"] as? String)?.toIntOrNull() ?: 0
                    
                    val activeUsers = getActiveUsersCount()
                    
                    Log.i(TAG, "📊 COLLECTIVE STATS: trades=$totalTrades, sells=$totalSells, wins=$totalWins, users=$activeUsers")
                    
                    // V5.2.10: Safe parsing helper for all field types
                    fun parseIntField(value: Any?): Int = when (value) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull() ?: 0
                        null -> 0
                        else -> 0
                    }
                    fun parseDoubleField(value: Any?): Double = when (value) {
                        is Number -> value.toDouble()
                        is String -> value.toDoubleOrNull() ?: 0.0
                        null -> 0.0
                        else -> 0.0
                    }
                    
                    CollectiveStats(
                        totalTrades = totalTrades,
                        totalBuys = parseIntField(row["total_buys"]),
                        totalSells = totalSells,
                        totalWins = totalWins,
                        totalLosses = parseIntField(row["total_losses"]),
                        winRate = if (totalSells > 0) (totalWins.toDouble() / totalSells * 100) else 0.0,
                        avgPnlPct = parseDoubleField(row["avg_pnl"]),
                        totalProfitPct = parseDoubleField(row["total_profit"]),
                        totalLossPct = parseDoubleField(row["total_loss"]),
                        activeUsers24h = activeUsers,
                        lastUpdated = System.currentTimeMillis()
                    )
                } else {
                    Log.w(TAG, "📊 COLLECTIVE EMPTY: No rows returned from collective_trades")
                    CollectiveStats(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 1, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "📊 getCollectiveStats ERROR: ${e.message}", e)
                CollectiveStats(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 1, 0)
            }
        }
    }
    
    /**
     * V4.0: Get top performing modes from collective trades.
     * Returns modes ranked by win rate with enough sample size.
     */
    data class ModeRanking(
        val modeName: String,
        val trades: Int,
        val wins: Int,
        val winRate: Double,
        val avgPnlPct: Double,
        val rank: Int
    )
    
    suspend fun getTopModes(limit: Int = 5): List<ModeRanking> {
        if (!isEnabled()) return emptyList()
        
        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query("""
                    SELECT 
                        mode,
                        COUNT(*) as trades,
                        SUM(CASE WHEN is_win = 1 THEN 1 ELSE 0 END) as wins,
                        AVG(pnl_pct) as avg_pnl
                    FROM collective_trades 
                    WHERE side = 'SELL'
                    GROUP BY mode
                    HAVING COUNT(*) >= 5
                    ORDER BY (SUM(CASE WHEN is_win = 1 THEN 1 ELSE 0 END) * 1.0 / COUNT(*)) DESC
                    LIMIT $limit
                """.trimIndent())
                
                if (result.success) {
                    result.rows.mapIndexed { index, row ->
                        val trades = (row["trades"] as? Number)?.toInt() ?: 0
                        val wins = (row["wins"] as? Number)?.toInt() ?: 0
                        ModeRanking(
                            modeName = row["mode"] as? String ?: "UNKNOWN",
                            trades = trades,
                            wins = wins,
                            winRate = if (trades > 0) (wins.toDouble() / trades * 100) else 0.0,
                            avgPnlPct = (row["avg_pnl"] as? Number)?.toDouble() ?: 0.0,
                            rank = index + 1
                        )
                    }
                } else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "getTopModes error: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * V4.0: Get modes to AVOID (worst performing).
     */
    suspend fun getModesToAvoid(limit: Int = 3): List<ModeRanking> {
        if (!isEnabled()) return emptyList()
        
        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query("""
                    SELECT 
                        mode,
                        COUNT(*) as trades,
                        SUM(CASE WHEN is_win = 1 THEN 1 ELSE 0 END) as wins,
                        AVG(pnl_pct) as avg_pnl
                    FROM collective_trades 
                    WHERE side = 'SELL'
                    GROUP BY mode
                    HAVING COUNT(*) >= 5
                    ORDER BY AVG(pnl_pct) ASC
                    LIMIT $limit
                """.trimIndent())
                
                if (result.success) {
                    result.rows.mapIndexed { index, row ->
                        val trades = (row["trades"] as? Number)?.toInt() ?: 0
                        val wins = (row["wins"] as? Number)?.toInt() ?: 0
                        ModeRanking(
                            modeName = row["mode"] as? String ?: "UNKNOWN",
                            trades = trades,
                            wins = wins,
                            winRate = if (trades > 0) (wins.toDouble() / trades * 100) else 0.0,
                            avgPnlPct = (row["avg_pnl"] as? Number)?.toDouble() ?: 0.0,
                            rank = index + 1
                        )
                    }
                } else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "getModesToAvoid error: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * V4.0: Get hot tokens - tokens that are WINNING right now across the network.
     * Other bots can add these to their watchlist!
     */
    data class HotToken(
        val symbol: String,
        val trades: Int,
        val wins: Int,
        val winRate: Double,
        val avgPnlPct: Double,
        val lastTradeTime: Long,
        val botsTrading: Int
    )
    
    suspend fun getHotTokens(hoursBack: Int = 6, limit: Int = 10): List<HotToken> {
        if (!isEnabled()) return emptyList()
        
        return withContext(Dispatchers.IO) {
            try {
                val cutoffTime = System.currentTimeMillis() - (hoursBack * 60 * 60 * 1000L)
                
                val result = client!!.query("""
                    SELECT 
                        symbol,
                        COUNT(*) as trades,
                        SUM(CASE WHEN is_win = 1 THEN 1 ELSE 0 END) as wins,
                        AVG(pnl_pct) as avg_pnl,
                        MAX(timestamp) as last_trade,
                        COUNT(DISTINCT instance_id) as bots_trading
                    FROM collective_trades 
                    WHERE side = 'SELL' AND timestamp > $cutoffTime AND is_win = 1
                    GROUP BY symbol
                    HAVING COUNT(*) >= 2 AND AVG(pnl_pct) > 5
                    ORDER BY AVG(pnl_pct) DESC
                    LIMIT $limit
                """.trimIndent())
                
                if (result.success) {
                    result.rows.map { row ->
                        val trades = (row["trades"] as? Number)?.toInt() ?: 0
                        val wins = (row["wins"] as? Number)?.toInt() ?: 0
                        HotToken(
                            symbol = row["symbol"] as? String ?: "???",
                            trades = trades,
                            wins = wins,
                            winRate = if (trades > 0) (wins.toDouble() / trades * 100) else 0.0,
                            avgPnlPct = (row["avg_pnl"] as? Number)?.toDouble() ?: 0.0,
                            lastTradeTime = (row["last_trade"] as? Number)?.toLong() ?: 0,
                            botsTrading = (row["bots_trading"] as? Number)?.toInt() ?: 1
                        )
                    }
                } else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "getHotTokens error: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * V4.0: BROADCAST a hot token to the entire network!
     * Call this when your bot finds a huge winner (>20% gain).
     * Other bots will receive this and can add to their watchlist.
     */
    data class NetworkSignal(
        val id: Long,
        val signalType: String,      // "HOT_TOKEN", "AVOID", "WHALE_ALERT"
        val mint: String,
        val symbol: String,
        val broadcasterId: String,
        val timestamp: Long,
        val pnlPct: Double,
        val confidence: Int,
        val liquidityUsd: Double,
        val mode: String,
        val reason: String,
        val expiresAt: Long,
        val ackCount: Int
    )
    
    suspend fun broadcastHotToken(
        mint: String,
        symbol: String,
        pnlPct: Double,
        confidence: Int,
        liquidityUsd: Double,
        mode: String,
        reason: String = "BIG_WINNER"
    ): Boolean {
        if (!isEnabled()) return false
        
        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val expiresAt = now + (2 * 60 * 60 * 1000L)  // Expires in 2 hours
                
                val signalType = when {
                    pnlPct >= 50 -> "MEGA_WINNER"
                    pnlPct >= 20 -> "HOT_TOKEN"
                    pnlPct <= -15 -> "AVOID"
                    else -> "INFO"
                }
                
                val result = client!!.execute("""
                    INSERT INTO network_signals 
                        (signal_type, mint, symbol, broadcaster_id, timestamp, pnl_pct, 
                         confidence, liquidity_usd, mode, reason, expires_at, ack_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent(), listOf(
                    signalType, mint, symbol, instanceId, now, pnlPct,
                    confidence, liquidityUsd, mode, reason, expiresAt
                ))
                
                if (result.success) {
                    Log.i(TAG, "📡 BROADCAST: $signalType $symbol +${pnlPct.toInt()}% → NETWORK")
                }
                
                result.success
            } catch (e: Exception) {
                Log.e(TAG, "broadcastHotToken error: ${e.message}")
                false
            }
        }
    }
    
    /**
     * V4.0: Get network signals from other bots.
     * Returns hot tokens that other bots have broadcast.
     * Your bot should add these to watchlist and potentially trade them!
     */
    suspend fun getNetworkSignals(limit: Int = 20): List<NetworkSignal> {
        if (!isEnabled()) return emptyList()
        
        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                
                val result = client!!.query("""
                    SELECT * FROM network_signals 
                    WHERE expires_at > $now 
                      AND broadcaster_id != '$instanceId'
                    ORDER BY pnl_pct DESC, timestamp DESC
                    LIMIT $limit
                """.trimIndent())
                
                if (result.success) {
                    result.rows.map { row ->
                        NetworkSignal(
                            id = (row["id"] as? Number)?.toLong() ?: 0,
                            signalType = row["signal_type"] as? String ?: "",
                            mint = row["mint"] as? String ?: "",
                            symbol = row["symbol"] as? String ?: "",
                            broadcasterId = row["broadcaster_id"] as? String ?: "",
                            timestamp = (row["timestamp"] as? Number)?.toLong() ?: 0,
                            pnlPct = (row["pnl_pct"] as? Number)?.toDouble() ?: 0.0,
                            confidence = (row["confidence"] as? Number)?.toInt() ?: 0,
                            liquidityUsd = (row["liquidity_usd"] as? Number)?.toDouble() ?: 0.0,
                            mode = row["mode"] as? String ?: "",
                            reason = row["reason"] as? String ?: "",
                            expiresAt = (row["expires_at"] as? Number)?.toLong() ?: 0,
                            ackCount = (row["ack_count"] as? Number)?.toInt() ?: 0
                        )
                    }
                } else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "getNetworkSignals error: ${e.message}")
                emptyList()
            }
        }
    }
    
    /**
     * V4.0: Acknowledge a network signal (increment ack_count).
     * Call this when your bot acts on a signal from the network.
     */
    suspend fun acknowledgeSignal(signalId: Long) {
        if (!isEnabled()) return
        
        withContext(Dispatchers.IO) {
            try {
                client!!.execute("""
                    UPDATE network_signals 
                    SET ack_count = ack_count + 1 
                    WHERE id = $signalId
                """.trimIndent())
            } catch (_: Exception) {}
        }
    }
    
    /**
     * V4.0: Check if a mint has a hot signal on the network.
     * Returns boost points if other bots are winning with this token.
     */
    suspend fun getNetworkBoostForMint(mint: String): Int {
        if (!isEnabled()) return 0
        
        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                
                val result = client!!.query("""
                    SELECT signal_type, pnl_pct, ack_count 
                    FROM network_signals 
                    WHERE mint = ? AND expires_at > $now
                    ORDER BY pnl_pct DESC
                    LIMIT 1
                """.trimIndent(), listOf(mint))
                
                if (result.success && result.rows.isNotEmpty()) {
                    val row = result.rows[0]
                    val signalType = row["signal_type"] as? String ?: ""
                    val pnlPct = (row["pnl_pct"] as? Number)?.toDouble() ?: 0.0
                    val ackCount = (row["ack_count"] as? Number)?.toInt() ?: 0
                    
                    // Calculate boost based on signal strength
                    when {
                        signalType == "MEGA_WINNER" -> 20 + (ackCount * 2).coerceAtMost(10)
                        signalType == "HOT_TOKEN" -> 12 + (ackCount * 2).coerceAtMost(8)
                        signalType == "AVOID" -> -15
                        pnlPct > 30 -> 15
                        pnlPct > 15 -> 10
                        pnlPct > 5 -> 5
                        pnlPct < -10 -> -10
                        else -> 0
                    }
                } else 0
            } catch (e: Exception) {
                0
            }
        }
    }
    
    /**
     * V4.0: Clean up expired network signals.
     */
    suspend fun cleanupExpiredSignals() {
        if (!isEnabled()) return
        
        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val result = client!!.execute("""
                    DELETE FROM network_signals WHERE expires_at < $now
                """.trimIndent())
                
                if (result.success && (result.rowsAffected ?: 0) > 0) {
                    Log.d(TAG, "🧹 Cleaned ${result.rowsAffected} expired network signals")
                }
            } catch (_: Exception) {}
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V3.3: PERSISTENT LOCAL CACHE (SharedPreferences)
    // 
    // Downloaded collective data is SAVED to local storage so that new app 
    // instances inherit the "hive mind" immediately, even before network sync.
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Save cached data to SharedPreferences for persistence across restarts.
     */
    private fun saveCacheToPrefs() {
        val p = prefs ?: return
        
        try {
            val editor = p.edit()
            
            // Save blacklist as JSON array
            val blacklistJson = org.json.JSONArray(cachedBlacklist.toList()).toString()
            editor.putString(KEY_BLACKLIST, blacklistJson)
            
            // Save patterns as JSON object (simplified - just key fields)
            val patternsJson = org.json.JSONObject()
            cachedPatterns.forEach { (hash, pattern) ->
                val obj = org.json.JSONObject()
                obj.put("patternType", pattern.patternType)
                obj.put("discoverySource", pattern.discoverySource)
                obj.put("liquidityBucket", pattern.liquidityBucket)
                obj.put("emaTrend", pattern.emaTrend)
                obj.put("totalTrades", pattern.totalTrades)
                obj.put("wins", pattern.wins)
                obj.put("losses", pattern.losses)
                obj.put("avgPnlPct", pattern.avgPnlPct)
                obj.put("avgHoldMins", pattern.avgHoldMins)
                obj.put("lastUpdated", pattern.lastUpdated)
                patternsJson.put(hash, obj)
            }
            editor.putString(KEY_PATTERNS, patternsJson.toString())
            
            // Save mode stats as JSON object
            val modeStatsJson = org.json.JSONObject()
            cachedModeStats.forEach { (key, stat) ->
                val obj = org.json.JSONObject()
                obj.put("modeName", stat.modeName)
                obj.put("marketCondition", stat.marketCondition)
                obj.put("liquidityBucket", stat.liquidityBucket)
                obj.put("totalTrades", stat.totalTrades)
                obj.put("wins", stat.wins)
                obj.put("losses", stat.losses)
                obj.put("avgPnlPct", stat.avgPnlPct)
                obj.put("avgHoldMins", stat.avgHoldMins)
                obj.put("lastUpdated", stat.lastUpdated)
                modeStatsJson.put(key, obj)
            }
            editor.putString(KEY_MODE_STATS, modeStatsJson.toString())
            
            editor.putLong(KEY_LAST_SYNC, lastSyncTime)
            
            // Use commit() for synchronous write (ensures data is saved)
            editor.commit()
            
            Log.i(TAG, "💾 CACHE SAVED: ${cachedBlacklist.size} blacklist | ${cachedPatterns.size} patterns | ${cachedModeStats.size} mode stats")
        } catch (e: Exception) {
            Log.e(TAG, "saveCacheToPrefs error: ${e.message}")
        }
    }
    
    /**
     * Load cached data from SharedPreferences.
     */
    private fun loadCacheFromPrefs() {
        val p = prefs ?: return
        
        try {
            // Load blacklist
            val blacklistJson = p.getString(KEY_BLACKLIST, null)
            if (blacklistJson != null) {
                val arr = org.json.JSONArray(blacklistJson)
                cachedBlacklist.clear()
                for (i in 0 until arr.length()) {
                    cachedBlacklist.add(arr.getString(i))
                }
            }
            
            // Load patterns
            val patternsJson = p.getString(KEY_PATTERNS, null)
            if (patternsJson != null) {
                val obj = org.json.JSONObject(patternsJson)
                cachedPatterns.clear()
                obj.keys().forEach { hash ->
                    val pObj = obj.getJSONObject(hash)
                    val pattern = CollectivePattern(
                        id = 0,
                        patternHash = hash,
                        patternType = pObj.optString("patternType", ""),
                        discoverySource = pObj.optString("discoverySource", ""),
                        liquidityBucket = pObj.optString("liquidityBucket", ""),
                        emaTrend = pObj.optString("emaTrend", ""),
                        totalTrades = pObj.optInt("totalTrades", 0),
                        wins = pObj.optInt("wins", 0),
                        losses = pObj.optInt("losses", 0),
                        avgPnlPct = pObj.optDouble("avgPnlPct", 0.0),
                        avgHoldMins = pObj.optDouble("avgHoldMins", 0.0),
                        lastUpdated = pObj.optLong("lastUpdated", 0)
                    )
                    cachedPatterns[hash] = pattern
                }
            }
            
            // Load mode stats
            val modeStatsJson = p.getString(KEY_MODE_STATS, null)
            if (modeStatsJson != null) {
                val obj = org.json.JSONObject(modeStatsJson)
                cachedModeStats.clear()
                obj.keys().forEach { key ->
                    val mObj = obj.getJSONObject(key)
                    val stat = ModePerformance(
                        id = 0,
                        modeName = mObj.optString("modeName", ""),
                        marketCondition = mObj.optString("marketCondition", ""),
                        liquidityBucket = mObj.optString("liquidityBucket", ""),
                        totalTrades = mObj.optInt("totalTrades", 0),
                        wins = mObj.optInt("wins", 0),
                        losses = mObj.optInt("losses", 0),
                        avgPnlPct = mObj.optDouble("avgPnlPct", 0.0),
                        avgHoldMins = mObj.optDouble("avgHoldMins", 0.0),
                        lastUpdated = mObj.optLong("lastUpdated", 0)
                    )
                    cachedModeStats[key] = stat
                }
            }
            
            lastSyncTime = p.getLong(KEY_LAST_SYNC, 0)
            
            Log.i(TAG, "📂 CACHE LOADED: ${cachedBlacklist.size} blacklist | ${cachedPatterns.size} patterns | ${cachedModeStats.size} mode stats")
        } catch (e: Exception) {
            Log.e(TAG, "loadCacheFromPrefs error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V4.20: SYNC STATUS VERIFICATION
    // Returns a clear status string for UI display
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class SyncStatus(
        val isConnected: Boolean,
        val tursoConfigured: Boolean,
        val lastSyncTimeMs: Long,
        val totalUploads: Int,
        val totalDownloads: Int,
        val cachedBlacklistCount: Int,
        val cachedPatternsCount: Int,
        val cachedModeStatsCount: Int,
        val instanceId: String,
        val statusMessage: String,
    )
    
    private var totalUploadsThisSession = 0
    private var totalDownloadsThisSession = 0
    
    fun getSyncStatus(): SyncStatus {
        val tursoConfigured = client != null
        val connected = isInitialized && tursoConfigured
        
        val message = when {
            !tursoConfigured -> "❌ Turso not configured - LOCAL ONLY mode"
            !isInitialized -> "⚠️ Collective learning not initialized"
            lastSyncTime == 0L -> "🔄 Connected but never synced"
            else -> {
                val ago = (System.currentTimeMillis() - lastSyncTime) / 60000
                "✅ Connected | Last sync: ${ago}m ago"
            }
        }
        
        return SyncStatus(
            isConnected = connected,
            tursoConfigured = tursoConfigured,
            lastSyncTimeMs = lastSyncTime,
            totalUploads = totalUploadsThisSession,
            totalDownloads = totalDownloadsThisSession,
            cachedBlacklistCount = cachedBlacklist.size,
            cachedPatternsCount = cachedPatterns.size,
            cachedModeStatsCount = cachedModeStats.size,
            instanceId = instanceId,
            statusMessage = message,
        )
    }
    
    /**
     * Force an immediate sync and return detailed result.
     * Use this for manual verification.
     */
    suspend fun forceSyncNow(): String {
        if (!isEnabled()) {
            return "❌ Collective learning disabled or Turso not configured"
        }
        
        return try {
            val beforeBlacklist = cachedBlacklist.size
            val beforePatterns = cachedPatterns.size
            
            downloadAll()
            
            val afterBlacklist = cachedBlacklist.size
            val afterPatterns = cachedPatterns.size
            
            val blacklistDelta = afterBlacklist - beforeBlacklist
            val patternDelta = afterPatterns - beforePatterns
            
            totalDownloadsThisSession++
            
            """
✅ SYNC COMPLETE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Instance: $instanceId
Blacklist: $afterBlacklist (+$blacklistDelta new)
Patterns: $afterPatterns (+$patternDelta new)
Mode Stats: ${cachedModeStats.size}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            """.trimIndent()
        } catch (e: Exception) {
            "❌ Sync failed: ${e.message}"
        }
    }
    
    /**
     * V5.2 DEBUG: Direct database diagnostic query.
     * Returns detailed info about what's actually in Turso.
     */
    suspend fun runDiagnostics(): String {
        if (!isEnabled()) {
            return """
❌ COLLECTIVE DIAGNOSTICS FAILED
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Turso NOT CONFIGURED
Client: ${client != null}
Initialized: $isInitialized
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
To fix: Configure Turso DB URL and Auth Token in Settings
            """.trimIndent()
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val diagnostics = StringBuilder()
                diagnostics.appendLine("═══════════════════════════════════════")
                diagnostics.appendLine("🔬 V5.2 COLLECTIVE DIAGNOSTICS")
                diagnostics.appendLine("═══════════════════════════════════════")
                diagnostics.appendLine("Instance ID: $instanceId")
                diagnostics.appendLine("")
                
                // 1. Count total trades (ALL users)
                val totalResult = client!!.query("SELECT COUNT(*) as cnt FROM collective_trades")
                val totalTrades = if (totalResult.success && totalResult.rows.isNotEmpty()) {
                    (totalResult.rows[0]["cnt"] as? Number)?.toInt() ?: 0
                } else 0
                diagnostics.appendLine("📊 TOTAL TRADES IN DATABASE: $totalTrades")
                
                // 2. Count MY trades
                val myResult = client!!.query(
                    "SELECT COUNT(*) as cnt FROM collective_trades WHERE instance_id = ?",
                    listOf(instanceId)
                )
                val myTrades = if (myResult.success && myResult.rows.isNotEmpty()) {
                    (myResult.rows[0]["cnt"] as? Number)?.toInt() ?: 0
                } else 0
                diagnostics.appendLine("📊 MY TRADES: $myTrades")
                
                // 3. Count OTHER users' trades
                val othersResult = client!!.query(
                    "SELECT COUNT(*) as cnt FROM collective_trades WHERE instance_id != ?",
                    listOf(instanceId)
                )
                val othersTrades = if (othersResult.success && othersResult.rows.isNotEmpty()) {
                    (othersResult.rows[0]["cnt"] as? Number)?.toInt() ?: 0
                } else 0
                diagnostics.appendLine("📊 OTHER USERS' TRADES: $othersTrades")
                
                // 4. Count distinct users
                val usersResult = client!!.query("SELECT COUNT(DISTINCT instance_id) as cnt FROM collective_trades")
                val distinctUsers = if (usersResult.success && usersResult.rows.isNotEmpty()) {
                    (usersResult.rows[0]["cnt"] as? Number)?.toInt() ?: 0
                } else 0
                diagnostics.appendLine("👥 DISTINCT USERS: $distinctUsers")
                
                // 5. Show last 5 trades (all users, anonymized)
                diagnostics.appendLine("")
                diagnostics.appendLine("📝 LAST 5 COLLECTIVE TRADES:")
                val recentResult = client!!.query("""
                    SELECT timestamp, side, symbol, mode, pnl_pct, instance_id
                    FROM collective_trades 
                    ORDER BY timestamp DESC 
                    LIMIT 5
                """.trimIndent())
                
                if (recentResult.success && recentResult.rows.isNotEmpty()) {
                    for (row in recentResult.rows) {
                        val ts = row["timestamp"] as? Number ?: 0
                        val side = row["side"] as? String ?: "?"
                        val symbol = row["symbol"] as? String ?: "?"
                        val mode = row["mode"] as? String ?: "?"
                        val pnl = (row["pnl_pct"] as? Number)?.toDouble() ?: 0.0
                        val inst = (row["instance_id"] as? String)?.take(8) ?: "?"
                        
                        val isMe = inst == instanceId.take(8)
                        val marker = if (isMe) "👤" else "🌐"
                        val ago = (System.currentTimeMillis() - ts.toLong()) / 60000
                        
                        diagnostics.appendLine("  $marker $side $symbol ($mode) ${if (side == "SELL") "${pnl.toInt()}%" else ""} | ${ago}m ago")
                    }
                } else {
                    diagnostics.appendLine("  (no trades found)")
                }
                
                // 6. Check table existence
                diagnostics.appendLine("")
                diagnostics.appendLine("🔧 TABLE CHECK:")
                val tablesResult = client!!.query("SELECT name FROM sqlite_master WHERE type='table'")
                if (tablesResult.success) {
                    val tables = tablesResult.rows.mapNotNull { it["name"] as? String }
                    diagnostics.appendLine("  Tables: ${tables.joinToString(", ")}")
                }
                
                diagnostics.appendLine("")
                diagnostics.appendLine("═══════════════════════════════════════")
                
                diagnostics.toString()
            } catch (e: Exception) {
                """
❌ DIAGNOSTICS ERROR
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Error: ${e.message}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                """.trimIndent()
            }
        }
    }
}


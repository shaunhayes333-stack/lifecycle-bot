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
    
    // Sync interval (15 minutes)
    private const val SYNC_INTERVAL_MS = 15 * 60 * 1000L
    
    // Local cache of collective data
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
        if (isInitialized) {
            Log.d(TAG, "Already initialized")
            return true
        }
        
        try {
            val config = ConfigStore.load(ctx)
            val dbUrl = config.tursoDbUrl
            val authToken = config.tursoAuthToken
            
            if (dbUrl.isBlank() || authToken.isBlank()) {
                Log.w(TAG, "Turso credentials not configured - collective learning disabled")
                return false
            }
            
            client = TursoClient(dbUrl, authToken)
            
            // Test connection
            if (!client!!.testConnection()) {
                Log.e(TAG, "Failed to connect to Turso database")
                client = null
                return false
            }
            
            // Initialize schema
            if (!client!!.initSchema()) {
                Log.e(TAG, "Failed to initialize schema")
                client = null
                return false
            }
            
            isInitialized = true
            Log.i(TAG, "✅ Collective learning initialized successfully")
            
            // Initial sync
            downloadAll()
            
            // Start background sync
            startBackgroundSync()
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}")
            return false
        }
    }
    
    /**
     * Check if collective learning is enabled and connected.
     */
    fun isEnabled(): Boolean = isInitialized && client != null
    
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
            val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000L)
            
            val result = client!!.query(
                "SELECT COUNT(*) as count FROM instance_heartbeats WHERE last_heartbeat > ?",
                listOf(fifteenMinsAgo)
            )
            
            if (result.success && result.rows.isNotEmpty()) {
                val count = (result.rows[0]["count"] as? Number)?.toInt() ?: 1
                Log.d(TAG, "📊 Active instances: $count")
                return count.coerceAtLeast(1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Count instances error: ${e.message}")
        }
        
        return 1  // Fallback to at least this instance
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
            
            // V3.2: Count active instances
            val activeInstances = countActiveInstances()
            
            Log.i(TAG, "📥 Full sync completed (${activeInstances} active instances)")
            
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
            "blacklistedTokens" to cachedBlacklist.size,
            "patterns" to cachedPatterns.size,
            "modeStats" to cachedModeStats.size,
            "whaleStats" to cachedWhaleStats.size,
            "lastSyncTime" to lastSyncTime,
        )
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
}

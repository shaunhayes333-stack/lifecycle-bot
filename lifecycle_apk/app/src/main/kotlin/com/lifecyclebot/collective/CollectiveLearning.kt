package com.lifecyclebot.collective

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Collective Learning
 *
 * Main orchestrator for the shared knowledge base.
 */
object CollectiveLearning {

    private const val TAG = "CollectiveLearning"

    private var client: TursoClient? = null
    private var isInitialized = false
    private var lastSyncTime = 0L

    private var appContext: Context? = null
    private var lastReconnectAttempt = 0L
    private const val RECONNECT_COOLDOWN_MS = 60_000L

    private var instanceId: String = ""
    
    /** V5.7.3: Public getter for instance ID (used by perps learning) */
    fun getInstanceId(): String? = instanceId.takeIf { it.isNotBlank() }
    
    /** V5.7.3: Public getter for TursoClient (used by perps learning) */
    fun getClient(): TursoClient? = client

    private const val SYNC_INTERVAL_MS = 15 * 60 * 1000L

    private var totalUploadAttemptsThisSession = 0
    private var totalUploadSuccessThisSession = 0
    private var totalUploadSkippedThisSession = 0
    private var totalUploadsThisSession = 0
    private var totalDownloadsThisSession = 0

    private const val PREFS_NAME = "collective_learning_cache"
    private const val KEY_BLACKLIST = "cached_blacklist_json"
    private const val KEY_PATTERNS = "cached_patterns_json"
    private const val KEY_MODE_STATS = "cached_mode_stats_json"
    private const val KEY_LAST_SYNC = "last_sync_time"
    private const val KEY_INSTANCE_ID = "instance_id"

    private var prefs: SharedPreferences? = null

    private val cachedBlacklist = mutableSetOf<String>()
    private val cachedPatterns = mutableMapOf<String, CollectivePattern>()
    private val cachedModeStats = mutableMapOf<String, ModePerformance>()
    private val cachedWhaleStats = mutableMapOf<String, WhaleEffectiveness>()

    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun init(ctx: Context): Boolean {
        Log.i(TAG, "INIT CALLED | alreadyInit=$isInitialized | clientExists=${client != null}")

        if (isInitialized && client != null) {
            Log.d(TAG, "Already initialized and connected")
            return true
        }

        appContext = ctx.applicationContext

        return try {
            prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            instanceId = prefs?.getString(KEY_INSTANCE_ID, null) ?: run {
                val newId = sha256(
                    ctx.packageName + "|" +
                        System.currentTimeMillis() + "|" +
                        android.os.Build.FINGERPRINT
                ).take(16)
                prefs?.edit()?.putString(KEY_INSTANCE_ID, newId)?.apply()
                newId
            }

            Log.i(TAG, "Instance ID: $instanceId")

            loadCacheFromPrefs()
            Log.i(
                TAG,
                "Loaded ${cachedBlacklist.size} blacklist + ${cachedPatterns.size} patterns from LOCAL cache"
            )

            val config = ConfigStore.load(ctx)
            val dbUrl = config.tursoDbUrl
            val authToken = config.tursoAuthToken

            Log.i(
                TAG,
                "INIT: dbUrl=${dbUrl.take(50)}... authToken=${authToken.take(20)}..."
            )

            if (dbUrl.isBlank() || authToken.isBlank()) {
                Log.w(TAG, "Turso credentials not configured - using LOCAL CACHE ONLY")
                if (cachedBlacklist.isNotEmpty() || cachedPatterns.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "Local collective cache available (${cachedBlacklist.size} blacklist, ${cachedPatterns.size} patterns)"
                    )
                }
                return false
            }

            client = TursoClient(dbUrl, authToken)
            Log.i(TAG, "TursoClient created, testing connection...")

            var connectionSuccess = false
            for (attempt in 1..3) {
                try {
                    if (client!!.testConnection()) {
                        connectionSuccess = true
                        break
                    }
                    Log.w(TAG, "Connection attempt $attempt/3 failed, retrying...")
                    if (attempt < 3) {
                        delay(attempt * 1000L)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Connection attempt $attempt error: ${e.message}")
                }
            }

            if (!connectionSuccess) {
                Log.e(TAG, "TURSO CONNECTION FAILED after 3 attempts - using LOCAL CACHE")
                client = null
                return false
            }

            Log.i(TAG, "Turso connection successful!")

            if (!client!!.initSchema()) {
                Log.e(TAG, "Failed to initialize schema")
                client = null
                return false
            }

            isInitialized = true
            Log.i(TAG, "COLLECTIVE LEARNING ONLINE - HIVE MIND ACTIVE")

            downloadAll()

            try {
                Log.i(TAG, "Triggering CollectiveIntelligenceAI refresh...")
                com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.refresh()
                Log.i(TAG, "CollectiveIntelligenceAI caches populated")
            } catch (e: Exception) {
                Log.w(TAG, "CollectiveIntelligenceAI refresh warning: ${e.message}")
            }

            startBackgroundSync()
            registerInstance(ctx)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Init error: ${e.message}", e)
            false
        }
    }

    private suspend fun registerInstance(ctx: Context) {
        if (!isEnabled()) return

        try {
            val now = System.currentTimeMillis()
            val isoTimestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(now))

            val deviceInfo = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
            val appVersion = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
            } catch (_: Exception) {
                "unknown"
            }

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

            val result = client?.execute(
                sql,
                listOf(instanceId, now, isoTimestamp, deviceInfo, appVersion, now, now, appVersion)
            )

            if (result?.success == true) {
                Log.i(TAG, "Instance registered: $instanceId (installed: $isoTimestamp)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instance registration warning: ${e.message}")
        }
    }

    fun isEnabled(): Boolean {
        val enabled = isInitialized && client != null
        if (!enabled) {
            Log.d(TAG, "isEnabled=false | init=$isInitialized | client=${client != null}")
        }
        return enabled
    }

    suspend fun ensureConnected(): Boolean {
        if (isEnabled()) return true

        val now = System.currentTimeMillis()
        if (now - lastReconnectAttempt < RECONNECT_COOLDOWN_MS) {
            return false
        }
        lastReconnectAttempt = now

        val ctx = appContext ?: return false
        Log.i(TAG, "Attempting to reconnect to Turso...")

        isInitialized = false
        client = null

        return try {
            init(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "Reconnect failed: ${e.message}")
            false
        }
    }

    fun shutdown() {
        syncJob?.cancel()
        syncJob = null
        client = null
        isInitialized = false
        Log.i(TAG, "Collective learning shutdown")
    }

    suspend fun getDiagnostics(): CollectiveDiagnostics {
        val connected = isEnabled()
        var canQuery = false
        var tableCount = 0
        var tradeCount = 0
        var errorMessage: String? = null

        if (connected) {
            try {
                val testResult = client?.query("SELECT 1 as test")
                canQuery = testResult?.success == true

                val tablesResult = client?.query("SELECT name FROM sqlite_master WHERE type='table'")
                tableCount = tablesResult?.rows?.size ?: 0

                val tradesResult = client?.query("SELECT COUNT(*) as cnt FROM collective_trades")
                tradeCount = parseInt(tradesResult?.rows?.firstOrNull()?.get("cnt"))
            } catch (e: Exception) {
                errorMessage = e.message
            }
        }

        return CollectiveDiagnostics(
            isConnected = connected,
            canQuery = canQuery,
            tableCount = tableCount,
            tradeCount = tradeCount,
            instanceId = if (instanceId.isNotBlank()) instanceId.take(8) + "..." else "",
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

    suspend fun uploadPatternOutcome(
        patternType: String,
        discoverySource: String,
        liquidityBucket: String,
        emaTrend: String,
        isWin: Boolean,
        pnlPct: Double,
        holdMins: Double
    ) {
        if (!isEnabled()) return

        try {
            val patternHash = hashPattern(patternType, discoverySource, liquidityBucket, emaTrend)
            val now = System.currentTimeMillis()
            val winInc = if (isWin) 1 else 0
            val lossInc = if (isWin) 0 else 1

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

            val result = client!!.execute(
                sql,
                listOf(
                    patternHash, patternType, discoverySource, liquidityBucket, emaTrend,
                    winInc, lossInc, sanitizeDouble(pnlPct), sanitizeDouble(holdMins), now,
                    winInc, lossInc, sanitizeDouble(pnlPct), sanitizeDouble(holdMins), now
                )
            )

            if (result.success) {
                Log.d(TAG, "Uploaded pattern: $patternType (${if (isWin) "WIN" else "LOSS"})")
            } else {
                Log.w(TAG, "Failed to upload pattern: ${result.error}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload pattern error: ${e.message}")
        }
    }

    suspend fun uploadTrade(
        side: String,
        symbol: String,
        mode: String,
        source: String,
        liquidityUsd: Double,
        marketSentiment: String,
        entryScore: Int,
        confidence: Int,
        pnlPct: Double,
        holdMins: Double,
        isWin: Boolean,
        paperMode: Boolean
    ) {
        totalUploadAttemptsThisSession++

        ErrorLogger.info(
            "CollectiveTrade",
            "ATTEMPT #$totalUploadAttemptsThisSession: $side $symbol | enabled=${isEnabled()} | init=$isInitialized | client=${client != null} | inst=${instanceId.take(8)}"
        )

        if (instanceId.isBlank()) {
            totalUploadSkippedThisSession++
            ErrorLogger.error("CollectiveTrade", "SKIP: $side $symbol - instanceId BLANK")
            return
        }

        if (!isEnabled()) {
            ErrorLogger.warn("CollectiveTrade", "Reconnecting for $side $symbol...")
            ensureConnected()
            if (!isEnabled()) {
                totalUploadSkippedThisSession++
                ErrorLogger.error("CollectiveTrade", "SKIP: $side $symbol - DISABLED")
                return
            }
        }

        // Capture client in a local val to avoid TOCTOU race: isEnabled() may pass but
        // another coroutine can null out client before client!!.execute() is reached.
        val c = client ?: run {
            ErrorLogger.error("CollectiveTrade", "SKIP: $side $symbol - client became null after isEnabled check")
            return
        }

        try {
            val now = System.currentTimeMillis()
            val tradeHash = sha256("$now|$side|$symbol|$mode|${System.nanoTime()}").take(24)

            val liquidityBucket = when {
                liquidityUsd < 5_000.0 -> "MICRO"
                liquidityUsd < 25_000.0 -> "SMALL"
                liquidityUsd < 100_000.0 -> "MID"
                else -> "LARGE"
            }

            val sql = """
                INSERT OR REPLACE INTO collective_trades
                    (trade_hash, instance_id, timestamp, side, symbol, mode, source, liquidity_bucket,
                     market_sentiment, entry_score, confidence, pnl_pct, hold_mins, is_win, paper_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()

            ErrorLogger.info(
                "CollectiveTrade",
                "INSERT: $side $symbol | hash=${tradeHash.take(8)} | inst=${instanceId.take(8)}"
            )

            val result = c.execute(
                sql,
                listOf(
                    tradeHash,
                    instanceId,
                    now,
                    side,
                    symbol,
                    mode,
                    source,
                    liquidityBucket,
                    marketSentiment,
                    entryScore,
                    confidence,
                    sanitizeDouble(pnlPct),
                    sanitizeDouble(holdMins),
                    if (isWin) 1 else 0,
                    if (paperMode) 1 else 0
                )
            )

            ErrorLogger.info(
                "CollectiveTrade",
                "RESULT: $side $symbol | success=${result.success} | rows=${result.rowsAffected} | lastId=${result.lastInsertId} | err=${result.error?.take(80)}"
            )

            if (result.success && result.error.isNullOrBlank()) {
                val verify = c.query(
                    "SELECT COUNT(*) as cnt FROM collective_trades WHERE trade_hash = ?",
                    listOf(tradeHash)
                )
                val count = parseInt(verify.rows.firstOrNull()?.get("cnt"))

                if (count > 0) {
                    totalUploadSuccessThisSession++
                    totalUploadsThisSession++
                    ErrorLogger.info(
                        "CollectiveTrade",
                        "VERIFIED: $side $symbol exists in DB! [$totalUploadSuccessThisSession/$totalUploadAttemptsThisSession]"
                    )
                    updateInstanceTradeCount()
                } else {
                    ErrorLogger.error(
                        "CollectiveTrade",
                        "VERIFY FAILED: $side $symbol - INSERT returned success but row not found in DB"
                    )
                }
            } else {
                ErrorLogger.error("CollectiveTrade", "FAILED: $side $symbol | err=${result.error}")
            }
        } catch (e: Exception) {
            ErrorLogger.error("CollectiveTrade", "EXCEPTION: $side $symbol | ${e.message}")
        }
    }

    private suspend fun updateInstanceTradeCount() {
        try {
            val now = System.currentTimeMillis()
            client?.execute(
                """
                UPDATE instance_registry
                SET total_trades = total_trades + 1, last_active = ?
                WHERE instance_id = ?
                """.trimIndent(),
                listOf(now, instanceId)
            )
        } catch (_: Exception) {
        }
    }

    suspend fun getCollectiveTotalTradeCount(): Int {
        if (!isEnabled()) return 0

        return try {
            val result = client?.query("SELECT COUNT(*) as cnt FROM collective_trades")
            if (result?.success == true && result.rows.isNotEmpty()) {
                parseInt(result.rows[0]["cnt"])
            } else {
                0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get trade count: ${e.message}")
            0
        }
    }

    suspend fun reportBlacklistedToken(
        mint: String,
        symbol: String,
        reason: String,
        severity: Int
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

            val result = client!!.execute(
                sql,
                listOf(mint, symbol, reason, now, now, severity, now, severity)
            )

            if (result.success) {
                cachedBlacklist.add(mint)
                Log.i(TAG, "Reported blacklist: $symbol ($reason)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Report blacklist error: ${e.message}")
        }
    }

    suspend fun uploadModePerformance(
        modeName: String,
        marketCondition: String,
        liquidityBucket: String,
        totalTrades: Int,
        wins: Int,
        losses: Int,
        avgPnlPct: Double,
        avgHoldMins: Double
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

            client!!.execute(
                sql,
                listOf(
                    modeName, marketCondition, liquidityBucket, totalTrades, wins, losses,
                    sanitizeDouble(avgPnlPct), sanitizeDouble(avgHoldMins), now,
                    totalTrades, wins, losses,
                    sanitizeDouble(avgPnlPct), totalTrades, totalTrades,
                    sanitizeDouble(avgHoldMins), totalTrades, totalTrades,
                    now
                )
            )

            Log.d(TAG, "Uploaded mode stats: $modeName ($marketCondition)")
        } catch (e: Exception) {
            Log.e(TAG, "Upload mode stats error: ${e.message}")
        }
    }

    suspend fun uploadWhaleEffectiveness(
        walletAddress: String,
        isProfitable: Boolean,
        pnlPct: Double,
        leadTimeSec: Int
    ) {
        if (!isEnabled()) return

        try {
            val walletHash = hashWallet(walletAddress)
            val now = System.currentTimeMillis()
            val profitInc = if (isProfitable) 1 else 0

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

            client!!.execute(
                sql,
                listOf(
                    walletHash, profitInc, sanitizeDouble(pnlPct), leadTimeSec, now,
                    profitInc, sanitizeDouble(pnlPct), leadTimeSec, now
                )
            )

            Log.d(TAG, "Uploaded whale stats: ${walletHash.take(8)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Upload whale stats error: ${e.message}")
        }
    }

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
            """.trimIndent()

            client!!.execute(
                sql,
                listOf(
                    instanceId,
                    now,
                    appVersion,
                    if (paperMode) 1 else 0,
                    trades24h,
                    sanitizeDouble(pnl24hPct)
                )
            )

            Log.d(TAG, "Heartbeat sent: $instanceId")
        } catch (e: Exception) {
            Log.e(TAG, "Heartbeat error: ${e.message}")
        }
    }

    suspend fun countActiveInstances(): Int {
        if (!isEnabled()) return 1

        try {
            val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)

            val registryResult = client!!.query(
                "SELECT COUNT(*) as count FROM instance_registry WHERE last_active > ? AND total_trades > 0",
                listOf(oneDayAgo)
            )

            if (registryResult.success && registryResult.rows.isNotEmpty()) {
                val activeTraders = parseInt(registryResult.rows[0]["count"])
                if (activeTraders > 0) {
                    Log.d(TAG, "Active trading users (24h): $activeTraders")
                    return activeTraders.coerceAtLeast(1)
                }
            }

            val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000L)
            val heartbeatResult = client!!.query(
                "SELECT COUNT(*) as count FROM instance_heartbeats WHERE last_heartbeat > ?",
                listOf(fifteenMinsAgo)
            )

            if (heartbeatResult.success && heartbeatResult.rows.isNotEmpty()) {
                val count = parseInt(heartbeatResult.rows[0]["count"]).coerceAtLeast(1)
                Log.d(TAG, "Active instances (heartbeat): $count")
                return count
            }
        } catch (e: Exception) {
            Log.e(TAG, "Count instances error: ${e.message}")
        }

        return 1
    }

    suspend fun getActiveUsersCount(): Int {
        if (!isEnabled()) return 1

        try {
            val totalResult = client!!.query(
                "SELECT COUNT(DISTINCT instance_id) as total, COUNT(*) as trades FROM collective_trades"
            )

            if (totalResult.success && totalResult.rows.isNotEmpty()) {
                val totalUsers = parseInt(totalResult.rows[0]["total"])
                val totalTrades = parseInt(totalResult.rows[0]["trades"])
                Log.d(TAG, "TOTAL in collective_trades: $totalUsers users, $totalTrades trades")
            }

            val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)
            val result = client!!.query(
                "SELECT COUNT(DISTINCT instance_id) as count FROM collective_trades WHERE timestamp > ?",
                listOf(oneDayAgo)
            )

            if (result.success && result.rows.isNotEmpty()) {
                val count = parseInt(result.rows[0]["count"]).coerceAtLeast(1)
                Log.d(TAG, "Active users (24h): $count")
                return count
            }
        } catch (e: Exception) {
            Log.w(TAG, "getActiveUsersCount error: ${e.message}")
        }

        return 1
    }

    suspend fun downloadAll() {
        if (!isEnabled()) return

        try {
            downloadBlacklist()
            downloadPatterns()
            downloadModeStats()
            downloadWhaleStats()

            lastSyncTime = System.currentTimeMillis()
            totalDownloadsThisSession++
            saveCacheToPrefs()

            val activeInstances = countActiveInstances()

            Log.i(
                TAG,
                "Full sync completed ($activeInstances active instances) | CACHED TO LOCAL STORAGE"
            )

            try {
                com.lifecyclebot.engine.CollectiveAnalytics.updateCollectiveStats(
                    totalPatterns = cachedPatterns.size,
                    blacklistSize = cachedBlacklist.size,
                    estimatedInstances = activeInstances
                )

                val sortedPatterns = cachedPatterns.values.filter { it.isReliable }

                val bestPatterns = sortedPatterns
                    .sortedByDescending { it.winRate }
                    .take(5)
                    .map { p ->
                        com.lifecyclebot.engine.CollectiveAnalytics.PatternStat(
                            patternType = p.patternType + "_" + p.discoverySource,
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
                            patternType = p.patternType + "_" + p.discoverySource,
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

    private suspend fun downloadBlacklist() {
        try {
            val result = client!!.query("SELECT mint FROM token_blacklist WHERE report_count >= 3")

            if (result.success) {
                cachedBlacklist.clear()
                for (row in result.rows) {
                    val mint = parseString(row["mint"])
                    if (mint.isNotBlank()) {
                        cachedBlacklist.add(mint)
                    }
                }
                Log.i(TAG, "Downloaded ${cachedBlacklist.size} blacklisted tokens")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download blacklist error: ${e.message}")
        }
    }

    private suspend fun downloadPatterns() {
        try {
            val result = client!!.query("SELECT * FROM collective_patterns WHERE total_trades >= 10")

            if (result.success) {
                cachedPatterns.clear()
                for (row in result.rows) {
                    val pattern = CollectivePattern(
                        id = parseLong(row["id"]),
                        patternHash = parseString(row["pattern_hash"]),
                        patternType = parseString(row["pattern_type"]),
                        discoverySource = parseString(row["discovery_source"]),
                        liquidityBucket = parseString(row["liquidity_bucket"]),
                        emaTrend = parseString(row["ema_trend"]),
                        totalTrades = parseInt(row["total_trades"]),
                        wins = parseInt(row["wins"]),
                        losses = parseInt(row["losses"]),
                        avgPnlPct = parseDouble(row["avg_pnl_pct"]),
                        avgHoldMins = parseDouble(row["avg_hold_mins"]),
                        lastUpdated = parseLong(row["last_updated"])
                    )
                    if (pattern.patternHash.isNotBlank()) {
                        cachedPatterns[pattern.patternHash] = pattern
                    }
                }
                Log.i(TAG, "Downloaded ${cachedPatterns.size} patterns")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download patterns error: ${e.message}")
        }
    }

    private suspend fun downloadModeStats() {
        try {
            val result = client!!.query("SELECT * FROM mode_performance WHERE total_trades >= 20")

            if (result.success) {
                cachedModeStats.clear()
                for (row in result.rows) {
                    val key =
                        parseString(row["mode_name"]) + "_" +
                            parseString(row["market_condition"]) + "_" +
                            parseString(row["liquidity_bucket"])

                    val stat = ModePerformance(
                        id = parseLong(row["id"]),
                        modeName = parseString(row["mode_name"]),
                        marketCondition = parseString(row["market_condition"]),
                        liquidityBucket = parseString(row["liquidity_bucket"]),
                        totalTrades = parseInt(row["total_trades"]),
                        wins = parseInt(row["wins"]),
                        losses = parseInt(row["losses"]),
                        avgPnlPct = parseDouble(row["avg_pnl_pct"]),
                        avgHoldMins = parseDouble(row["avg_hold_mins"]),
                        lastUpdated = parseLong(row["last_updated"])
                    )
                    cachedModeStats[key] = stat
                }
                Log.i(TAG, "Downloaded ${cachedModeStats.size} mode stats")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download mode stats error: ${e.message}")
        }
    }

    private suspend fun downloadWhaleStats() {
        try {
            val result = client!!.query("SELECT * FROM whale_effectiveness WHERE total_follows >= 5")

            if (result.success) {
                cachedWhaleStats.clear()
                for (row in result.rows) {
                    val stat = WhaleEffectiveness(
                        id = parseLong(row["id"]),
                        walletHash = parseString(row["wallet_hash"]),
                        totalFollows = parseInt(row["total_follows"]),
                        profitableFollows = parseInt(row["profitable_follows"]),
                        avgPnlPct = parseDouble(row["avg_pnl_pct"]),
                        avgLeadTimeSec = parseInt(row["avg_lead_time_sec"]),
                        lastUpdated = parseLong(row["last_updated"])
                    )
                    if (stat.walletHash.isNotBlank()) {
                        cachedWhaleStats[stat.walletHash] = stat
                    }
                }
                Log.i(TAG, "Downloaded ${cachedWhaleStats.size} whale stats")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download whale stats error: ${e.message}")
        }
    }

    fun isBlacklisted(mint: String): Boolean {
        return cachedBlacklist.contains(mint)
    }

    fun getPatternStats(
        patternType: String,
        discoverySource: String,
        liquidityBucket: String,
        emaTrend: String
    ): CollectivePattern? {
        val hash = hashPattern(patternType, discoverySource, liquidityBucket, emaTrend)
        return cachedPatterns[hash]
    }

    fun getModePerformance(
        modeName: String,
        marketCondition: String,
        liquidityBucket: String
    ): ModePerformance? {
        val key = modeName + "_" + marketCondition + "_" + liquidityBucket
        return cachedModeStats[key]
    }

    fun getWhaleEffectiveness(walletAddress: String): WhaleEffectiveness? {
        val hash = hashWallet(walletAddress)
        return cachedWhaleStats[hash]
    }

    fun getHighLossPatterns(): List<CollectivePattern> {
        return cachedPatterns.values.filter { it.isReliable && it.winRate < 30.0 }
    }

    fun getHighWinPatterns(): List<CollectivePattern> {
        return cachedPatterns.values.filter { it.isReliable && it.winRate > 60.0 }
    }

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

    private fun hashPattern(
        patternType: String,
        discoverySource: String,
        liquidityBucket: String,
        emaTrend: String
    ): String {
        val input = "$patternType|$discoverySource|$liquidityBucket|$emaTrend"
        return sha256(input).take(16)
    }

    private fun hashWallet(walletAddress: String): String {
        return sha256("AATE_WHALE_$walletAddress").take(16)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

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
            "uploadAttempts" to totalUploadAttemptsThisSession,
            "uploadSuccess" to totalUploadSuccessThisSession,
            "uploadSkipped" to totalUploadSkippedThisSession
        )
    }

    suspend fun getCollectiveTradeCount(): Int {
        if (!isEnabled()) return 0

        return try {
            val tradeCount = getCollectiveTotalTradeCount()
            if (tradeCount > 0) return tradeCount

            val fromPatterns = cachedPatterns.values.sumOf { it.totalTrades }
            val fromModes = cachedModeStats.values.sumOf { it.totalTrades }

            maxOf(fromPatterns, fromModes)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get collective trade count: ${e.message}")
            0
        }
    }

    fun getPatternScoreAdjustment(
        entryPhase: String,
        tradingMode: String,
        discoverySource: String,
        liquidityUsd: Double,
        emaTrend: String
    ): Int {
        if (!isEnabled()) return 0

        val liquidityBucket = when {
            liquidityUsd < 5_000.0 -> "MICRO"
            liquidityUsd < 25_000.0 -> "SMALL"
            liquidityUsd < 100_000.0 -> "MID"
            else -> "LARGE"
        }

        val patternType = entryPhase + "_" + tradingMode
        val stats = getPatternStats(patternType, discoverySource, liquidityBucket, emaTrend)

        if (stats == null || !stats.isReliable) return 0

        return when {
            stats.winRate >= 70.0 -> 30
            stats.winRate >= 60.0 -> 20
            stats.winRate >= 50.0 -> 10
            stats.winRate >= 40.0 -> 0
            stats.winRate >= 30.0 -> -10
            stats.winRate >= 20.0 -> -20
            else -> -30
        }
    }

    fun getRecommendedMode(
        liquidityUsd: Double,
        emaTrend: String
    ): String? {
        if (!isEnabled()) return null

        val liquidityBucket = when {
            liquidityUsd < 5_000.0 -> "MICRO"
            liquidityUsd < 25_000.0 -> "SMALL"
            liquidityUsd < 100_000.0 -> "MID"
            else -> "LARGE"
        }

        val marketCondition = when (emaTrend.uppercase(Locale.US)) {
            "BULL", "BULLISH" -> "BULL"
            "BEAR", "BEARISH" -> "BEAR"
            else -> "SIDEWAYS"
        }

        return getBestMode(marketCondition, liquidityBucket)
    }

    suspend fun syncModeLearning(modeStats: Map<String, ModeStatSnapshot>) {
        if (!isEnabled()) return

        try {
            for ((modeName, stats) in modeStats) {
                if (stats.totalTrades < 5) continue

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
            Log.i(TAG, "Synced ${modeStats.size} mode stats to collective")
        } catch (e: Exception) {
            Log.e(TAG, "Mode sync error: ${e.message}")
        }
    }

    data class ModeStatSnapshot(
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val avgPnlPct: Double,
        val avgHoldMins: Double,
        val marketCondition: String = "NEUTRAL",
        val liquidityBucket: String = "MID"
    )

    fun getInsightsSummary(): String {
        if (!isEnabled()) return "Collective: DISABLED"

        val highWin = getHighWinPatterns().size
        val highLoss = getHighLossPatterns().size
        val blacklisted = cachedBlacklist.size

        return "Collective: $blacklisted blacklisted | $highWin winning patterns | $highLoss losing patterns"
    }

    suspend fun recordLegalAgreement(record: LegalAgreementRecord): Boolean {
        if (!isEnabled()) {
            Log.w(TAG, "Collective disabled - storing legal agreement locally only")
            return true
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
                """.trimIndent()

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

                Log.i(TAG, "Legal agreement recorded: ${record.agreementType} v${record.agreementVersion}")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record legal agreement: ${e.message}")
                false
            }
        }
    }

    suspend fun getLegalAgreementCount(): Int {
        if (!isEnabled()) return 0

        return withContext(Dispatchers.IO) {
            try {
                val result = client?.query("SELECT COUNT(*) as cnt FROM legal_agreements")
                parseInt(result?.rows?.firstOrNull()?.get("cnt"))
            } catch (_: Exception) {
                0
            }
        }
    }

    data class AIPattern(
        val patternKey: String,
        val totalTrades: Int,
        val wins: Int,
        val avgPnlPct: Double,
        val instanceCount: Int,
        val lastUpdated: Long
    )

    data class CollectiveModeStats(
        val modeName: String,
        val totalTrades: Int,
        val wins: Int,
        val avgPnlPct: Double,
        val instanceCount: Int
    )

    data class CollectiveSignal(
        val mint: String,
        val signal: String,
        val instanceId: String,
        val timestamp: Long
    )

    suspend fun downloadAllPatterns(): List<AIPattern> {
        if (!isEnabled()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query(
                    """
                    SELECT pattern_hash as pattern_key, total_trades, wins, avg_pnl_pct,
                           1 as instance_count, last_updated
                    FROM collective_patterns
                    """.trimIndent()
                )

                if (result.success) {
                    result.rows.map { row ->
                        AIPattern(
                            patternKey = parseString(row["pattern_key"]),
                            totalTrades = parseInt(row["total_trades"]),
                            wins = parseInt(row["wins"]),
                            avgPnlPct = parseDouble(row["avg_pnl_pct"]),
                            instanceCount = parseInt(row["instance_count"]).coerceAtLeast(1),
                            lastUpdated = parseLong(row["last_updated"])
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

    suspend fun downloadModeStatsForAI(): Map<String, CollectiveModeStats> {
        if (!isEnabled()) return emptyMap()

        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query(
                    """
                    SELECT mode_name, SUM(total_trades) as total_trades, SUM(wins) as wins,
                           AVG(avg_pnl_pct) as avg_pnl_pct, COUNT(DISTINCT id) as instance_count
                    FROM mode_performance
                    GROUP BY mode_name
                    """.trimIndent()
                )

                if (result.success) {
                    result.rows.associate { row ->
                        val modeName = parseString(row["mode_name"])
                        modeName to CollectiveModeStats(
                            modeName = modeName,
                            totalTrades = parseInt(row["total_trades"]),
                            wins = parseInt(row["wins"]),
                            avgPnlPct = parseDouble(row["avg_pnl_pct"]),
                            instanceCount = parseInt(row["instance_count"]).coerceAtLeast(1)
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

    suspend fun downloadRecentSignals(): List<CollectiveSignal> {
        if (!isEnabled()) return emptyList()

        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                val result = client!!.query(
                    """
                    SELECT mint, signal_type as signal, broadcaster_id as instance_id, timestamp
                    FROM network_signals
                    WHERE timestamp > ? AND expires_at > ?
                    """.trimIndent(),
                    listOf(cutoff, cutoff)
                )

                if (result.success) {
                    result.rows.map { row ->
                        CollectiveSignal(
                            mint = parseString(row["mint"]),
                            signal = parseString(row["signal"]),
                            instanceId = parseString(row["instance_id"]),
                            timestamp = parseLong(row["timestamp"])
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

    suspend fun pruneOldPatterns(cutoffTime: Long): Int {
        if (!isEnabled()) return 0

        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.execute(
                    "DELETE FROM collective_patterns WHERE last_updated < ?",
                    listOf(cutoffTime)
                )

                if (result.success) {
                    val deleted = (result.rowsAffected ?: 0).toInt()
                    Log.i(TAG, "Pruned $deleted old patterns")
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
            Log.w(TAG, "getCollectiveStats: DISABLED (client=${client != null}, init=$isInitialized)")
            return CollectiveStats(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 1, 0)
        }

        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query(
                    """
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
                    """.trimIndent()
                )

                if (result.success && result.rows.isNotEmpty()) {
                    val row = result.rows[0]

                    val totalTrades = parseInt(row["total_trades"])
                    val totalSells = parseInt(row["total_sells"])
                    val totalWins = parseInt(row["total_wins"])
                    val activeUsers = getActiveUsersCount()

                    CollectiveStats(
                        totalTrades = totalTrades,
                        totalBuys = parseInt(row["total_buys"]),
                        totalSells = totalSells,
                        totalWins = totalWins,
                        totalLosses = parseInt(row["total_losses"]),
                        winRate = if (totalSells > 0) (totalWins.toDouble() / totalSells.toDouble()) * 100.0 else 0.0,
                        avgPnlPct = parseDouble(row["avg_pnl"]),
                        totalProfitPct = parseDouble(row["total_profit"]),
                        totalLossPct = parseDouble(row["total_loss"]),
                        activeUsers24h = activeUsers,
                        lastUpdated = System.currentTimeMillis()
                    )
                } else {
                    CollectiveStats(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 1, 0)
                }
            } catch (e: Exception) {
                Log.e(TAG, "getCollectiveStats ERROR: ${e.message}", e)
                CollectiveStats(0, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0.0, 1, 0)
            }
        }
    }

    data class ModeRanking(
        val modeName: String,
        val trades: Int,
        val wins: Int,
        val winRate: Double,
        val avgPnlPct: Double,
        val rank: Int
    )

    suspend fun getTopModes(limit: Int): List<ModeRanking> {
        if (!isEnabled()) return emptyList()

        val safeLimit = limit.coerceIn(1, 50)

        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query(
                    """
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
                    LIMIT $safeLimit
                    """.trimIndent()
                )

                if (result.success) {
                    result.rows.mapIndexed { index, row ->
                        val trades = parseInt(row["trades"])
                        val wins = parseInt(row["wins"])
                        ModeRanking(
                            modeName = parseString(row["mode"]).ifBlank { "UNKNOWN" },
                            trades = trades,
                            wins = wins,
                            winRate = if (trades > 0) (wins.toDouble() / trades.toDouble()) * 100.0 else 0.0,
                            avgPnlPct = parseDouble(row["avg_pnl"]),
                            rank = index + 1
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "getTopModes error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun getModesToAvoid(limit: Int): List<ModeRanking> {
        if (!isEnabled()) return emptyList()

        val safeLimit = limit.coerceIn(1, 50)

        return withContext(Dispatchers.IO) {
            try {
                val result = client!!.query(
                    """
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
                    LIMIT $safeLimit
                    """.trimIndent()
                )

                if (result.success) {
                    result.rows.mapIndexed { index, row ->
                        val trades = parseInt(row["trades"])
                        val wins = parseInt(row["wins"])
                        ModeRanking(
                            modeName = parseString(row["mode"]).ifBlank { "UNKNOWN" },
                            trades = trades,
                            wins = wins,
                            winRate = if (trades > 0) (wins.toDouble() / trades.toDouble()) * 100.0 else 0.0,
                            avgPnlPct = parseDouble(row["avg_pnl"]),
                            rank = index + 1
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "getModesToAvoid error: ${e.message}")
                emptyList()
            }
        }
    }

    data class HotToken(
        val symbol: String,
        val trades: Int,
        val wins: Int,
        val winRate: Double,
        val avgPnlPct: Double,
        val lastTradeTime: Long,
        val botsTrading: Int
    )

    suspend fun getHotTokens(hoursBack: Int, limit: Int): List<HotToken> {
        if (!isEnabled()) return emptyList()

        val safeHours = hoursBack.coerceIn(1, 72)
        val safeLimit = limit.coerceIn(1, 100)

        return withContext(Dispatchers.IO) {
            try {
                val cutoffTime = System.currentTimeMillis() - (safeHours * 60L * 60L * 1000L)

                val result = client!!.query(
                    """
                    SELECT
                        symbol,
                        COUNT(*) as trades,
                        SUM(CASE WHEN is_win = 1 THEN 1 ELSE 0 END) as wins,
                        AVG(pnl_pct) as avg_pnl,
                        MAX(timestamp) as last_trade,
                        COUNT(DISTINCT instance_id) as bots_trading
                    FROM collective_trades
                    WHERE side = 'SELL' AND timestamp > ? AND is_win = 1
                    GROUP BY symbol
                    HAVING COUNT(*) >= 2 AND AVG(pnl_pct) > 5
                    ORDER BY AVG(pnl_pct) DESC
                    LIMIT $safeLimit
                    """.trimIndent(),
                    listOf(cutoffTime)
                )

                if (result.success) {
                    result.rows.map { row ->
                        val trades = parseInt(row["trades"])
                        val wins = parseInt(row["wins"])
                        HotToken(
                            symbol = parseString(row["symbol"]).ifBlank { "???" },
                            trades = trades,
                            wins = wins,
                            winRate = if (trades > 0) (wins.toDouble() / trades.toDouble()) * 100.0 else 0.0,
                            avgPnlPct = parseDouble(row["avg_pnl"]),
                            lastTradeTime = parseLong(row["last_trade"]),
                            botsTrading = parseInt(row["bots_trading"]).coerceAtLeast(1)
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "getHotTokens error: ${e.message}")
                emptyList()
            }
        }
    }

    data class NetworkSignal(
        val id: Long,
        val signalType: String,
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
        reason: String
    ): Boolean {
        if (!isEnabled()) return false

        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val expiresAt = now + (2 * 60 * 60 * 1000L)

                val signalType = when {
                    pnlPct >= 50.0 -> "MEGA_WINNER"
                    pnlPct >= 20.0 -> "HOT_TOKEN"
                    pnlPct <= -15.0 -> "AVOID"
                    else -> "INFO"
                }

                val result = client!!.execute(
                    """
                    INSERT INTO network_signals
                        (signal_type, mint, symbol, broadcaster_id, timestamp, pnl_pct,
                         confidence, liquidity_usd, mode, reason, expires_at, ack_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    listOf(
                        signalType,
                        mint,
                        symbol,
                        instanceId,
                        now,
                        sanitizeDouble(pnlPct),
                        confidence,
                        sanitizeDouble(liquidityUsd),
                        mode,
                        reason,
                        expiresAt
                    )
                )

                if (result.success) {
                    Log.i(TAG, "BROADCAST: $signalType $symbol ${pnlPct.toInt()}% -> NETWORK")
                }

                result.success
            } catch (e: Exception) {
                Log.e(TAG, "broadcastHotToken error: ${e.message}")
                false
            }
        }
    }

    suspend fun getNetworkSignals(limit: Int): List<NetworkSignal> {
        if (!isEnabled()) return emptyList()

        val safeLimit = limit.coerceIn(1, 100)

        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()

                val result = client!!.query(
                    """
                    SELECT * FROM network_signals
                    WHERE expires_at > ?
                      AND broadcaster_id != ?
                    ORDER BY pnl_pct DESC, timestamp DESC
                    LIMIT $safeLimit
                    """.trimIndent(),
                    listOf(now, instanceId)
                )

                if (result.success) {
                    result.rows.map { row ->
                        NetworkSignal(
                            id = parseLong(row["id"]),
                            signalType = parseString(row["signal_type"]),
                            mint = parseString(row["mint"]),
                            symbol = parseString(row["symbol"]),
                            broadcasterId = parseString(row["broadcaster_id"]),
                            timestamp = parseLong(row["timestamp"]),
                            pnlPct = parseDouble(row["pnl_pct"]),
                            confidence = parseInt(row["confidence"]),
                            liquidityUsd = parseDouble(row["liquidity_usd"]),
                            mode = parseString(row["mode"]),
                            reason = parseString(row["reason"]),
                            expiresAt = parseLong(row["expires_at"]),
                            ackCount = parseInt(row["ack_count"])
                        )
                    }
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "getNetworkSignals error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun acknowledgeSignal(signalId: Long) {
        if (!isEnabled()) return

        withContext(Dispatchers.IO) {
            try {
                client!!.execute(
                    "UPDATE network_signals SET ack_count = ack_count + 1 WHERE id = ?",
                    listOf(signalId)
                )
            } catch (_: Exception) {
            }
        }
    }

    suspend fun getNetworkBoostForMint(mint: String): Int {
        if (!isEnabled()) return 0

        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()

                val result = client!!.query(
                    """
                    SELECT signal_type, pnl_pct, ack_count
                    FROM network_signals
                    WHERE mint = ? AND expires_at > ?
                    ORDER BY pnl_pct DESC
                    LIMIT 1
                    """.trimIndent(),
                    listOf(mint, now)
                )

                if (result.success && result.rows.isNotEmpty()) {
                    val row = result.rows[0]
                    val signalType = parseString(row["signal_type"])
                    val pnlPct = parseDouble(row["pnl_pct"])
                    val ackCount = parseInt(row["ack_count"])

                    when {
                        signalType == "MEGA_WINNER" -> 20 + (ackCount * 2).coerceAtMost(10)
                        signalType == "HOT_TOKEN" -> 12 + (ackCount * 2).coerceAtMost(8)
                        signalType == "AVOID" -> -15
                        pnlPct > 30.0 -> 15
                        pnlPct > 15.0 -> 10
                        pnlPct > 5.0 -> 5
                        pnlPct < -10.0 -> -10
                        else -> 0
                    }
                } else {
                    0
                }
            } catch (_: Exception) {
                0
            }
        }
    }

    suspend fun cleanupExpiredSignals() {
        if (!isEnabled()) return

        withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val result = client!!.execute(
                    "DELETE FROM network_signals WHERE expires_at < ?",
                    listOf(now)
                )

                if (result.success && (result.rowsAffected ?: 0) > 0) {
                    Log.d(TAG, "Cleaned ${result.rowsAffected} expired network signals")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun saveCacheToPrefs() {
        val p = prefs ?: return

        try {
            val editor = p.edit()

            val blacklistJson = org.json.JSONArray(cachedBlacklist.toList()).toString()
            editor.putString(KEY_BLACKLIST, blacklistJson)

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
                obj.put("avgPnlPct", sanitizeDouble(pattern.avgPnlPct))
                obj.put("avgHoldMins", sanitizeDouble(pattern.avgHoldMins))
                obj.put("lastUpdated", pattern.lastUpdated)
                patternsJson.put(hash, obj)
            }
            editor.putString(KEY_PATTERNS, patternsJson.toString())

            val modeStatsJson = org.json.JSONObject()
            cachedModeStats.forEach { (key, stat) ->
                val obj = org.json.JSONObject()
                obj.put("modeName", stat.modeName)
                obj.put("marketCondition", stat.marketCondition)
                obj.put("liquidityBucket", stat.liquidityBucket)
                obj.put("totalTrades", stat.totalTrades)
                obj.put("wins", stat.wins)
                obj.put("losses", stat.losses)
                obj.put("avgPnlPct", sanitizeDouble(stat.avgPnlPct))
                obj.put("avgHoldMins", sanitizeDouble(stat.avgHoldMins))
                obj.put("lastUpdated", stat.lastUpdated)
                modeStatsJson.put(key, obj)
            }
            editor.putString(KEY_MODE_STATS, modeStatsJson.toString())

            editor.putLong(KEY_LAST_SYNC, lastSyncTime)
            editor.commit()

            Log.i(
                TAG,
                "CACHE SAVED: ${cachedBlacklist.size} blacklist | ${cachedPatterns.size} patterns | ${cachedModeStats.size} mode stats"
            )
        } catch (e: Exception) {
            Log.e(TAG, "saveCacheToPrefs error: ${e.message}")
        }
    }

    private fun loadCacheFromPrefs() {
        val p = prefs ?: return

        try {
            val blacklistJson = p.getString(KEY_BLACKLIST, null)
            if (!blacklistJson.isNullOrBlank()) {
                val arr = org.json.JSONArray(blacklistJson)
                cachedBlacklist.clear()
                for (i in 0 until arr.length()) {
                    cachedBlacklist.add(arr.optString(i, ""))
                }
            }

            val patternsJson = p.getString(KEY_PATTERNS, null)
            if (!patternsJson.isNullOrBlank()) {
                val obj = org.json.JSONObject(patternsJson)
                cachedPatterns.clear()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val hash = keys.next()
                    val pObj = obj.optJSONObject(hash) ?: continue
                    val pattern = CollectivePattern(
                        id = 0L,
                        patternHash = hash,
                        patternType = pObj.optString("patternType", ""),
                        discoverySource = pObj.optString("discoverySource", ""),
                        liquidityBucket = pObj.optString("liquidityBucket", ""),
                        emaTrend = pObj.optString("emaTrend", ""),
                        totalTrades = pObj.optInt("totalTrades", 0),
                        wins = pObj.optInt("wins", 0),
                        losses = pObj.optInt("losses", 0),
                        avgPnlPct = sanitizeDouble(pObj.optDouble("avgPnlPct", 0.0)),
                        avgHoldMins = sanitizeDouble(pObj.optDouble("avgHoldMins", 0.0)),
                        lastUpdated = pObj.optLong("lastUpdated", 0L)
                    )
                    cachedPatterns[hash] = pattern
                }
            }

            val modeStatsJson = p.getString(KEY_MODE_STATS, null)
            if (!modeStatsJson.isNullOrBlank()) {
                val obj = org.json.JSONObject(modeStatsJson)
                cachedModeStats.clear()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val mObj = obj.optJSONObject(key) ?: continue
                    val stat = ModePerformance(
                        id = 0L,
                        modeName = mObj.optString("modeName", ""),
                        marketCondition = mObj.optString("marketCondition", ""),
                        liquidityBucket = mObj.optString("liquidityBucket", ""),
                        totalTrades = mObj.optInt("totalTrades", 0),
                        wins = mObj.optInt("wins", 0),
                        losses = mObj.optInt("losses", 0),
                        avgPnlPct = sanitizeDouble(mObj.optDouble("avgPnlPct", 0.0)),
                        avgHoldMins = sanitizeDouble(mObj.optDouble("avgHoldMins", 0.0)),
                        lastUpdated = mObj.optLong("lastUpdated", 0L)
                    )
                    cachedModeStats[key] = stat
                }
            }

            lastSyncTime = p.getLong(KEY_LAST_SYNC, 0L)

            Log.i(
                TAG,
                "CACHE LOADED: ${cachedBlacklist.size} blacklist | ${cachedPatterns.size} patterns | ${cachedModeStats.size} mode stats"
            )
        } catch (e: Exception) {
            Log.e(TAG, "loadCacheFromPrefs error: ${e.message}")
        }
    }

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
        val statusMessage: String
    )

    fun getSyncStatus(): SyncStatus {
        val tursoConfigured = client != null
        val connected = isInitialized && tursoConfigured

        val message = when {
            !tursoConfigured -> "Turso not configured - LOCAL ONLY mode"
            !isInitialized -> "Collective learning not initialized"
            lastSyncTime == 0L -> "Connected but never synced"
            else -> {
                val ago = (System.currentTimeMillis() - lastSyncTime) / 60000L
                "Connected | Last sync: ${ago}m ago"
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
            statusMessage = message
        )
    }

    suspend fun forceSyncNow(): String {
        if (!isEnabled()) {
            return "Collective learning disabled or Turso not configured"
        }

        return try {
            val beforeBlacklist = cachedBlacklist.size
            val beforePatterns = cachedPatterns.size

            downloadAll()

            val afterBlacklist = cachedBlacklist.size
            val afterPatterns = cachedPatterns.size

            val blacklistDelta = afterBlacklist - beforeBlacklist
            val patternDelta = afterPatterns - beforePatterns

            val sb = StringBuilder()
            sb.append("SYNC COMPLETE\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            sb.append("Instance: ").append(instanceId).append('\n')
            sb.append("Blacklist: ").append(afterBlacklist).append(" (+").append(blacklistDelta).append(" new)\n")
            sb.append("Patterns: ").append(afterPatterns).append(" (+").append(patternDelta).append(" new)\n")
            sb.append("Mode Stats: ").append(cachedModeStats.size).append('\n')
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.toString()
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        }
    }

    suspend fun runDiagnostics(): String {
        if (!isEnabled()) {
            return """
COLLECTIVE DIAGNOSTICS FAILED
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
                diagnostics.append("═══════════════════════════════════════\n")
                diagnostics.append("V5.2 COLLECTIVE DIAGNOSTICS\n")
                diagnostics.append("═══════════════════════════════════════\n")
                diagnostics.append("Instance ID: ").append(instanceId).append("\n\n")

                val totalResult = client!!.query("SELECT COUNT(*) as cnt FROM collective_trades")
                val totalTrades = if (totalResult.success && totalResult.rows.isNotEmpty()) {
                    parseInt(totalResult.rows[0]["cnt"])
                } else {
                    0
                }
                diagnostics.append("TOTAL TRADES IN DATABASE: ").append(totalTrades).append('\n')

                val myResult = client!!.query(
                    "SELECT COUNT(*) as cnt FROM collective_trades WHERE instance_id = ?",
                    listOf(instanceId)
                )
                val myTrades = if (myResult.success && myResult.rows.isNotEmpty()) {
                    parseInt(myResult.rows[0]["cnt"])
                } else {
                    0
                }
                diagnostics.append("MY TRADES: ").append(myTrades).append('\n')

                val othersResult = client!!.query(
                    "SELECT COUNT(*) as cnt FROM collective_trades WHERE instance_id != ?",
                    listOf(instanceId)
                )
                val othersTrades = if (othersResult.success && othersResult.rows.isNotEmpty()) {
                    parseInt(othersResult.rows[0]["cnt"])
                } else {
                    0
                }
                diagnostics.append("OTHER USERS' TRADES: ").append(othersTrades).append('\n')

                val usersResult = client!!.query("SELECT COUNT(DISTINCT instance_id) as cnt FROM collective_trades")
                val distinctUsers = if (usersResult.success && usersResult.rows.isNotEmpty()) {
                    parseInt(usersResult.rows[0]["cnt"])
                } else {
                    0
                }
                diagnostics.append("DISTINCT USERS: ").append(distinctUsers).append("\n\n")

                diagnostics.append("LAST 5 COLLECTIVE TRADES:\n")
                val recentResult = client!!.query(
                    """
                    SELECT timestamp, side, symbol, mode, pnl_pct, instance_id
                    FROM collective_trades
                    ORDER BY timestamp DESC
                    LIMIT 5
                    """.trimIndent()
                )

                if (recentResult.success && recentResult.rows.isNotEmpty()) {
                    for (row in recentResult.rows) {
                        val ts = parseLong(row["timestamp"])
                        val side = parseString(row["side"]).ifBlank { "?" }
                        val symbol = parseString(row["symbol"]).ifBlank { "?" }
                        val mode = parseString(row["mode"]).ifBlank { "?" }
                        val pnl = parseDouble(row["pnl_pct"])
                        val inst = parseString(row["instance_id"]).take(8)

                        val isMe = inst == instanceId.take(8)
                        val marker = if (isMe) "ME" else "NET"
                        val ago = if (ts > 0L) (System.currentTimeMillis() - ts) / 60000L else -1L

                        diagnostics.append("  ")
                            .append(marker)
                            .append(' ')
                            .append(side)
                            .append(' ')
                            .append(symbol)
                            .append(" (")
                            .append(mode)
                            .append(") ")
                        if (side == "SELL") {
                            diagnostics.append(pnl.toInt()).append("% ")
                        }
                        if (ago >= 0) {
                            diagnostics.append("| ").append(ago).append("m ago")
                        }
                        diagnostics.append('\n')
                    }
                } else {
                    diagnostics.append("  (no trades found)\n")
                }

                diagnostics.append("\nTABLE CHECK:\n")
                val tablesResult = client!!.query("SELECT name FROM sqlite_master WHERE type='table'")
                if (tablesResult.success) {
                    val tables = tablesResult.rows.mapNotNull { parseString(it["name"]).ifBlank { null } }
                    diagnostics.append("  Tables: ").append(tables.joinToString(", ")).append('\n')
                }

                diagnostics.append("\n═══════════════════════════════════════")
                diagnostics.toString()
            } catch (e: Exception) {
                """
DIAGNOSTICS ERROR
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Error: ${e.message}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                """.trimIndent()
            }
        }
    }

    private fun parseString(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> value
            else -> value.toString()
        }
    }

    private fun parseInt(value: Any?): Int {
        return when (value) {
            null -> 0
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Float -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
    }

    private fun parseLong(value: Any?): Long {
        return when (value) {
            null -> 0L
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong() ?: 0L
            else -> 0L
        }
    }

    private fun parseDouble(value: Any?): Double {
        return when (value) {
            null -> 0.0
            is Double -> sanitizeDouble(value)
            is Float -> sanitizeDouble(value.toDouble())
            is Int -> value.toDouble()
            is Long -> value.toDouble()
            is Number -> sanitizeDouble(value.toDouble())
            is String -> sanitizeDouble(value.toDoubleOrNull() ?: 0.0)
            else -> 0.0
        }
    }

    private fun sanitizeDouble(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }
}
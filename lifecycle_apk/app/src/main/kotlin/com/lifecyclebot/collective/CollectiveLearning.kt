package com.lifecyclebot.collective

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.lifecyclebot.data.CanonicalMint
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val initMutex = Mutex()
    @Volatile private var lastInitError: String = ""
    
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
    private val cachedTokenMints = mutableMapOf<String, SharedTokenMint>()

    private var syncJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun init(ctx: Context): Boolean = initMutex.withLock {
        Log.i(TAG, "INIT CALLED | alreadyInit=$isInitialized | clientExists=${client != null}")

        if (isInitialized && client != null) {
            Log.d(TAG, "Already initialized and connected")
            return@withLock true
        }

        // V5.9.1557 — source fix for Client=true / Initialized=false.
        // A previous partial init could leave a client object visible while schema/download
        // was still running or had failed. Reset the state before each non-ready init so
        // diagnostics never classify half-init as "not configured" and concurrent init calls
        // cannot race each other.
        isInitialized = false
        client = null
        lastInitError = ""
        appContext = ctx.applicationContext

        return@withLock try {
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
                "INIT: dbUrlHost=${dbUrl.replace("libsql://", "").replace("https://", "").take(80)} authTokenPresent=${authToken.isNotBlank()} tokenLen=${authToken.length}"
            )

            if (dbUrl.isBlank() || authToken.isBlank()) {
                lastInitError = "Turso credentials blank after ConfigStore load"
                Log.w(TAG, "Turso credentials not configured - using LOCAL CACHE ONLY")
                if (cachedBlacklist.isNotEmpty() || cachedPatterns.isNotEmpty()) {
                    Log.i(
                        TAG,
                        "Local collective cache available (${cachedBlacklist.size} blacklist, ${cachedPatterns.size} patterns)"
                    )
                }
                return@withLock false
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
                lastInitError = "Connection test failed after 3 attempts"
                Log.e(TAG, "TURSO CONNECTION FAILED after 3 attempts - using LOCAL CACHE")
                client = null
                isInitialized = false
                return@withLock false
            }

            Log.i(TAG, "Turso connection successful!")

            if (!client!!.initSchema()) {
                lastInitError = "Schema initialization failed"
                Log.e(TAG, "Failed to initialize schema")
                client = null
                isInitialized = false
                return@withLock false
            }

            isInitialized = true
            Log.i(TAG, "COLLECTIVE LEARNING ONLINE - HIVE MIND ACTIVE")

            uploadLocalPatternAggregates()
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
            lastInitError = e.message ?: e.javaClass.simpleName
            client = null
            isInitialized = false
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
        if (!isEnabled()) {
            try { ensureConnected() } catch (_: Throwable) {}
        }
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
            errorMessage = errorMessage ?: lastInitError.takeIf { it.isNotBlank() },
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

    data class SharedTokenMint(
        val mint: String,
        val symbol: String,
        val name: String,
        val source: String,
        val creatorAddress: String,
        val logoUrl: String,
        val twitter: String,
        val telegram: String,
        val discord: String,
        val website: String,
        val coingeckoId: String,
        val socialCount: Int,
        val pairAddress: String,
        val pairUrl: String,
        val pairDex: String,
        val lastPriceSource: String,
        val quoteSuccessCount: Int,
        val quoteFailCount: Int,
        val lastLiquidityUsd: Double,
        val lastMcapUsd: Double,
        val createdAtMs: Long,
        val firstSeenMs: Long,
        val lastSeenMs: Long,
        val reportCount: Int,
    )

    data class HiveGenomeBlend(
        val featureWeights: Map<String, Double>,
        val contributors: Int,
        val totalTrades: Int,
        val avgWinRatePct: Double,
        val avgNetPnlSol: Double,
        val bestWinRatePct: Double,
    )

    data class HivePatternEdge(
        val patternType: String,
        val patternValue: String,
        val totalTrades: Int,
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        val avgPnlPct: Double,
        val scoreAdj: Int,
        val confAdj: Int,
    )

    data class SourceReliability(
        val source: String,
        val totalOutcomes: Int,
        val wins: Int,
        val losses: Int,
        val avgPnlPct: Double,
        val bestPnlPct: Double,
        val worstPnlPct: Double,
        val instanceCount: Int,
        val lastSeen: Long,
    ) { val winRate: Double get() = if (totalOutcomes > 0) wins.toDouble() / totalOutcomes * 100.0 else 50.0 }

    data class CreatorReputation(
        val creatorAddress: String,
        val tokenCount: Int,
        val totalOutcomes: Int,
        val wins: Int,
        val losses: Int,
        val avgPnlPct: Double,
        val worstPnlPct: Double,
        val rugLikeLosses: Int,
        val instanceCount: Int,
        val lastSeen: Long,
    ) { val winRate: Double get() = if (totalOutcomes > 0) wins.toDouble() / totalOutcomes * 100.0 else 50.0 }

    data class RugCluster(
        val clusterType: String,
        val clusterKey: String,
        val tokenCount: Int,
        val rugLikeLosses: Int,
        val avgPnlPct: Double,
        val worstPnlPct: Double,
        val instanceCount: Int,
        val lastSeen: Long,
    )

    data class LiquidityDrainSignature(
        val signatureKey: String,
        val source: String,
        val pairDex: String,
        val creatorAddress: String,
        val events: Int,
        val avgPnlPct: Double,
        val avgHoldMins: Double,
        val worstPnlPct: Double,
        val instanceCount: Int,
        val lastSeen: Long,
    )

    data class EndpointHealthRecord(
        val host: String,
        val successRate: Double,
        val avgLatencyMs: Double,
        val successes: Int,
        val failures4xx: Int,
        val failures5xx: Int,
        val networkErrors: Int,
        val regionCode: String,
        val deviceModel: String,
        val lastSeen: Long,
    )

    fun getCachedTokenMint(mint: String): SharedTokenMint? {
        val key = CanonicalMint.normalize(mint)
        if (key.isBlank()) return null
        return cachedTokenMints[key]
    }

    suspend fun uploadPerformanceGenome(
        appVersion: String,
        totalTrades: Int,
        winRatePct: Double,
        netPnlSol: Double,
        profitFactor: Double,
        featureWeights: Map<String, Double>,
    ): Boolean {
        if (!isEnabled()) return false
        if (instanceId.isBlank()) return false
        if (totalTrades < 20) return false
        return withContext(Dispatchers.IO) {
            try {
                val weightsJson = org.json.JSONObject().apply {
                    featureWeights.forEach { (k, v) ->
                        if (k.isNotBlank()) put(k, sanitizeDouble(v).takeIf { !it.isNaN() && !it.isInfinite() }?.coerceIn(0.1, 5.0) ?: 1.0)
                    }
                }.toString()
                val now = System.currentTimeMillis()
                val result = client!!.execute(
                    """
                    INSERT INTO hive_performance_genomes
                        (instance_id, app_version, total_trades, win_rate_pct, net_pnl_sol,
                         profit_factor, feature_weights_json, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(instance_id) DO UPDATE SET
                        app_version = excluded.app_version,
                        total_trades = excluded.total_trades,
                        win_rate_pct = excluded.win_rate_pct,
                        net_pnl_sol = excluded.net_pnl_sol,
                        profit_factor = excluded.profit_factor,
                        feature_weights_json = excluded.feature_weights_json,
                        updated_at = excluded.updated_at
                    """.trimIndent(),
                    listOf(instanceId, appVersion.take(40), totalTrades.coerceAtLeast(0),
                        sanitizeDouble(winRatePct).coerceIn(0.0, 100.0), sanitizeDouble(netPnlSol),
                        sanitizeDouble(profitFactor).coerceIn(0.0, 25.0), weightsJson, now)
                )
                if (result.success) Log.i(TAG, "Uploaded hive genome: trades=$totalTrades WR=${winRatePct.toInt()}%")
                result.success
            } catch (e: Exception) {
                Log.e(TAG, "uploadPerformanceGenome error: ${e.message}")
                false
            }
        }
    }

    suspend fun downloadPerformanceGenomeBlend(localTradeCount: Int, localWinRatePct: Double): HiveGenomeBlend? {
        if (!isEnabled()) return null
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
                val result = client!!.query(
                    """
                    SELECT instance_id, total_trades, win_rate_pct, net_pnl_sol, profit_factor, feature_weights_json
                    FROM hive_performance_genomes
                    WHERE updated_at > ? AND instance_id != ? AND total_trades >= 100
                      AND win_rate_pct >= 55.0 AND net_pnl_sol > 0.0 AND profit_factor >= 1.10
                    ORDER BY win_rate_pct DESC, net_pnl_sol DESC
                    LIMIT 25
                    """.trimIndent(), listOf(cutoff, instanceId)
                )
                if (!result.success || result.rows.isEmpty()) return@withContext null
                val weighted = mutableMapOf<String, Double>(); val weights = mutableMapOf<String, Double>()
                var contributors = 0; var totalTrades = 0; var wrSum = 0.0; var pnlSum = 0.0; var bestWr = 0.0
                for (row in result.rows) {
                    val trades = parseInt(row["total_trades"]).coerceAtLeast(0)
                    val wr = parseDouble(row["win_rate_pct"]).coerceIn(0.0, 100.0)
                    val pnl = parseDouble(row["net_pnl_sol"])
                    val pf = parseDouble(row["profit_factor"]).coerceIn(0.0, 25.0)
                    val json = parseString(row["feature_weights_json"])
                    if (trades < 100 || wr < 55.0 || pnl <= 0.0 || pf < 1.10 || json.isBlank()) continue
                    val contributorWeight = ((trades.coerceAtMost(1000).toDouble() / 1000.0) * 0.35 +
                        ((wr - 50.0) / 50.0).coerceIn(0.0, 1.0) * 0.45 +
                        (pf / 3.0).coerceIn(0.0, 1.0) * 0.20).coerceIn(0.05, 1.0)
                    val obj = org.json.JSONObject(json); val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next(); val v = sanitizeDouble(obj.optDouble(k, 1.0)).takeIf { !it.isNaN() && !it.isInfinite() }?.coerceIn(0.1, 5.0) ?: 1.0
                        weighted[k] = (weighted[k] ?: 0.0) + v * contributorWeight
                        weights[k] = (weights[k] ?: 0.0) + contributorWeight
                    }
                    contributors++; totalTrades += trades; wrSum += wr; pnlSum += pnl; if (wr > bestWr) bestWr = wr
                }
                if (contributors <= 0 || weighted.isEmpty()) return@withContext null
                HiveGenomeBlend(weighted.mapValues { (k, v) -> v / (weights[k] ?: 1.0) }, contributors, totalTrades, wrSum / contributors, pnlSum / contributors, bestWr)
            } catch (e: Exception) { Log.e(TAG, "downloadPerformanceGenomeBlend error: ${e.message}"); null }
        }
    }

    suspend fun uploadTokenMint(
        mint: String,
        symbol: String,
        name: String,
        source: String,
        creatorAddress: String,
        logoUrl: String,
        twitter: String = "",
        telegram: String = "",
        discord: String = "",
        website: String = "",
        coingeckoId: String = "",
        socialCount: Int = 0,
        pairAddress: String,
        pairUrl: String,
        pairDex: String = "",
        lastPriceSource: String = "",
        quoteSuccessful: Boolean = false,
        lastLiquidityUsd: Double,
        lastMcapUsd: Double,
        createdAtMs: Long,
    ): Boolean {
        if (!isEnabled()) return false
        val safeMint = CanonicalMint.normalize(mint)
        if (safeMint.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val result = client!!.execute(
                    """
                    INSERT INTO collective_token_mints
                        (mint, symbol, name, source, creator_address, logo_url,
                         twitter, telegram, discord, website, coingecko_id, social_count,
                         pair_address, pair_url, pair_dex, last_price_source, quote_success_count, quote_fail_count,
                         last_liquidity_usd, last_mcap_usd, created_at_ms, first_seen_ms, last_seen_ms, report_count)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
                    ON CONFLICT(mint) DO UPDATE SET
                        symbol = COALESCE(NULLIF(excluded.symbol, ''), symbol),
                        name = COALESCE(NULLIF(excluded.name, ''), name),
                        source = COALESCE(NULLIF(excluded.source, ''), source),
                        creator_address = COALESCE(NULLIF(excluded.creator_address, ''), creator_address),
                        logo_url = COALESCE(NULLIF(excluded.logo_url, ''), logo_url),
                        twitter = COALESCE(NULLIF(excluded.twitter, ''), twitter),
                        telegram = COALESCE(NULLIF(excluded.telegram, ''), telegram),
                        discord = COALESCE(NULLIF(excluded.discord, ''), discord),
                        website = COALESCE(NULLIF(excluded.website, ''), website),
                        coingecko_id = COALESCE(NULLIF(excluded.coingecko_id, ''), coingecko_id),
                        social_count = MAX(social_count, excluded.social_count),
                        pair_address = COALESCE(NULLIF(excluded.pair_address, ''), pair_address),
                        pair_url = COALESCE(NULLIF(excluded.pair_url, ''), pair_url),
                        pair_dex = COALESCE(NULLIF(excluded.pair_dex, ''), pair_dex),
                        last_price_source = COALESCE(NULLIF(excluded.last_price_source, ''), last_price_source),
                        quote_success_count = quote_success_count + excluded.quote_success_count,
                        quote_fail_count = quote_fail_count + excluded.quote_fail_count,
                        last_liquidity_usd = MAX(last_liquidity_usd, excluded.last_liquidity_usd),
                        last_mcap_usd = MAX(last_mcap_usd, excluded.last_mcap_usd),
                        created_at_ms = CASE
                            WHEN created_at_ms <= 0 THEN excluded.created_at_ms
                            WHEN excluded.created_at_ms <= 0 THEN created_at_ms
                            ELSE MIN(created_at_ms, excluded.created_at_ms)
                        END,
                        last_seen_ms = excluded.last_seen_ms,
                        report_count = report_count + 1
                    """.trimIndent(),
                    listOf(
                        safeMint,
                        symbol.take(64),
                        name.take(128),
                        source.take(128),
                        creatorAddress.take(64),
                        logoUrl.take(512),
                        twitter.take(256),
                        telegram.take(256),
                        discord.take(256),
                        website.take(512),
                        coingeckoId.take(128),
                        socialCount.coerceIn(0, 10),
                        pairAddress.take(96),
                        pairUrl.take(512),
                        pairDex.take(40),
                        lastPriceSource.take(80),
                        if (quoteSuccessful) 1 else 0,
                        if (!quoteSuccessful && pairAddress.isBlank()) 1 else 0,
                        sanitizeDouble(lastLiquidityUsd),
                        sanitizeDouble(lastMcapUsd),
                        createdAtMs.coerceAtLeast(0L),
                        now,
                        now,
                    )
                )
                result.success
            } catch (e: Exception) {
                Log.e(TAG, "uploadTokenMint error: ${e.message}")
                false
            }
        }
    }

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
        val pnlVerdict = com.lifecyclebot.engine.LearningPnlSanitizer.inspectPct(pnlPct, "CollectiveLearning.uploadPatternOutcome/$patternType")
        if (!pnlVerdict.ok) return

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
                    winInc, lossInc, sanitizeDouble(pnlVerdict.pnlPct), sanitizeDouble(holdMins), now,
                    winInc, lossInc, sanitizeDouble(pnlVerdict.pnlPct), sanitizeDouble(holdMins), now
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

    suspend fun uploadLocalPatternAggregates(limit: Int = 250): Int {
        if (!isEnabled()) return 0
        if (instanceId.isBlank()) return 0
        val c = client ?: return 0
        return withContext(Dispatchers.IO) {
            var uploaded = 0
            try {
                val now = System.currentTimeMillis()
                val aggregates = com.lifecyclebot.engine.TokenWinMemory.exportPatternAggregates(limit)
                for (p in aggregates) {
                    if (p.totalTrades <= 0) continue
                    val safeType = p.type.take(80).ifBlank { "UNKNOWN" }
                    val safeValue = p.value.take(120).ifBlank { "UNKNOWN" }
                    // Per-instance hash makes the upload idempotent. Download readers still
                    // aggregate by pattern fields, but re-syncing the same phone won't double-count.
                    val patternHash = sha256("LOCAL_PATTERN|$instanceId|$safeType|$safeValue").take(32)
                    val avgPnl = p.totalPnl / p.totalTrades.toDouble()
                    val result = c.execute(
                        """
                        INSERT INTO collective_patterns
                            (pattern_hash, pattern_type, discovery_source, liquidity_bucket, ema_trend,
                             total_trades, wins, losses, avg_pnl_pct, avg_hold_mins, last_updated)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0.0, ?)
                        ON CONFLICT(pattern_hash) DO UPDATE SET
                            total_trades = excluded.total_trades,
                            wins = excluded.wins,
                            losses = excluded.losses,
                            avg_pnl_pct = excluded.avg_pnl_pct,
                            avg_hold_mins = excluded.avg_hold_mins,
                            last_updated = excluded.last_updated
                        """.trimIndent(),
                        listOf(patternHash, safeType, safeValue, "TOKEN_WIN_MEMORY", "AGGREGATE",
                            p.totalTrades, p.wins, p.losses, sanitizeDouble(avgPnl), now)
                    )
                    if (result.success) uploaded++
                }
                if (uploaded > 0) {
                    totalUploadsThisSession += uploaded
                    try { com.lifecyclebot.engine.CollectiveAnalytics.recordPatternUpload(uploaded) } catch (_: Throwable) {}
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HIVE_PATTERN_AGG_UPLOAD") } catch (_: Throwable) {}
                    Log.i(TAG, "Uploaded $uploaded local TokenWinMemory pattern aggregates to hive")
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadLocalPatternAggregates error: ${e.message}")
            }
            uploaded
        }
    }

    suspend fun uploadJournalTradeRow(
        side: String,
        symbol: String,
        mint: String,
        mode: String,
        source: String,
        liquidityUsd: Double,
        marketSentiment: String,
        entryScore: Int,
        confidence: Int,
        pnlPct: Double,
        holdMins: Double,
        isWin: Boolean,
        paperMode: Boolean,
        journalKey: String,
    ): Boolean {
        if (journalKey.isBlank()) return false
        if (!isEnabled()) {
            try { ensureConnected() } catch (_: Throwable) {}
            if (!isEnabled()) return false
        }
        val c = client ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val closeLike = side.equals("SELL", true) || side.equals("PARTIAL_SELL", true)
                val pnlVerdict = if (closeLike) com.lifecyclebot.engine.LearningPnlSanitizer.inspectPct(pnlPct, "CollectiveLearning.uploadJournalTradeRow/$side/$mode") else com.lifecyclebot.engine.LearningPnlSanitizer.Verdict(true, pnlPct)
                if (!pnlVerdict.ok) return@withContext false
                val tradeHash = sha256("JOURNAL|$instanceId|$journalKey").take(24)
                val liquidityBucket = when {
                    liquidityUsd < 5_000.0 -> "MICRO"
                    liquidityUsd < 25_000.0 -> "SMALL"
                    liquidityUsd < 100_000.0 -> "MID"
                    else -> "LARGE"
                }
                val result = c.execute(
                    """
                    INSERT OR REPLACE INTO collective_trades
                        (trade_hash, instance_id, timestamp, side, symbol, mint, mode, source, liquidity_bucket,
                         market_sentiment, entry_score, confidence, pnl_pct, hold_mins, is_win, paper_mode)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    listOf(
                        tradeHash, instanceId, now, side.take(24), symbol.take(40), mint.take(80),
                        mode.take(40).ifBlank { "STANDARD" }, source.take(80).ifBlank { "JOURNAL" }, liquidityBucket,
                        marketSentiment.take(40).ifBlank { "JOURNAL" }, entryScore, confidence,
                        sanitizeDouble(pnlVerdict.pnlPct), sanitizeDouble(holdMins), if (pnlVerdict.pnlPct >= 0.5) 1 else 0, if (paperMode) 1 else 0
                    )
                )
                if (result.success) {
                    totalUploadSuccessThisSession++
                    totalUploadsThisSession++
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HIVE_JOURNAL_TRADE_UPLOAD") } catch (_: Throwable) {}
                    true
                } else {
                    Log.w(TAG, "uploadJournalTradeRow failed: ${result.error}")
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadJournalTradeRow error: ${e.message}")
                false
            }
        }
    }

    suspend fun uploadTrade(
        side: String,
        symbol: String,
        mint: String = "",
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
        
        // V5.7.8: Don't upload scratch trades to collective — they waste DB space and pollute learning
        val isScratch = kotlin.math.abs(pnlPct) < 0.5
        if (isScratch) {
            ErrorLogger.debug("CollectiveTrade", "SKIP SCRATCH: $side $symbol | pnl=${String.format("%.2f", pnlPct)}% < 0.5%")
            return
        }

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
        val closeLike = side.equals("SELL", true) || side.equals("PARTIAL_SELL", true)
        val pnlVerdict = if (closeLike) com.lifecyclebot.engine.LearningPnlSanitizer.inspectPct(pnlPct, "CollectiveLearning.uploadTrade/$side/$mode") else com.lifecyclebot.engine.LearningPnlSanitizer.Verdict(true, pnlPct)
        if (!pnlVerdict.ok) return

        try {
            val now = System.currentTimeMillis()
            val safeMint = mint.trim()
            val tradeHash = sha256("$now|$side|$symbol|$safeMint|$mode|${System.nanoTime()}").take(24)

            val liquidityBucket = when {
                liquidityUsd < 5_000.0 -> "MICRO"
                liquidityUsd < 25_000.0 -> "SMALL"
                liquidityUsd < 100_000.0 -> "MID"
                else -> "LARGE"
            }

            val sql = """
                INSERT OR REPLACE INTO collective_trades
                    (trade_hash, instance_id, timestamp, side, symbol, mint, mode, source, liquidity_bucket,
                     market_sentiment, entry_score, confidence, pnl_pct, hold_mins, is_win, paper_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    safeMint,
                    mode,
                    source,
                    liquidityBucket,
                    marketSentiment,
                    entryScore,
                    confidence,
                    sanitizeDouble(pnlVerdict.pnlPct),
                    sanitizeDouble(holdMins),
                    if (pnlVerdict.pnlPct >= 0.5) 1 else 0,
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
        val pnlVerdict = com.lifecyclebot.engine.LearningPnlSanitizer.inspectPct(pnlPct, "CollectiveLearning.uploadWhaleEffectiveness")
        if (!pnlVerdict.ok) return

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
                    walletHash, profitInc, sanitizeDouble(pnlVerdict.pnlPct), leadTimeSec, now,
                    profitInc, sanitizeDouble(pnlVerdict.pnlPct), leadTimeSec, now
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
            downloadTokenMints()

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

    private suspend fun downloadTokenMints() {
        try {
            val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
            val result = client!!.query(
                """
                SELECT mint, symbol, name, source, creator_address, logo_url,
                       twitter, telegram, discord, website, coingecko_id, social_count,
                       pair_address, pair_url, pair_dex, last_price_source, quote_success_count, quote_fail_count,
                       last_liquidity_usd, last_mcap_usd, created_at_ms, first_seen_ms, last_seen_ms, report_count
                FROM collective_token_mints
                WHERE last_seen_ms > ?
                ORDER BY last_seen_ms DESC
                LIMIT 5000
                """.trimIndent(),
                listOf(cutoff)
            )
            if (result.success) {
                cachedTokenMints.clear()
                for (row in result.rows) {
                    val mint = CanonicalMint.normalize(parseString(row["mint"]))
                    if (mint.isBlank()) continue
                    cachedTokenMints[mint] = SharedTokenMint(
                        mint = mint,
                        symbol = parseString(row["symbol"]),
                        name = parseString(row["name"]),
                        source = parseString(row["source"]),
                        creatorAddress = parseString(row["creator_address"]),
                        logoUrl = parseString(row["logo_url"]),
                        twitter = parseString(row["twitter"]),
                        telegram = parseString(row["telegram"]),
                        discord = parseString(row["discord"]),
                        website = parseString(row["website"]),
                        coingeckoId = parseString(row["coingecko_id"]),
                        socialCount = parseInt(row["social_count"]),
                        pairAddress = parseString(row["pair_address"]),
                        pairUrl = parseString(row["pair_url"]),
                        pairDex = parseString(row["pair_dex"]),
                        lastPriceSource = parseString(row["last_price_source"]),
                        quoteSuccessCount = parseInt(row["quote_success_count"]),
                        quoteFailCount = parseInt(row["quote_fail_count"]),
                        lastLiquidityUsd = parseDouble(row["last_liquidity_usd"]),
                        lastMcapUsd = parseDouble(row["last_mcap_usd"]),
                        createdAtMs = parseLong(row["created_at_ms"]),
                        firstSeenMs = parseLong(row["first_seen_ms"]),
                        lastSeenMs = parseLong(row["last_seen_ms"]),
                        reportCount = parseInt(row["report_count"]),
                    )
                    cachedTokenMints[mint]?.let { shared ->
                        if (shared.socialCount > 0 || shared.coingeckoId.isNotBlank()) {
                            try {
                                com.lifecyclebot.engine.BirdeyeMetaDataProvider.seedFromHive(
                                    mint = mint,
                                    name = shared.name,
                                    symbol = shared.symbol,
                                    twitter = shared.twitter,
                                    telegram = shared.telegram,
                                    discord = shared.discord,
                                    website = shared.website,
                                    coingeckoId = shared.coingeckoId,
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                }
                Log.i(TAG, "Downloaded ${cachedTokenMints.size} shared token mints")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download token mints error: ${e.message}")
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

    fun getPatternEdgesForCandidate(
        symbol: String,
        source: String,
        liquidityUsd: Double,
        marketCapUsd: Double = 0.0,
        buyPressurePct: Double = 0.0,
        limit: Int = 6,
    ): List<HivePatternEdge> {
        if (cachedPatterns.isEmpty()) return emptyList()
        val wanted = mutableSetOf<Pair<String, String>>()
        val src = source.ifBlank { "UNKNOWN" }
        wanted += "source" to src
        wanted += "symbol_pattern" to when {
            symbol.length <= 3 -> "sym_short"
            symbol.length <= 5 -> "sym_medium"
            else -> "sym_long"
        }
        if (symbol.isNotBlank() && symbol.all { it.isUpperCase() || it.isDigit() }) wanted += "symbol_pattern" to "sym_standard"
        if (marketCapUsd > 0.0) {
            wanted += "mcap_bucket" to when {
                marketCapUsd < 20_000.0 -> "micro_<20k"
                marketCapUsd < 50_000.0 -> "small_20k_50k"
                marketCapUsd < 100_000.0 -> "mid_50k_100k"
                marketCapUsd < 500_000.0 -> "large_100k_500k"
                else -> "mega_500k+"
            }
            val liqRatio = if (marketCapUsd > 0.0) liquidityUsd / marketCapUsd else 0.0
            wanted += "liq_ratio" to when {
                liqRatio < 0.1 -> "liq_ratio_<10%"
                liqRatio < 0.3 -> "liq_ratio_10_30%"
                liqRatio < 0.5 -> "liq_ratio_30_50%"
                else -> "liq_ratio_50%+"
            }
        }
        if (buyPressurePct > 0.0) {
            wanted += "buy_pressure" to when {
                buyPressurePct < 40.0 -> "buy_weak_<40%"
                buyPressurePct < 55.0 -> "buy_neutral_40_55%"
                buyPressurePct < 70.0 -> "buy_strong_55_70%"
                else -> "buy_fomo_70%+"
            }
        }
        return cachedPatterns.values.asSequence()
            .filter { p -> p.totalTrades >= 5 && ((p.patternType to p.discoverySource) in wanted) }
            .mapNotNull { p ->
                val wr = p.winRate
                val avg = sanitizeDouble(p.avgPnlPct)
                val strongWin = wr >= 62.0 && avg >= 2.0
                val strongLoss = wr <= 35.0 && avg <= -2.0
                if (!strongWin && !strongLoss) return@mapNotNull null
                val sampleWeight = when {
                    p.totalTrades >= 100 -> 1.0
                    p.totalTrades >= 40 -> 0.75
                    p.totalTrades >= 15 -> 0.55
                    else -> 0.35
                }
                val raw = if (strongWin) {
                    4.0 + ((wr - 60.0) / 8.0).coerceIn(0.0, 4.0) + (avg / 8.0).coerceIn(0.0, 4.0)
                } else {
                    -4.0 - ((40.0 - wr) / 8.0).coerceIn(0.0, 5.0) - ((-avg) / 8.0).coerceIn(0.0, 5.0)
                }
                val score = (raw * sampleWeight).toInt().coerceIn(-12, 12)
                if (score == 0) return@mapNotNull null
                HivePatternEdge(
                    patternType = p.patternType,
                    patternValue = p.discoverySource,
                    totalTrades = p.totalTrades,
                    wins = p.wins,
                    losses = p.losses,
                    winRate = wr,
                    avgPnlPct = avg,
                    scoreAdj = score,
                    confAdj = (score / 3).coerceIn(-4, 4),
                )
            }
            .sortedWith(compareByDescending<HivePatternEdge> { kotlin.math.abs(it.scoreAdj) }.thenByDescending { it.totalTrades })
            .take(limit.coerceIn(1, 12))
            .toList()
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
                        uploadLocalPatternAggregates()
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
            "tokenMints" to cachedTokenMints.size,
            "hiveGenome" to "enabled",
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

    data class CollectiveMintMemory(
        val mint: String,
        val symbol: String,
        val totalOutcomes: Int,
        val wins: Int,
        val losses: Int,
        val avgPnlPct: Double,
        val bestPnlPct: Double,
        val worstPnlPct: Double,
        val instanceCount: Int,
        val avgEntryScore: Double,
        val avgConfidence: Double,
        val lastSeen: Long,
    ) {
        val winRate: Double get() = if (totalOutcomes > 0) wins.toDouble() / totalOutcomes.toDouble() * 100.0 else 0.0
    }

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

    suspend fun uploadEndpointHealth(
        appVersion: String,
        regionCode: String,
        deviceModel: String,
        host: String,
        successRate: Double,
        avgLatencyMs: Double,
        successes: Int,
        failures4xx: Int,
        failures5xx: Int,
        networkErrors: Int,
        lastSuccessMs: Long,
        lastFailureMs: Long,
        lastError: String,
    ): Boolean {
        if (!isEnabled() || instanceId.isBlank() || host.isBlank()) return false
        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val result = client!!.execute(
                    """
                    INSERT INTO collective_endpoint_health
                        (instance_id, app_version, region_code, device_model, host, success_rate,
                         avg_latency_ms, successes, failures_4xx, failures_5xx, network_errors,
                         last_success_ms, last_failure_ms, last_error, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    listOf(instanceId, appVersion.take(40), regionCode.take(20), deviceModel.take(80), host.take(120),
                        sanitizeDouble(successRate).coerceIn(0.0, 1.0), sanitizeDouble(avgLatencyMs).coerceAtLeast(0.0),
                        successes.coerceAtLeast(0), failures4xx.coerceAtLeast(0), failures5xx.coerceAtLeast(0), networkErrors.coerceAtLeast(0),
                        lastSuccessMs.coerceAtLeast(0L), lastFailureMs.coerceAtLeast(0L), lastError.take(180), now)
                )
                result.success
            } catch (e: Exception) {
                Log.e(TAG, "uploadEndpointHealth error: ${e.message}")
                false
            }
        }
    }

    suspend fun downloadRugClustersForAI(limit: Int = 500): List<RugCluster> {
        if (!isEnabled()) return emptyList()
        val out = mutableListOf<RugCluster>()
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
                val specs = listOf(
                    "CREATOR" to "m.creator_address",
                    "SOURCE" to "t.source",
                    "DEX" to "m.pair_dex"
                )
                for ((type, field) in specs) {
                    val result = client!!.query(
                        """
                        SELECT $field as cluster_key,
                               COUNT(DISTINCT t.mint) as token_count,
                               SUM(CASE WHEN t.pnl_pct <= -25.0 THEN 1 ELSE 0 END) as rug_like_losses,
                               AVG(t.pnl_pct) as avg_pnl_pct,
                               MIN(t.pnl_pct) as worst_pnl_pct,
                               COUNT(DISTINCT t.instance_id) as instance_count,
                               MAX(t.timestamp) as last_seen
                        FROM collective_trades t
                        LEFT JOIN collective_token_mints m ON m.mint = t.mint
                        WHERE t.side = 'SELL' AND t.timestamp > ? AND $field != ''
                        GROUP BY $field
                        HAVING rug_like_losses >= 2 OR (COUNT(*) >= 3 AND AVG(t.pnl_pct) <= -10.0)
                        ORDER BY rug_like_losses DESC, worst_pnl_pct ASC
                        LIMIT ${limit.coerceIn(50, 2000)}
                        """.trimIndent(), listOf(cutoff)
                    )
                    if (result.success) result.rows.forEach { row ->
                        val key = parseString(row["cluster_key"])
                        if (key.isNotBlank()) out.add(RugCluster(type, key, parseInt(row["token_count"]), parseInt(row["rug_like_losses"]), parseDouble(row["avg_pnl_pct"]), parseDouble(row["worst_pnl_pct"]), parseInt(row["instance_count"]).coerceAtLeast(1), parseLong(row["last_seen"])))
                    }
                }
                out
            } catch (e: Exception) {
                Log.e(TAG, "downloadRugClustersForAI error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun downloadLiquidityDrainSignaturesForAI(limit: Int = 500): List<LiquidityDrainSignature> {
        if (!isEnabled()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 14L * 24L * 60L * 60L * 1000L
                val result = client!!.query(
                    """
                    SELECT COALESCE(NULLIF(t.source,''),'?') as source,
                           COALESCE(NULLIF(m.pair_dex,''),'?') as pair_dex,
                           COALESCE(NULLIF(m.creator_address,''),'?') as creator_address,
                           COUNT(*) as events,
                           AVG(t.pnl_pct) as avg_pnl_pct,
                           AVG(t.hold_mins) as avg_hold_mins,
                           MIN(t.pnl_pct) as worst_pnl_pct,
                           COUNT(DISTINCT t.instance_id) as instance_count,
                           MAX(t.timestamp) as last_seen
                    FROM collective_trades t
                    LEFT JOIN collective_token_mints m ON m.mint = t.mint
                    WHERE t.side = 'SELL' AND t.timestamp > ? AND t.pnl_pct <= -15.0 AND t.hold_mins <= 20.0
                    GROUP BY source, pair_dex, creator_address
                    HAVING COUNT(*) >= 2
                    ORDER BY events DESC, worst_pnl_pct ASC
                    LIMIT ${limit.coerceIn(50, 2000)}
                    """.trimIndent(), listOf(cutoff)
                )
                if (!result.success) emptyList() else result.rows.map { row ->
                    val src = parseString(row["source"]); val dex = parseString(row["pair_dex"]); val creator = parseString(row["creator_address"])
                    LiquidityDrainSignature("$src|$dex|$creator", src, dex, creator, parseInt(row["events"]), parseDouble(row["avg_pnl_pct"]), parseDouble(row["avg_hold_mins"]), parseDouble(row["worst_pnl_pct"]), parseInt(row["instance_count"]).coerceAtLeast(1), parseLong(row["last_seen"]))
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadLiquidityDrainSignaturesForAI error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun downloadEndpointHealthForAI(limit: Int = 500): List<EndpointHealthRecord> {
        if (!isEnabled()) return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
                val result = client!!.query(
                    """
                    SELECT host, region_code, device_model,
                           AVG(success_rate) as success_rate,
                           AVG(avg_latency_ms) as avg_latency_ms,
                           SUM(successes) as successes,
                           SUM(failures_4xx) as failures_4xx,
                           SUM(failures_5xx) as failures_5xx,
                           SUM(network_errors) as network_errors,
                           MAX(updated_at) as last_seen
                    FROM collective_endpoint_health
                    WHERE updated_at > ?
                    GROUP BY host, region_code, device_model
                    ORDER BY success_rate DESC, avg_latency_ms ASC
                    LIMIT ${limit.coerceIn(50, 2000)}
                    """.trimIndent(), listOf(cutoff)
                )
                if (!result.success) emptyList() else result.rows.map { row ->
                    EndpointHealthRecord(parseString(row["host"]), parseDouble(row["success_rate"]), parseDouble(row["avg_latency_ms"]), parseInt(row["successes"]), parseInt(row["failures_4xx"]), parseInt(row["failures_5xx"]), parseInt(row["network_errors"]), parseString(row["region_code"]), parseString(row["device_model"]), parseLong(row["last_seen"]))
                }
            } catch (e: Exception) {
                Log.e(TAG, "downloadEndpointHealthForAI error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun downloadSourceReliabilityForAI(limit: Int = 500): List<SourceReliability> {
        if (!isEnabled()) return emptyList()
        val safeLimit = limit.coerceIn(50, 2000)
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 14L * 24L * 60L * 60L * 1000L
                val result = client!!.query(
                    """
                    SELECT source,
                           COUNT(*) as total_outcomes,
                           SUM(CASE WHEN is_win = 1 THEN 1 ELSE 0 END) as wins,
                           SUM(CASE WHEN is_win = 0 THEN 1 ELSE 0 END) as losses,
                           AVG(pnl_pct) as avg_pnl_pct,
                           MAX(pnl_pct) as best_pnl_pct,
                           MIN(pnl_pct) as worst_pnl_pct,
                           COUNT(DISTINCT instance_id) as instance_count,
                           MAX(timestamp) as last_seen
                    FROM collective_trades
                    WHERE source != '' AND side = 'SELL' AND timestamp > ?
                    GROUP BY source
                    HAVING COUNT(*) >= 3
                    ORDER BY last_seen DESC
                    LIMIT $safeLimit
                    """.trimIndent(),
                    listOf(cutoff)
                )
                if (result.success) {
                    result.rows.mapNotNull { row ->
                        val src = parseString(row["source"])
                        if (src.isBlank()) null else SourceReliability(
                            source = src,
                            totalOutcomes = parseInt(row["total_outcomes"]),
                            wins = parseInt(row["wins"]),
                            losses = parseInt(row["losses"]),
                            avgPnlPct = parseDouble(row["avg_pnl_pct"]),
                            bestPnlPct = parseDouble(row["best_pnl_pct"]),
                            worstPnlPct = parseDouble(row["worst_pnl_pct"]),
                            instanceCount = parseInt(row["instance_count"]).coerceAtLeast(1),
                            lastSeen = parseLong(row["last_seen"]),
                        )
                    }
                } else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "downloadSourceReliabilityForAI error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun downloadCreatorReputationForAI(limit: Int = 1000): List<CreatorReputation> {
        if (!isEnabled()) return emptyList()
        val safeLimit = limit.coerceIn(100, 5000)
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
                val result = client!!.query(
                    """
                    SELECT m.creator_address as creator_address,
                           COUNT(DISTINCT t.mint) as token_count,
                           COUNT(*) as total_outcomes,
                           SUM(CASE WHEN t.is_win = 1 THEN 1 ELSE 0 END) as wins,
                           SUM(CASE WHEN t.is_win = 0 THEN 1 ELSE 0 END) as losses,
                           AVG(t.pnl_pct) as avg_pnl_pct,
                           MIN(t.pnl_pct) as worst_pnl_pct,
                           SUM(CASE WHEN t.pnl_pct <= -25.0 THEN 1 ELSE 0 END) as rug_like_losses,
                           COUNT(DISTINCT t.instance_id) as instance_count,
                           MAX(t.timestamp) as last_seen
                    FROM collective_trades t
                    JOIN collective_token_mints m ON m.mint = t.mint
                    WHERE m.creator_address != '' AND t.side = 'SELL' AND t.timestamp > ?
                    GROUP BY m.creator_address
                    HAVING COUNT(*) >= 2
                    ORDER BY last_seen DESC
                    LIMIT $safeLimit
                    """.trimIndent(),
                    listOf(cutoff)
                )
                if (result.success) {
                    result.rows.mapNotNull { row ->
                        val creator = parseString(row["creator_address"])
                        if (creator.isBlank()) null else CreatorReputation(
                            creatorAddress = creator,
                            tokenCount = parseInt(row["token_count"]),
                            totalOutcomes = parseInt(row["total_outcomes"]),
                            wins = parseInt(row["wins"]),
                            losses = parseInt(row["losses"]),
                            avgPnlPct = parseDouble(row["avg_pnl_pct"]),
                            worstPnlPct = parseDouble(row["worst_pnl_pct"]),
                            rugLikeLosses = parseInt(row["rug_like_losses"]),
                            instanceCount = parseInt(row["instance_count"]).coerceAtLeast(1),
                            lastSeen = parseLong(row["last_seen"]),
                        )
                    }
                } else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "downloadCreatorReputationForAI error: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun downloadMintMemoryForAI(limit: Int = 1000): List<CollectiveMintMemory> {
        if (!isEnabled()) return emptyList()
        val safeLimit = limit.coerceIn(100, 5000)
        return withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - 14L * 24L * 60L * 60L * 1000L
                val result = client!!.query(
                    """
                    SELECT
                        mint,
                        MAX(symbol) as symbol,
                        COUNT(*) as total_outcomes,
                        SUM(CASE WHEN is_win = 1 THEN 1 ELSE 0 END) as wins,
                        SUM(CASE WHEN is_win = 0 THEN 1 ELSE 0 END) as losses,
                        AVG(pnl_pct) as avg_pnl_pct,
                        MAX(pnl_pct) as best_pnl_pct,
                        MIN(pnl_pct) as worst_pnl_pct,
                        COUNT(DISTINCT instance_id) as instance_count,
                        AVG(entry_score) as avg_entry_score,
                        AVG(confidence) as avg_confidence,
                        MAX(timestamp) as last_seen
                    FROM collective_trades
                    WHERE mint != '' AND side = 'SELL' AND timestamp > ?
                    GROUP BY mint
                    HAVING COUNT(*) >= 1
                    ORDER BY last_seen DESC
                    LIMIT $safeLimit
                    """.trimIndent(),
                    listOf(cutoff)
                )
                if (result.success) {
                    result.rows.mapNotNull { row ->
                        val m = parseString(row["mint"])
                        if (m.isBlank()) null else CollectiveMintMemory(
                            mint = m,
                            symbol = parseString(row["symbol"]),
                            totalOutcomes = parseInt(row["total_outcomes"]),
                            wins = parseInt(row["wins"]),
                            losses = parseInt(row["losses"]),
                            avgPnlPct = parseDouble(row["avg_pnl_pct"]),
                            bestPnlPct = parseDouble(row["best_pnl_pct"]),
                            worstPnlPct = parseDouble(row["worst_pnl_pct"]),
                            instanceCount = parseInt(row["instance_count"]).coerceAtLeast(1),
                            avgEntryScore = parseDouble(row["avg_entry_score"]),
                            avgConfidence = parseDouble(row["avg_confidence"]),
                            lastSeen = parseLong(row["last_seen"]),
                        )
                    }
                } else emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "downloadMintMemoryForAI error: ${e.message}")
                emptyList()
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
                val pnlVerdict = com.lifecyclebot.engine.LearningPnlSanitizer.inspectPct(pnlPct, "CollectiveLearning.broadcastHotToken/$mode")
                if (!pnlVerdict.ok) return@withContext false
                val safePnlPct = pnlVerdict.pnlPct
                val expiresAt = now + (2 * 60 * 60 * 1000L)

                val signalType = when {
                    safePnlPct >= 50.0 -> "MEGA_WINNER"
                    safePnlPct >= 20.0 -> "HOT_TOKEN"
                    safePnlPct <= -15.0 -> "AVOID"
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
                        sanitizeDouble(safePnlPct),
                        confidence,
                        sanitizeDouble(liquidityUsd),
                        mode,
                        reason,
                        expiresAt
                    )
                )

                if (result.success) {
                    Log.i(TAG, "BROADCAST: $signalType $symbol ${safePnlPct.toInt()}% -> NETWORK")
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
        // V5.9.1556b — client!=null means connected/initialized, not configured.
        // The UI was reporting "Turso not configured" whenever init failed or was
        // still async, even though defaults/config credentials exist. That hid the
        // real issue and made Hive Mind look disabled. Check config separately.
        val configuredByPrefs = try {
            val ctx = appContext
            if (ctx != null) {
                val cfg = com.lifecyclebot.data.ConfigStore.load(ctx)
                cfg.collectiveLearningEnabled && cfg.tursoDbUrl.isNotBlank() && cfg.tursoAuthToken.isNotBlank()
            } else false
        } catch (_: Throwable) { false }
        val connected = isInitialized && client != null
        val tursoConfigured = configuredByPrefs || client != null

        val message = when {
            !tursoConfigured -> "Turso credentials missing - LOCAL ONLY mode"
            !connected -> "Turso configured but not connected - reconnect pending"
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
            val uploadedPatterns = uploadLocalPatternAggregates()

            downloadAll()
            try { com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI.refresh() } catch (_: Throwable) {}

            val afterBlacklist = cachedBlacklist.size
            val afterPatterns = cachedPatterns.size

            val blacklistDelta = afterBlacklist - beforeBlacklist
            val patternDelta = afterPatterns - beforePatterns

            val sb = StringBuilder()
            sb.append("SYNC COMPLETE\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
            sb.append("Instance: ").append(instanceId).append('\n')
            sb.append("Blacklist: ").append(afterBlacklist).append(" (+").append(blacklistDelta).append(" new)\n")
            sb.append("Patterns: ").append(afterPatterns).append(" (+").append(patternDelta).append(" new, uploaded=").append(uploadedPatterns).append(")\n")
            sb.append("Mode Stats: ").append(cachedModeStats.size).append('\n')
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            sb.toString()
        } catch (e: Exception) {
            "Sync failed: ${e.message}"
        }
    }

    suspend fun runDiagnostics(): String {
        if (!isEnabled()) {
            try { ensureConnected() } catch (_: Throwable) {}
        }
        if (!isEnabled()) {
            val cfg = try { appContext?.let { ConfigStore.load(it) } } catch (_: Throwable) { null }
            return """
COLLECTIVE DIAGNOSTICS FAILED
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Turso CONFIGURED: ${cfg?.tursoDbUrl?.isNotBlank() == true && cfg.tursoAuthToken.isNotBlank()}
Client: ${client != null}
Initialized: $isInitialized
Last init error: ${lastInitError.ifBlank { "none captured" }}
URL present: ${cfg?.tursoDbUrl?.isNotBlank() == true}
Token present: ${cfg?.tursoAuthToken?.isNotBlank() == true}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Tap Sync to retry; if this persists, check the init error above.
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

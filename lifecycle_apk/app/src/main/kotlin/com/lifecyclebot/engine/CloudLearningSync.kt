package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

object CloudLearningSync {

    private const val PREFS_NAME = "cloud_learning_sync"
    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_LAST_UPLOAD = "last_upload_ts"
    private const val KEY_LAST_DOWNLOAD = "last_download_ts"
    private const val KEY_OPT_IN = "opt_in_sharing"
    private const val KEY_USE_COMMUNITY = "use_community_weights"
    private const val KEY_COMMUNITY_WEIGHTS = "cached_community_weights"
    private const val KEY_SCHEMA_READY = "schema_ready"

    // TURSO CONFIG
    // NOTE: Converted from libsql:// to https:// for SQL-over-HTTP pipeline usage
    private const val TURSO_HTTP_URL = "https://superbrain-shaunhayes333-stack.aws-ap-northeast-1.turso.io"
    private const val TURSO_AUTH_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJpYXQiOjE3NzU1NTE3MzQsImlkIjoiMDE5ZDMwNjYtMmUwMS03NzcyLTgyMTYtMDIyYzY1YzRmNmVjIiwicmlkIjoiMGExMzRiY2EtZmY1YS00NmQ2LWI2ZWYtYmU4MjAyYWE1ZWI4In0.PNhzeQw2rXloG3cDJaOPRg-Kq6rCpOy5kk6Q6GCD8Ar_AKC2iiW5OTKoK-q3Y78LFPWp_8ttrEhtlPz0VJ_VDw"

    private const val MIN_TRADES_TO_CONTRIBUTE = 20
    private const val UPLOAD_INTERVAL_MS = 4 * 60 * 60 * 1000L
    private const val DOWNLOAD_INTERVAL_MS = 60 * 60 * 1000L
    private const val STALE_INSTANCE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_EFFECTIVE_TRADE_WEIGHT = 250

    private var prefs: SharedPreferences? = null
    private var instanceId: String = ""
    private var isOptedIn: Boolean = true
    private var useCommunityWeights: Boolean = true
    private var lastUploadTs: Long = 0
    private var lastDownloadTs: Long = 0
    private var schemaReady: Boolean = false

    private var communityWeights: CommunityWeights? = null

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

    data class CommunityWeights(
        val featureWeights: Map<String, Double>,
        val patternMultipliers: Map<String, Double>,
        val totalContributors: Int,
        val totalTrades: Int,
        val lastUpdated: Long,
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()

        if (instanceId.isBlank()) {
            instanceId = generateAnonymousId()
            saveState()
        }

        ErrorLogger.info(
            "CloudSync",
            "Initialized Turso sync: id=${instanceId.take(8)}... optIn=$isOptedIn useCommunity=$useCommunityWeights"
        )
    }

    private fun loadState() {
        val p = prefs ?: return
        instanceId = p.getString(KEY_INSTANCE_ID, "") ?: ""
        lastUploadTs = p.getLong(KEY_LAST_UPLOAD, 0L)
        lastDownloadTs = p.getLong(KEY_LAST_DOWNLOAD, 0L)
        isOptedIn = p.getBoolean(KEY_OPT_IN, true)
        useCommunityWeights = p.getBoolean(KEY_USE_COMMUNITY, true)
        schemaReady = p.getBoolean(KEY_SCHEMA_READY, false)

        try {
            val json = p.getString(KEY_COMMUNITY_WEIGHTS, null)
            if (json != null) {
                communityWeights = parseCommunityWeights(JSONObject(json))
            }
        } catch (e: Exception) {
            ErrorLogger.debug("CloudSync", "No cached community weights: ${e.message}")
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
            putBoolean(KEY_SCHEMA_READY, schemaReady)

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

    private fun generateAnonymousId(): String {
        val uuid = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis().toString()
        val combined = "$uuid-$timestamp"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun isConfigured(): Boolean {
        return TURSO_HTTP_URL.isNotBlank() && TURSO_AUTH_TOKEN.isNotBlank()
    }

    private suspend fun ensureSchema(): Boolean = withContext(Dispatchers.IO) {
        if (schemaReady) return@withContext true
        if (!isConfigured()) return@withContext false

        try {
            val requests = JSONArray()

            requests.put(exec("PRAGMA journal_mode = WAL"))
            requests.put(exec("""
                CREATE TABLE IF NOT EXISTS collective_instances (
                    instance_id TEXT PRIMARY KEY,
                    trade_count INTEGER NOT NULL,
                    win_rate REAL NOT NULL,
                    app_version TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                )
            """.trimIndent()))

            requests.put(exec("""
                CREATE TABLE IF NOT EXISTS collective_feature_weights (
                    instance_id TEXT NOT NULL,
                    feature_name TEXT NOT NULL,
                    weight REAL NOT NULL,
                    trade_count INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (instance_id, feature_name)
                )
            """.trimIndent()))

            requests.put(exec("""
                CREATE TABLE IF NOT EXISTS collective_patterns (
                    instance_id TEXT NOT NULL,
                    pattern_name TEXT NOT NULL,
                    win_rate REAL NOT NULL,
                    profit_factor REAL NOT NULL,
                    sample_count INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY (instance_id, pattern_name)
                )
            """.trimIndent()))

            requests.put(exec("CREATE INDEX IF NOT EXISTS idx_collective_instances_updated_at ON collective_instances(updated_at)"))
            requests.put(exec("CREATE INDEX IF NOT EXISTS idx_collective_features_updated_at ON collective_feature_weights(updated_at)"))
            requests.put(exec("CREATE INDEX IF NOT EXISTS idx_collective_patterns_updated_at ON collective_patterns(updated_at)"))
            requests.put(closeReq())

            val response = pipeline(requests)
            if (response == null) {
                ErrorLogger.error("CloudSync", "Schema init failed: null response")
                return@withContext false
            }

            schemaReady = true
            saveState()
            ErrorLogger.info("CloudSync", "Turso schema ready")
            true
        } catch (e: Exception) {
            ErrorLogger.error("CloudSync", "Schema init failed: ${e.message}")
            false
        }
    }

    suspend fun uploadLearnings(
        tradeCount: Int,
        winRate: Double,
        featureWeights: Map<String, Double>,
        patternStats: List<PatternBacktester.PatternStats>,
    ): Boolean {
        if (!isConfigured()) {
            ErrorLogger.debug("CloudSync", "Upload skipped: Turso not configured")
            return false
        }

        if (!isOptedIn) {
            ErrorLogger.debug("CloudSync", "Upload skipped: opted out")
            return false
        }

        if (tradeCount < MIN_TRADES_TO_CONTRIBUTE) {
            ErrorLogger.debug(
                "CloudSync",
                "Upload skipped: need $MIN_TRADES_TO_CONTRIBUTE trades (have $tradeCount)"
            )
            return false
        }

        val now = System.currentTimeMillis()
        if (now - lastUploadTs < UPLOAD_INTERVAL_MS) {
            ErrorLogger.debug("CloudSync", "Upload skipped: too soon")
            return false
        }

        val schemaOk = ensureSchema()
        if (!schemaOk) {
            ErrorLogger.error("CloudSync", "Upload skipped: schema not ready")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val localPatternStats = patternStats.map {
                    PatternStat(
                        name = it.patternName,
                        winRate = it.winRate,
                        profitFactor = it.profitFactor,
                        sampleCount = it.totalTrades
                    )
                }

                val payload = ContributionPayload(
                    instanceId = instanceId,
                    tradeCount = tradeCount,
                    winRate = sanitizeDouble(winRate),
                    featureWeights = featureWeights.mapValues { sanitizeDouble(it.value) },
                    patternStats = localPatternStats.map {
                        it.copy(
                            winRate = sanitizeDouble(it.winRate),
                            profitFactor = sanitizeDouble(it.profitFactor)
                        )
                    },
                    appVersion = "1.0.0",
                    timestamp = now
                )

                val requests = JSONArray()
                requests.put(exec("BEGIN"))

                requests.put(
                    exec(
                        """
                        INSERT INTO collective_instances (instance_id, trade_count, win_rate, app_version, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(instance_id) DO UPDATE SET
                            trade_count = excluded.trade_count,
                            win_rate = excluded.win_rate,
                            app_version = excluded.app_version,
                            updated_at = excluded.updated_at
                        """.trimIndent(),
                        payload.instanceId,
                        payload.tradeCount,
                        payload.winRate,
                        payload.appVersion,
                        payload.timestamp
                    )
                )

                requests.put(
                    exec(
                        "DELETE FROM collective_feature_weights WHERE instance_id = ?",
                        payload.instanceId
                    )
                )
                requests.put(
                    exec(
                        "DELETE FROM collective_patterns WHERE instance_id = ?",
                        payload.instanceId
                    )
                )

                payload.featureWeights.forEach { (feature, weight) ->
                    requests.put(
                        exec(
                            """
                            INSERT INTO collective_feature_weights
                                (instance_id, feature_name, weight, trade_count, updated_at)
                            VALUES (?, ?, ?, ?, ?)
                            """.trimIndent(),
                            payload.instanceId,
                            feature,
                            weight,
                            payload.tradeCount,
                            payload.timestamp
                        )
                    )
                }

                payload.patternStats.forEach { pattern ->
                    requests.put(
                        exec(
                            """
                            INSERT INTO collective_patterns
                                (instance_id, pattern_name, win_rate, profit_factor, sample_count, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            payload.instanceId,
                            pattern.name,
                            pattern.winRate,
                            pattern.profitFactor,
                            pattern.sampleCount,
                            payload.timestamp
                        )
                    )
                }

                val staleBefore = now - STALE_INSTANCE_MS
                requests.put(
                    exec(
                        "DELETE FROM collective_feature_weights WHERE instance_id IN (SELECT instance_id FROM collective_instances WHERE updated_at < ?)",
                        staleBefore
                    )
                )
                requests.put(
                    exec(
                        "DELETE FROM collective_patterns WHERE instance_id IN (SELECT instance_id FROM collective_instances WHERE updated_at < ?)",
                        staleBefore
                    )
                )
                requests.put(
                    exec("DELETE FROM collective_instances WHERE updated_at < ?", staleBefore)
                )

                requests.put(exec("COMMIT"))
                requests.put(closeReq())

                val response = pipeline(requests)
                val success = response != null && !response.has("error")

                if (success) {
                    lastUploadTs = now
                    saveState()
                    ErrorLogger.info(
                        "CloudSync",
                        "☁️ Turso upload OK: $tradeCount trades, ${winRate.toInt()}% win rate, " +
                            "${featureWeights.size} features, ${patternStats.size} patterns"
                    )
                } else {
                    ErrorLogger.error("CloudSync", "Turso upload failed: empty/invalid response")
                }

                success
            } catch (e: Exception) {
                ErrorLogger.error("CloudSync", "Upload failed: ${e.message}")
                false
            }
        }
    }

    suspend fun downloadCommunityWeights(): CommunityWeights? {
        ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Starting community weights download...")
        
        if (!isConfigured()) {
            ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Skipped - Turso not configured")
            return communityWeights
        }

        if (!useCommunityWeights) {
            ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Skipped - community weights disabled in settings")
            return communityWeights
        }

        val now = System.currentTimeMillis()
        if (now - lastDownloadTs < DOWNLOAD_INTERVAL_MS && communityWeights != null) {
            ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Using cached weights (age=${(now - lastDownloadTs)/1000}s)")
            return communityWeights
        }

        val schemaOk = ensureSchema()
        if (!schemaOk) {
            ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Skipped - schema setup failed")
            return communityWeights
        }
        
        ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Querying Turso for community data...")

        return withContext(Dispatchers.IO) {
            try {
                val activeSince = now - STALE_INSTANCE_MS

                val statsResp = pipeline(
                    JSONArray().apply {
                        put(
                            exec(
                                """
                                SELECT
                                    COUNT(*) AS contributor_count,
                                    COALESCE(SUM(trade_count), 0) AS total_trades
                                FROM collective_instances
                                WHERE updated_at >= ?
                                """.trimIndent(),
                                activeSince
                            )
                        )
                        put(closeReq())
                    }
                )

                val statsRows = extractRows(statsResp, 0)
                val contributorCount = statsRows.firstOrNull()?.optInt("contributor_count", 0) ?: 0
                val totalTrades = statsRows.firstOrNull()?.optInt("total_trades", 0) ?: 0

                val featureResp = pipeline(
                    JSONArray().apply {
                        put(
                            exec(
                                """
                                SELECT
                                    feature_name,
                                    CASE
                                        WHEN SUM(CASE WHEN trade_count > ? THEN ? ELSE trade_count END) = 0 THEN 1.0
                                        ELSE
                                            SUM(
                                                weight *
                                                (CASE WHEN trade_count > ? THEN ? ELSE trade_count END)
                                            ) /
                                            SUM(
                                                CASE WHEN trade_count > ? THEN ? ELSE trade_count END
                                            )
                                    END AS blended_weight
                                FROM collective_feature_weights
                                WHERE updated_at >= ?
                                GROUP BY feature_name
                                """.trimIndent(),
                                MAX_EFFECTIVE_TRADE_WEIGHT,
                                MAX_EFFECTIVE_TRADE_WEIGHT,
                                MAX_EFFECTIVE_TRADE_WEIGHT,
                                MAX_EFFECTIVE_TRADE_WEIGHT,
                                MAX_EFFECTIVE_TRADE_WEIGHT,
                                MAX_EFFECTIVE_TRADE_WEIGHT,
                                activeSince
                            )
                        )
                        put(closeReq())
                    }
                )

                val featureWeights = mutableMapOf<String, Double>()
                extractRows(featureResp, 0).forEach { row ->
                    val name = row.optString("feature_name", "")
                    val weight = row.optDouble("blended_weight", 1.0)
                    if (name.isNotBlank()) {
                        featureWeights[name] = sanitizeDouble(weight, 1.0)
                    }
                }

                val patternResp = pipeline(
                    JSONArray().apply {
                        put(
                            exec(
                                """
                                SELECT pattern_name, win_rate, profit_factor, sample_count
                                FROM collective_patterns
                                WHERE updated_at >= ?
                                """.trimIndent(),
                                activeSince
                            )
                        )
                        put(closeReq())
                    }
                )

                val patternBuckets = mutableMapOf<String, MutableList<PatternStat>>()
                extractRows(patternResp, 0).forEach { row ->
                    val patternName = row.optString("pattern_name", "")
                    if (patternName.isBlank()) return@forEach

                    val stat = PatternStat(
                        name = patternName,
                        winRate = sanitizeDouble(row.optDouble("win_rate", 0.0), 0.0),
                        profitFactor = sanitizeDouble(row.optDouble("profit_factor", 1.0), 1.0),
                        sampleCount = row.optInt("sample_count", 0)
                    )
                    patternBuckets.getOrPut(patternName) { mutableListOf() }.add(stat)
                }

                val patternMultipliers = mutableMapOf<String, Double>()
                patternBuckets.forEach { (patternName, stats) ->
                    val weightedTrades = stats.sumOf { min(it.sampleCount, MAX_EFFECTIVE_TRADE_WEIGHT) }
                    if (weightedTrades <= 0) {
                        patternMultipliers[patternName] = 1.0
                    } else {
                        val weightedWinRate = stats.sumOf {
                            it.winRate * min(it.sampleCount, MAX_EFFECTIVE_TRADE_WEIGHT).toDouble()
                        } / weightedTrades.toDouble()

                        val weightedPf = stats.sumOf {
                            clamp(it.profitFactor, 0.25, 4.0) *
                                min(it.sampleCount, MAX_EFFECTIVE_TRADE_WEIGHT).toDouble()
                        } / weightedTrades.toDouble()

                        val winComponent = ((weightedWinRate - 50.0) / 50.0) * 0.35
                        val pfComponent = ((weightedPf - 1.0) / 1.5) * 0.25
                        val sampleConfidence = clamp(weightedTrades / 100.0, 0.15, 1.0)

                        val raw = 1.0 + ((winComponent + pfComponent) * sampleConfidence)
                        patternMultipliers[patternName] = clamp(raw, 0.70, 1.35)
                    }
                }

                communityWeights = CommunityWeights(
                    featureWeights = featureWeights,
                    patternMultipliers = patternMultipliers,
                    totalContributors = contributorCount,
                    totalTrades = totalTrades,
                    lastUpdated = now
                )

                lastDownloadTs = now
                saveState()

                ErrorLogger.info(
                    "CloudSync",
                    "☁️ Turso download OK: contributors=$contributorCount totalTrades=$totalTrades " +
                        "features=${featureWeights.size} patterns=${patternMultipliers.size}"
                )

                communityWeights
            } catch (e: Exception) {
                ErrorLogger.error("CloudSync", "Download failed: ${e.message}")
                communityWeights
            }
        }
    }

    private fun parseCommunityWeights(obj: JSONObject): CommunityWeights {
        val weightsObj = obj.optJSONObject("featureWeights")
            ?: obj.optJSONObject("feature_weights")
            ?: JSONObject()
        val weights = mutableMapOf<String, Double>()
        weightsObj.keys().forEach { key ->
            weights[key] = sanitizeDouble(weightsObj.optDouble(key, 1.0), 1.0)
        }

        val multObj = obj.optJSONObject("patternMultipliers")
            ?: obj.optJSONObject("pattern_multipliers")
            ?: JSONObject()
        val mults = mutableMapOf<String, Double>()
        multObj.keys().forEach { key ->
            mults[key] = sanitizeDouble(multObj.optDouble(key, 1.0), 1.0)
        }

        return CommunityWeights(
            featureWeights = weights,
            patternMultipliers = mults,
            totalContributors = obj.optInt("totalContributors", obj.optInt("total_contributors", 0)),
            totalTrades = obj.optInt("totalTrades", obj.optInt("total_trades", 0)),
            lastUpdated = obj.optLong("lastUpdated", System.currentTimeMillis())
        )
    }

    fun blendWeights(
        localWeights: Map<String, Double>,
        localTradeCount: Int,
    ): Map<String, Double> {
        val community = communityWeights ?: return localWeights
        if (!useCommunityWeights || community.featureWeights.isEmpty()) return localWeights

        val localRatio = when {
            localTradeCount >= 300 -> 0.80
            localTradeCount >= 150 -> 0.65
            localTradeCount >= 75 -> 0.50
            localTradeCount >= 30 -> 0.35
            else -> 0.15
        }
        val communityRatio = 1.0 - localRatio

        val blended = mutableMapOf<String, Double>()
        val allKeys = localWeights.keys + community.featureWeights.keys

        for (key in allKeys) {
            val local = sanitizeDouble(localWeights[key] ?: 1.0, 1.0)
            val comm = sanitizeDouble(community.featureWeights[key] ?: 1.0, 1.0)
            blended[key] = (local * localRatio) + (comm * communityRatio)
        }

        return blended
    }

    fun getCommunityPatternMultiplier(patternName: String): Double {
        if (!useCommunityWeights) return 1.0
        return sanitizeDouble(communityWeights?.patternMultipliers?.get(patternName) ?: 1.0, 1.0)
    }

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
            return "CloudSync: Turso not configured"
        }
        val community = communityWeights
        return if (community != null) {
            "CloudSync[Turso]: ${community.totalContributors} contributors, ${community.totalTrades} trades | " +
                "optIn=$isOptedIn useCommunity=$useCommunityWeights"
        } else {
            "CloudSync[Turso]: No community data yet | optIn=$isOptedIn useCommunity=$useCommunityWeights"
        }
    }

    fun getCommunityStats(): String {
        val community = communityWeights ?: return "No community data"
        return "🌐 Community: ${community.totalContributors} bots, ${community.totalTrades} trades learned"
    }

    private fun exec(sql: String, vararg args: Any?): JSONObject {
        val stmt = JSONObject().put("sql", sql)
        if (args.isNotEmpty()) {
            val jsonArgs = JSONArray()
            args.forEach { jsonArgs.put(toTursoArg(it)) }
            stmt.put("args", jsonArgs)
        }
        return JSONObject()
            .put("type", "execute")
            .put("stmt", stmt)
    }

    private fun closeReq(): JSONObject = JSONObject().put("type", "close")

    private fun toTursoArg(value: Any?): JSONObject {
        return when (value) {
            null -> JSONObject().put("type", "null")
            is Int -> JSONObject().put("type", "integer").put("value", value.toString())
            is Long -> JSONObject().put("type", "integer").put("value", value.toString())
            is Float -> JSONObject().put("type", "float").put("value", value.toString())
            is Double -> JSONObject().put("type", "float").put("value", value.toString())
            is Boolean -> JSONObject().put("type", "integer").put("value", if (value) "1" else "0")
            else -> JSONObject().put("type", "text").put("value", value.toString())
        }
    }

    private fun pipeline(requests: JSONArray): JSONObject? {
        val baseUrl = TURSO_HTTP_URL.removeSuffix("/")
        val endpoint = "$baseUrl/v2/pipeline"
        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection

        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $TURSO_AUTH_TOKEN")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().put("requests", requests).toString()
            conn.outputStream.bufferedWriter().use { it.write(body) }

            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "{\"error\":\"HTTP $code\"}"
            }

            val obj = JSONObject(text)
            if (code !in 200..299) {
                ErrorLogger.error("CloudSync", "Turso HTTP $code: $text")
            }
            obj
        } catch (e: Exception) {
            ErrorLogger.error("CloudSync", "Turso pipeline error: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun extractRows(response: JSONObject?, resultIndex: Int): List<JSONObject> {
        if (response == null) return emptyList()

        val results = response.optJSONArray("results") ?: return emptyList()
        if (resultIndex !in 0 until results.length()) return emptyList()

        val result = results.optJSONObject(resultIndex) ?: return emptyList()
        val cols = result.optJSONArray("cols") ?: JSONArray()
        val rows = result.optJSONArray("rows") ?: JSONArray()

        val output = mutableListOf<JSONObject>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val mapped = JSONObject()

            for (j in 0 until cols.length()) {
                val colObj = cols.optJSONObject(j)
                val colName = colObj?.optString("name")
                    ?: colObj?.optString("column")
                    ?: "col_$j"

                val cell = row.opt(j)
                when (val v = decodeCell(cell)) {
                    null -> mapped.put(colName, JSONObject.NULL)
                    is Int -> mapped.put(colName, v)
                    is Long -> mapped.put(colName, v)
                    is Double -> mapped.put(colName, v)
                    is Boolean -> mapped.put(colName, v)
                    else -> mapped.put(colName, v.toString())
                }
            }

            output.add(mapped)
        }

        return output
    }

    private fun decodeCell(cell: Any?): Any? {
        return when (cell) {
            null, JSONObject.NULL -> null
            is JSONObject -> {
                val type = cell.optString("type", "")
                when (type) {
                    "null" -> null
                    "integer" -> cell.optString("value", "0").toLongOrNull() ?: 0L
                    "float" -> cell.optString("value", "0").toDoubleOrNull() ?: 0.0
                    "text" -> cell.optString("value", "")
                    "blob" -> cell.optString("base64", "")
                    else -> {
                        if (cell.has("value")) {
                            cell.opt("value")
                        } else {
                            cell.toString()
                        }
                    }
                }
            }
            is Int, is Long, is Double, is Boolean, is String -> cell
            is Number -> cell.toDouble()
            else -> cell.toString()
        }
    }

    private fun sanitizeDouble(value: Double, default: Double = 0.0): Double {
        if (value.isNaN() || value.isInfinite()) return default
        return value
    }

    private fun clamp(value: Double, min: Double, max: Double): Double {
        return max(min, min(value, max))
    }
}
package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import com.lifecyclebot.data.ConfigStore
import com.lifecyclebot.network.SharedHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.UUID

object CloudLearningSync {
    // Direct database writes from untrusted mobile clients are not a hive
    // protocol. Keep cross-instance sync disabled until contributions pass a
    // signed, rate-limited gateway with provenance and poisoning controls.
    private const val SECURE_HIVE_GATEWAY_READY_6637 = false

    private const val PREFS_NAME = "cloud_learning_sync"
    private const val KEY_INSTANCE_ID = "instance_id"
    private const val KEY_LAST_UPLOAD = "last_upload_ts"
    private const val KEY_LAST_DOWNLOAD = "last_download_ts"
    private const val KEY_OPT_IN = "opt_in_sharing"
    private const val KEY_USE_COMMUNITY = "use_community_weights"
    private const val KEY_COMMUNITY_WEIGHTS = "cached_community_weights"
    private const val KEY_SCHEMA_READY = "schema_ready"
    // Database layout bookkeeping only. Model compatibility uses a typed namespace;
    // a client must never delete shared tables to repair local schema-version drift.
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val CURRENT_SCHEMA_VERSION = 2

    // Credentials are loaded from ConfigStore's encrypted settings at init.
    // No shared database token is compiled into the APK.
    @Volatile private var tursoHttpUrl: String = ""
    @Volatile private var tursoAuthToken: String = ""

    private const val MIN_TRADES_TO_CONTRIBUTE = 20
    private const val UPLOAD_INTERVAL_MS = 4 * 60 * 60 * 1000L
    private const val DOWNLOAD_INTERVAL_MS = 60 * 60 * 1000L
    private const val STALE_INSTANCE_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_EFFECTIVE_TRADE_WEIGHT = 250
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val CALL_TIMEOUT_MS = 12_000L
    private val httpClient = SharedHttpClient.builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private var prefs: SharedPreferences? = null
    private var instanceId: String = ""
    private var optedIn: Boolean = false
    private var usingCommunityWeights: Boolean = false
    private var lastUploadTs: Long = 0L
    private var lastDownloadTs: Long = 0L
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
        val adaptiveFeatureSamples8527: Map<String, Int> = emptyMap(),
    )

    data class PatternStat(
        val name: String,
        val winRate: Double,
        val profitFactor: Double,
        val sampleCount: Int
    )

    data class CommunityWeights(
        val featureWeights: Map<String, Double>,
        val patternMultipliers: Map<String, Double>,
        val totalContributors: Int,
        val totalTrades: Int,
        val lastUpdated: Long,
        val adaptive8527: AdaptiveWeightTransfer8527.CompatibleModel = AdaptiveWeightTransfer8527.CompatibleModel(),
    )

    fun init(context: Context) {
        val configured = try { ConfigStore.load(context.applicationContext) } catch (_: Throwable) { null }
        tursoAuthToken = configured?.tursoAuthToken.orEmpty()
        tursoHttpUrl = configured?.tursoDbUrl.orEmpty()
            .trim()
            .removePrefix("libsql://")
            .removePrefix("https://")
            .removePrefix("http://")
            .takeIf { it.isNotBlank() }
            ?.let { "https://$it" }
            .orEmpty()
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadState()

        if (instanceId.isBlank()) {
            instanceId = generateAnonymousId()
            saveState()
        }

        ErrorLogger.info(
            "CloudSync",
            "Initialized Turso sync: id=${instanceId.take(8)}... optIn=$optedIn useCommunity=$usingCommunityWeights"
        )
    }

    private fun loadState() {
        val p = prefs ?: return

        instanceId = p.getString(KEY_INSTANCE_ID, "") ?: ""
        lastUploadTs = p.getLong(KEY_LAST_UPLOAD, 0L)
        lastDownloadTs = p.getLong(KEY_LAST_DOWNLOAD, 0L)
        optedIn = p.getBoolean(KEY_OPT_IN, false)
        usingCommunityWeights = p.getBoolean(KEY_USE_COMMUNITY, false)
        schemaReady = p.getBoolean(KEY_SCHEMA_READY, false)

        try {
            val json = p.getString(KEY_COMMUNITY_WEIGHTS, null)
            if (!json.isNullOrBlank()) {
                communityWeights = parseCommunityWeights(JSONObject(json))
            }
        } catch (e: Exception) {
            ErrorLogger.debug("CloudSync", "Failed to parse cached community weights: ${e.message}")
            communityWeights = null
        }
    }

    private fun saveState() {
        val p = prefs ?: return

        p.edit().apply {
            putString(KEY_INSTANCE_ID, instanceId)
            putLong(KEY_LAST_UPLOAD, lastUploadTs)
            putLong(KEY_LAST_DOWNLOAD, lastDownloadTs)
            putBoolean(KEY_OPT_IN, optedIn)
            putBoolean(KEY_USE_COMMUNITY, usingCommunityWeights)
            putBoolean(KEY_SCHEMA_READY, schemaReady)

            if (communityWeights != null) {
                val cw = communityWeights!!
                val json = JSONObject().apply {
                    put("featureWeights", JSONObject(cw.featureWeights))
                    put("patternMultipliers", JSONObject(cw.patternMultipliers))
                    put("totalContributors", cw.totalContributors)
                    put("totalTrades", cw.totalTrades)
                    put("lastUpdated", cw.lastUpdated)
                    put("adaptive8527", AdaptiveWeightTransfer8527.encodeCompatible8527(cw.adaptive8527))
                }
                putString(KEY_COMMUNITY_WEIGHTS, json.toString())
            } else {
                remove(KEY_COMMUNITY_WEIGHTS)
            }

            apply()
        }
    }

    private fun generateAnonymousId(): String {
        val combined = "${UUID.randomUUID()}-${System.currentTimeMillis()}"
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(combined.toByteArray())
        return hash.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun isConfigured(): Boolean {
        return SECURE_HIVE_GATEWAY_READY_6637 &&
            tursoHttpUrl.isNotBlank() &&
            tursoAuthToken.isNotBlank()
    }

    private suspend fun ensureSchema(): Boolean = withContext(Dispatchers.IO) {
        if (schemaReady) return@withContext true
        if (!isConfigured()) return@withContext false

        try {
            // Existing shared tables survive client upgrades. Retain the additive
            // CREATE/ALTER/index path; incompatible data remains non-authoritative.
            // Phase 1: Create tables (IF NOT EXISTS won't modify existing tables)
            val createRequests = JSONArray().apply {
                put(
                    exec(
                        """
                        CREATE TABLE IF NOT EXISTS collective_instances (
                            instance_id TEXT PRIMARY KEY,
                            trade_count INTEGER NOT NULL,
                            win_rate REAL NOT NULL,
                            app_version TEXT NOT NULL,
                            updated_at INTEGER NOT NULL
                        )
                        """.trimIndent()
                    )
                )
                put(
                    exec(
                        """
                        CREATE TABLE IF NOT EXISTS collective_feature_weights (
                            instance_id TEXT NOT NULL,
                            feature_name TEXT NOT NULL,
                            weight REAL NOT NULL,
                            trade_count INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY (instance_id, feature_name)
                        )
                        """.trimIndent()
                    )
                )
                put(
                    exec(
                        """
                        CREATE TABLE IF NOT EXISTS collective_patterns (
                            instance_id TEXT NOT NULL,
                            pattern_name TEXT NOT NULL,
                            win_rate REAL NOT NULL,
                            profit_factor REAL NOT NULL,
                            sample_count INTEGER NOT NULL,
                            updated_at INTEGER NOT NULL,
                            PRIMARY KEY (instance_id, pattern_name)
                        )
                        """.trimIndent()
                    )
                )
                put(closeReq())
            }

            val createResp = pipeline(createRequests)
            if (createResp == null) {
                ErrorLogger.error("CloudSync", "Schema init failed: null response")
                return@withContext false
            }
            // Log but don't fail on create errors (tables may already exist)
            if (pipelineHasErrors(createResp)) {
                ErrorLogger.warn("CloudSync", "Schema create had warnings (tables may pre-exist)")
            }

            // Phase 2: Migrations — add missing columns to older tables
            // ALTER TABLE ADD COLUMN is safe: errors if column exists (expected, ignore)
            val migrateRequests = JSONArray().apply {
                put(exec("ALTER TABLE collective_instances ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"))
                put(exec("ALTER TABLE collective_feature_weights ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"))
                put(exec("ALTER TABLE collective_patterns ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0"))
                put(exec("ALTER TABLE collective_patterns ADD COLUMN pattern_name TEXT NOT NULL DEFAULT ''"))
                put(exec("ALTER TABLE collective_patterns ADD COLUMN win_rate REAL NOT NULL DEFAULT 0.0"))
                put(exec("ALTER TABLE collective_patterns ADD COLUMN profit_factor REAL NOT NULL DEFAULT 0.0"))
                put(exec("ALTER TABLE collective_patterns ADD COLUMN sample_count INTEGER NOT NULL DEFAULT 0"))
                put(closeReq())
            }
            pipeline(migrateRequests) // Ignore errors — columns may already exist

            // Phase 3: Create indexes (now columns guaranteed to exist)
            val indexRequests = JSONArray().apply {
                put(exec("CREATE INDEX IF NOT EXISTS idx_collective_instances_updated_at ON collective_instances(updated_at)"))
                put(exec("CREATE INDEX IF NOT EXISTS idx_collective_features_updated_at ON collective_feature_weights(updated_at)"))
                put(exec("CREATE INDEX IF NOT EXISTS idx_collective_patterns_updated_at ON collective_patterns(updated_at)"))
                put(closeReq())
            }
            val indexResp = pipeline(indexRequests)
            if (indexResp != null && pipelineHasErrors(indexResp)) {
                ErrorLogger.warn("CloudSync", "Index creation had warnings (non-fatal)")
            }

            schemaReady = true
            saveState()
            // Pin the additive layout version; shared tables are never dropped.
            prefs?.edit()?.putInt(KEY_SCHEMA_VERSION, CURRENT_SCHEMA_VERSION)?.apply()
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
        adaptive8527: AdaptiveWeightTransfer8527.LocalModel = AdaptiveWeightTransfer8527.LocalModel(),
    ): Boolean {
        if (!isConfigured()) {
            ErrorLogger.debug("CloudSync", "Upload skipped: Turso not configured")
            return false
        }

        if (!optedIn) {
            ErrorLogger.debug("CloudSync", "Upload skipped: opted out")
            return false
        }

        val legacyContributionReady8527 = tradeCount >= MIN_TRADES_TO_CONTRIBUTE
        val adaptiveContributionReady8527 = AdaptiveWeightTransfer8527.exportReady8527(adaptive8527, MIN_TRADES_TO_CONTRIBUTE)
        if (!legacyContributionReady8527 && !adaptiveContributionReady8527) {
            ErrorLogger.debug("CloudSync", "Upload skipped: neither channel has its own observed sample support")
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
                val localPatternStats = (if (legacyContributionReady8527) patternStats else emptyList()).map {
                    PatternStat(
                        name = it.patternName,
                        winRate = sanitizeDouble(it.winRate),
                        profitFactor = sanitizeDouble(it.profitFactor, 1.0),
                        sampleCount = it.totalTrades
                    )
                }

                // Backtest/global totals do not certify any adaptive feature's training support.
                val transferWeights8527 = if (adaptiveContributionReady8527) AdaptiveWeightTransfer8527.cloudWeights8527(adaptive8527) else emptyMap()
                val payload = ContributionPayload(
                    instanceId = instanceId,
                    tradeCount = if (legacyContributionReady8527) tradeCount else adaptive8527.trainedPositions,
                    winRate = if (legacyContributionReady8527) sanitizeDouble(winRate) else 0.0,
                    featureWeights = transferWeights8527,
                    patternStats = localPatternStats,
                    appVersion = com.lifecyclebot.BuildConfig.VERSION_NAME,
                    timestamp = now,
                    adaptiveFeatureSamples8527 = transferWeights8527.keys.associateWith {
                        requireNotNull(AdaptiveWeightTransfer8527.cloudSampleCount8527(adaptive8527, it))
                    },
                )

                val requests = JSONArray().apply {
                    put(exec("BEGIN"))

                    put(
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

                    if (adaptiveContributionReady8527) {
                        put(
                            exec(
                                "DELETE FROM collective_feature_weights WHERE instance_id = ?",
                                payload.instanceId
                            )
                        )
                    }

                    if (legacyContributionReady8527) {
                        put(
                            exec(
                                "DELETE FROM collective_patterns WHERE instance_id = ?",
                                payload.instanceId
                            )
                        )
                    }

                    payload.featureWeights.forEach { (feature, weight) ->
                        put(
                            exec(
                                """
                                INSERT INTO collective_feature_weights
                                    (instance_id, feature_name, weight, trade_count, updated_at)
                                VALUES (?, ?, ?, ?, ?)
                                """.trimIndent(),
                                payload.instanceId,
                                feature,
                                weight,
                                payload.adaptiveFeatureSamples8527.getValue(feature),
                                payload.timestamp
                            )
                        )
                    }

                    payload.patternStats.forEach { pattern ->
                        put(
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

                    put(
                        exec(
                            "DELETE FROM collective_feature_weights WHERE instance_id IN (SELECT instance_id FROM collective_instances WHERE updated_at < ?)",
                            staleBefore
                        )
                    )

                    put(
                        exec(
                            "DELETE FROM collective_patterns WHERE instance_id IN (SELECT instance_id FROM collective_instances WHERE updated_at < ?)",
                            staleBefore
                        )
                    )

                    put(
                        exec(
                            "DELETE FROM collective_instances WHERE updated_at < ?",
                            staleBefore
                        )
                    )

                    put(exec("COMMIT"))
                    put(closeReq())
                }

                val response = pipeline(requests)
                val success = response != null && !pipelineHasErrors(response)

                if (success) {
                    lastUploadTs = now
                    saveState()
                    ErrorLogger.info(
                        "CloudSync",
                        "☁️ Turso upload OK: $tradeCount trades, ${winRate.toInt()}% win rate, " +
                            "${featureWeights.size} features, ${patternStats.size} patterns"
                    )
                } else {
                    ErrorLogger.error("CloudSync", "Turso upload failed: pipeline returned errors")
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
        val now = System.currentTimeMillis()
        // Disabled means not applicable, including cached/error/configuration branches.
        if (!usingCommunityWeights) return null

        if (!isConfigured()) {
            ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Skipped - Turso not configured")
            return applicableCache8527(now)
        }

        if (now - lastDownloadTs in 0L until DOWNLOAD_INTERVAL_MS && applicableCache8527(now) != null) {
            ErrorLogger.info(
                "CloudSync",
                "☁️ DOWNLOAD: Using cached weights (age=${(now - lastDownloadTs) / 1000}s)"
            )
            return applicableCache8527(now)
        }

        val schemaOk = ensureSchema()
        if (!schemaOk) {
            ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Skipped - schema setup failed")
            return applicableCache8527(now)
        }

        ErrorLogger.info("CloudSync", "☁️ DOWNLOAD: Querying Turso for community data...")

        return withContext(Dispatchers.IO) {
            try {
                val activeSince = now - STALE_INSTANCE_MS

                val featureResp = pipeline(
                    JSONArray().apply {
                        put(
                            exec(
                                """
                                SELECT instance_id, feature_name, weight, trade_count, updated_at
                                FROM collective_feature_weights
                                WHERE updated_at >= ? AND updated_at <= ?
                                  AND substr(feature_name, 1, ?) = ?
                                  AND trade_count BETWEEN 1 AND ?
                                  AND instance_id != ?
                                ORDER BY updated_at DESC
                                LIMIT 10000
                                """.trimIndent(),
                                activeSince,
                                now,
                                AdaptiveWeightTransfer8527.WIRE_PREFIX.length,
                                AdaptiveWeightTransfer8527.WIRE_PREFIX,
                                AdaptiveWeightTransfer8527.MAX_FEATURE_SAMPLES,
                                instanceId
                            )
                        )
                        put(closeReq())
                    }
                )

                if (featureResp == null || pipelineHasErrors(featureResp)) {
                    ErrorLogger.error("CloudSync", "DOWNLOAD feature query failed")
                    return@withContext applicableCache8527(now)
                }

                val contributions8527 = mutableListOf<AdaptiveWeightTransfer8527.Contribution>()
                extractRows(featureResp, 0).forEach { row ->
                    val count = (row.opt("trade_count") as? Number)?.toDouble()
                    if (count == null || !count.isFinite() || count != count.toInt().toDouble()) return@forEach
                    AdaptiveWeightTransfer8527.cloudContribution8527(
                        row.optString("instance_id", ""),
                        row.optString("feature_name", ""),
                        row.optDouble("weight", Double.NaN),
                        count.toInt(),
                        row.optLong("updated_at", 0L),
                    )?.let { contributions8527.add(it) }
                }
                val compatible8527 = AdaptiveWeightTransfer8527.aggregate8527(
                    contributions8527, now, STALE_INSTANCE_MS, instanceId)
                val featureWeights = AdaptiveWeightTransfer8527.importableWeights8527(compatible8527, 1)
                // Conservative support summaries; never sum the same position across 17 features.
                val contributorCount = compatible8527.features.values.maxOfOrNull { it.contributors } ?: 0
                val totalTrades = compatible8527.features.values.maxOfOrNull { it.samples } ?: 0

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

                val patternRows8527 = if (patternResp != null && !pipelineHasErrors(patternResp)) {
                    extractRows(patternResp, 0)
                } else {
                    ErrorLogger.error("CloudSync", "DOWNLOAD pattern query failed; compatible adaptive evidence retained")
                    emptyList()
                }

                val patternBuckets = mutableMapOf<String, MutableList<PatternStat>>()
                patternRows8527.forEach { row ->
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
                    val weightedTrades = stats.sumOf { minInt(it.sampleCount, MAX_EFFECTIVE_TRADE_WEIGHT) }

                    if (weightedTrades <= 0) {
                        patternMultipliers[patternName] = 1.0
                    } else {
                        val weightedWinRate = stats.sumOf {
                            it.winRate * minInt(it.sampleCount, MAX_EFFECTIVE_TRADE_WEIGHT).toDouble()
                        } / weightedTrades.toDouble()

                        val weightedPf = stats.sumOf {
                            clamp(it.profitFactor, 0.25, 4.0) *
                                minInt(it.sampleCount, MAX_EFFECTIVE_TRADE_WEIGHT).toDouble()
                        } / weightedTrades.toDouble()

                        val winComponent = ((weightedWinRate - 50.0) / 50.0) * 0.35
                        val pfComponent = ((weightedPf - 1.0) / 1.5) * 0.25
                        val sampleConfidence = clamp(weightedTrades / 100.0, 0.15, 1.0)

                        val raw = 1.0 + ((winComponent + pfComponent) * sampleConfidence)
                        patternMultipliers[patternName] = clamp(raw, 0.70, 1.35)
                    }
                // V5.9.8: Push collective pattern win rates into BehaviorAI for local gating
                patternMultipliers.forEach { (name, mult) ->
                    // mult < 0.8 means collective win rate < 40% — suppress locally too
                    if (mult < 0.8) {
                        try { com.lifecyclebot.v3.scoring.BehaviorAI.suppressPattern(name) } catch (_: Exception) {}
                    } else if (mult > 1.1) {
                        try { com.lifecyclebot.v3.scoring.BehaviorAI.boostPattern(name) } catch (_: Exception) {}
                    }
                }
                }

                communityWeights = CommunityWeights(
                    featureWeights = featureWeights,
                    patternMultipliers = patternMultipliers,
                    totalContributors = contributorCount,
                    totalTrades = totalTrades,
                    lastUpdated = now,
                    adaptive8527 = compatible8527,
                )

                lastDownloadTs = now
                saveState()

                ErrorLogger.info(
                    "CloudSync",
                    "☁️ Turso download OK: contributors=$contributorCount totalTrades=$totalTrades " +
                        "features=${featureWeights.size} patterns=${patternMultipliers.size}"
                )

                applicableCache8527(now)
            } catch (e: Exception) {
                ErrorLogger.error("CloudSync", "Download failed: ${e.message}")
                applicableCache8527(now)
            }
        }
    }

    private fun applicableCache8527(nowMs: Long): CommunityWeights? =
        communityWeights?.takeIf {
            AdaptiveWeightTransfer8527.cacheApplicable8527(usingCommunityWeights, it.lastUpdated, nowMs, STALE_INSTANCE_MS)
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

        val multipliers = mutableMapOf<String, Double>()
        multObj.keys().forEach { key ->
            multipliers[key] = sanitizeDouble(multObj.optDouble(key, 1.0), 1.0)
        }

        return CommunityWeights(
            featureWeights = weights,
            patternMultipliers = multipliers,
            totalContributors = obj.optInt("totalContributors", obj.optInt("total_contributors", 0)),
            totalTrades = obj.optInt("totalTrades", obj.optInt("total_trades", 0)),
            lastUpdated = obj.optLong("lastUpdated", obj.optLong("last_updated", 0L)),
            adaptive8527 = AdaptiveWeightTransfer8527.decodeCompatible8527(obj.optJSONObject("adaptive8527")),
        )
    }

    fun blendWeights(
        localWeights: Map<String, Double>,
        localTradeCount: Int
    ): Map<String, Double> {
        val community = applicableCache8527(System.currentTimeMillis()) ?: return localWeights
        return AdaptiveWeightTransfer8527.communityBlend8527(
            localWeights, community.adaptive8527, AdaptiveLearningEngine.getTradeCount())
    }

    fun getCommunityPatternMultiplier(patternName: String): Double {
        if (!usingCommunityWeights) return 1.0
        return sanitizeDouble(communityWeights?.patternMultipliers?.get(patternName) ?: 1.0, 1.0)
    }

    fun setOptIn(enabled: Boolean) {
        optedIn = enabled
        saveState()
        ErrorLogger.info("CloudSync", "Opt-in sharing: $enabled")
    }

    fun setUseCommunityWeights(enabled: Boolean) {
        usingCommunityWeights = enabled
        saveState()
        ErrorLogger.info("CloudSync", "Use community weights: $enabled")
    }

    fun isOptedIn(): Boolean = optedIn

    fun isUsingCommunityWeights(): Boolean = usingCommunityWeights

    fun getStatus(): String {
        if (!isConfigured()) {
            return "CloudSync: Turso not configured"
        }

        val community = communityWeights
        return if (community != null) {
            "CloudSync[Turso]: ${community.totalContributors} contributors, ${community.totalTrades} trades | " +
                "optIn=$optedIn useCommunity=$usingCommunityWeights"
        } else {
            "CloudSync[Turso]: No community data yet | optIn=$optedIn useCommunity=$usingCommunityWeights"
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

    private fun closeReq(): JSONObject {
        return JSONObject().put("type", "close")
    }

    private fun toTursoArg(value: Any?): JSONObject {
        return when (value) {
            null -> JSONObject().put("type", "null")
            is Int -> JSONObject().put("type", "integer").put("value", value.toString())
            is Long -> JSONObject().put("type", "integer").put("value", value.toString())
            // Turso hrana protocol requires float values as JSON numbers, NOT strings.
            // Integers use strings (to preserve 64-bit precision), but f64 must be numeric.
            is Float -> JSONObject().put("type", "float").put("value", value.toDouble())
            is Double -> JSONObject().put("type", "float").put("value", value)
            is Boolean -> JSONObject().put("type", "integer").put("value", if (value) "1" else "0")
            else -> JSONObject().put("type", "text").put("value", value.toString())
        }
    }

    private fun pipeline(requests: JSONArray): JSONObject? {
        val baseUrl = tursoHttpUrl.removeSuffix("/")
        val endpoint = "$baseUrl/v2/pipeline"
        return try {
            val body = JSONObject().put("requests", requests).toString()
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $tursoAuthToken")
                .header("Accept", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string() ?: "{\"error\":\"HTTP ${response.code}\"}"
                if (!response.isSuccessful) ErrorLogger.error("CloudSync", "Turso HTTP ${response.code}: $text")
                JSONObject(text)
            }
        } catch (e: Exception) {
            ErrorLogger.error("CloudSync", "Turso pipeline error: ${e.message}")
            null
        }
    }

    private fun pipelineHasErrors(response: JSONObject): Boolean {
        if (response.has("error")) {
            ErrorLogger.error("CloudSync", "Pipeline top-level error: ${response.optString("error")}")
            return true
        }

        val results = response.optJSONArray("results") ?: return false
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue

            // Skip close responses — they don't have error fields
            if (item.optString("type") == "close") continue

            if (item.has("error")) {
                ErrorLogger.error("CloudSync", "Pipeline item #$i error: ${item.optJSONObject("error")?.optString("message") ?: item.optString("error")}")
                return true
            }

            val responseObj = item.optJSONObject("response")
            if (responseObj?.has("error") == true) {
                ErrorLogger.error("CloudSync", "Pipeline item #$i response error: ${responseObj.optString("error")}")
                return true
            }

            val resultObj = responseObj?.optJSONObject("result")
            if (resultObj?.has("error") == true) {
                ErrorLogger.error("CloudSync", "Pipeline item #$i result error: ${resultObj.optString("error")}")
                return true
            }
        }

        return false
    }

    private fun extractRows(response: JSONObject?, resultIndex: Int): List<JSONObject> {
        if (response == null) return emptyList()

        val results = response.optJSONArray("results") ?: return emptyList()
        if (resultIndex !in 0 until results.length()) return emptyList()

        val item = results.optJSONObject(resultIndex) ?: return emptyList()

        val container =
            item.optJSONObject("response")?.optJSONObject("result")
                ?: item.optJSONObject("result")
                ?: item

        val cols = container.optJSONArray("cols") ?: JSONArray()
        val rows = container.optJSONArray("rows") ?: JSONArray()
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
                        if (cell.has("value")) cell.opt("value") else cell.toString()
                    }
                }
            }
            is Int, is Long, is Double, is Boolean, is String -> cell
            is Number -> cell.toDouble()
            else -> cell.toString()
        }
    }

    private fun sanitizeDouble(value: Double, default: Double = 0.0): Double {
        return if (value.isNaN() || value.isInfinite()) default else value
    }

    private fun clamp(value: Double, minValue: Double, maxValue: Double): Double {
        return value.coerceIn(minValue, maxValue)
    }

    private fun minInt(a: Int, b: Int): Int {
        return if (a < b) a else b
    }
}

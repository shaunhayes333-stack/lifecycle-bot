package com.lifecyclebot.engine

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * RemoteKillSwitch — emergency remote control
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Polls a remote JSON endpoint periodically to check for:
 *   1. Kill switch — immediately halt all trading
 *   2. Config overrides — force conservative settings remotely
 *   3. Version warnings — notify user of critical updates
 *
 * HOST YOUR OWN JSON FILE at any URL (GitHub Gist, Pastebin, your server):
 *
 * {
 *   "version": 1,
 *   "kill_switch": false,
 *   "kill_reason": "",
 *   "min_version": "6.7.0",
 *   "force_paper_mode": false,
 *   "max_position_sol_override": 0,
 *   "message": "",
 *   "updated_at": "2024-01-15T12:00:00Z"
 * }
 *
 * This gives you the ability to:
 *   - Emergency halt all your bots from your phone
 *   - Force paper mode during market crashes
 *   - Push size limits across all instances
 *   - Display urgent messages to users
 *
 * Set the URL in BotConfig.remoteConfigUrl (empty = disabled)
 */
object RemoteKillSwitch {

    data class RemoteConfig(
        val version: Int = 0,
        val killSwitch: Boolean = false,
        val killReason: String = "",
        val minVersion: String = "",
        val forcePaperMode: Boolean = false,
        val maxPositionSolOverride: Double = 0.0,  // 0 = no override
        val message: String = "",
        val updatedAt: String = "",
        val fetchedAt: Long = System.currentTimeMillis(),
        val fetchError: String? = null,
    )

    @Volatile
    var lastConfig: RemoteConfig = RemoteConfig()
        private set

    @Volatile
    var isKilled: Boolean = false
        private set

    @Volatile
    var killReason: String = ""
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var pollingJob: Job? = null
    private var configUrl: String = ""

    /**
     * Start polling the remote config endpoint.
     * @param url The JSON endpoint URL (GitHub Gist raw URL works great)
     * @param intervalMs How often to check (default: 60 seconds)
     * @param onKillSwitch Callback when kill switch is activated
     * @param onMessage Callback when a new message is received
     */
    fun startPolling(
        url: String,
        intervalMs: Long = 60_000L,
        onKillSwitch: (String) -> Unit = {},
        onMessage: (String) -> Unit = {},
        onVersionWarning: (String) -> Unit = {},
    ) {
        if (url.isBlank()) return
        configUrl = url
        
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                try {
                    val config = fetchConfig(url)
                    lastConfig = config

                    // Kill switch check
                    if (config.killSwitch && !isKilled) {
                        isKilled = true
                        killReason = config.killReason.ifBlank { "Remote kill switch activated" }
                        onKillSwitch(killReason)
                    } else if (!config.killSwitch && isKilled) {
                        // Kill switch cleared remotely
                        isKilled = false
                        killReason = ""
                    }

                    // Message check
                    if (config.message.isNotBlank()) {
                        onMessage(config.message)
                    }

                    // Version check
                    if (config.minVersion.isNotBlank()) {
                        val currentVersion = "6.9.0"  // TODO: get from BuildConfig
                        if (isVersionLower(currentVersion, config.minVersion)) {
                            onVersionWarning("Update required: v${config.minVersion}+ (you have v$currentVersion)")
                        }
                    }

                } catch (e: Exception) {
                    lastConfig = lastConfig.copy(
                        fetchError = e.message?.take(100),
                        fetchedAt = System.currentTimeMillis()
                    )
                }

                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * Manually trigger a kill (local, without remote).
     */
    fun localKill(reason: String) {
        isKilled = true
        killReason = reason
    }

    /**
     * Clear a local kill (remote kill can only be cleared from remote).
     */
    fun clearLocalKill() {
        if (!lastConfig.killSwitch) {
            isKilled = false
            killReason = ""
        }
    }

    /**
     * Get the current position size override (0 = no override).
     */
    fun getMaxPositionOverride(): Double = lastConfig.maxPositionSolOverride

    /**
     * Check if paper mode is forced remotely.
     */
    fun isForcePaperMode(): Boolean = lastConfig.forcePaperMode

    private suspend fun fetchConfig(url: String): RemoteConfig = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()

        val response = http.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}")
        }

        val body = response.body?.string() ?: throw Exception("Empty response")
        val json = JSONObject(body)

        RemoteConfig(
            version = json.optInt("version", 0),
            killSwitch = json.optBoolean("kill_switch", false),
            killReason = json.optString("kill_reason", ""),
            minVersion = json.optString("min_version", ""),
            forcePaperMode = json.optBoolean("force_paper_mode", false),
            maxPositionSolOverride = json.optDouble("max_position_sol_override", 0.0),
            message = json.optString("message", ""),
            updatedAt = json.optString("updated_at", ""),
            fetchedAt = System.currentTimeMillis(),
            fetchError = null,
        )
    }

    private fun isVersionLower(current: String, required: String): Boolean {
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val rParts = required.split(".").mapNotNull { it.toIntOrNull() }
        
        for (i in 0 until maxOf(cParts.size, rParts.size)) {
            val c = cParts.getOrElse(i) { 0 }
            val r = rParts.getOrElse(i) { 0 }
            if (c < r) return true
            if (c > r) return false
        }
        return false
    }

    /**
     * Status summary for UI display.
     */
    fun getStatusSummary(): String = buildString {
        appendLine("🔒 Remote Kill Switch")
        appendLine("  Status: ${if (isKilled) "🛑 KILLED" else "✅ Active"}")
        if (isKilled) appendLine("  Reason: $killReason")
        appendLine("  Last check: ${(System.currentTimeMillis() - lastConfig.fetchedAt) / 1000}s ago")
        if (lastConfig.fetchError != null) appendLine("  Error: ${lastConfig.fetchError}")
        if (lastConfig.forcePaperMode) appendLine("  ⚠️ Paper mode forced remotely")
        if (lastConfig.maxPositionSolOverride > 0) 
            appendLine("  ⚠️ Max position: ${lastConfig.maxPositionSolOverride} SOL (remote)")
        if (lastConfig.message.isNotBlank()) appendLine("  📢 ${lastConfig.message}")
    }
}

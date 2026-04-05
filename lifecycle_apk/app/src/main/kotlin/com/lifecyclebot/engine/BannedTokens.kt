package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * BannedTokens — Permanent ban list for tokens that failed checks
 *
 * V5.2 FIX:
 * - PAPER MODE does NOT permanently ban tokens
 * - RC=1 / safety bans should not poison learning mode
 * - LIVE MODE still uses permanent bans normally
 *
 * IMPORTANT:
 * Call BannedTokens.setPaperMode(config.paperMode) whenever mode changes.
 */
object BannedTokens {

    private const val TAG = "BannedTokens"
    private const val PREFS_NAME = "banned_tokens"
    private const val KEY_BANNED_SET = "banned_mints"
    private const val KEY_REASON_SET = "banned_reasons"
    private const val MAX_BANNED_SIZE = 5000

    private val bannedMints = ConcurrentHashMap<String, Long>()          // mint -> timestamp
    private val bannedReasons = ConcurrentHashMap<String, String>()      // mint -> reason

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var paperMode = true

    /**
     * Initialize with context - call once at app start
     */
    fun init(ctx: Context) {
        try {
            prefs = ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            loadFromPrefs()
            initialized = true
            ErrorLogger.info(TAG, "Loaded ${bannedMints.size} banned tokens | paperMode=$paperMode")
        } catch (e: Exception) {
            initialized = false
            ErrorLogger.warn(TAG, "init error: ${e.message}")
        }
    }

    /**
     * Set current mode.
     * In paper mode, bans are bypassed and new bans are ignored.
     */
    fun setPaperMode(enabled: Boolean) {
        paperMode = enabled
        ErrorLogger.info(TAG, "paperMode=$paperMode")
    }

    /**
     * Check if a token is banned.
     * PAPER MODE BYPASS: never block from permanent bans in paper mode.
     */
    fun isBanned(mint: String): Boolean {
        if (paperMode) return false
        return bannedMints.containsKey(mint)
    }

    /**
     * Ban a token permanently.
     * PAPER MODE BYPASS: do not persist bans while learning in paper mode.
     */
    fun ban(mint: String, reason: String = "unknown") {
        if (mint.isBlank()) return

        if (paperMode) {
            ErrorLogger.debug(TAG, "PAPER BYPASS: not banning ${mint.take(12)}... ($reason)")
            return
        }

        try {
            enforceMaxSize()

            val now = System.currentTimeMillis()
            bannedMints[mint] = now
            bannedReasons[mint] = reason

            saveToPrefs()
            ErrorLogger.debug(TAG, "BANNED: ${mint.take(12)}... ($reason)")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "ban error for ${mint.take(12)}...: ${e.message}")
        }
    }

    /**
     * Get reason for a ban if available.
     */
    fun getReason(mint: String): String? {
        return bannedReasons[mint]
    }

    /**
     * Get ban timestamp if available.
     */
    fun getBanTimestamp(mint: String): Long? {
        return bannedMints[mint]
    }

    /**
     * Get ban count.
     * In paper mode this is still the stored live ban list count, just bypassed.
     */
    fun count(): Int = bannedMints.size

    /**
     * Unban a specific token.
     */
    fun unban(mint: String) {
        try {
            val removedTs = bannedMints.remove(mint)
            bannedReasons.remove(mint)

            if (removedTs != null) {
                saveToPrefs()
                ErrorLogger.info(TAG, "UNBANNED: ${mint.take(12)}...")
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "unban error for ${mint.take(12)}...: ${e.message}")
        }
    }

    /**
     * Get list of all banned tokens with timestamps.
     */
    fun getBannedList(): List<Pair<String, Long>> {
        return try {
            bannedMints.entries
                .map { it.key to it.value }
                .sortedByDescending { it.second }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Get list of banned tokens with reasons.
     */
    fun getDetailedBannedList(): List<Triple<String, Long, String>> {
        return try {
            bannedMints.entries
                .map { entry ->
                    Triple(
                        entry.key,
                        entry.value,
                        bannedReasons[entry.key] ?: "unknown"
                    )
                }
                .sortedByDescending { it.second }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Clear all bans (use with caution).
     */
    fun clearAll() {
        try {
            bannedMints.clear()
            bannedReasons.clear()
            saveToPrefs()
            ErrorLogger.info(TAG, "All bans cleared")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "clearAll error: ${e.message}")
        }
    }

    /**
     * Reload bans from prefs.
     */
    fun reload() {
        try {
            bannedMints.clear()
            bannedReasons.clear()
            loadFromPrefs()
            ErrorLogger.info(TAG, "Reloaded ${bannedMints.size} banned tokens")
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "reload error: ${e.message}")
        }
    }

    /**
     * Get stats string.
     */
    fun getStats(): String {
        return "Banned: ${bannedMints.size} tokens | paperMode=$paperMode | initialized=$initialized"
    }

    private fun enforceMaxSize() {
        if (bannedMints.size < MAX_BANNED_SIZE) return

        val oldestKeys = bannedMints.entries
            .sortedBy { it.value }
            .take(500)
            .map { it.key }

        oldestKeys.forEach { key ->
            bannedMints.remove(key)
            bannedReasons.remove(key)
        }

        ErrorLogger.info(TAG, "Trimmed 500 oldest bans to stay under max size")
    }

    private fun loadFromPrefs() {
        val localPrefs = prefs ?: return

        try {
            val stored = localPrefs.getStringSet(KEY_BANNED_SET, emptySet()) ?: emptySet()
            stored.forEach { entry ->
                // Format: "mint:timestamp"
                val parts = entry.split(":")
                if (parts.isNotEmpty()) {
                    val mint = parts[0]
                    val ts = parts.getOrNull(1)?.toLongOrNull() ?: System.currentTimeMillis()
                    if (mint.isNotBlank()) {
                        bannedMints[mint] = ts
                    }
                }
            }

            val storedReasons = localPrefs.getStringSet(KEY_REASON_SET, emptySet()) ?: emptySet()
            storedReasons.forEach { entry ->
                // Format: "mint|reason"
                val idx = entry.indexOf('|')
                if (idx > 0 && idx < entry.length - 1) {
                    val mint = entry.substring(0, idx)
                    val reason = entry.substring(idx + 1)
                    if (mint.isNotBlank()) {
                        bannedReasons[mint] = reason
                    }
                }
            }
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "loadFromPrefs error: ${e.message}")
        }
    }

    private fun saveToPrefs() {
        val localPrefs = prefs ?: return

        try {
            val bannedSet = bannedMints.entries
                .map { "${it.key}:${it.value}" }
                .toSet()

            val reasonSet = bannedReasons.entries
                .map { "${it.key}|${it.value}" }
                .toSet()

            localPrefs.edit()
                .putStringSet(KEY_BANNED_SET, bannedSet)
                .putStringSet(KEY_REASON_SET, reasonSet)
                .apply()
        } catch (e: Exception) {
            ErrorLogger.warn(TAG, "saveToPrefs error: ${e.message}")
        }
    }
}
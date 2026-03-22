package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * BannedTokens — Permanent ban list for tokens that failed checks
 * 
 * Once a token is banned, it will NEVER be scanned again until manually cleared.
 * This prevents wasting resources rescanning the same bad tokens.
 */
object BannedTokens {
    
    private const val PREFS_NAME = "banned_tokens"
    private const val KEY_BANNED_SET = "banned_mints"
    private const val MAX_BANNED_SIZE = 5000  // Limit to prevent memory issues
    
    private val bannedMints = ConcurrentHashMap<String, Long>()  // mint -> ban timestamp
    private var prefs: SharedPreferences? = null
    
    /**
     * Initialize with context - call once at app start
     */
    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        ErrorLogger.info("BannedTokens", "Loaded ${bannedMints.size} banned tokens")
    }
    
    /**
     * Check if a token is banned
     */
    fun isBanned(mint: String): Boolean {
        return bannedMints.containsKey(mint)
    }
    
    /**
     * Ban a token permanently
     */
    fun ban(mint: String, reason: String = "unknown") {
        if (bannedMints.size >= MAX_BANNED_SIZE) {
            // Remove oldest 500 entries to make room
            val oldest = bannedMints.entries
                .sortedBy { it.value }
                .take(500)
                .map { it.key }
            oldest.forEach { bannedMints.remove(it) }
        }
        
        bannedMints[mint] = System.currentTimeMillis()
        saveToPrefs()
        ErrorLogger.debug("BannedTokens", "BANNED: ${mint.take(12)}... ($reason)")
    }
    
    /**
     * Get ban count
     */
    fun count(): Int = bannedMints.size
    
    /**
     * Unban a specific token
     */
    fun unban(mint: String) {
        if (bannedMints.remove(mint) != null) {
            saveToPrefs()
            ErrorLogger.info("BannedTokens", "UNBANNED: ${mint.take(12)}...")
        }
    }
    
    /**
     * Get list of all banned tokens with timestamps
     */
    fun getBannedList(): List<Pair<String, Long>> {
        return bannedMints.entries.map { it.key to it.value }
            .sortedByDescending { it.second }
    }
    
    /**
     * Clear all bans (use with caution)
     */
    fun clearAll() {
        bannedMints.clear()
        saveToPrefs()
        ErrorLogger.info("BannedTokens", "All bans cleared")
    }
    
    /**
     * Get stats string
     */
    fun getStats(): String {
        return "Banned: ${bannedMints.size} tokens"
    }
    
    private fun loadFromPrefs() {
        val stored = prefs?.getStringSet(KEY_BANNED_SET, emptySet()) ?: emptySet()
        stored.forEach { entry ->
            // Format: "mint:timestamp"
            val parts = entry.split(":")
            if (parts.size >= 2) {
                bannedMints[parts[0]] = parts[1].toLongOrNull() ?: System.currentTimeMillis()
            } else if (parts.isNotEmpty()) {
                bannedMints[parts[0]] = System.currentTimeMillis()
            }
        }
    }
    
    private fun saveToPrefs() {
        val set = bannedMints.entries.map { "${it.key}:${it.value}" }.toSet()
        prefs?.edit()?.putStringSet(KEY_BANNED_SET, set)?.apply()
    }
}

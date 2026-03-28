package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * WhaleWalletTracker — Track and learn from successful whale wallets
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Enhanced whale tracking with:
 * - Track specific successful whale wallets
 * - Record whale trade outcomes (did following them work?)
 * - Share whale success rates via collective
 * - Alert when tracked whales move
 * 
 * Privacy: Only hashed wallet addresses are shared with collective.
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object WhaleWalletTracker {
    
    private const val TAG = "WhaleWalletTracker"
    private const val PREFS_NAME = "whale_wallet_tracker"
    private const val MAX_TRACKED_WHALES = 50
    private const val MIN_TRADES_FOR_RELIABLE = 5
    
    private var prefs: SharedPreferences? = null
    
    // Tracked whale wallets with their performance
    private val trackedWhales = ConcurrentHashMap<String, WhaleProfile>()
    
    // Recent whale movements (for alerts)
    private val recentMovements = mutableListOf<WhaleMovement>()
    
    // Callbacks for whale alerts
    private var onWhaleMovementCallback: ((WhaleMovement) -> Unit)? = null
    
    data class WhaleProfile(
        val walletAddress: String,
        val walletHash: String,        // SHA-256 hash for privacy
        val firstSeen: Long,
        var lastSeen: Long,
        var totalTrades: Int,
        var successfulTrades: Int,     // Trades where price went up after
        var failedTrades: Int,         // Trades where price went down after
        var avgPnlWhenFollowed: Double, // If we followed this whale, what was our PnL?
        var followedCount: Int,        // How many times we followed
        var followedWins: Int,         // Wins when following
        var isWatched: Boolean,        // User explicitly watching this whale
        var notes: String,             // User notes
    ) {
        val winRate: Double
            get() = if (totalTrades > 0) (successfulTrades.toDouble() / totalTrades * 100) else 0.0
        
        val followWinRate: Double
            get() = if (followedCount > 0) (followedWins.toDouble() / followedCount * 100) else 0.0
        
        val isReliable: Boolean
            get() = totalTrades >= MIN_TRADES_FOR_RELIABLE
        
        val score: Int
            get() = when {
                !isReliable -> 0
                winRate >= 70 -> 90
                winRate >= 60 -> 70
                winRate >= 50 -> 50
                winRate >= 40 -> 30
                else -> 10
            }
    }
    
    data class WhaleMovement(
        val timestamp: Long,
        val walletAddress: String,
        val walletHash: String,
        val tokenMint: String,
        val tokenSymbol: String,
        val action: String,           // "BUY" or "SELL"
        val solAmount: Double,
        val whaleScore: Int,          // Score of this whale
        val isWatched: Boolean,       // Is this a watched whale?
    )
    
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }
    
    private fun loadFromPrefs() {
        prefs?.let {
            val json = it.getString("tracked_whales", null) ?: return
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val profile = WhaleProfile(
                        walletAddress = obj.getString("wallet"),
                        walletHash = obj.getString("hash"),
                        firstSeen = obj.getLong("first_seen"),
                        lastSeen = obj.getLong("last_seen"),
                        totalTrades = obj.getInt("total_trades"),
                        successfulTrades = obj.getInt("successful"),
                        failedTrades = obj.getInt("failed"),
                        avgPnlWhenFollowed = obj.optDouble("avg_pnl", 0.0),
                        followedCount = obj.optInt("followed_count", 0),
                        followedWins = obj.optInt("followed_wins", 0),
                        isWatched = obj.optBoolean("watched", false),
                        notes = obj.optString("notes", ""),
                    )
                    trackedWhales[profile.walletAddress] = profile
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Failed to load whales: ${e.message}")
            }
        }
    }
    
    private fun saveToPrefs() {
        prefs?.edit()?.apply {
            val arr = JSONArray()
            trackedWhales.values.forEach { p ->
                arr.put(JSONObject().apply {
                    put("wallet", p.walletAddress)
                    put("hash", p.walletHash)
                    put("first_seen", p.firstSeen)
                    put("last_seen", p.lastSeen)
                    put("total_trades", p.totalTrades)
                    put("successful", p.successfulTrades)
                    put("failed", p.failedTrades)
                    put("avg_pnl", p.avgPnlWhenFollowed)
                    put("followed_count", p.followedCount)
                    put("followed_wins", p.followedWins)
                    put("watched", p.isWatched)
                    put("notes", p.notes)
                })
            }
            putString("tracked_whales", arr.toString())
            apply()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRACKING - Record whale trades and outcomes
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a whale trade observation.
     * Called from WhaleDetector when a large trade is detected.
     */
    fun recordWhaleTrade(
        walletAddress: String,
        tokenMint: String,
        tokenSymbol: String,
        solAmount: Double,
        isBuy: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val hash = hashWallet(walletAddress)
        
        val profile = trackedWhales.getOrPut(walletAddress) {
            WhaleProfile(
                walletAddress = walletAddress,
                walletHash = hash,
                firstSeen = now,
                lastSeen = now,
                totalTrades = 0,
                successfulTrades = 0,
                failedTrades = 0,
                avgPnlWhenFollowed = 0.0,
                followedCount = 0,
                followedWins = 0,
                isWatched = false,
                notes = "",
            )
        }
        
        profile.lastSeen = now
        profile.totalTrades++
        
        // Trim to max tracked
        if (trackedWhales.size > MAX_TRACKED_WHALES) {
            trimOldWhales()
        }
        
        saveToPrefs()
        
        // Create movement alert
        val movement = WhaleMovement(
            timestamp = now,
            walletAddress = walletAddress,
            walletHash = hash,
            tokenMint = tokenMint,
            tokenSymbol = tokenSymbol,
            action = if (isBuy) "BUY" else "SELL",
            solAmount = solAmount,
            whaleScore = profile.score,
            isWatched = profile.isWatched,
        )
        
        recentMovements.add(movement)
        if (recentMovements.size > 100) {
            recentMovements.removeAt(0)
        }
        
        // Fire alert callback if whale is watched or high score
        if (profile.isWatched || profile.score >= 70) {
            onWhaleMovementCallback?.invoke(movement)
        }
        
        // Upload to collective if good whale
        if (profile.isReliable && profile.winRate >= 55) {
            uploadWhaleToCollective(profile)
        }
    }
    
    /**
     * Record the outcome of a whale's trade.
     * Called after we see if the token went up or down.
     */
    fun recordWhaleOutcome(
        walletAddress: String,
        wasSuccessful: Boolean,
    ) {
        trackedWhales[walletAddress]?.let { profile ->
            if (wasSuccessful) {
                profile.successfulTrades++
            } else {
                profile.failedTrades++
            }
            saveToPrefs()
        }
    }
    
    /**
     * Record when we followed a whale and the outcome.
     */
    fun recordFollowOutcome(
        walletAddress: String,
        pnlPct: Double,
    ) {
        trackedWhales[walletAddress]?.let { profile ->
            profile.followedCount++
            if (pnlPct > 5.0) {
                profile.followedWins++
            }
            // Update rolling average PnL
            profile.avgPnlWhenFollowed = 
                (profile.avgPnlWhenFollowed * (profile.followedCount - 1) + pnlPct) / profile.followedCount
            saveToPrefs()
            
            // Report to collective analytics
            CollectiveAnalytics.recordWhaleTradeReport()
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COLLECTIVE SHARING - Upload whale stats (privacy-preserving)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun uploadWhaleToCollective(profile: WhaleProfile) {
        if (!com.lifecyclebot.collective.CollectiveLearning.isEnabled()) return
        
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // Use the correct API signature
                com.lifecyclebot.collective.CollectiveLearning.uploadWhaleEffectiveness(
                    walletAddress = profile.walletAddress,  // Will be hashed internally
                    isProfitable = profile.winRate >= 50.0,
                    pnlPct = profile.avgPnlWhenFollowed,
                    leadTimeSec = 60  // Default lead time estimate
                )
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Failed to upload whale to collective: ${e.message}")
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // USER CONTROLS - Watch/unwatch whales
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun watchWhale(walletAddress: String, notes: String = "") {
        trackedWhales[walletAddress]?.let { profile ->
            profile.isWatched = true
            profile.notes = notes
            saveToPrefs()
            ErrorLogger.info(TAG, "Now watching whale: ${walletAddress.take(8)}...")
        }
    }
    
    fun unwatchWhale(walletAddress: String) {
        trackedWhales[walletAddress]?.let { profile ->
            profile.isWatched = false
            saveToPrefs()
        }
    }
    
    fun setOnMovementCallback(callback: (WhaleMovement) -> Unit) {
        onWhaleMovementCallback = callback
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS - For UI and decision making
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getWhaleProfile(walletAddress: String): WhaleProfile? {
        return trackedWhales[walletAddress]
    }
    
    fun getWhaleScore(walletAddress: String): Int {
        return trackedWhales[walletAddress]?.score ?: 0
    }
    
    fun isWhaleReliable(walletAddress: String): Boolean {
        return trackedWhales[walletAddress]?.isReliable == true
    }
    
    fun getTopWhales(limit: Int = 10): List<WhaleProfile> {
        return trackedWhales.values
            .filter { it.isReliable }
            .sortedByDescending { it.winRate }
            .take(limit)
    }
    
    fun getWatchedWhales(): List<WhaleProfile> {
        return trackedWhales.values.filter { it.isWatched }
    }
    
    fun getRecentMovements(limit: Int = 20): List<WhaleMovement> {
        return recentMovements.takeLast(limit).reversed()
    }
    
    fun getWatchedWhaleMovements(): List<WhaleMovement> {
        val watchedAddresses = trackedWhales.values.filter { it.isWatched }.map { it.walletAddress }.toSet()
        return recentMovements.filter { it.walletAddress in watchedAddresses }.takeLast(20).reversed()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun hashWallet(address: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(address.toByteArray())
            hash.joinToString("") { "%02x".format(it) }.take(16)  // First 16 chars
        } catch (e: Exception) {
            address.hashCode().toString()
        }
    }
    
    private fun trimOldWhales() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L  // 7 days
        val toRemove = trackedWhales.entries
            .filter { !it.value.isWatched && it.value.lastSeen < cutoff }
            .sortedBy { it.value.score }
            .take(10)
            .map { it.key }
        
        toRemove.forEach { trackedWhales.remove(it) }
    }
    
    fun getFormattedSummary(): String {
        val top = getTopWhales(5)
        val watched = getWatchedWhales()
        val recent = getRecentMovements(5)
        
        return buildString {
            appendLine("═══ WHALE WALLET TRACKER ═══")
            appendLine()
            appendLine("TOP PERFORMING WHALES:")
            if (top.isEmpty()) {
                appendLine("  No reliable whales tracked yet")
            } else {
                top.forEach { w ->
                    appendLine("  ${w.walletAddress.take(8)}... | ${w.winRate.toInt()}% WR | ${w.totalTrades} trades | Score: ${w.score}")
                }
            }
            appendLine()
            appendLine("WATCHED WHALES: ${watched.size}")
            watched.take(3).forEach { w ->
                appendLine("  ${w.walletAddress.take(8)}... | ${w.winRate.toInt()}% WR")
            }
            appendLine()
            appendLine("RECENT MOVEMENTS:")
            if (recent.isEmpty()) {
                appendLine("  No recent whale activity")
            } else {
                recent.forEach { m ->
                    val watchTag = if (m.isWatched) "👁️ " else ""
                    appendLine("  $watchTag${m.action} ${m.tokenSymbol} | ${m.solAmount.toInt()} SOL | Score: ${m.whaleScore}")
                }
            }
        }
    }
}

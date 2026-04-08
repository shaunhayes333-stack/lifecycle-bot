package com.lifecyclebot.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * WhaleWalletTracker — Track and learn from successful whale wallets
 *
 * Decisive-trade rules used here:
 * - WIN    = pnlPct >= 0.5
 * - LOSS   = pnlPct <= -2.0
 * - SCRATCH = between those thresholds, ignored for win-rate learning
 */
object WhaleWalletTracker {

    private const val TAG = "WhaleWalletTracker"
    private const val PREFS_NAME = "whale_wallet_tracker"
    private const val MAX_TRACKED_WHALES = 50
    private const val MIN_TRADES_FOR_RELIABLE = 5

    private const val WIN_THRESHOLD_PCT = 0.5
    private const val LOSS_THRESHOLD_PCT = -2.0

    private var prefs: SharedPreferences? = null

    // Tracked whale wallets with their performance
    private val trackedWhales = ConcurrentHashMap<String, WhaleProfile>()

    // Recent whale movements
    private val recentMovements = mutableListOf<WhaleMovement>()

    // Callbacks for whale alerts
    private var onWhaleMovementCallback: ((WhaleMovement) -> Unit)? = null

    data class WhaleProfile(
        val walletAddress: String,
        val walletHash: String,
        val firstSeen: Long,
        var lastSeen: Long,

        // Raw observations of whale activity
        var observedTrades: Int,

        // Decisive whale outcomes only
        var totalTrades: Int,
        var successfulTrades: Int,
        var failedTrades: Int,

        // Follow performance
        var avgPnlWhenFollowed: Double,
        var followedCount: Int,      // decisive follow outcomes only
        var followedWins: Int,
        var followedLosses: Int,
        var followedScratches: Int,

        var isWatched: Boolean,
        var notes: String,
    ) {
        val winRate: Double
            get() = if (totalTrades > 0) {
                successfulTrades.toDouble() / totalTrades.toDouble() * 100.0
            } else {
                0.0
            }

        val followWinRate: Double
            get() = if (followedCount > 0) {
                followedWins.toDouble() / followedCount.toDouble() * 100.0
            } else {
                0.0
            }

        val isReliable: Boolean
            get() = totalTrades >= MIN_TRADES_FOR_RELIABLE

        val score: Int
            get() = when {
                !isReliable -> 0
                winRate >= 70.0 -> 90
                winRate >= 60.0 -> 70
                winRate >= 50.0 -> 50
                winRate >= 40.0 -> 30
                else -> 10
            }
    }

    data class WhaleMovement(
        val timestamp: Long,
        val walletAddress: String,
        val walletHash: String,
        val tokenMint: String,
        val tokenSymbol: String,
        val action: String,
        val solAmount: Double,
        val whaleScore: Int,
        val isWatched: Boolean,
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        prefs?.let { sharedPrefs ->
            val json = sharedPrefs.getString("tracked_whales", null) ?: return
            try {
                val arr = JSONArray(json)
                trackedWhales.clear()

                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val successful = obj.optInt("successful", 0)
                    val failed = obj.optInt("failed", 0)
                    val legacyTotalTrades = obj.optInt("total_trades", successful + failed)

                    val profile = WhaleProfile(
                        walletAddress = obj.getString("wallet"),
                        walletHash = obj.getString("hash"),
                        firstSeen = obj.getLong("first_seen"),
                        lastSeen = obj.getLong("last_seen"),
                        observedTrades = obj.optInt("observed_trades", legacyTotalTrades),
                        totalTrades = maxOf(obj.optInt("decisive_trades", successful + failed), successful + failed),
                        successfulTrades = successful,
                        failedTrades = failed,
                        avgPnlWhenFollowed = obj.optDouble("avg_pnl", 0.0),
                        followedCount = obj.optInt(
                            "followed_count",
                            obj.optInt("followed_wins", 0) + obj.optInt("followed_losses", 0)
                        ),
                        followedWins = obj.optInt("followed_wins", 0),
                        followedLosses = obj.optInt("followed_losses", maxOf(obj.optInt("followed_count", 0) - obj.optInt("followed_wins", 0), 0)),
                        followedScratches = obj.optInt("followed_scratches", 0),
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
                    put("observed_trades", p.observedTrades)
                    put("decisive_trades", p.totalTrades)
                    put("total_trades", p.totalTrades) // legacy compatibility
                    put("successful", p.successfulTrades)
                    put("failed", p.failedTrades)
                    put("avg_pnl", p.avgPnlWhenFollowed)
                    put("followed_count", p.followedCount)
                    put("followed_wins", p.followedWins)
                    put("followed_losses", p.followedLosses)
                    put("followed_scratches", p.followedScratches)
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
     * This is just an observed move, not yet a win/loss outcome.
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
                observedTrades = 0,
                totalTrades = 0,
                successfulTrades = 0,
                failedTrades = 0,
                avgPnlWhenFollowed = 0.0,
                followedCount = 0,
                followedWins = 0,
                followedLosses = 0,
                followedScratches = 0,
                isWatched = false,
                notes = "",
            )
        }

        profile.lastSeen = now
        profile.observedTrades++

        if (trackedWhales.size > MAX_TRACKED_WHALES) {
            trimOldWhales()
        }

        saveToPrefs()

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

        synchronized(recentMovements) {
            recentMovements.add(movement)
            while (recentMovements.size > 100) {
                recentMovements.removeAt(0)
            }
        }

        if (profile.isWatched || profile.score >= 70) {
            onWhaleMovementCallback?.invoke(movement)
        }

        if (profile.isReliable && profile.winRate >= 55.0) {
            uploadWhaleToCollective(profile)
        }
    }

    /**
     * Record the outcome of a whale's trade using actual pnl%.
     * Scratch outcomes are ignored.
     */
    fun recordWhaleOutcome(
        walletAddress: String,
        pnlPct: Double,
    ) {
        trackedWhales[walletAddress]?.let { profile ->
            when {
                isWin(pnlPct) -> {
                    profile.totalTrades++
                    profile.successfulTrades++
                    saveToPrefs()
                }

                isLoss(pnlPct) -> {
                    profile.totalTrades++
                    profile.failedTrades++
                    saveToPrefs()
                }

                else -> {
                    ErrorLogger.debug(TAG, "Whale outcome scratch ignored: ${walletAddress.take(8)}... pnl=${String.format("%.2f", pnlPct)}%")
                }
            }
        }
    }

    /**
     * Legacy compatibility overload.
     * Boolean-only outcomes cannot express scratch, so this remains coarse.
     */
    fun recordWhaleOutcome(
        walletAddress: String,
        wasSuccessful: Boolean,
    ) {
        trackedWhales[walletAddress]?.let { profile ->
            profile.totalTrades++
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
     * Decisive-trade rules:
     * - win  >= 0.5
     * - loss <= -2.0
     * - scratch ignored for followWinRate denominator
     */
    fun recordFollowOutcome(
        walletAddress: String,
        pnlPct: Double,
    ) {
        trackedWhales[walletAddress]?.let { profile ->
            when {
                isWin(pnlPct) -> {
                    profile.followedCount++
                    profile.followedWins++
                }

                isLoss(pnlPct) -> {
                    profile.followedCount++
                    profile.followedLosses++
                }

                else -> {
                    profile.followedScratches++
                }
            }

            val decisiveAndScratchCount = profile.followedCount + profile.followedScratches
            profile.avgPnlWhenFollowed =
                if (decisiveAndScratchCount > 0) {
                    ((profile.avgPnlWhenFollowed * (decisiveAndScratchCount - 1)) + pnlPct) / decisiveAndScratchCount.toDouble()
                } else {
                    pnlPct
                }

            saveToPrefs()
            CollectiveAnalytics.recordWhaleTradeReport()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COLLECTIVE SHARING
    // ═══════════════════════════════════════════════════════════════════════════

    private fun uploadWhaleToCollective(profile: WhaleProfile) {
        if (!com.lifecyclebot.collective.CollectiveLearning.isEnabled()) return

        GlobalScope.launch(Dispatchers.IO) {
            try {
                com.lifecyclebot.collective.CollectiveLearning.uploadWhaleEffectiveness(
                    walletAddress = profile.walletAddress,
                    isProfitable = profile.winRate >= 50.0,
                    pnlPct = profile.avgPnlWhenFollowed,
                    leadTimeSec = 60
                )
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Failed to upload whale to collective: ${e.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // USER CONTROLS
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
    // GETTERS
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
        return synchronized(recentMovements) {
            recentMovements.takeLast(limit).reversed()
        }
    }

    fun getWatchedWhaleMovements(): List<WhaleMovement> {
        val watchedAddresses = trackedWhales.values
            .filter { it.isWatched }
            .map { it.walletAddress }
            .toSet()

        return synchronized(recentMovements) {
            recentMovements
                .filter { it.walletAddress in watchedAddresses }
                .takeLast(20)
                .reversed()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun hashWallet(address: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(address.toByteArray())
            hash.joinToString("") { "%02x".format(it) }.take(16)
        } catch (_: Exception) {
            address.hashCode().toString()
        }
    }

    private fun trimOldWhales() {
        val cutoff = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
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
                    appendLine(
                        "  ${w.walletAddress.take(8)}... | ${w.winRate.toInt()}% WR | " +
                            "${w.totalTrades} decisive | obs=${w.observedTrades} | Score: ${w.score}"
                    )
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

    private fun isWin(pnlPct: Double): Boolean = pnlPct >= WIN_THRESHOLD_PCT

    private fun isLoss(pnlPct: Double): Boolean = pnlPct <= LOSS_THRESHOLD_PCT
}
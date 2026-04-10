package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🔍 INSIDER TRACKER AI - V5.7.4
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Monitors wallets of high-profile individuals and institutions to detect
 * insider activity BEFORE public announcements, tweets, or market moves.
 * 
 * TRACKED ENTITIES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🇺🇸 POLITICIANS
 *      • Nancy Pelosi (legendary stock trader)
 *      • Congress Members with known crypto holdings
 *   
 *   🏛️ TRUMP FAMILY
 *      • Barron Trump (crypto/meme coin ties)
 *      • Trump family wallets (DJT, TRUMP token)
 *      • Trump Organization treasury
 *   
 *   🐋 KNOWN WHALES & INSIDERS
 *      • SBF remnant wallets (monitored for moves)
 *      • Jump Trading / Alameda residuals
 *      • Known influencer wallets
 *   
 *   🏢 INSTITUTIONAL
 *      • Major fund treasury wallets
 *      • Known market maker wallets
 *      • CEX hot/cold wallets
 * 
 * SIGNAL TYPES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🔥 PRE_TWEET:      Wallet activity before known announcement patterns
 *   💰 ACCUMULATION:   Large buys detected
 *   🚨 DISTRIBUTION:   Large sells / exits detected
 *   ⚡ UNUSUAL:        Activity outside normal patterns
 *   🎯 FRONT_RUN:      Possible front-running detected
 * 
 * DATA SOURCES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Solana RPC (direct transaction monitoring)
 *   • Helius API (enriched transaction data)
 *   • Public wallet databases
 *   • Social correlation (tweet timing analysis)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object InsiderTrackerAI {
    
    private const val TAG = "🔍InsiderTracker"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRACKED WALLET DATABASE
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class WalletCategory {
        POLITICIAN,
        TRUMP_FAMILY,
        WHALE,
        INSTITUTION,
        INFLUENCER,
        MARKET_MAKER,
        EXCHANGE
    }
    
    enum class RiskLevel {
        ALPHA,      // Highest signal quality - known to precede moves
        HIGH,       // Strong correlation with price moves
        MEDIUM,     // Moderate correlation
        LOW         // Informational only
    }
    
    data class TrackedWallet(
        val address: String,
        val label: String,
        val category: WalletCategory,
        val riskLevel: RiskLevel,
        val notes: String = "",
        val twitterHandle: String? = null,  // For tweet correlation
        val isActive: Boolean = true
    )
    
    // Known wallets to track (Solana addresses)
    // Note: These are example/placeholder addresses - replace with real addresses
    private val TRACKED_WALLETS = listOf(
        // ═══════════════════════════════════════════════════════════════════════
        // 🇺🇸 POLITICIANS
        // ═══════════════════════════════════════════════════════════════════════
        TrackedWallet(
            address = "PeLoSiXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Pelosi Trading Desk",
            category = WalletCategory.POLITICIAN,
            riskLevel = RiskLevel.ALPHA,
            notes = "Legendary congressional trader",
            twitterHandle = "@SpeakerPelosi"
        ),
        
        // ═══════════════════════════════════════════════════════════════════════
        // 🏛️ TRUMP FAMILY
        // ═══════════════════════════════════════════════════════════════════════
        TrackedWallet(
            address = "TrUmPfAmXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Trump Family Treasury",
            category = WalletCategory.TRUMP_FAMILY,
            riskLevel = RiskLevel.ALPHA,
            notes = "Main Trump crypto holdings",
            twitterHandle = "@realDonaldTrump"
        ),
        TrackedWallet(
            address = "BaRrOnTXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Barron Trump",
            category = WalletCategory.TRUMP_FAMILY,
            riskLevel = RiskLevel.ALPHA,
            notes = "Known meme coin early adopter"
        ),
        TrackedWallet(
            address = "DJTtOkEnXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "DJT Token Treasury",
            category = WalletCategory.TRUMP_FAMILY,
            riskLevel = RiskLevel.HIGH,
            notes = "Official DJT/TRUMP token treasury"
        ),
        
        // ═══════════════════════════════════════════════════════════════════════
        // 🐋 KNOWN WHALES
        // ═══════════════════════════════════════════════════════════════════════
        TrackedWallet(
            address = "JuMpCrYpXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Jump Crypto",
            category = WalletCategory.WHALE,
            riskLevel = RiskLevel.HIGH,
            notes = "Major market maker"
        ),
        TrackedWallet(
            address = "WiNtErMuXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Wintermute",
            category = WalletCategory.MARKET_MAKER,
            riskLevel = RiskLevel.HIGH,
            notes = "Liquidity provider"
        ),
        TrackedWallet(
            address = "AnsEmXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Ansem",
            category = WalletCategory.INFLUENCER,
            riskLevel = RiskLevel.ALPHA,
            notes = "Solana alpha influencer",
            twitterHandle = "@blknoiz06"
        ),
        TrackedWallet(
            address = "CoInBaSeXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Coinbase Hot Wallet",
            category = WalletCategory.EXCHANGE,
            riskLevel = RiskLevel.MEDIUM,
            notes = "Exchange inflows/outflows"
        ),
        TrackedWallet(
            address = "BiNaNcEhXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Binance Hot Wallet",
            category = WalletCategory.EXCHANGE,
            riskLevel = RiskLevel.MEDIUM,
            notes = "Exchange inflows/outflows"
        ),
        
        // ═══════════════════════════════════════════════════════════════════════
        // 🏢 INSTITUTIONAL
        // ═══════════════════════════════════════════════════════════════════════
        TrackedWallet(
            address = "a]16qYCXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "a]16z Crypto",
            category = WalletCategory.INSTITUTION,
            riskLevel = RiskLevel.HIGH,
            notes = "Top VC firm"
        ),
        TrackedWallet(
            address = "PoLyChaXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX",
            label = "Polychain Capital",
            category = WalletCategory.INSTITUTION,
            riskLevel = RiskLevel.HIGH,
            notes = "Major crypto fund"
        ),
    )
    
    // Custom wallets added by user
    private val customWallets = ConcurrentHashMap<String, TrackedWallet>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SIGNAL DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class InsiderSignalType {
        PRE_TWEET,       // Activity before announcement pattern
        ACCUMULATION,    // Large buying
        DISTRIBUTION,    // Large selling
        UNUSUAL,         // Anomalous activity
        FRONT_RUN,       // Possible front-running
        TOKEN_CREATION,  // New token/mint activity
        TRANSFER_OUT,    // Moving to cold storage
        TRANSFER_IN      // Moving from cold storage
    }
    
    data class InsiderSignal(
        val id: Long = System.currentTimeMillis(),
        val wallet: TrackedWallet,
        val signalType: InsiderSignalType,
        val tokenMint: String?,
        val tokenSymbol: String?,
        val amountUsd: Double,
        val direction: String,  // BUY, SELL, TRANSFER
        val confidence: Int,    // 0-100
        val timestamp: Long = System.currentTimeMillis(),
        val txSignature: String?,
        val notes: String = "",
        val tweetCorrelation: Boolean = false,  // If activity correlates with tweet timing
    ) {
        val age: Long get() = System.currentTimeMillis() - timestamp
        val isRecent: Boolean get() = age < 30 * 60 * 1000  // 30 min
        val isFresh: Boolean get() = age < 5 * 60 * 1000    // 5 min
    }
    
    data class WalletActivity(
        val wallet: TrackedWallet,
        val recentTxCount: Int,
        val last24hVolume: Double,
        val lastActivity: Long,
        val topTokens: List<String>,
        val isAnomalous: Boolean,
        val anomalyScore: Double  // 0-100
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val isRunning = AtomicBoolean(false)
    private var scanJob: Job? = null
    
    // Signal cache
    private val recentSignals = ConcurrentHashMap<Long, InsiderSignal>()
    private val walletActivities = ConcurrentHashMap<String, WalletActivity>()
    
    // Stats
    private val lastScanTime = AtomicLong(0)
    private var totalSignalsGenerated = 0
    private var alphaSignalsGenerated = 0
    private var preTweetSignals = 0
    
    // Callbacks for real-time alerts
    private var onSignalCallback: ((InsiderSignal) -> Unit)? = null
    
    // Config
    private const val SCAN_INTERVAL_MS = 30_000L  // 30 seconds
    private const val SIGNAL_EXPIRY_MS = 4 * 60 * 60 * 1000L  // 4 hours
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun start(onSignal: ((InsiderSignal) -> Unit)? = null) {
        if (isRunning.get()) {
            ErrorLogger.warn(TAG, "Already running")
            return
        }
        
        onSignalCallback = onSignal
        isRunning.set(true)
        
        scanJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            ErrorLogger.info(TAG, "🔍 Insider Tracker STARTED | Watching ${getAllWallets().size} wallets")
            
            while (isRunning.get() && isActive) {
                try {
                    scanAllWallets()
                    cleanupOldSignals()
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Scan error: ${e.message}", e)
                }
                
                delay(SCAN_INTERVAL_MS)
            }
        }
    }
    
    fun stop() {
        isRunning.set(false)
        scanJob?.cancel()
        scanJob = null
        ErrorLogger.info(TAG, "🔍 Insider Tracker STOPPED")
    }
    
    fun isEnabled(): Boolean = isRunning.get()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // WALLET MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getAllWallets(): List<TrackedWallet> = TRACKED_WALLETS + customWallets.values
    
    fun getWalletsByCategory(category: WalletCategory): List<TrackedWallet> =
        getAllWallets().filter { it.category == category }
    
    fun getAlphaWallets(): List<TrackedWallet> =
        getAllWallets().filter { it.riskLevel == RiskLevel.ALPHA }
    
    fun addCustomWallet(
        address: String,
        label: String,
        category: WalletCategory,
        riskLevel: RiskLevel = RiskLevel.MEDIUM,
        notes: String = "",
        twitterHandle: String? = null
    ): Boolean {
        if (address.length < 32) return false
        
        customWallets[address] = TrackedWallet(
            address = address,
            label = label,
            category = category,
            riskLevel = riskLevel,
            notes = notes,
            twitterHandle = twitterHandle
        )
        
        ErrorLogger.info(TAG, "Added custom wallet: $label ($address)")
        return true
    }
    
    fun removeCustomWallet(address: String): Boolean {
        return customWallets.remove(address) != null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN SCAN LOGIC
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun scanAllWallets() = withContext(Dispatchers.IO) {
        lastScanTime.set(System.currentTimeMillis())
        
        val wallets = getAllWallets().filter { it.isActive }
        
        // Prioritize ALPHA wallets
        val prioritized = wallets.sortedBy { 
            when (it.riskLevel) {
                RiskLevel.ALPHA -> 0
                RiskLevel.HIGH -> 1
                RiskLevel.MEDIUM -> 2
                RiskLevel.LOW -> 3
            }
        }
        
        for (wallet in prioritized) {
            try {
                val activity = fetchWalletActivity(wallet)
                walletActivities[wallet.address] = activity
                
                // Check for signals
                val signals = analyzeActivity(wallet, activity)
                
                for (signal in signals) {
                    recentSignals[signal.id] = signal
                    totalSignalsGenerated++
                    
                    if (wallet.riskLevel == RiskLevel.ALPHA) {
                        alphaSignalsGenerated++
                    }
                    
                    if (signal.signalType == InsiderSignalType.PRE_TWEET) {
                        preTweetSignals++
                    }
                    
                    // Callback for real-time alerts
                    onSignalCallback?.invoke(signal)
                    
                    logSignal(signal)
                }
                
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Error scanning ${wallet.label}: ${e.message}")
            }
            
            // Small delay between wallets to avoid rate limiting
            delay(100)
        }
    }
    
    /**
     * Fetch recent activity for a wallet using Solana RPC / Helius
     */
    private suspend fun fetchWalletActivity(wallet: TrackedWallet): WalletActivity {
        // TODO: Replace with actual Solana RPC / Helius API calls
        // For now, simulate activity detection
        
        return try {
            // Simulated activity - in production, call:
            // - getSignaturesForAddress
            // - getParsedTransaction for each
            // - Calculate volume, token movements, etc.
            
            val randomActivity = (Math.random() * 100).toInt()
            val isAnomalous = randomActivity > 80
            
            WalletActivity(
                wallet = wallet,
                recentTxCount = randomActivity,
                last24hVolume = randomActivity * 1000.0,
                lastActivity = System.currentTimeMillis() - (randomActivity * 60000L),
                topTokens = listOf("SOL", "USDC"),
                isAnomalous = isAnomalous,
                anomalyScore = if (isAnomalous) randomActivity.toDouble() else 0.0
            )
        } catch (e: Exception) {
            WalletActivity(
                wallet = wallet,
                recentTxCount = 0,
                last24hVolume = 0.0,
                lastActivity = 0,
                topTokens = emptyList(),
                isAnomalous = false,
                anomalyScore = 0.0
            )
        }
    }
    
    /**
     * Analyze wallet activity and generate signals
     */
    private fun analyzeActivity(wallet: TrackedWallet, activity: WalletActivity): List<InsiderSignal> {
        val signals = mutableListOf<InsiderSignal>()
        
        // Check for anomalous activity
        if (activity.isAnomalous && activity.anomalyScore > 70) {
            val signalType = when {
                activity.last24hVolume > 100_000 -> InsiderSignalType.ACCUMULATION
                activity.recentTxCount > 50 -> InsiderSignalType.UNUSUAL
                else -> InsiderSignalType.UNUSUAL
            }
            
            signals.add(InsiderSignal(
                wallet = wallet,
                signalType = signalType,
                tokenMint = null,
                tokenSymbol = activity.topTokens.firstOrNull(),
                amountUsd = activity.last24hVolume,
                direction = "UNKNOWN",
                confidence = activity.anomalyScore.toInt(),
                txSignature = null,
                notes = "Anomalous activity detected: ${activity.recentTxCount} txns in 24h"
            ))
        }
        
        // Check for pre-tweet patterns (ALPHA wallets only)
        if (wallet.riskLevel == RiskLevel.ALPHA && wallet.twitterHandle != null) {
            if (activity.recentTxCount > 10 && activity.lastActivity > System.currentTimeMillis() - 5 * 60 * 1000) {
                // Fresh activity from ALPHA wallet with Twitter - possible pre-tweet
                signals.add(InsiderSignal(
                    wallet = wallet,
                    signalType = InsiderSignalType.PRE_TWEET,
                    tokenMint = null,
                    tokenSymbol = activity.topTokens.firstOrNull(),
                    amountUsd = activity.last24hVolume,
                    direction = "BUY",
                    confidence = 85,
                    txSignature = null,
                    notes = "Fresh activity from ${wallet.label} - watch for tweet!",
                    tweetCorrelation = true
                ))
            }
        }
        
        return signals
    }
    
    private fun logSignal(signal: InsiderSignal) {
        val emoji = when (signal.signalType) {
            InsiderSignalType.PRE_TWEET -> "🐦"
            InsiderSignalType.ACCUMULATION -> "💰"
            InsiderSignalType.DISTRIBUTION -> "🚨"
            InsiderSignalType.UNUSUAL -> "⚡"
            InsiderSignalType.FRONT_RUN -> "🎯"
            InsiderSignalType.TOKEN_CREATION -> "🆕"
            InsiderSignalType.TRANSFER_OUT -> "📤"
            InsiderSignalType.TRANSFER_IN -> "📥"
        }
        
        ErrorLogger.info(TAG, "$emoji INSIDER SIGNAL: ${signal.wallet.label} | " +
            "${signal.signalType.name} | ${signal.tokenSymbol ?: "?"} | " +
            "$${String.format("%.0f", signal.amountUsd)} | " +
            "Confidence: ${signal.confidence}%")
    }
    
    private fun cleanupOldSignals() {
        val cutoff = System.currentTimeMillis() - SIGNAL_EXPIRY_MS
        recentSignals.entries.removeIf { it.value.timestamp < cutoff }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // QUERY METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getRecentSignals(limit: Int = 50): List<InsiderSignal> =
        recentSignals.values
            .sortedByDescending { it.timestamp }
            .take(limit)
    
    fun getAlphaSignals(limit: Int = 20): List<InsiderSignal> =
        recentSignals.values
            .filter { it.wallet.riskLevel == RiskLevel.ALPHA }
            .sortedByDescending { it.timestamp }
            .take(limit)
    
    fun getPreTweetSignals(): List<InsiderSignal> =
        recentSignals.values
            .filter { it.signalType == InsiderSignalType.PRE_TWEET && it.isRecent }
            .sortedByDescending { it.timestamp }
    
    fun getSignalsByWallet(address: String): List<InsiderSignal> =
        recentSignals.values
            .filter { it.wallet.address == address }
            .sortedByDescending { it.timestamp }
    
    fun getSignalsByToken(mint: String): List<InsiderSignal> =
        recentSignals.values
            .filter { it.tokenMint == mint }
            .sortedByDescending { it.timestamp }
    
    fun getWalletActivity(address: String): WalletActivity? =
        walletActivities[address]
    
    fun hasRecentAlphaSignal(tokenMint: String): Boolean =
        recentSignals.values.any { 
            it.tokenMint == tokenMint && 
            it.wallet.riskLevel == RiskLevel.ALPHA && 
            it.isRecent 
        }
    
    fun getInsiderScore(tokenMint: String): Int {
        val signals = getSignalsByToken(tokenMint)
        if (signals.isEmpty()) return 0
        
        var score = 0
        
        for (signal in signals.filter { it.isRecent }) {
            score += when (signal.wallet.riskLevel) {
                RiskLevel.ALPHA -> 30
                RiskLevel.HIGH -> 20
                RiskLevel.MEDIUM -> 10
                RiskLevel.LOW -> 5
            }
            
            score += when (signal.signalType) {
                InsiderSignalType.PRE_TWEET -> 25
                InsiderSignalType.ACCUMULATION -> 15
                InsiderSignalType.FRONT_RUN -> 20
                else -> 5
            }
            
            // Bonus for fresh signals
            if (signal.isFresh) score += 10
        }
        
        return score.coerceIn(0, 100)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class InsiderTrackerStats(
        val isRunning: Boolean,
        val walletsTracked: Int,
        val alphaWallets: Int,
        val totalSignals: Int,
        val alphaSignals: Int,
        val preTweetSignals: Int,
        val recentSignalCount: Int,
        val lastScanTime: Long,
    )
    
    fun getStats(): InsiderTrackerStats = InsiderTrackerStats(
        isRunning = isRunning.get(),
        walletsTracked = getAllWallets().size,
        alphaWallets = getAlphaWallets().size,
        totalSignals = totalSignalsGenerated,
        alphaSignals = alphaSignalsGenerated,
        preTweetSignals = preTweetSignals,
        recentSignalCount = recentSignals.size,
        lastScanTime = lastScanTime.get()
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING INTEGRATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get entry boost for a token based on insider activity
     * Used by UnifiedScorer and other AI layers
     */
    fun getEntryBoost(mint: String, symbol: String): Int {
        val insiderScore = getInsiderScore(mint)
        
        return when {
            insiderScore >= 80 -> 25  // Very strong insider signal
            insiderScore >= 60 -> 15  // Strong signal
            insiderScore >= 40 -> 8   // Moderate signal
            insiderScore >= 20 -> 3   // Weak signal
            else -> 0
        }
    }
    
    /**
     * Check if we should avoid a token due to insider selling
     */
    fun shouldAvoid(mint: String): Boolean {
        return recentSignals.values.any {
            it.tokenMint == mint &&
            it.signalType == InsiderSignalType.DISTRIBUTION &&
            it.wallet.riskLevel in listOf(RiskLevel.ALPHA, RiskLevel.HIGH) &&
            it.isRecent
        }
    }
    
    /**
     * Get a summary string for UI display
     */
    fun getSummary(): String {
        val stats = getStats()
        val recentAlpha = getAlphaSignals(5)
        
        return buildString {
            appendLine("🔍 INSIDER TRACKER")
            appendLine("─".repeat(30))
            appendLine("Wallets: ${stats.walletsTracked} (${stats.alphaWallets} ALPHA)")
            appendLine("Signals: ${stats.recentSignalCount} active")
            
            if (recentAlpha.isNotEmpty()) {
                appendLine()
                appendLine("Recent ALPHA:")
                for (sig in recentAlpha.take(3)) {
                    val age = (System.currentTimeMillis() - sig.timestamp) / 60000
                    appendLine("  ${sig.wallet.label}: ${sig.signalType.name} (${age}m ago)")
                }
            }
        }
    }
}

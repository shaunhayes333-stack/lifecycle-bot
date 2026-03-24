package com.lifecyclebot.engine

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * WhaleTrackerAI - Follow the Smart Money
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 * CONCEPT: Track profitable wallets and follow their trades
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Smart money (whales) often know things before retail:
 * - Inside info on upcoming listings/partnerships
 * - Early access to promising projects
 * - Better technical analysis
 * 
 * When multiple whales buy the same token = STRONG BUY SIGNAL
 * When whales start selling = EXIT WARNING
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 * HOW IT WORKS:
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * 1. WHALE DISCOVERY
 *    - Track wallets that consistently profit
 *    - Monitor Helius for large transactions
 *    - Learn which whales to follow (track their win rates)
 * 
 * 2. WHALE CONSENSUS DETECTION
 *    - Alert when 2+ tracked whales buy same token
 *    - Stronger signal when more whales agree
 *    - Track timing (whales buying within same hour = coordinated)
 * 
 * 3. WHALE EXIT WARNINGS
 *    - Detect when whales start selling
 *    - Reduce position or tighten stops when whales exit
 * 
 * 4. LEARNING
 *    - Track which whale signals led to profits
 *    - Increase weight for accurate whales
 *    - Decrease weight for whales that lose
 */
object WhaleTrackerAI {
    
    private const val TAG = "WhaleTracker"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // WHALE DATABASE
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class WhaleProfile(
        val address: String,
        val nickname: String = "",           // Optional friendly name
        var totalTrades: Int = 0,
        var winningTrades: Int = 0,
        var totalPnlSol: Double = 0.0,
        var avgWinPct: Double = 0.0,
        var avgLossPct: Double = 0.0,
        var lastSeenMs: Long = 0L,
        var trustScore: Double = 50.0,       // 0-100, higher = more trustworthy
        var isActive: Boolean = true,
    ) {
        val winRate: Double get() = if (totalTrades > 0) winningTrades.toDouble() / totalTrades * 100 else 0.0
    }
    
    data class WhaleActivity(
        val whaleAddress: String,
        val mint: String,
        val symbol: String,
        val action: WhaleAction,
        val amountSol: Double,
        val timestamp: Long,
        val priceAtAction: Double,
    )
    
    enum class WhaleAction { BUY, SELL, ACCUMULATE, DISTRIBUTE }
    
    // Tracked whales (loaded from persistent storage)
    private val trackedWhales = ConcurrentHashMap<String, WhaleProfile>()
    
    // Recent whale activity per token
    private val recentActivity = ConcurrentHashMap<String, MutableList<WhaleActivity>>()
    
    // Whale consensus signals
    private val consensusSignals = ConcurrentHashMap<String, WhaleConsensus>()
    
    data class WhaleConsensus(
        val mint: String,
        val symbol: String,
        val buyingWhales: MutableSet<String> = mutableSetOf(),
        val sellingWhales: MutableSet<String> = mutableSetOf(),
        val firstDetectedMs: Long = System.currentTimeMillis(),
        val totalBuyVolumeSol: Double = 0.0,
        val totalSellVolumeSol: Double = 0.0,
    ) {
        val netBuyers: Int get() = buyingWhales.size - sellingWhales.size
        val isBullish: Boolean get() = buyingWhales.size >= 2 && netBuyers > 0
        val isBearish: Boolean get() = sellingWhales.size >= 2 && netBuyers < 0
        val consensusStrength: Double get() = (buyingWhales.size + sellingWhales.size).toDouble().coerceAtMost(10.0) / 10.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // WHALE SIGNAL SCORING
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class WhaleSignal(
        val mint: String,
        val symbol: String,
        val signal: SignalType,
        val strength: Double,           // 0-100
        val whaleCount: Int,
        val avgWhaleTrustScore: Double,
        val totalVolumeSol: Double,
        val recommendation: String,     // "STRONG_BUY", "BUY", "HOLD", "SELL", "STRONG_SELL"
        val reason: String,
    )
    
    enum class SignalType { 
        WHALE_ACCUMULATION,    // Multiple whales buying
        WHALE_DISTRIBUTION,    // Multiple whales selling
        SINGLE_WHALE_BUY,      // One trusted whale buying
        SINGLE_WHALE_SELL,     // One trusted whale selling
        NO_SIGNAL,
    }
    
    /**
     * Get whale signal for a token.
     * Call this before making entry/exit decisions.
     */
    fun getWhaleSignal(mint: String, symbol: String = ""): WhaleSignal {
        val consensus = consensusSignals[mint]
        
        if (consensus == null || consensus.buyingWhales.isEmpty() && consensus.sellingWhales.isEmpty()) {
            return WhaleSignal(
                mint = mint,
                symbol = symbol,
                signal = SignalType.NO_SIGNAL,
                strength = 0.0,
                whaleCount = 0,
                avgWhaleTrustScore = 0.0,
                totalVolumeSol = 0.0,
                recommendation = "NEUTRAL",
                reason = "No whale activity detected"
            )
        }
        
        // Calculate average trust score of involved whales
        val buyingTrust = consensus.buyingWhales.mapNotNull { trackedWhales[it]?.trustScore }.average().takeIf { !it.isNaN() } ?: 50.0
        val sellingTrust = consensus.sellingWhales.mapNotNull { trackedWhales[it]?.trustScore }.average().takeIf { !it.isNaN() } ?: 50.0
        
        return when {
            // STRONG ACCUMULATION: 3+ whales buying, high trust
            consensus.buyingWhales.size >= 3 && buyingTrust >= 60 -> WhaleSignal(
                mint = mint,
                symbol = symbol,
                signal = SignalType.WHALE_ACCUMULATION,
                strength = (consensus.buyingWhales.size * 20.0 + buyingTrust / 2).coerceAtMost(100.0),
                whaleCount = consensus.buyingWhales.size,
                avgWhaleTrustScore = buyingTrust,
                totalVolumeSol = consensus.totalBuyVolumeSol,
                recommendation = "STRONG_BUY",
                reason = "${consensus.buyingWhales.size} trusted whales accumulating (avg trust: ${buyingTrust.toInt()}%)"
            )
            
            // ACCUMULATION: 2+ whales buying
            consensus.buyingWhales.size >= 2 -> WhaleSignal(
                mint = mint,
                symbol = symbol,
                signal = SignalType.WHALE_ACCUMULATION,
                strength = (consensus.buyingWhales.size * 15.0 + buyingTrust / 3).coerceAtMost(80.0),
                whaleCount = consensus.buyingWhales.size,
                avgWhaleTrustScore = buyingTrust,
                totalVolumeSol = consensus.totalBuyVolumeSol,
                recommendation = "BUY",
                reason = "${consensus.buyingWhales.size} whales buying"
            )
            
            // STRONG DISTRIBUTION: 3+ whales selling
            consensus.sellingWhales.size >= 3 && sellingTrust >= 60 -> WhaleSignal(
                mint = mint,
                symbol = symbol,
                signal = SignalType.WHALE_DISTRIBUTION,
                strength = (consensus.sellingWhales.size * 20.0 + sellingTrust / 2).coerceAtMost(100.0),
                whaleCount = consensus.sellingWhales.size,
                avgWhaleTrustScore = sellingTrust,
                totalVolumeSol = consensus.totalSellVolumeSol,
                recommendation = "STRONG_SELL",
                reason = "${consensus.sellingWhales.size} trusted whales distributing - EXIT NOW"
            )
            
            // DISTRIBUTION: 2+ whales selling
            consensus.sellingWhales.size >= 2 -> WhaleSignal(
                mint = mint,
                symbol = symbol,
                signal = SignalType.WHALE_DISTRIBUTION,
                strength = (consensus.sellingWhales.size * 15.0 + sellingTrust / 3).coerceAtMost(80.0),
                whaleCount = consensus.sellingWhales.size,
                avgWhaleTrustScore = sellingTrust,
                totalVolumeSol = consensus.totalSellVolumeSol,
                recommendation = "SELL",
                reason = "${consensus.sellingWhales.size} whales selling - consider exit"
            )
            
            // Single whale activity
            consensus.buyingWhales.size == 1 && buyingTrust >= 70 -> WhaleSignal(
                mint = mint,
                symbol = symbol,
                signal = SignalType.SINGLE_WHALE_BUY,
                strength = buyingTrust * 0.6,
                whaleCount = 1,
                avgWhaleTrustScore = buyingTrust,
                totalVolumeSol = consensus.totalBuyVolumeSol,
                recommendation = "LEAN_BUY",
                reason = "Trusted whale (${buyingTrust.toInt()}% trust) buying"
            )
            
            consensus.sellingWhales.size == 1 && sellingTrust >= 70 -> WhaleSignal(
                mint = mint,
                symbol = symbol,
                signal = SignalType.SINGLE_WHALE_SELL,
                strength = sellingTrust * 0.6,
                whaleCount = 1,
                avgWhaleTrustScore = sellingTrust,
                totalVolumeSol = consensus.totalSellVolumeSol,
                recommendation = "LEAN_SELL",
                reason = "Trusted whale (${sellingTrust.toInt()}% trust) selling"
            )
            
            else -> WhaleSignal(
                mint = mint,
                symbol = symbol,
                signal = SignalType.NO_SIGNAL,
                strength = 0.0,
                whaleCount = 0,
                avgWhaleTrustScore = 0.0,
                totalVolumeSol = 0.0,
                recommendation = "NEUTRAL",
                reason = "Whale activity inconclusive"
            )
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // WHALE MONITORING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record whale activity (called when we detect a whale transaction).
     */
    fun recordWhaleActivity(
        whaleAddress: String,
        mint: String,
        symbol: String,
        action: WhaleAction,
        amountSol: Double,
        priceAtAction: Double,
    ) {
        val activity = WhaleActivity(
            whaleAddress = whaleAddress,
            mint = mint,
            symbol = symbol,
            action = action,
            amountSol = amountSol,
            timestamp = System.currentTimeMillis(),
            priceAtAction = priceAtAction,
        )
        
        // Add to recent activity
        recentActivity.getOrPut(mint) { mutableListOf() }.add(activity)
        
        // Update consensus
        val consensus = consensusSignals.getOrPut(mint) { 
            WhaleConsensus(mint = mint, symbol = symbol) 
        }
        
        when (action) {
            WhaleAction.BUY, WhaleAction.ACCUMULATE -> {
                consensus.buyingWhales.add(whaleAddress)
                // Update volume
                consensusSignals[mint] = consensus.copy(
                    totalBuyVolumeSol = consensus.totalBuyVolumeSol + amountSol
                )
            }
            WhaleAction.SELL, WhaleAction.DISTRIBUTE -> {
                consensus.sellingWhales.add(whaleAddress)
                consensusSignals[mint] = consensus.copy(
                    totalSellVolumeSol = consensus.totalSellVolumeSol + amountSol
                )
            }
        }
        
        // Update whale profile
        val whale = trackedWhales.getOrPut(whaleAddress) { 
            WhaleProfile(address = whaleAddress) 
        }
        whale.lastSeenMs = System.currentTimeMillis()
        
        ErrorLogger.info(TAG, "🐋 Whale ${whaleAddress.take(8)}... ${action.name} $symbol | ${amountSol.toInt()} SOL")
    }
    
    /**
     * Record trade outcome for whale signal accuracy tracking.
     */
    fun recordSignalOutcome(mint: String, wasSignalCorrect: Boolean, pnlPct: Double) {
        val consensus = consensusSignals[mint] ?: return
        
        // Update trust scores for involved whales
        val whaleAddresses = if (pnlPct > 0) consensus.buyingWhales else consensus.sellingWhales
        
        whaleAddresses.forEach { address ->
            val whale = trackedWhales[address] ?: return@forEach
            whale.totalTrades++
            
            if (wasSignalCorrect) {
                whale.winningTrades++
                whale.trustScore = (whale.trustScore + 2.0).coerceAtMost(100.0)
                if (pnlPct > 0) whale.avgWinPct = (whale.avgWinPct + pnlPct) / 2
            } else {
                whale.trustScore = (whale.trustScore - 3.0).coerceAtLeast(10.0)
                if (pnlPct < 0) whale.avgLossPct = (whale.avgLossPct + pnlPct) / 2
            }
            
            whale.totalPnlSol += pnlPct / 100.0  // Rough conversion
        }
        
        // Clear old consensus after outcome recorded
        consensusSignals.remove(mint)
    }
    
    /**
     * Add a known whale to track.
     */
    fun addWhaleToTrack(address: String, nickname: String = "", initialTrust: Double = 50.0) {
        trackedWhales[address] = WhaleProfile(
            address = address,
            nickname = nickname,
            trustScore = initialTrust,
        )
        ErrorLogger.info(TAG, "🐋 Added whale to track: ${nickname.ifEmpty { address.take(8) }}...")
    }
    
    /**
     * Check if an address is a tracked whale.
     */
    fun isTrackedWhale(address: String): Boolean = trackedWhales.containsKey(address)
    
    /**
     * Get whale profile.
     */
    fun getWhaleProfile(address: String): WhaleProfile? = trackedWhales[address]
    
    /**
     * Get all tracked whales sorted by trust score.
     */
    fun getTopWhales(limit: Int = 20): List<WhaleProfile> {
        return trackedWhales.values
            .filter { it.isActive }
            .sortedByDescending { it.trustScore }
            .take(limit)
    }
    
    /**
     * Clean up old data.
     */
    fun cleanup() {
        val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000L)
        
        // Remove old activity
        recentActivity.forEach { (_, activities) ->
            activities.removeIf { it.timestamp < oneHourAgo }
        }
        recentActivity.entries.removeIf { it.value.isEmpty() }
        
        // Remove stale consensus signals
        consensusSignals.entries.removeIf { 
            it.value.firstDetectedMs < oneHourAgo 
        }
    }
    
    /**
     * Get stats for logging.
     */
    fun getStats(): String {
        val activeWhales = trackedWhales.values.count { it.isActive }
        val avgTrust = trackedWhales.values.map { it.trustScore }.average().takeIf { !it.isNaN() } ?: 0.0
        val activeSignals = consensusSignals.size
        return "WhaleAI: $activeWhales whales tracked, avg trust ${avgTrust.toInt()}%, $activeSignals active signals"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        val json = JSONObject()
        val whalesArray = JSONArray()
        
        trackedWhales.values.forEach { whale ->
            whalesArray.put(JSONObject().apply {
                put("address", whale.address)
                put("nickname", whale.nickname)
                put("totalTrades", whale.totalTrades)
                put("winningTrades", whale.winningTrades)
                put("totalPnlSol", whale.totalPnlSol)
                put("avgWinPct", whale.avgWinPct)
                put("avgLossPct", whale.avgLossPct)
                put("trustScore", whale.trustScore)
                put("isActive", whale.isActive)
            })
        }
        
        json.put("whales", whalesArray)
        json.put("savedAt", System.currentTimeMillis())
        return json
    }
    
    fun loadFromJson(json: JSONObject) {
        trackedWhales.clear()
        
        val whalesArray = json.optJSONArray("whales") ?: return
        
        for (i in 0 until whalesArray.length()) {
            val w = whalesArray.optJSONObject(i) ?: continue
            val whale = WhaleProfile(
                address = w.optString("address"),
                nickname = w.optString("nickname", ""),
                totalTrades = w.optInt("totalTrades", 0),
                winningTrades = w.optInt("winningTrades", 0),
                totalPnlSol = w.optDouble("totalPnlSol", 0.0),
                avgWinPct = w.optDouble("avgWinPct", 0.0),
                avgLossPct = w.optDouble("avgLossPct", 0.0),
                trustScore = w.optDouble("trustScore", 50.0),
                isActive = w.optBoolean("isActive", true),
            )
            if (whale.address.isNotEmpty()) {
                trackedWhales[whale.address] = whale
            }
        }
        
        ErrorLogger.info(TAG, "🐋 Loaded ${trackedWhales.size} tracked whales")
    }
}

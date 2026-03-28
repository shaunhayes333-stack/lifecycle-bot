package com.lifecyclebot.v3.learning

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ShadowLearningEngine - Permanent Paper Mode for Continuous Learning
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Runs in the background CONSTANTLY, making virtual trades that feed the 
 * learning engine without risking real capital. This is the "always-on" 
 * training system that makes the bot smarter over time.
 * 
 * SHADOW LEARNING CONCEPT:
 *   - Every token the scanner sees gets a "shadow trade" decision
 *   - Shadow trades execute instantly with zero slippage
 *   - Outcomes are tracked and fed to MetaCognitionAI
 *   - AI layers get accuracy feedback from shadow results
 *   - System learns from 100x more "trades" than real execution
 * 
 * BENEFITS:
 *   1. Rapid AI calibration without capital risk
 *   2. Mode/regime accuracy tracking across all market conditions
 *   3. Signal quality measurement for every AI layer
 *   4. Pattern library building at massive scale
 *   5. Continuous backtesting on live data
 * 
 * SHADOW TRADE TYPES:
 *   - SHADOW_LONG: Would have bought
 *   - SHADOW_SHORT: Would have shorted (conceptual tracking)
 *   - SHADOW_STRANGLE: Would have played volatility
 *   - SHADOW_AVOID: Correctly avoided bad setup
 */
object ShadowLearningEngine {
    
    private const val TAG = "ShadowLearning"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SHADOW TRADE TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class ShadowType(val emoji: String) {
        SHADOW_LONG("📈"),
        SHADOW_SHORT("📉"),
        SHADOW_STRANGLE("🎯"),  // Volatility play
        SHADOW_STRADDLE("⚖️"),  // Neutral volatility
        SHADOW_AVOID("🚫"),     // Correctly avoided
    }
    
    data class ShadowTrade(
        val id: String,
        val mint: String,
        val symbol: String,
        val type: ShadowType,
        val entryPrice: Double,
        val entryTime: Long,
        var exitPrice: Double = 0.0,
        var exitTime: Long = 0,
        var pnlPct: Double = 0.0,
        var isOpen: Boolean = true,
        
        // AI context at entry
        val aiConfidence: Int,
        val setupQuality: String,
        val regime: String,
        val mode: String,
        val aiPredictions: Map<String, Int>,  // layer name -> prediction score
        
        // For strangle/straddle
        val volatilityTarget: Double = 0.0,
        val upperStrike: Double = 0.0,
        val lowerStrike: Double = 0.0,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Active shadow trades
    private val openShadowTrades = ConcurrentHashMap<String, ShadowTrade>()
    
    // Completed shadow trades (for learning)
    private val completedTrades = mutableListOf<ShadowTrade>()
    private const val MAX_COMPLETED = 1000
    
    // Stats
    private val totalShadowTrades = AtomicLong(0)
    private val shadowWins = AtomicLong(0)
    private val shadowLosses = AtomicLong(0)
    private val totalShadowPnl = AtomicLong(0)  // Stored as basis points
    
    // Engine state
    private val isRunning = AtomicBoolean(false)
    private var engineJob: Job? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ENGINE CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Start the shadow learning engine.
     * Call this on app startup after all AI layers are initialized.
     */
    fun start(scope: CoroutineScope) {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Shadow engine already running")
            return
        }
        
        Log.i(TAG, "🌑 Shadow Learning Engine STARTED")
        
        engineJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                try {
                    // Process open trades every 30 seconds
                    processOpenTrades()
                    delay(30_000)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Shadow engine error: ${e.message}")
                    delay(60_000)  // Back off on error
                }
            }
        }
    }
    
    /**
     * Stop the shadow learning engine.
     */
    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        engineJob = null
        Log.i(TAG, "🌑 Shadow Learning Engine STOPPED")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SHADOW TRADE ENTRY
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Open a shadow long trade.
     * Call this when a token passes filters but doesn't meet execution threshold.
     */
    fun openShadowLong(
        mint: String,
        symbol: String,
        entryPrice: Double,
        aiConfidence: Int,
        setupQuality: String,
        regime: String,
        mode: String,
        aiPredictions: Map<String, Int> = emptyMap(),
    ): ShadowTrade {
        val trade = ShadowTrade(
            id = "SL_${System.currentTimeMillis()}_${mint.take(8)}",
            mint = mint,
            symbol = symbol,
            type = ShadowType.SHADOW_LONG,
            entryPrice = entryPrice,
            entryTime = System.currentTimeMillis(),
            aiConfidence = aiConfidence,
            setupQuality = setupQuality,
            regime = regime,
            mode = mode,
            aiPredictions = aiPredictions,
        )
        
        openShadowTrades[trade.id] = trade
        totalShadowTrades.incrementAndGet()
        
        Log.d(TAG, "📈 Shadow LONG: $symbol @ $entryPrice | conf=$aiConfidence | $mode")
        
        return trade
    }
    
    /**
     * Open a shadow short trade (conceptual - tracking what would happen if we shorted).
     */
    fun openShadowShort(
        mint: String,
        symbol: String,
        entryPrice: Double,
        aiConfidence: Int,
        setupQuality: String,
        regime: String,
        mode: String,
        aiPredictions: Map<String, Int> = emptyMap(),
    ): ShadowTrade {
        val trade = ShadowTrade(
            id = "SS_${System.currentTimeMillis()}_${mint.take(8)}",
            mint = mint,
            symbol = symbol,
            type = ShadowType.SHADOW_SHORT,
            entryPrice = entryPrice,
            entryTime = System.currentTimeMillis(),
            aiConfidence = aiConfidence,
            setupQuality = setupQuality,
            regime = regime,
            mode = mode,
            aiPredictions = aiPredictions,
        )
        
        openShadowTrades[trade.id] = trade
        totalShadowTrades.incrementAndGet()
        
        Log.d(TAG, "📉 Shadow SHORT: $symbol @ $entryPrice | conf=$aiConfidence | $mode")
        
        return trade
    }
    
    /**
     * Open a shadow strangle (volatility play).
     * Profits if price moves significantly in EITHER direction.
     */
    fun openShadowStrangle(
        mint: String,
        symbol: String,
        currentPrice: Double,
        volatilityTarget: Double,  // Expected % move to profit
        aiConfidence: Int,
        regime: String,
        mode: String,
        aiPredictions: Map<String, Int> = emptyMap(),
    ): ShadowTrade {
        val upperStrike = currentPrice * (1 + volatilityTarget / 100)
        val lowerStrike = currentPrice * (1 - volatilityTarget / 100)
        
        val trade = ShadowTrade(
            id = "ST_${System.currentTimeMillis()}_${mint.take(8)}",
            mint = mint,
            symbol = symbol,
            type = ShadowType.SHADOW_STRANGLE,
            entryPrice = currentPrice,
            entryTime = System.currentTimeMillis(),
            aiConfidence = aiConfidence,
            setupQuality = "VOLATILITY",
            regime = regime,
            mode = mode,
            aiPredictions = aiPredictions,
            volatilityTarget = volatilityTarget,
            upperStrike = upperStrike,
            lowerStrike = lowerStrike,
        )
        
        openShadowTrades[trade.id] = trade
        totalShadowTrades.incrementAndGet()
        
        Log.d(TAG, "🎯 Shadow STRANGLE: $symbol @ $currentPrice | target=$volatilityTarget% | " +
            "strikes=$lowerStrike-$upperStrike")
        
        return trade
    }
    
    /**
     * Open a shadow straddle (neutral volatility).
     * Similar to strangle but tighter strikes.
     */
    fun openShadowStraddle(
        mint: String,
        symbol: String,
        currentPrice: Double,
        volatilityTarget: Double,
        aiConfidence: Int,
        regime: String,
        mode: String,
        aiPredictions: Map<String, Int> = emptyMap(),
    ): ShadowTrade {
        val halfTarget = volatilityTarget / 2
        val upperStrike = currentPrice * (1 + halfTarget / 100)
        val lowerStrike = currentPrice * (1 - halfTarget / 100)
        
        val trade = ShadowTrade(
            id = "SD_${System.currentTimeMillis()}_${mint.take(8)}",
            mint = mint,
            symbol = symbol,
            type = ShadowType.SHADOW_STRADDLE,
            entryPrice = currentPrice,
            entryTime = System.currentTimeMillis(),
            aiConfidence = aiConfidence,
            setupQuality = "VOLATILITY",
            regime = regime,
            mode = mode,
            aiPredictions = aiPredictions,
            volatilityTarget = volatilityTarget,
            upperStrike = upperStrike,
            lowerStrike = lowerStrike,
        )
        
        openShadowTrades[trade.id] = trade
        totalShadowTrades.incrementAndGet()
        
        Log.d(TAG, "⚖️ Shadow STRADDLE: $symbol @ $currentPrice | target=$volatilityTarget%")
        
        return trade
    }
    
    /**
     * Record a shadow avoid (correctly didn't trade).
     * Used when filters block a trade - we track if avoiding was correct.
     */
    fun recordShadowAvoid(
        mint: String,
        symbol: String,
        price: Double,
        aiConfidence: Int,
        setupQuality: String,
        regime: String,
        mode: String,
        blockReason: String,
        aiPredictions: Map<String, Int> = emptyMap(),
    ): ShadowTrade {
        val trade = ShadowTrade(
            id = "SA_${System.currentTimeMillis()}_${mint.take(8)}",
            mint = mint,
            symbol = symbol,
            type = ShadowType.SHADOW_AVOID,
            entryPrice = price,
            entryTime = System.currentTimeMillis(),
            aiConfidence = aiConfidence,
            setupQuality = setupQuality,
            regime = regime,
            mode = "$mode|$blockReason",
            aiPredictions = aiPredictions,
        )
        
        openShadowTrades[trade.id] = trade
        totalShadowTrades.incrementAndGet()
        
        Log.d(TAG, "🚫 Shadow AVOID: $symbol @ $price | reason=$blockReason")
        
        return trade
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE UPDATES & TRADE PROCESSING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Update price for a token and check if any shadow trades should close.
     */
    fun updatePrice(mint: String, currentPrice: Double) {
        openShadowTrades.values
            .filter { it.mint == mint && it.isOpen }
            .forEach { trade ->
                checkExitConditions(trade, currentPrice)
            }
    }
    
    private fun checkExitConditions(trade: ShadowTrade, currentPrice: Double) {
        val holdTimeMs = System.currentTimeMillis() - trade.entryTime
        val holdTimeMins = holdTimeMs / 60_000
        
        when (trade.type) {
            ShadowType.SHADOW_LONG -> {
                val pnlPct = ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100
                
                // Exit conditions for shadow long
                val shouldExit = when {
                    pnlPct >= 30 -> true     // Take profit
                    pnlPct <= -20 -> true    // Stop loss
                    holdTimeMins >= 60 -> true  // Timeout
                    else -> false
                }
                
                if (shouldExit) {
                    closeShadowTrade(trade, currentPrice, pnlPct)
                }
            }
            
            ShadowType.SHADOW_SHORT -> {
                val pnlPct = ((trade.entryPrice - currentPrice) / trade.entryPrice) * 100
                
                val shouldExit = when {
                    pnlPct >= 20 -> true     // Take profit
                    pnlPct <= -15 -> true    // Stop loss
                    holdTimeMins >= 60 -> true
                    else -> false
                }
                
                if (shouldExit) {
                    closeShadowTrade(trade, currentPrice, pnlPct)
                }
            }
            
            ShadowType.SHADOW_STRANGLE, ShadowType.SHADOW_STRADDLE -> {
                // Strangle/straddle profits if price breaks either strike
                val pnlPct = when {
                    currentPrice >= trade.upperStrike -> {
                        ((currentPrice - trade.upperStrike) / trade.entryPrice) * 100
                    }
                    currentPrice <= trade.lowerStrike -> {
                        ((trade.lowerStrike - currentPrice) / trade.entryPrice) * 100
                    }
                    else -> {
                        // Still in range - track time decay (loss)
                        val timeDecay = (holdTimeMins / 60.0) * -2.0  // 2% per hour decay
                        timeDecay
                    }
                }
                
                val shouldExit = when {
                    currentPrice >= trade.upperStrike -> true  // Upper breakout
                    currentPrice <= trade.lowerStrike -> true  // Lower breakout
                    holdTimeMins >= 120 -> true                // Timeout (volatility didn't materialize)
                    else -> false
                }
                
                if (shouldExit) {
                    closeShadowTrade(trade, currentPrice, pnlPct)
                }
            }
            
            ShadowType.SHADOW_AVOID -> {
                // For avoids, we track what would have happened
                val wouldHavePnl = ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100
                
                val shouldClose = when {
                    holdTimeMins >= 30 -> true  // Check after 30 mins
                    kotlin.math.abs(wouldHavePnl) >= 20 -> true  // Significant move
                    else -> false
                }
                
                if (shouldClose) {
                    // For avoids, negative pnl means we were RIGHT to avoid
                    // Positive pnl means we missed an opportunity
                    closeShadowTrade(trade, currentPrice, -wouldHavePnl)  // Inverted!
                }
            }
        }
    }
    
    private fun closeShadowTrade(trade: ShadowTrade, exitPrice: Double, pnlPct: Double) {
        trade.exitPrice = exitPrice
        trade.exitTime = System.currentTimeMillis()
        trade.pnlPct = pnlPct
        trade.isOpen = false
        
        // Update stats
        if (pnlPct > 0) {
            shadowWins.incrementAndGet()
        } else {
            shadowLosses.incrementAndGet()
        }
        totalShadowPnl.addAndGet((pnlPct * 100).toLong())  // Store as basis points
        
        // Move to completed
        synchronized(completedTrades) {
            completedTrades.add(trade)
            if (completedTrades.size > MAX_COMPLETED) {
                completedTrades.removeAt(0)
            }
        }
        
        // Remove from open
        openShadowTrades.remove(trade.id)
        
        // Feed to MetaCognitionAI
        feedToMetaCognition(trade)
        
        val emoji = if (pnlPct > 0) "✅" else "❌"
        Log.d(TAG, "$emoji Shadow ${trade.type.name} closed: ${trade.symbol} | " +
            "pnl=${pnlPct.toInt()}% | entry=${trade.entryPrice} exit=$exitPrice")
    }
    
    private fun feedToMetaCognition(trade: ShadowTrade) {
        try {
            // Convert AI predictions to MetaCognition format and record
            com.lifecyclebot.v3.scoring.MetaCognitionAI.recordTradeOutcome(
                mint = trade.mint,
                symbol = trade.symbol,
                pnlPct = trade.pnlPct,
                holdTimeMs = trade.exitTime - trade.entryTime,
                exitReason = "shadow_${trade.type.name.lowercase()}"
            )
        } catch (e: Exception) {
            // Silently ignore
        }
    }
    
    private fun processOpenTrades() {
        val now = System.currentTimeMillis()
        
        // Close any trades that have been open too long
        openShadowTrades.values
            .filter { it.isOpen && (now - it.entryTime) > 3600_000 }  // 1 hour max
            .forEach { trade ->
                // Force close with estimated exit
                val estimatedExit = trade.entryPrice  // Assume flat if no price update
                val pnlPct = 0.0
                closeShadowTrade(trade, estimatedExit, pnlPct)
            }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYTICS & LEARNING SIGNALS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get shadow trading stats.
     */
    fun getStats(): ShadowStats {
        val wins = shadowWins.get()
        val losses = shadowLosses.get()
        val total = wins + losses
        val winRate = if (total > 0) (wins.toDouble() / total) * 100 else 0.0
        val avgPnl = if (total > 0) totalShadowPnl.get().toDouble() / 100 / total else 0.0
        
        return ShadowStats(
            totalTrades = totalShadowTrades.get(),
            openTrades = openShadowTrades.size,
            wins = wins,
            losses = losses,
            winRate = winRate,
            avgPnlPct = avgPnl,
        )
    }
    
    data class ShadowStats(
        val totalTrades: Long,
        val openTrades: Int,
        val wins: Long,
        val losses: Long,
        val winRate: Double,
        val avgPnlPct: Double,
    )
    
    /**
     * Get performance by mode.
     */
    fun getPerformanceByMode(): Map<String, ModePerformance> {
        val byMode = mutableMapOf<String, MutableList<ShadowTrade>>()
        
        synchronized(completedTrades) {
            completedTrades.forEach { trade ->
                val mode = trade.mode.substringBefore("|")  // Remove block reason if present
                byMode.getOrPut(mode) { mutableListOf() }.add(trade)
            }
        }
        
        return byMode.mapValues { (_, trades) ->
            val wins = trades.count { it.pnlPct > 0 }
            val winRate = if (trades.isNotEmpty()) wins.toDouble() / trades.size * 100 else 0.0
            val avgPnl = if (trades.isNotEmpty()) trades.map { it.pnlPct }.average() else 0.0
            
            ModePerformance(
                mode = trades.firstOrNull()?.mode ?: "",
                trades = trades.size,
                winRate = winRate,
                avgPnlPct = avgPnl,
            )
        }
    }
    
    data class ModePerformance(
        val mode: String,
        val trades: Int,
        val winRate: Double,
        val avgPnlPct: Double,
    )
    
    /**
     * Get performance by AI confidence level.
     */
    fun getPerformanceByConfidence(): Map<String, Double> {
        val buckets = mapOf(
            "0-30" to mutableListOf<Double>(),
            "30-50" to mutableListOf<Double>(),
            "50-70" to mutableListOf<Double>(),
            "70-100" to mutableListOf<Double>(),
        )
        
        synchronized(completedTrades) {
            completedTrades.forEach { trade ->
                val bucket = when {
                    trade.aiConfidence < 30 -> "0-30"
                    trade.aiConfidence < 50 -> "30-50"
                    trade.aiConfidence < 70 -> "50-70"
                    else -> "70-100"
                }
                buckets[bucket]?.add(trade.pnlPct)
            }
        }
        
        return buckets.mapValues { (_, pnls) ->
            if (pnls.isNotEmpty()) pnls.average() else 0.0
        }
    }
    
    /**
     * Get "avoid" accuracy - how often were we right to skip a trade?
     */
    fun getAvoidAccuracy(): Double {
        val avoids = synchronized(completedTrades) {
            completedTrades.filter { it.type == ShadowType.SHADOW_AVOID }
        }
        
        if (avoids.isEmpty()) return 0.0
        
        // For avoids, positive pnl means we were right (price went down)
        val correctAvoids = avoids.count { it.pnlPct > 0 }
        return correctAvoids.toDouble() / avoids.size * 100
    }
    
    /**
     * Get strangle/straddle performance.
     */
    fun getVolatilityPlayStats(): VolatilityStats {
        val volPlays = synchronized(completedTrades) {
            completedTrades.filter { 
                it.type == ShadowType.SHADOW_STRANGLE || it.type == ShadowType.SHADOW_STRADDLE 
            }
        }
        
        if (volPlays.isEmpty()) {
            return VolatilityStats(0, 0.0, 0.0, 0.0)
        }
        
        val wins = volPlays.count { it.pnlPct > 0 }
        val avgPnl = volPlays.map { it.pnlPct }.average()
        val avgBreakoutSize = volPlays.filter { it.pnlPct > 0 }.map { 
            kotlin.math.abs((it.exitPrice - it.entryPrice) / it.entryPrice * 100)
        }.average()
        
        return VolatilityStats(
            totalPlays = volPlays.size,
            winRate = wins.toDouble() / volPlays.size * 100,
            avgPnlPct = avgPnl,
            avgBreakoutSize = if (avgBreakoutSize.isNaN()) 0.0 else avgBreakoutSize,
        )
    }
    
    data class VolatilityStats(
        val totalPlays: Int,
        val winRate: Double,
        val avgPnlPct: Double,
        val avgBreakoutSize: Double,
    )
    
    /**
     * Get status string for logging/UI.
     */
    fun getStatus(): String {
        val stats = getStats()
        return "ShadowLearning: total=${stats.totalTrades} | open=${stats.openTrades} | " +
            "WR=${stats.winRate.toInt()}% | avgPnl=${stats.avgPnlPct.toInt()}%"
    }
    
    /**
     * Reset all stats (for testing).
     */
    fun reset() {
        openShadowTrades.clear()
        completedTrades.clear()
        totalShadowTrades.set(0)
        shadowWins.set(0)
        shadowLosses.set(0)
        totalShadowPnl.set(0)
    }
}

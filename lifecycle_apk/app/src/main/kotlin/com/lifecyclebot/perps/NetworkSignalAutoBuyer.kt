package com.lifecyclebot.perps

import com.lifecyclebot.collective.CollectiveLearning
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.CollectiveIntelligenceAI
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📡 NETWORK SIGNAL AUTO-BUYER - V5.7.3
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Automatically executes trades based on Network Signals from the Hive.
 * 
 * SIGNAL TYPES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   🔥 MEGA_WINNER (50%+ PnL) - High priority auto-buy
 *   🌐 HOT_TOKEN (20%+ PnL)   - Medium priority auto-buy
 *   ⚠️  AVOID (-15% PnL)       - Blacklist / do not buy
 * 
 * SAFETY FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Confirmation threshold (min ack_count from multiple bots)
 *   • Liquidity check (min $10k)
 *   • Cooldown per token (no double-buying)
 *   • Daily limit on auto-buys
 *   • Integration with existing AI layers for final confirmation
 *   • Paper mode by default
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object NetworkSignalAutoBuyer {
    
    private const val TAG = "📡AutoBuyer"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class AutoBuyerConfig(
        val enabled: Boolean = false,
        val paperModeOnly: Boolean = true,           // Safety: paper mode only
        val minAckCount: Int = 2,                    // Min confirmations from network
        val minLiquidityUsd: Double = 10_000.0,      // Min liquidity
        val minConfidence: Int = 60,                 // Min signal confidence
        val maxDailyAutoBuys: Int = 10,              // Max auto-buys per day
        val autoBuyMegaWinners: Boolean = true,      // Auto-buy 🔥 MEGA_WINNER
        val autoBuyHotTokens: Boolean = false,       // Auto-buy 🌐 HOT_TOKEN (conservative default)
        val positionSizePct: Double = 3.0,           // % of balance per auto-buy
        val useLeverage: Boolean = false,            // Use perps leverage (advanced)
        val leverageAmount: Double = 2.0,            // Default leverage if enabled
        val cooldownMinutes: Int = 30,               // Cooldown per token
        val requireAIConfirmation: Boolean = true,   // Require AI layer approval
    )
    
    private var config = AutoBuyerConfig()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val isRunning = AtomicBoolean(false)
    private var scanJob: Job? = null
    
    // Tracking
    private val dailyAutoBuys = AtomicInteger(0)
    private val totalAutoBuys = AtomicInteger(0)
    private val successfulBuys = AtomicInteger(0)
    private val failedBuys = AtomicInteger(0)
    private val lastScanTime = AtomicLong(0)
    private val lastDayReset = AtomicLong(0)
    
    // Cooldown tracking - mint -> last buy time
    private val tokenCooldowns = ConcurrentHashMap<String, Long>()
    
    // Recently executed signals (to prevent duplicates)
    private val executedSignals = ConcurrentHashMap<Long, Long>()  // signalId -> executeTime
    
    // Scan interval
    private const val SCAN_INTERVAL_MS = 15_000L  // 15 seconds
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun start(newConfig: AutoBuyerConfig = config) {
        if (isRunning.get()) {
            ErrorLogger.warn(TAG, "Already running")
            return
        }
        
        config = newConfig
        
        if (!config.enabled) {
            ErrorLogger.info(TAG, "📡 Auto-buyer disabled")
            return
        }
        
        isRunning.set(true)
        
        // Reset daily counter if new day
        checkDayReset()
        
        // Start scan loop
        scanJob = CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
            ErrorLogger.info(TAG, "📡 Network Signal Auto-Buyer STARTED | Paper=${config.paperModeOnly}")
            
            while (isRunning.get() && isActive) {
                try {
                    scanAndExecute()
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
        ErrorLogger.info(TAG, "📡 Auto-buyer STOPPED")
    }
    
    fun isEnabled(): Boolean = isRunning.get()
    
    fun updateConfig(newConfig: AutoBuyerConfig) {
        config = newConfig
        if (!newConfig.enabled && isRunning.get()) {
            stop()
        } else if (newConfig.enabled && !isRunning.get()) {
            start(newConfig)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN SCAN LOOP
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun scanAndExecute() {
        checkDayReset()
        lastScanTime.set(System.currentTimeMillis())
        
        // Check daily limit
        if (dailyAutoBuys.get() >= config.maxDailyAutoBuys) {
            return  // Daily limit reached
        }
        
        // Get active network signals
        val signals = CollectiveIntelligenceAI.getActiveNetworkSignals()
        
        if (signals.isEmpty()) return
        
        // Filter to actionable signals
        val actionableSignals = signals.filter { signal ->
            isSignalActionable(signal)
        }
        
        if (actionableSignals.isEmpty()) return
        
        // Sort by priority: MEGA_WINNER first, then by ackCount, then by pnlPct
        val prioritized = actionableSignals.sortedWith(
            compareBy(
                { if (it.signalType == "MEGA_WINNER") 0 else 1 },
                { -it.ackCount },
                { -it.pnlPct }
            )
        )
        
        // Execute top signal(s)
        for (signal in prioritized.take(2)) {  // Max 2 per scan cycle
            try {
                val executed = executeSignal(signal)
                if (executed) {
                    executedSignals[signal.id] = System.currentTimeMillis()
                    tokenCooldowns[signal.mint] = System.currentTimeMillis()
                    dailyAutoBuys.incrementAndGet()
                    totalAutoBuys.incrementAndGet()
                    
                    // Don't spam - one success per cycle is enough
                    break
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Execute signal error: ${e.message}", e)
                failedBuys.incrementAndGet()
            }
        }
        
        // Cleanup old executed signals (older than 1 hour)
        val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
        executedSignals.entries.removeIf { it.value < oneHourAgo }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SIGNAL VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun isSignalActionable(signal: CollectiveLearning.NetworkSignal): Boolean {
        // Check signal type
        val isAllowedType = when (signal.signalType) {
            "MEGA_WINNER" -> config.autoBuyMegaWinners
            "HOT_TOKEN" -> config.autoBuyHotTokens
            "AVOID" -> false  // Never auto-buy avoid signals
            else -> false
        }
        if (!isAllowedType) return false
        
        // Check if already executed
        if (executedSignals.containsKey(signal.id)) return false
        
        // Check cooldown
        val lastBuy = tokenCooldowns[signal.mint] ?: 0
        val cooldownMs = config.cooldownMinutes * 60 * 1000L
        if (System.currentTimeMillis() - lastBuy < cooldownMs) return false
        
        // Check ack count (network confirmations)
        if (signal.ackCount < config.minAckCount) return false
        
        // Check liquidity
        if (signal.liquidityUsd < config.minLiquidityUsd) return false
        
        // Check confidence
        if (signal.confidence < config.minConfidence) return false
        
        // Check expiration
        if (signal.expiresAt < System.currentTimeMillis()) return false
        
        return true
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeSignal(signal: CollectiveLearning.NetworkSignal): Boolean {
        ErrorLogger.info(TAG, "📡 Evaluating signal: ${signal.symbol} (${signal.signalType}) " +
            "PnL=${signal.pnlPct.toInt()}% | Acks=${signal.ackCount}")
        
        // Optional: AI layer confirmation
        if (config.requireAIConfirmation) {
            val aiApproved = getAILayerApproval(signal)
            if (!aiApproved) {
                ErrorLogger.debug(TAG, "❌ AI layers rejected signal: ${signal.symbol}")
                return false
            }
        }
        
        // Determine execution method
        return if (config.useLeverage) {
            executeLeveragedBuy(signal)
        } else {
            executeSpotBuy(signal)
        }
    }
    
    /**
     * Get approval from AI layers
     */
    private fun getAILayerApproval(signal: CollectiveLearning.NetworkSignal): Boolean {
        try {
            // Check if token is blacklisted
            val isBlacklisted = com.lifecyclebot.engine.TokenBlacklistAI.isBlacklisted(signal.mint)
            if (isBlacklisted) return false
            
            // Check collective sentiment
            val hasPositive = CollectiveIntelligenceAI.hasPositiveNetworkSignal(signal.mint)
            if (!hasPositive) return false
            
            // For MEGA_WINNERS, we're more lenient
            if (signal.signalType == "MEGA_WINNER" && signal.ackCount >= 3) {
                return true
            }
            
            // For HOT_TOKENs, require higher threshold
            if (signal.signalType == "HOT_TOKEN") {
                return signal.confidence >= 70 && signal.ackCount >= 2
            }
            
            return true
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "AI approval check failed: ${e.message}")
            return false
        }
    }
    
    /**
     * Execute a spot buy via existing bot infrastructure
     */
    private suspend fun executeSpotBuy(signal: CollectiveLearning.NetworkSignal): Boolean {
        try {
            // Use existing Executor to queue a buy
            val success = com.lifecyclebot.engine.Executor.queueNetworkSignalBuy(
                mint = signal.mint,
                symbol = signal.symbol,
                sizePct = config.positionSizePct,
                reason = "Auto-buy: ${signal.signalType} from network (${signal.ackCount} acks)",
                isPaper = config.paperModeOnly,
            )
            
            if (success) {
                successfulBuys.incrementAndGet()
                ErrorLogger.info(TAG, "✅ AUTO-BUY EXECUTED: ${signal.symbol} (${signal.signalType})")
                
                // Record to learning
                recordAutoBuy(signal, false)
            } else {
                failedBuys.incrementAndGet()
            }
            
            return success
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Spot buy error: ${e.message}", e)
            failedBuys.incrementAndGet()
            return false
        }
    }
    
    /**
     * Execute a leveraged buy via Perps system
     */
    private suspend fun executeLeveragedBuy(signal: CollectiveLearning.NetworkSignal): Boolean {
        try {
            // Only SOL is supported for perps leverage currently
            val market = PerpsMarket.SOL
            
            // Get SOL correlation - if the hot token is moving, SOL usually follows
            val marketData = PerpsMarketDataFetcher.getMarketData(market)
            
            // Determine direction based on signal
            val direction = if (signal.pnlPct > 0) PerpsDirection.LONG else PerpsDirection.SHORT
            
            // Execute via PerpsExecutionEngine
            val position = PerpsExecutionEngine.manualOpen(
                market = market,
                direction = direction,
                leverage = config.leverageAmount,
                sizePct = config.positionSizePct,
            )
            
            if (position != null) {
                successfulBuys.incrementAndGet()
                ErrorLogger.info(TAG, "✅ LEVERAGED AUTO-BUY: SOL ${direction.symbol} @ ${config.leverageAmount}x " +
                    "(triggered by ${signal.symbol})")
                
                // Record to learning
                recordAutoBuy(signal, true)
                return true
            } else {
                failedBuys.incrementAndGet()
                return false
            }
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Leveraged buy error: ${e.message}", e)
            failedBuys.incrementAndGet()
            return false
        }
    }
    
    /**
     * Record auto-buy for learning
     */
    private fun recordAutoBuy(signal: CollectiveLearning.NetworkSignal, wasLeveraged: Boolean) {
        try {
            // Record insight to perps learning
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val insight = com.lifecyclebot.collective.PerpsInsightRecord(
                        instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: "",
                        insightType = "AUTO_BUY",
                        layerName = "NetworkSignalAutoBuyer",
                        market = if (wasLeveraged) "SOL" else signal.symbol,
                        direction = "LONG",
                        insight = "Auto-bought ${signal.symbol} (${signal.signalType}) with ${signal.ackCount} network confirmations",
                        actionTaken = if (wasLeveraged) "LEVERAGED_BUY" else "SPOT_BUY",
                        impactScore = signal.pnlPct,
                        timestamp = System.currentTimeMillis(),
                    )
                    com.lifecyclebot.collective.TursoClient.savePerpsInsight(insight)
                } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "Record insight failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Silent fail
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun checkDayReset() {
        val now = System.currentTimeMillis()
        val dayStart = now - (now % (24 * 60 * 60 * 1000))
        
        if (lastDayReset.get() < dayStart) {
            dailyAutoBuys.set(0)
            lastDayReset.set(dayStart)
            ErrorLogger.debug(TAG, "Daily counter reset")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class AutoBuyerStats(
        val isEnabled: Boolean,
        val dailyAutoBuys: Int,
        val maxDailyAutoBuys: Int,
        val totalAutoBuys: Int,
        val successfulBuys: Int,
        val failedBuys: Int,
        val lastScanTime: Long,
        val activeCooldowns: Int,
        val paperModeOnly: Boolean,
    )
    
    fun getStats(): AutoBuyerStats = AutoBuyerStats(
        isEnabled = isRunning.get(),
        dailyAutoBuys = dailyAutoBuys.get(),
        maxDailyAutoBuys = config.maxDailyAutoBuys,
        totalAutoBuys = totalAutoBuys.get(),
        successfulBuys = successfulBuys.get(),
        failedBuys = failedBuys.get(),
        lastScanTime = lastScanTime.get(),
        activeCooldowns = tokenCooldowns.size,
        paperModeOnly = config.paperModeOnly,
    )
    
    fun getConfig(): AutoBuyerConfig = config
    
    fun getDailyRemaining(): Int = config.maxDailyAutoBuys - dailyAutoBuys.get()
}

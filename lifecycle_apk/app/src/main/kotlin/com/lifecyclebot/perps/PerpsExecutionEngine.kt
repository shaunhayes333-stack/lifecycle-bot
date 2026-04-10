package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ⚡ PERPS EXECUTION ENGINE - V5.7.0
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * The EXECUTION LAYER for perps trading. Integrates with:
 * - PerpsTraderAI: For signal generation and position management
 * - PerpsMarketScanners: For opportunity detection
 * - PerpsLearningBridge: For cross-layer intelligence
 * - PerpsMarketDataFetcher: For real-time prices
 * 
 * EXECUTION FLOW:
 * ─────────────────────────────────────────────────────────────────────────────
 *   1. Scanners detect opportunities
 *   2. LearningBridge aggregates 26-layer signals
 *   3. TraderAI generates final signal with sizing/leverage
 *   4. ExecutionEngine opens/manages/closes positions
 *   5. Outcomes route back to all layers for learning
 * 
 * SAFETY FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Paper mode by default
 *   • Daily loss limits
 *   • Position size limits
 *   • Automatic stop-loss enforcement
 *   • Liquidation protection
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsExecutionEngine {
    
    private const val TAG = "⚡PerpsExecutor"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val isRunning = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    
    // Execution stats
    private val executionCount = AtomicInteger(0)
    private val successfulExecutions = AtomicInteger(0)
    private val failedExecutions = AtomicInteger(0)
    private val lastExecutionTime = AtomicLong(0)
    
    // Scan interval - V5.7.4: Faster scanning in paper mode for more learning
    private const val SCAN_INTERVAL_MS_LIVE = 30_000L   // 30 seconds in live
    private const val SCAN_INTERVAL_MS_PAPER = 10_000L  // 10 seconds in paper (3x faster!)
    private const val POSITION_CHECK_INTERVAL_MS = 10_000L  // 10 seconds
    
    // Counters for logging
    private val scanCount = AtomicLong(0)
    
    // Job references
    private var scanJob: Job? = null
    private var positionMonitorJob: Job? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Start the perps execution engine
     */
    fun start(context: android.content.Context) {
        if (isRunning.get()) {
            ErrorLogger.warn(TAG, "Already running")
            return
        }
        
        // Initialize all perps components
        PerpsTraderAI.init(context)
        PerpsLearningBridge.init(context)
        
        isRunning.set(true)
        isPaused.set(false)
        
        // Start scan loop
        scanJob = CoroutineScope(Dispatchers.Default).launch {
            runScanLoop()
        }
        
        // Start position monitor loop
        positionMonitorJob = CoroutineScope(Dispatchers.Default).launch {
            runPositionMonitorLoop()
        }
        
        ErrorLogger.info(TAG, "⚡ PerpsExecutionEngine STARTED")
    }
    
    /**
     * Stop the perps execution engine
     */
    fun stop() {
        isRunning.set(false)
        scanJob?.cancel()
        positionMonitorJob?.cancel()
        
        // Save state
        PerpsTraderAI.save(force = true)
        PerpsLearningBridge.save()
        
        ErrorLogger.info(TAG, "⚡ PerpsExecutionEngine STOPPED")
    }
    
    /**
     * Pause/resume execution (keeps monitoring but no new trades)
     */
    fun setPaused(paused: Boolean) {
        isPaused.set(paused)
        ErrorLogger.info(TAG, "⚡ PerpsExecutionEngine ${if (paused) "PAUSED" else "RESUMED"}")
    }
    
    fun isRunning(): Boolean = isRunning.get()
    fun isPaused(): Boolean = isPaused.get()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN LOOP - Detect opportunities
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanLoop() {
        val scanIntervalMs = if (PerpsTraderAI.isPaperMode) SCAN_INTERVAL_MS_PAPER else SCAN_INTERVAL_MS_LIVE
        ErrorLogger.info(TAG, "⚡ PERPS SCAN LOOP STARTED - Running every ${scanIntervalMs/1000}s (paper=${PerpsTraderAI.isPaperMode})")
        
        while (isRunning.get()) {
            try {
                if (!isPaused.get() && PerpsTraderAI.isEnabled()) {
                    val isPaper = PerpsTraderAI.isPaperMode
                    scanCount.incrementAndGet()
                    
                    ErrorLogger.debug(TAG, "⚡ PERPS SCAN #${scanCount.get()} | paper=$isPaper")
                    
                    // Run all scanners
                    val scanResults = PerpsMarketScanners.runAllScanners(isPaper)
                    
                    // V5.7.3: In paper mode, lower threshold to priority >= 3 for learning
                    // In live mode, require priority >= 5 for safety
                    val priorityThreshold = if (isPaper) 3 else 5
                    val highPrioritySignals = scanResults.filter { it.priority >= priorityThreshold && it.signal != null }
                    
                    if (highPrioritySignals.isNotEmpty()) {
                        ErrorLogger.info(TAG, "⚡ PERPS SIGNALS: ${highPrioritySignals.size} signals above threshold (>=$priorityThreshold)")
                    } else {
                        // Log what we got even if no high priority
                        val anySignals = scanResults.filter { it.signal != null }
                        if (anySignals.isNotEmpty()) {
                            val maxPriority = anySignals.maxOfOrNull { it.priority } ?: 0
                            ErrorLogger.debug(TAG, "⚡ PERPS: ${anySignals.size} signals found but max priority=$maxPriority (need >=$priorityThreshold)")
                        }
                    }
                    
                    for (result in highPrioritySignals) {
                        val signal = result.signal ?: continue
                        
                        // Check if we should take this trade
                        if (shouldTakeTrade(signal)) {
                            ErrorLogger.info(TAG, "⚡ PERPS EXECUTING: ${signal.market.symbol} ${signal.direction.symbol} @ ${signal.recommendedLeverage}x")
                            executeSignal(signal, result.scanner, isPaper)
                        }
                    }
                } else {
                    if (isPaused.get()) {
                        ErrorLogger.debug(TAG, "⚡ PERPS SCAN SKIPPED - Engine paused")
                    } else if (!PerpsTraderAI.isEnabled()) {
                        ErrorLogger.debug(TAG, "⚡ PERPS SCAN SKIPPED - PerpsTraderAI disabled")
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Scan loop error: ${e.message}", e)
            }
            
            // V5.7.4: Dynamic interval - faster in paper mode for more learning
            val interval = if (PerpsTraderAI.isPaperMode) SCAN_INTERVAL_MS_PAPER else SCAN_INTERVAL_MS_LIVE
            delay(interval)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MONITOR LOOP - Manage open positions
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runPositionMonitorLoop() {
        while (isRunning.get()) {
            try {
                val positions = PerpsTraderAI.getActivePositions()
                
                for (position in positions) {
                    // Fetch current price
                    val marketData = PerpsMarketDataFetcher.getMarketData(position.market)
                    val currentPrice = marketData.price
                    
                    // V5.7.5: Record price for technical analysis
                    PerpsAdvancedAI.recordPrice(position.market, currentPrice, marketData.volume24h)
                    
                    // Check exit conditions
                    var exitSignal = PerpsTraderAI.checkExit(position.id, currentPrice)
                    
                    // V5.7.5: Check time-based exit if no other exit signal
                    if (exitSignal == PerpsExitSignal.HOLD) {
                        val pnlPct = position.getUnrealizedPnlPct()
                        val timeCheck = PerpsAdvancedAI.checkTimeBasedExit(
                            position.entryTime, 
                            pnlPct, 
                            position.market
                        )
                        if (timeCheck.shouldExit) {
                            ErrorLogger.info(TAG, "⏰ TIME EXIT: ${position.market.symbol} | ${timeCheck.reason} | hold=${timeCheck.holdMinutes}m")
                            exitSignal = PerpsExitSignal.TIMEOUT
                        }
                    }
                    
                    if (exitSignal != PerpsExitSignal.HOLD) {
                        // Execute exit
                        executeExit(position, currentPrice, exitSignal)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Position monitor error: ${e.message}", e)
            }
            
            delay(POSITION_CHECK_INTERVAL_MS)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRADE FILTERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Determine if we should take a trade based on current conditions
     */
    private fun shouldTakeTrade(signal: PerpsSignal): Boolean {
        // Must meet minimum thresholds
        if (!signal.shouldTrade()) {
            return false
        }
        
        // Check if we already have a position in this market
        if (PerpsTraderAI.hasPosition(signal.market)) {
            return false
        }
        
        // V5.7.4: SKIP ALL LIMITS IN PAPER MODE - Let it trade freely for learning!
        val isPaper = PerpsTraderAI.isPaperMode
        
        if (!isPaper) {
            // Check daily limits (LIVE MODE ONLY)
            val dailyTrades = PerpsTraderAI.getDailyTrades()
            if (dailyTrades >= 30) {
                ErrorLogger.debug(TAG, "Daily trade limit reached")
                return false
            }
            
            // Check daily P&L (LIVE MODE ONLY)
            val dailyPnlPct = PerpsTraderAI.getDailyPnlPct()
            if (dailyPnlPct <= -15.0) {
                ErrorLogger.debug(TAG, "Daily loss limit reached")
                return false
            }
        }
        
        // Aggregate layer signals for final confirmation
        try {
            val marketData = kotlinx.coroutines.runBlocking {
                PerpsMarketDataFetcher.getMarketData(signal.market)
            }
            val aggregatedSignal = PerpsLearningBridge.aggregateLayerSignals(signal.market, marketData)
            
            // V5.7.4: Lower consensus requirement in paper mode for more learning
            val minConsensus = if (isPaper) 1 else 3
            if (aggregatedSignal.layerConsensus < minConsensus) {
                ErrorLogger.debug(TAG, "Insufficient layer consensus: ${aggregatedSignal.layerConsensus} (need $minConsensus)")
                return false
            }
            
            // Direction must match (skip in paper mode for more diversity)
            if (!isPaper && aggregatedSignal.direction != signal.direction) {
                ErrorLogger.debug(TAG, "Layer direction mismatch")
                return false
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Layer aggregation failed: ${e.message}")
            // Continue anyway if aggregation fails
        }
        
        return true
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Execute a trading signal (open position)
     */
    private suspend fun executeSignal(
        signal: PerpsSignal,
        scanner: PerpsMarketScanners.ScannerType,
        isPaper: Boolean,
    ) {
        executionCount.incrementAndGet()
        
        try {
            // Get current price
            val marketData = PerpsMarketDataFetcher.getMarketData(signal.market)
            val entryPrice = marketData.price
            
            // Calculate position size
            val balance = PerpsTraderAI.getBalance(isPaper)
            val sizeSol = balance * (signal.recommendedSizePct / 100)
            
            if (sizeSol < 0.05) {
                ErrorLogger.warn(TAG, "Position size too small: $sizeSol SOL")
                failedExecutions.incrementAndGet()
                return
            }
            
            // Open position
            val position = PerpsTraderAI.openPosition(
                market = signal.market,
                direction = signal.direction,
                entryPrice = entryPrice,
                sizeSol = sizeSol,
                leverage = signal.recommendedLeverage,
                signal = signal,
                isPaper = isPaper,
            )
            
            if (position != null) {
                successfulExecutions.incrementAndGet()
                lastExecutionTime.set(System.currentTimeMillis())
                
                ErrorLogger.info(TAG, "⚡ EXECUTED [${scanner.displayName}]: " +
                    "${position.direction.emoji} ${position.market.symbol} | " +
                    "${position.leverage}x | size=${sizeSol.fmt(3)}◎ | " +
                    "entry=\$${entryPrice.fmt(2)}")
            } else {
                failedExecutions.incrementAndGet()
                ErrorLogger.warn(TAG, "Failed to open position")
            }
            
        } catch (e: Exception) {
            failedExecutions.incrementAndGet()
            ErrorLogger.error(TAG, "Execution error: ${e.message}", e)
        }
    }
    
    /**
     * Execute an exit (close position)
     * V5.7.5: Enhanced with pattern learning and time-of-day recording
     */
    private suspend fun executeExit(
        position: PerpsPosition,
        exitPrice: Double,
        exitSignal: PerpsExitSignal,
    ) {
        try {
            val trade = PerpsTraderAI.closePosition(position.id, exitPrice, exitSignal)
            
            if (trade != null) {
                // Route learning to all layers
                val contributingLayers = try {
                    val marketData = PerpsMarketDataFetcher.getMarketData(position.market)
                    val aggregated = PerpsLearningBridge.aggregateLayerSignals(position.market, marketData)
                    aggregated.contributingLayers
                } catch (_: Exception) {
                    emptyList()
                }
                
                PerpsLearningBridge.learnFromPerpsTrade(
                    trade = trade,
                    contributingLayers = contributingLayers,
                    predictedDirection = position.direction,
                )
                
                // V5.7.5: Record pattern for AdvancedAI learning
                try {
                    val technicals = PerpsAdvancedAI.analyzeTechnicals(position.market)
                    val wasWin = trade.pnlPct > 0
                    PerpsAdvancedAI.recordPattern(
                        position.market, 
                        position.direction, 
                        technicals, 
                        wasWin, 
                        trade.pnlPct
                    )
                    
                    // Record time-of-day performance
                    val entryHour = java.util.Calendar.getInstance().apply {
                        timeInMillis = position.entryTime
                    }.get(java.util.Calendar.HOUR_OF_DAY)
                    PerpsAdvancedAI.recordHourlyTrade(entryHour, wasWin, trade.pnlPct)
                } catch (e: Exception) {
                    ErrorLogger.debug(TAG, "AdvancedAI learning error: ${e.message}")
                }
                
                val emoji = when {
                    trade.pnlPct >= 50 -> "🚀"
                    trade.pnlPct >= 20 -> "🎯"
                    trade.pnlPct > 0 -> "✅"
                    trade.pnlPct > -10 -> "😐"
                    else -> "💀"
                }
                
                ErrorLogger.info(TAG, "$emoji EXIT [${exitSignal.displayName}]: " +
                    "${position.market.symbol} | " +
                    "P&L: ${if (trade.pnlPct >= 0) "+" else ""}${trade.pnlPct.fmt(1)}%")
            }
            
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Exit execution error: ${e.message}", e)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MANUAL CONTROLS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Manually open a position (for UI-triggered trades)
     */
    suspend fun manualOpen(
        market: PerpsMarket,
        direction: PerpsDirection,
        leverage: Double,
        sizePct: Double,
    ): PerpsPosition? {
        val isPaper = PerpsTraderAI.isPaperMode
        val marketData = PerpsMarketDataFetcher.getMarketData(market)
        
        // Create a manual signal
        val signal = PerpsSignal(
            market = market,
            direction = direction,
            score = 75,
            confidence = 70,
            recommendedLeverage = leverage,
            recommendedSizePct = sizePct,
            recommendedRiskTier = when {
                leverage >= 10 -> PerpsRiskTier.ASSAULT
                leverage >= 5 -> PerpsRiskTier.TACTICAL
                else -> PerpsRiskTier.SNIPER
            },
            takeProfitPct = 15.0,
            stopLossPct = 8.0,
            reasons = listOf("Manual trade"),
            aiReasoning = "MANUAL: ${direction.symbol} ${market.symbol} @ ${leverage}x",
        )
        
        val balance = PerpsTraderAI.getBalance(isPaper)
        val sizeSol = balance * (sizePct / 100)
        
        return PerpsTraderAI.openPosition(
            market = market,
            direction = direction,
            entryPrice = marketData.price,
            sizeSol = sizeSol,
            leverage = leverage,
            signal = signal,
            isPaper = isPaper,
        )
    }
    
    /**
     * Manually close a position
     */
    suspend fun manualClose(positionId: String): PerpsTrade? {
        val position = PerpsTraderAI.getActivePositions().find { it.id == positionId }
            ?: return null
        
        val marketData = PerpsMarketDataFetcher.getMarketData(position.market)
        return PerpsTraderAI.closePosition(positionId, marketData.price, PerpsExitSignal.AI_EXIT)
    }
    
    /**
     * Close all positions (emergency)
     */
    suspend fun closeAllPositions() {
        val positions = PerpsTraderAI.getActivePositions()
        
        for (position in positions) {
            try {
                val marketData = PerpsMarketDataFetcher.getMarketData(position.market)
                PerpsTraderAI.closePosition(position.id, marketData.price, PerpsExitSignal.AI_EXIT)
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Failed to close ${position.market.symbol}: ${e.message}")
            }
        }
        
        ErrorLogger.warn(TAG, "⚡ ALL POSITIONS CLOSED")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getExecutionCount(): Int = executionCount.get()
    fun getSuccessfulExecutions(): Int = successfulExecutions.get()
    fun getFailedExecutions(): Int = failedExecutions.get()
    fun getLastExecutionTime(): Long = lastExecutionTime.get()
    
    fun getStats(): ExecutionStats {
        return ExecutionStats(
            isRunning = isRunning.get(),
            isPaused = isPaused.get(),
            executionCount = executionCount.get(),
            successfulExecutions = successfulExecutions.get(),
            failedExecutions = failedExecutions.get(),
            lastExecutionTime = lastExecutionTime.get(),
            activePositions = PerpsTraderAI.getPositionCount(),
            scannerSignals = PerpsMarketScanners.getSignalsGenerated(),
            learningEvents = PerpsLearningBridge.getTotalLearningEvents(),
        )
    }
    
    data class ExecutionStats(
        val isRunning: Boolean,
        val isPaused: Boolean,
        val executionCount: Int,
        val successfulExecutions: Int,
        val failedExecutions: Int,
        val lastExecutionTime: Long,
        val activePositions: Int,
        val scannerSignals: Int,
        val learningEvents: Int,
    )
}

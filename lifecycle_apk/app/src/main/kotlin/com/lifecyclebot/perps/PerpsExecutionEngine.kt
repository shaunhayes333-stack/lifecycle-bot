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

    // V5.9.419 — one-shot diagnostic latch for zero-execution dump
    @Volatile private var perpsZeroExecDumped: Boolean = false
    
    // Job references
    private var scanJob: Job? = null
    private var positionMonitorJob: Job? = null
    private var engineScope: CoroutineScope? = null

    // V5.9.454 — retain the app context so the scan loop can read the
    // current lane toggles from ConfigStore without plumbing it through
    // every call site.
    @Volatile private var appContext: android.content.Context? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Start the perps execution engine
     */
    fun start(context: android.content.Context) {
        if (isRunning.get()) {
            // Check if jobs are actually alive — they may have died silently
            val scanAlive     = scanJob?.isActive == true
            val monitorAlive  = positionMonitorJob?.isActive == true
            val scopeAlive    = engineScope?.isActive == true
            if (scanAlive && monitorAlive && scopeAlive) {
                ErrorLogger.warn(TAG, "Already running and loops are alive — no restart needed")
                return
            }
            // Jobs died silently — force cleanup and restart
            ErrorLogger.warn(TAG, "⚠️ isRunning=true but jobs are dead! Force-restarting loops…")
            scanJob?.cancel()
            positionMonitorJob?.cancel()
            engineScope?.cancel()
            engineScope = null
            isRunning.set(false)
        }
        
        // Initialize all perps components
        PerpsTraderAI.init(context)
        PerpsLearningBridge.init(context)
        appContext = context.applicationContext
        
        isRunning.set(true)
        isPaused.set(false)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        engineScope = scope

        // Start scan loop
        scanJob = scope.launch {
            runScanLoop()
        }

        // Start position monitor loop
        positionMonitorJob = scope.launch {
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
        engineScope?.cancel()
        engineScope = null

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

    /**
     * Returns true only if the engine is running AND the scan/monitor coroutine jobs are alive.
     * Use this instead of isRunning() to detect silent loop deaths.
     */
    fun isHealthy(): Boolean {
        if (!isRunning.get()) return false
        val scanAlive    = scanJob?.isActive == true
        val monitorAlive = positionMonitorJob?.isActive == true
        return scanAlive && monitorAlive
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN LOOP - Detect opportunities
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanLoop() {
        val scanIntervalMs = if (PerpsTraderAI.isPaperMode) SCAN_INTERVAL_MS_PAPER else SCAN_INTERVAL_MS_LIVE
        ErrorLogger.info(TAG, "⚡ PERPS SCAN LOOP STARTED - Running every ${scanIntervalMs/1000}s (paper=${PerpsTraderAI.isPaperMode})")
        
        while (isRunning.get()) {
            try {
                // V5.7.5: Always log heartbeat so we know the loop is alive
                val scanNum = scanCount.get()
                if (scanNum % 6 == 0L) {  // Log every minute (6 x 10s)
                    ErrorLogger.info(TAG, "⚡ PERPS HEARTBEAT #$scanNum | running=${isRunning.get()} paused=${isPaused.get()} enabled=${PerpsTraderAI.isEnabled()}")
                }

                // V5.9.419 — DIAGNOSTIC DUMP after 5 min of 0 successful executions.
                // Fires exactly once per engine lifetime so the user can see why
                // Perps is "always 0 trades" even though heartbeat says enabled.
                // Dumps: scanner result counts by priority, last signal scores,
                // size-rejected attempts, current balance + recommended size.
                if (!perpsZeroExecDumped &&
                    scanNum >= 30 &&                        // ≥5 min of 10s scans
                    successfulExecutions.get() == 0 &&
                    isRunning.get() &&
                    !isPaused.get() &&
                    PerpsTraderAI.isEnabled()
                ) {
                    perpsZeroExecDumped = true
                    try {
                        val isPaperDbg = PerpsTraderAI.isPaperMode
                        val balDbg = PerpsTraderAI.getBalance(isPaperDbg)
                        val results = PerpsMarketScanners.runAllScanners(isPaperDbg)
                        val withSig = results.filter { it.signal != null }
                        val priCounts = withSig.groupBy { it.priority }
                            .mapValues { it.value.size }
                            .toSortedMap()
                            .entries.joinToString(", ") { "p${it.key}=${it.value}" }
                        val topSig = withSig.sortedByDescending { it.signal!!.score }
                            .take(5)
                            .joinToString(" · ") {
                                "${it.signal!!.market.symbol} sc=${it.signal!!.score} cf=${it.signal!!.confidence} pr=${it.priority} szPct=${"%.1f".format(it.signal!!.recommendedSizePct)}%"
                            }
                        ErrorLogger.warn(TAG,
                            "⚠️ PERPS_ZERO_EXEC_DUMP after $scanNum scans | bal=${balDbg.fmt(3)}◎ paper=$isPaperDbg | " +
                            "scanners returned ${results.size} results, ${withSig.size} with signals (priCounts=$priCounts) | " +
                            "exec stats: success=${successfulExecutions.get()} fail=${failedExecutions.get()} attempted=${executionCount.get()} | " +
                            "top5 signals: $topSig"
                        )
                    } catch (e: Throwable) {
                        ErrorLogger.warn(TAG, "PERPS_ZERO_EXEC_DUMP failed: ${e.message}")
                    }
                }
                
                if (!isPaused.get() && PerpsTraderAI.isEnabled()) {
                    val isPaper = PerpsTraderAI.isPaperMode
                    scanCount.incrementAndGet()
                    
                    ErrorLogger.info(TAG, "⚡ PERPS SCAN #${scanCount.get()} | paper=$isPaper")
                    
                    // Run all scanners
                    val scanResults = PerpsMarketScanners.runAllScanners(isPaper)

                    // V5.9.454 — LANE-TOGGLE FILTER.
                    // Previously runAllScanners returned signals across the
                    // FULL universe (all cryptos + stocks + commodities +
                    // metals + forex) regardless of the user's lane
                    // toggles in Settings. That caused "all the universe
                    // starts whether it's selected or not". Now we drop
                    // every signal whose asset class the user has
                    // disabled before the priority filter runs.
                    val laneFilteredResults = try {
                        val ctx = appContext
                        if (ctx == null) {
                            scanResults
                        } else {
                            val cfg = com.lifecyclebot.data.ConfigStore.load(ctx)
                            scanResults.filter { r ->
                                val m = r.signal?.market ?: return@filter true
                                when {
                                    m.isStock     -> cfg.stocksEnabled
                                    m.isCommodity -> cfg.commoditiesEnabled
                                    m.isMetal     -> cfg.metalsEnabled
                                    m.isForex     -> cfg.forexEnabled
                                    m.isCrypto    -> cfg.perpsEnabled
                                    else          -> true
                                }
                            }.also { kept ->
                                val dropped = scanResults.size - kept.size
                                if (dropped > 0) {
                                    ErrorLogger.debug(TAG,
                                        "⚡ LANE GATE: dropped $dropped/${scanResults.size} signals for disabled lanes " +
                                        "(perps=${cfg.perpsEnabled} stocks=${cfg.stocksEnabled} " +
                                        "comm=${cfg.commoditiesEnabled} metals=${cfg.metalsEnabled} forex=${cfg.forexEnabled})")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        ErrorLogger.warn(TAG, "Lane filter failed, passing through: ${e.message}")
                        scanResults
                    }

                    // V5.7.3: In paper mode, lower threshold to priority >= 3 for learning
                    // In live mode, require priority >= 5 for safety
                    // V5.9.419 — paper threshold dropped from 1 → 0 so even
                    // priority-0 scanner signals fire (matches free-range mode
                    // doctrine; we already had heaps of paper signals being
                    // silently dropped because the scanner never tagged them
                    // priority>=1).
                    val priorityThreshold = if (isPaper) 0 else 5
                    val highPrioritySignals = laneFilteredResults.filter { it.priority >= priorityThreshold && it.signal != null }
                    
                    if (highPrioritySignals.isNotEmpty()) {
                        ErrorLogger.info(TAG, "⚡ PERPS SIGNALS: ${highPrioritySignals.size} signals above threshold (>=$priorityThreshold)")
                    } else {
                        // Log what we got even if no high priority
                        val anySignals = laneFilteredResults.filter { it.signal != null }
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
            } catch (e: CancellationException) {
                throw e
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
            } catch (e: CancellationException) {
                throw e
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
     * V5.7.5: Simplified for paper mode - minimal filtering for maximum learning
     */
    private fun shouldTakeTrade(signal: PerpsSignal): Boolean {
        // Check if we already have a position in this market
        if (PerpsTraderAI.hasPosition(signal.market)) {
            ErrorLogger.debug(TAG, "⚡ SKIP ${signal.market.symbol}: Already have position")
            return false
        }
        
        val isPaper = PerpsTraderAI.isPaperMode
        
        // V5.7.5: In paper mode, be EXTREMELY aggressive - almost no filtering
        if (isPaper) {
            // Only check very basic signal validity - almost everything passes
            if (signal.confidence < 20 || signal.score < 20) {
                ErrorLogger.debug(TAG, "⚡ SKIP ${signal.market.symbol}: Very low score/conf (${signal.score}/${signal.confidence})")
                return false
            }
            
            // Check max positions (allow up to 30 in paper mode)
            val currentPositions = PerpsTraderAI.getActivePositions().size
            if (currentPositions >= 30) {
                ErrorLogger.debug(TAG, "⚡ SKIP ${signal.market.symbol}: Max positions reached ($currentPositions)")
                return false
            }
            
            ErrorLogger.info(TAG, "⚡ TRADE OK: ${signal.market.symbol} | score=${signal.score} conf=${signal.confidence}")
            return true
        }
        
        // LIVE MODE: Stricter checks
        if (!signal.shouldTrade()) {
            return false
        }
        
        // Check daily limits
        val dailyTrades = PerpsTraderAI.getDailyTrades()
        if (dailyTrades >= 30) {
            ErrorLogger.debug(TAG, "Daily trade limit reached")
            return false
        }
        
        // Check daily P&L
        val dailyPnlPct = PerpsTraderAI.getDailyPnlPct()
        if (dailyPnlPct <= -15.0) {
            ErrorLogger.debug(TAG, "Daily loss limit reached")
            return false
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

            // V5.9.13: Symbolic leverage cap + size bias from SymbolicContext
            val symSizeAdj = try {
                com.lifecyclebot.engine.SymbolicContext.getSizeAdjustment()
            } catch (_: Exception) { 1.0 }
            val symLevCapMult = try {
                val sc = com.lifecyclebot.engine.SymbolicContext
                when (sc.emotionalState) {
                    "PANIC"    -> 0.4   // cut leverage to 40%
                    "FEARFUL"  -> 0.6
                    "EUPHORIC" -> 1.0   // don't increase leverage (risk discipline)
                    "GREEDY"   -> 0.95
                    else       -> if (sc.shouldBeDefensive()) 0.75 else 1.0
                }
            } catch (_: Exception) { 1.0 }

            // Calculate position size (with symbolic bias)
            val balance = PerpsTraderAI.getBalance(isPaper)
            val rawSizeSol = balance * (signal.recommendedSizePct / 100)
            val sizeSol = (rawSizeSol * symSizeAdj).coerceAtLeast(0.0)
            val effectiveLeverage = (signal.recommendedLeverage * symLevCapMult).coerceAtLeast(1.0)

            if (sizeSol < (if (isPaper) 0.01 else 0.05)) {
                ErrorLogger.warn(TAG, "Position size too small: $sizeSol SOL (raw=$rawSizeSol × sym=$symSizeAdj) [floor=${if (isPaper) 0.01 else 0.05}]")
                failedExecutions.incrementAndGet()
                return
            }
            
            // Open position
            val position = PerpsTraderAI.openPosition(
                market = signal.market,
                direction = signal.direction,
                entryPrice = entryPrice,
                sizeSol = sizeSol,
                leverage = effectiveLeverage,
                signal = signal,
                isPaper = isPaper,
            )
            
            if (position != null) {
                successfulExecutions.incrementAndGet()
                lastExecutionTime.set(System.currentTimeMillis())
                
                ErrorLogger.info(TAG, "⚡ EXECUTED [${scanner.displayName}]: " +
                    "${position.direction.emoji} ${position.market.symbol} | " +
                    "${position.leverage}x (sym×${"%.2f".format(symLevCapMult)}) | size=${sizeSol.fmt(3)}◎ (sym×${"%.2f".format(symSizeAdj)}) | " +
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
    
    // V5.7.6: MultiAssetActivity compatibility helpers
    fun getActivePositions(): List<PerpsPosition> = PerpsTraderAI.getActivePositions()
    fun getPaperBalance(): Double = PerpsTraderAI.getBalance(isPaper = true)
    
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

    /**
     * Add to an existing perps position (scale-in).
     * Opens a new position in the same direction if one already exists.
     */
    fun addToPosition(market: PerpsMarket, direction: PerpsDirection, additionalSol: Double): Boolean {
        val existing = PerpsTraderAI.getActivePositions().firstOrNull { it.market == market && it.direction == direction }
        return if (existing != null) {
            // Delegate to PerpsTraderAI to handle the scale-in
            PerpsTraderAI.scaleInPosition(market, direction, additionalSol)
        } else {
            ErrorLogger.warn("PerpsExecutionEngine", "addToPosition: no existing ${market.symbol} ${direction.symbol} position")
            false
        }
    }

}

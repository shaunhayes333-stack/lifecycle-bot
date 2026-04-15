package com.lifecyclebot.perps

import com.lifecyclebot.collective.CollectiveSchema
import com.lifecyclebot.collective.CollectiveLearning
import com.lifecyclebot.collective.MarketsTradeRecord
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.FluidLearningAI
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * V5.7.6: METALS TRADER
 * Dedicated trading engine for precious and industrial metals:
 * - Precious: Gold (XAU), Silver (XAG), Platinum (XPT), Palladium (XPD)
 * - Industrial: Copper, Aluminum, Nickel, Titanium, Zinc, Lead, Tin, Iron, Cobalt, Lithium, Uranium
 * 
 * 24/7 trading via Pyth Oracle price feeds
 */
object MetalsTrader {
    private const val TAG = "MetalsTrader"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val MAX_POSITIONS = 20
    private const val SCAN_INTERVAL_MS = 20_000L  // 20 seconds
    private const val DEFAULT_SIZE_PCT = 5.0  // 5% of balance per trade (matches TokenizedStockTrader)
    private const val TP_PERCENT = 5.0
    private const val SL_PERCENT = 3.0
    private const val SPOT_TRADING_FEE_PERCENT = 0.005     // 0.5% for spot (1x)
    private const val LEVERAGE_TRADING_FEE_PERCENT = 0.01  // 1.0% for leverage (5x)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val spotPositions = ConcurrentHashMap<String, MetalPosition>()      // 1x SPOT
    private val leveragePositions = ConcurrentHashMap<String, MetalPosition>()  // 5x LEVERAGE
    private val isRunning = AtomicBoolean(false)
    private val isEnabled = AtomicBoolean(true)
    private val isPaperMode = AtomicBoolean(true)
    private val scanCount = AtomicInteger(0)
    
    @Volatile private var paperBalance = 50.0  // 50 SOL for metals
    private val totalTrades   = java.util.concurrent.atomic.AtomicInteger(0)
    private val winningTrades = java.util.concurrent.atomic.AtomicInteger(0)
    private val losingTrades  = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var totalPnlSol = 0.0
    @Volatile private var appCtx: android.content.Context? = null
    private val PREFS_NAME = "metals_trader_v1"
    private val KEY_BALANCE = "paper_balance"
    private val KEY_TRADES  = "total_trades"
    private val KEY_WINS    = "winning_trades"
    private val KEY_LOSSES  = "losing_trades"
    private val KEY_PNL     = "total_pnl_sol"
    private var engineJob: Job? = null
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class MetalPosition(
        val id: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val entryPrice: Double,
        var currentPrice: Double,
        val size: Double,
        val leverage: Double,  // 1.0 for SPOT, 5.0 for LEVERAGE
        val takeProfit: Double,
        val stopLoss: Double,
        val openTime: Long = System.currentTimeMillis(),
        var closeTime: Long? = null,
        var closePrice: Double? = null,
        var realizedPnl: Double? = null,
        val reasons: List<String> = emptyList()
    ) {
        fun getPnlPercent(): Double {
            val priceDiff = currentPrice - entryPrice
            val dirMultiplier = if (direction == PerpsDirection.LONG) 1 else -1
            return (priceDiff / entryPrice) * 100.0 * dirMultiplier * leverage
        }
        
        fun getPnlSol(): Double = size * (getPnlPercent() / 100.0)
        
        fun shouldTakeProfit(): Boolean = getPnlPercent() >= TP_PERCENT
        fun shouldStopLoss(): Boolean = getPnlPercent() <= -SL_PERCENT
    }
    
    data class MetalSignal(
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val score: Int,
        val confidence: Int,
        val price: Double,
        val priceChange24h: Double,
        val reasons: List<String>,
        val leverage: Double = 1.0  // V5.7.6: 1.0 = SPOT, 5.0 = LEVERAGE
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ENGINE CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun initialize() {
        ErrorLogger.info(TAG, "🥇 MetalsTrader INITIALIZED")
    }
    
    fun start() {
        if (isRunning.get()) {
            // Detect silent loop death — check if jobs are actually alive
            val engineAlive  = engineJob?.isActive == true
            val monitorAlive = monitorJob?.isActive == true
            if (engineAlive && monitorAlive) {
                ErrorLogger.debug(TAG, "Already running and jobs alive — skip restart")
                return
            }
            // Jobs died silently — force cleanup and restart
            ErrorLogger.warn(TAG, "⚠️ isRunning=true but jobs dead — force-restarting...")
            engineJob?.cancel()
            monitorJob?.cancel()
            isRunning.set(false)
        }
        isRunning.set(true)
        
        engineJob = scope.launch {
            ErrorLogger.info(TAG, "🥇🥇🥇 MetalsTrader ENGINE STARTED 🥇🥇🥇")
            
            // Initial scan
            try {
                runScanCycle()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "Initial scan error: ${e.message}", e)
            }

            // Main loop
            while (isRunning.get()) {
                try {
                    delay(SCAN_INTERVAL_MS)
                    if (isEnabled.get()) {
                        runScanCycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Scan cycle error: ${e.message}", e)
                }
            }
        }

        // Start position monitor
        monitorJob = scope.launch {
            while (isRunning.get()) {
                try {
                    delay(5000)
                    monitorPositions()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Monitor error: ${e.message}", e)
                }
            }
        }
    }

    // V5.9.5: Call this from Activity/Service to provide context for persistence
    @Volatile private var ctxInited = false
    fun initContext(ctx: android.content.Context) {
        appCtx = ctx.applicationContext
        if (!ctxInited) {
            ctxInited = true
            loadState()
            ErrorLogger.info(TAG, "🥇 initContext: state loaded for first time")
        } else {
            ErrorLogger.debug(TAG, "🥇 initContext: already inited — skipping loadState to preserve running state")
        }
    }

    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        monitorJob?.cancel()
        ErrorLogger.info(TAG, "🥇 MetalsTrader STOPPED")
    }

    /** Close all open positions immediately (called on STOP). */
    fun closeAllPositions() {
        val all = spotPositions.values.toList() + leveragePositions.values.toList()
        all.forEach { pos ->
            try { val map = if (pos.leverage == 1.0) spotPositions else leveragePositions; closePosition(pos, map, "STOP — all positions closed") } catch (_: Exception) {}
        }
        ErrorLogger.info(TAG, "🥇 All metals positions closed on STOP (${all.size} positions)")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE - V5.7.6: Now generates BOTH SPOT and LEVERAGE signals
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()

        // V5.7.7: Check if weekend (metals markets closed on weekends) - use NY timezone
        val nyZone = java.util.TimeZone.getTimeZone("America/New_York")
        val cal = java.util.Calendar.getInstance(nyZone)
        val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
        if (dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY) {
            ErrorLogger.info(TAG, "🥇 SCAN #$scanNum SKIPPED - Metals markets CLOSED (Weekend)")
            return
        }

        
        val totalPositions = spotPositions.size + leveragePositions.size
        ErrorLogger.info(TAG, "🥇 ═══════════════════════════════════════════════")
        ErrorLogger.info(TAG, "🥇 METALS SCAN #$scanNum | spot=${spotPositions.size} | lev=${leveragePositions.size} | total=$totalPositions/$MAX_POSITIONS | balance=${"%.2f".format(paperBalance)} SOL")
        
        // Get all metal markets
        val metalMarkets = PerpsMarket.values().filter { it.isMetal }
        ErrorLogger.info(TAG, "🥇 Found ${metalMarkets.size} metals: ${metalMarkets.map { it.symbol }}")
        
        val spotSignals = mutableListOf<MetalSignal>()
        val leverageSignals = mutableListOf<MetalSignal>()
        
        for (market in metalMarkets) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(market)
                if (data.price <= 0) {
                    ErrorLogger.warn(TAG, "🥇 ${market.symbol}: SKIPPED - price=0")
                    continue
                }
                
                // V5.7.6: Use FLUID thresholds from FluidLearningAI
                val spotScoreThresh = FluidLearningAI.getMarketsSpotScoreThreshold()
                val spotConfThresh = FluidLearningAI.getMarketsSpotConfThreshold()
                val levScoreThresh = FluidLearningAI.getMarketsLeverageScoreThreshold()
                
                val signal = analyzeMarket(market, data)
                if (signal != null && signal.score >= spotScoreThresh && signal.confidence >= spotConfThresh) {
                    // SPOT signal if no spot position
                    if (!spotPositions.values.any { it.market == market }) {
                        spotSignals.add(signal.copy(leverage = 1.0))
                    }
                    // LEVERAGE signal - uses fluid threshold
                    if (signal.score >= levScoreThresh && !leveragePositions.values.any { it.market == market }) {
                        leverageSignals.add(signal.copy(leverage = 5.0))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "🥇 ${market.symbol} EXCEPTION: ${e.message}")
            }
        }
        
        // Execute top SPOT signals
        val topSpotSignals = spotSignals.sortedByDescending { it.score }.take(4)
        if (topSpotSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "🥇 TOP SPOT: ${topSpotSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        // Execute top LEVERAGE signals
        val topLeverageSignals = leverageSignals.sortedByDescending { it.score }.take(2)
        if (topLeverageSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "🥇 TOP LEVERAGE: ${topLeverageSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        ErrorLogger.info(TAG, "🥇 ═══════════════════════════════════════════════")
        
        for (signal in topSpotSignals) {
            if (spotPositions.size + leveragePositions.size >= MAX_POSITIONS) break
            executeSignal(signal, spotPositions, "💰 SPOT")
        }
        for (signal in topLeverageSignals) {
            if (spotPositions.size + leveragePositions.size >= MAX_POSITIONS) break
            executeSignal(signal, leveragePositions, "⚡ 5x")
        }
    }
    
    private suspend fun analyzeMarket(market: PerpsMarket, data: PerpsMarketData): MetalSignal? {
        val reasons = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score = 50
        var confidence = 50
        
        val change = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        // 1. Momentum analysis
        when {
            abs(change) > 2.0 -> {
                score += 20
                confidence += 15
                reasons.add("🔥 Strong move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
            abs(change) > 1.0 -> {
                score += 10
                confidence += 10
                reasons.add("📈 Good move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
        }
        layerVotes["Momentum"] = direction
        
        // 2. Metal-specific boosts
        when {
            market.isPreciousMetal -> {
                score += 10
                confidence += 10
                reasons.add("💎 Precious metal - safe haven")
            }
            market.isIndustrialMetal -> {
                score += 5
                reasons.add("🔩 Industrial metal")
            }
        }
        
        // 3. Special metals
        when (market) {
            PerpsMarket.XAU -> {
                score += 5
                reasons.add("🥇 GOLD - King of metals")
            }
            PerpsMarket.XAG -> {
                score += 5
                reasons.add("🥈 SILVER - Industrial + store of value")
            }
            PerpsMarket.LITHIUM -> {
                score += 10
                reasons.add("🔋 LITHIUM - EV battery demand")
            }
            PerpsMarket.URANIUM -> {
                score += 10
                reasons.add("☢️ URANIUM - Nuclear renaissance")
            }
            else -> {}
        }
        
        // 4. Technical analysis via PerpsAdvancedAI (FULL AI INTEGRATION)
        try {
            PerpsAdvancedAI.recordPrice(market, data.price, data.volume24h)
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)
            
            if (technicals.recommendation == direction) {
                score += 10
                confidence += 10
                reasons.add("📊 RSI: ${"%.0f".format(technicals.rsi)}")
                layerVotes["Technical"] = direction
            }
            
            if (technicals.isOversold && direction == PerpsDirection.LONG) {
                score += 15
                reasons.add("📉 Oversold bounce")
            }
            if (technicals.isOverbought && direction == PerpsDirection.SHORT) {
                score += 15
                reasons.add("📈 Overbought short")
            }
        } catch (_: Exception) {}
        
        // 5. Volume analysis
        try {
            val volume = PerpsAdvancedAI.analyzeVolume(market)
            if (volume.isSpike) {
                score += when (volume.spikeStrength) {
                    "EXTREME" -> 20
                    "STRONG" -> 15
                    "MILD" -> 10
                    else -> 0
                }
                reasons.add("📊 Volume ${volume.spikeStrength}")
                layerVotes["Volume"] = direction
            }
        } catch (_: Exception) {}
        
        // 6. Support/Resistance
        try {
            val sr = PerpsAdvancedAI.analyzeSupportResistance(market, data.price)
            if (sr.nearSupport && direction == PerpsDirection.LONG) {
                score += 10
                reasons.add("📍 Near support")
            }
            if (sr.nearResistance && direction == PerpsDirection.SHORT) {
                score += 10
                reasons.add("📍 Near resistance")
            }
        } catch (_: Exception) {}
        
        // 7. Fluid Learning progress
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) {
                confidence += 5
                reasons.add("📚 Learning: ${progress.toInt()}%")
            }
        } catch (_: Exception) {}
        
        // 8. Pattern memory check
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)
            val patternConf = PerpsAdvancedAI.getPatternConfidence(market, direction, technicals)
            if (patternConf > 60) {
                score += 10
                confidence += 10
                reasons.add("🧠 Pattern WR: ${"%.0f".format(patternConf)}%")
            }
        } catch (_: Exception) {}
        
        // Floor for paper mode learning
        if (score < 35) score = 35
        if (confidence < 30) confidence = 30
        reasons.add("📚 ALWAYS_TRADE mode")
        
        return MetalSignal(
            market = market,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            price = data.price,
            priceChange24h = change,
            reasons = reasons
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION - V5.7.6b: Supports SPOT and LEVERAGE + LIVE mode
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeSignal(signal: MetalSignal, positionMap: ConcurrentHashMap<String, MetalPosition>, typeLabel: String) {
        // V5.7.7 FIX: Refresh live wallet balance if uninitialized (0) — prevents all trades being silently blocked
        if (!isPaperMode.get() && liveWalletBalance <= 0.0) {
            try {
                val fresh = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (fresh > 0) updateLiveBalance(fresh)
            } catch (_: Exception) {}
        }
        val balance = getEffectiveBalance()
        if (balance < positionSizeSol) {
            ErrorLogger.warn(TAG, "🥇 Insufficient balance for ${signal.market.symbol}")
            return
        }
        
        // V5.7.6b: Check if LIVE mode - execute on-chain
        if (!isPaperMode.get()) {
            val success = executeLiveTrade(signal, typeLabel)
            if (!success) {
                ErrorLogger.warn(TAG, "🔴 LIVE trade not executed for ${signal.market.symbol}")
                return
            }
        }
        
        val tp = if (signal.direction == PerpsDirection.LONG) {
            signal.price * (1 + TP_PERCENT / 100)
        } else {
            signal.price * (1 - TP_PERCENT / 100)
        }
        
        val sl = if (signal.direction == PerpsDirection.LONG) {
            signal.price * (1 - SL_PERCENT / 100)
        } else {
            signal.price * (1 + SL_PERCENT / 100)
        }
        
        val position = MetalPosition(
            id = "${if (signal.leverage == 1.0) "SPOT" else "LEV"}_${signal.market.symbol}_${System.currentTimeMillis()}",
            market = signal.market,
            direction = signal.direction,
            entryPrice = signal.price,
            currentPrice = signal.price,
            size = positionSizeSol,
            leverage = signal.leverage,
            takeProfit = tp,
            stopLoss = sl,
            reasons = signal.reasons
        )
        
        positionMap[position.id] = position
        
        // Deduct from appropriate balance
        if (isPaperMode.get()) {
            paperBalance -= positionSizeSol
        }
        
        ErrorLogger.info(TAG, "🥇 OPENED: $typeLabel ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | size=${positionSizeSol}◎ | score=${signal.score}")
        
        // V5.7.6b: Record trade start for Markets learning counter
        try {
            FluidLearningAI.recordMarketsTradeStart()
        } catch (_: Exception) {}
    }
    
    /** V5.7.6b: Execute LIVE trade via MarketsLiveExecutor */
    private suspend fun executeLiveTrade(signal: MetalSignal, typeLabel: String): Boolean {
        ErrorLogger.info(TAG, "🔴 LIVE METAL TRADE: ${signal.direction.emoji} ${signal.market.symbol}")
        ErrorLogger.info(TAG, "🔴 Price: \$${signal.price.fmt(2)} | $typeLabel")
        
        val (success, txSignature) = MarketsLiveExecutor.executeLiveTrade(
            market = signal.market,
            direction = signal.direction,
            sizeSol = positionSizeSol,
            leverage = signal.leverage,
            priceUsd = signal.price,
            traderType = "Metals",
        )
        
        // V5.9.2: success is authoritative — txSignature null = bridge trade (no swap needed)
        if (success) {
            ErrorLogger.info(TAG, "🔴 LIVE SUCCESS: ${signal.market.symbol} | tx=${txSignature?.take(16) ?: "bridge"}")
            
            // Update live wallet balance
            try {
                val newBalance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBalance)
            } catch (_: Exception) {}
            
            return true
        }
        return false
    }
    
    private suspend fun monitorPositions() {
        // Monitor SPOT positions
        spotPositions.values.toList().forEach { position ->
            monitorSinglePosition(position, spotPositions)
        }
        // Monitor LEVERAGE positions  
        leveragePositions.values.toList().forEach { position ->
            monitorSinglePosition(position, leveragePositions)
        }
    }
    
    private suspend fun monitorSinglePosition(position: MetalPosition, positionMap: ConcurrentHashMap<String, MetalPosition>) {
        try {
            val data = PerpsMarketDataFetcher.getMarketData(position.market)
            if (data.price > 0) {
                position.currentPrice = data.price
                
                if (position.shouldTakeProfit()) {
                    closePosition(position, positionMap, "TP HIT")
                } else if (position.shouldStopLoss()) {
                    closePosition(position, positionMap, "SL HIT")
                }
            }
        } catch (_: Exception) {}
    }
    
    private fun closePosition(position: MetalPosition, positionMap: ConcurrentHashMap<String, MetalPosition>, reason: String) {
        val grossPnl = position.getPnlSol()
        val feePercent = if (position.leverage == 1.0) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
        val totalFeeSol = position.size * feePercent * 2  // fee on open + close
        val pnl = grossPnl - totalFeeSol
        val pnlPct = position.getPnlPercent() - (totalFeeSol / position.size * 100)
        val isWin = pnl >= 0

        // V5.7.7 FIX: Execute live on-chain close, not just paper bookkeeping
        if (!isPaperMode.get()) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    MarketsLiveExecutor.closeLivePosition(
                        market = position.market,
                        direction = position.direction,
                        sizeSol = position.size,
                        leverage = position.leverage,
                        traderType = "Metals",
                    )
                } catch (e: Exception) {
                    ErrorLogger.warn(TAG, "Live close failed for ${position.market.symbol}: ${e.message}")
                }
            }
            try {
                val newBal = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBal)
            } catch (_: Exception) {}
        } else {
            paperBalance += position.size + pnl
            totalPnlSol  += pnl
            totalTrades.incrementAndGet()
            if (isWin) winningTrades.incrementAndGet() else losingTrades.incrementAndGet()
            saveState()
        }
        positionMap.remove(position.id)

        val typeLabel = if (position.leverage == 1.0) "💰" else "⚡"
        val emoji = if (isWin) "✅" else "❌"
        ErrorLogger.info(TAG, "🥇 CLOSED: $typeLabel $emoji ${position.market.symbol} | PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎ | $reason")
        
        // Record to FluidLearningAI for unified learning
        // V5.7.6b: Use Markets-specific recording to avoid affecting Meme thresholds
        try {
            FluidLearningAI.recordMarketsPaperTrade(isWin)
        } catch (_: Exception) {}
        
        // Record pattern for AI memory
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(position.market)
            PerpsAdvancedAI.recordPattern(
                position.market,
                position.direction,
                technicals,
                isWin,
                pnlPct
            )
        } catch (_: Exception) {}
        
        // Record hourly performance
        try {
            val entryHour = java.util.Calendar.getInstance().apply { 
                timeInMillis = position.openTime 
            }.get(java.util.Calendar.HOUR_OF_DAY)
            PerpsAdvancedAI.recordHourlyTrade(entryHour, isWin, pnlPct)
        } catch (_: Exception) {}
        
        // Notify PerpsLearningBridge
        try {
            PerpsLearningBridge.recordMetalTrade(position.market, position.direction, isWin, pnlPct)
        } catch (_: Exception) {}
        
        // V5.7.6b: Persist trade to Turso
        persistTradeToTurso(position, reason, pnl, pnlPct, isWin)
    }
    
    /**
     * V5.7.6b: Persist trade to Turso for AI learning memory
     */
    private fun persistTradeToTurso(position: MetalPosition, reason: String, pnlSol: Double, pnlPct: Double, isWin: Boolean) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                val instanceId = CollectiveLearning.getInstanceId() ?: ""
                val solPrice = try { PerpsMarketDataFetcher.getSolPrice() } catch (_: Exception) { 150.0 }
                val holdMins = (System.currentTimeMillis() - position.openTime) / 60_000.0
                
                val tradeRecord = MarketsTradeRecord(
                    tradeHash = "METAL_${position.id}_${System.currentTimeMillis()}",
                    instanceId = instanceId,
                    assetClass = "METAL",
                    market = position.market.symbol,
                    direction = position.direction.name,
                    tradeType = if (position.leverage == 1.0) "SPOT" else "LEVERAGE",
                    entryPrice = position.entryPrice,
                    exitPrice = position.currentPrice,
                    sizeSol = position.size,
                    sizeUsd = position.size * solPrice,
                    leverage = position.leverage,
                    pnlSol = pnlSol,
                    pnlUsd = pnlSol * solPrice,
                    pnlPct = pnlPct,
                    openTime = position.openTime,
                    closeTime = System.currentTimeMillis(),
                    closeReason = reason,
                    aiScore = 50,
                    aiConfidence = 50,
                    paperMode = isPaperMode.get(),
                    isWin = isWin,
                    holdMins = holdMins
                )
                
                client.saveMarketsTradeRecord(tradeRecord)
                client.updateMarketsAssetPerformance("METAL", position.market.symbol, position.leverage == 1.0, isWin, pnlPct, holdMins)
                client.updateMarketsDailyStats(instanceId, "METAL", isWin, pnlSol * solPrice)
                ErrorLogger.debug(TAG, "📊 Trade persisted to Turso: ${position.market.symbol}")
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "📊 Turso persist error: ${e.message}")
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getSpotPositions(): List<MetalPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<MetalPosition> = leveragePositions.values.toList()
    fun getAllPositions(): List<MetalPosition> = spotPositions.values.toList() + leveragePositions.values.toList()
    fun getBalance(): Double = if (isPaperMode.get()) com.lifecyclebot.engine.FluidLearning.getSimulatedBalance() else liveWalletBalance
    fun getTotalTrades(): Int = totalTrades.get()
    fun getWinRate(): Double {
        val t = winningTrades.get() + losingTrades.get()
        return if (t > 0) winningTrades.get().toDouble() / t * 100.0 else 0.0
    }

    // V5.9.5: Local persistence — survives app restarts without needing Turso
    private fun prefs() = appCtx?.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)

    fun saveState() {
        val p = prefs() ?: return
        p.edit()
            .putFloat(KEY_BALANCE, paperBalance.toFloat())
            .putInt(KEY_TRADES,    totalTrades.get())
            .putInt(KEY_WINS,      winningTrades.get())
            .putInt(KEY_LOSSES,    losingTrades.get())
            .putFloat(KEY_PNL,     totalPnlSol.toFloat())
            .apply()
    }

    fun loadState() {
        val p = prefs() ?: return
        val savedBal = p.getFloat(KEY_BALANCE, 0f).toDouble()
        if (savedBal > 0.0) paperBalance = savedBal
        totalTrades.set(  p.getInt(KEY_TRADES,  0))
        winningTrades.set(p.getInt(KEY_WINS,    0))
        losingTrades.set( p.getInt(KEY_LOSSES,  0))
        totalPnlSol = p.getFloat(KEY_PNL, 0f).toDouble()
        ErrorLogger.info(TAG, "[MetalsTrader] Loaded: bal=${"%.2f".format(paperBalance)} trades=${totalTrades.get()} wr=${"%.0f".format(getWinRate())}%")
    }
    
    // V5.7.6b: Set balance for paper trading
    fun setBalance(balance: Double) {
        paperBalance = balance
        ErrorLogger.info(TAG, "🥇 MetalsTrader balance set to ${"%.2f".format(balance)} SOL")
    }
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        ErrorLogger.info(TAG, "🥇 Metals Trader enabled: $enabled")
    }
    fun isEnabled(): Boolean = isEnabled.get()

    fun isRunning(): Boolean = isRunning.get()

    /** V5.9.3: Receive paper balance broadcast from BotService */
    fun setPaperBalance(sol: Double) { if (sol > 0.0) paperBalance = sol }

    /** V5.9.3: UI toggle compatibility — Commod/Metals/Forex open both spot+lev automatically */
    fun setPreferLeverage(lev: Boolean) {}
    fun isPreferLeverage(): Boolean = false

    /** Returns true only if running AND engine/monitor coroutines are actually alive. */
    fun isHealthy(): Boolean {
        if (!isRunning.get()) return false
        return (engineJob?.isActive == true) && (monitorJob?.isActive == true)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.6b: LIVE TRADING MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var liveWalletBalance = 0.0
    
    fun isLiveMode(): Boolean = !isPaperMode.get()
    fun isPaperMode(): Boolean = isPaperMode.get()
    
    fun setLiveMode(live: Boolean) {
        isPaperMode.set(!live)
        ErrorLogger.info(TAG, "🥇 MetalsTrader mode: ${if (live) "🔴 LIVE" else "📄 PAPER"}")
        if (live) {
            // Fetch actual wallet balance so live trading doesn't start at 0
            try {
                val balance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: 0.0
                if (balance > 0) updateLiveBalance(balance)
                ErrorLogger.info(TAG, "🥇 Live wallet balance: ${"%.4f".format(liveWalletBalance)} SOL")
            } catch (_: Exception) {}
        }
    }
    
    fun updateLiveBalance(balanceSol: Double) {
        liveWalletBalance = balanceSol
    }
    
    fun getEffectiveBalance(): Double = if (isPaperMode.get()) com.lifecyclebot.engine.FluidLearning.getSimulatedBalance() else liveWalletBalance

    /**
     * Add SOL to an existing open position (scale-in / pyramid).
     * Returns true if a position was found and the add-on was recorded.
     */
    fun addToPosition(market: PerpsMarket, additionalSol: Double): Boolean {
        val allPos = spotPositions.values.toList() + leveragePositions.values.toList()
        val pos = allPos.firstOrNull { p: MetalPosition -> p.market == market } ?: return false
        val currentPrice = try {
            PerpsMarketDataFetcher.getCachedPrice(market)?.price?.takeIf { price -> price > 0 } ?: pos.currentPrice
        } catch (_: Exception) { pos.currentPrice }
        if (currentPrice <= 0) return false

        val totalCost = pos.size + additionalSol
        val blendedEntry = (pos.entryPrice * pos.size + currentPrice * additionalSol) / totalCost

        val updated = pos.copy(
            size = totalCost,
            entryPrice = blendedEntry,
            currentPrice = currentPrice
        )
        if (pos.leverage == 1.0) spotPositions[pos.id] = updated else leveragePositions[pos.id] = updated

        ErrorLogger.info(TAG, "addToPosition ${market.symbol} +$additionalSol SOL | blendedEntry=$blendedEntry")
        return true
    }

}




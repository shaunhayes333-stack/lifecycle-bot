package com.lifecyclebot.perps

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
    private const val POSITION_SIZE_SOL = 4.0
    private const val TP_PERCENT = 5.0
    private const val SL_PERCENT = 3.0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val spotPositions = ConcurrentHashMap<String, MetalPosition>()      // 1x SPOT
    private val leveragePositions = ConcurrentHashMap<String, MetalPosition>()  // 5x LEVERAGE
    private val isRunning = AtomicBoolean(false)
    private val isEnabled = AtomicBoolean(true)
    private val isPaperMode = AtomicBoolean(true)
    private val scanCount = AtomicInteger(0)
    
    private var paperBalance = 50.0  // 50 SOL for metals
    private var engineJob: Job? = null
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
            val direction = if (direction == PerpsDirection.LONG) 1 else -1
            return (priceDiff / entryPrice) * 100.0 * direction * leverage
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
        if (isRunning.get()) return
        isRunning.set(true)
        
        engineJob = scope.launch {
            ErrorLogger.error(TAG, "🥇🥇🥇 MetalsTrader ENGINE STARTED 🥇🥇🥇")
            
            // Initial scan
            try {
                runScanCycle()
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
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Scan cycle error: ${e.message}", e)
                }
            }
        }
        
        // Start position monitor
        scope.launch {
            while (isRunning.get()) {
                delay(5000)
                monitorPositions()
            }
        }
    }
    
    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        ErrorLogger.info(TAG, "🥇 MetalsTrader STOPPED")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE - V5.7.6: Now generates BOTH SPOT and LEVERAGE signals
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()
        
        val totalPositions = spotPositions.size + leveragePositions.size
        ErrorLogger.error(TAG, "🥇 ═══════════════════════════════════════════════")
        ErrorLogger.error(TAG, "🥇 METALS SCAN #$scanNum | spot=${spotPositions.size} | lev=${leveragePositions.size} | total=$totalPositions/$MAX_POSITIONS | balance=${"%.2f".format(paperBalance)} SOL")
        
        // Get all metal markets
        val metalMarkets = PerpsMarket.values().filter { it.isMetal }
        ErrorLogger.error(TAG, "🥇 Found ${metalMarkets.size} metals: ${metalMarkets.map { it.symbol }}")
        
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
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "🥇 ${market.symbol} EXCEPTION: ${e.message}")
            }
        }
        
        // Execute top SPOT signals
        val topSpotSignals = spotSignals.sortedByDescending { it.score }.take(4)
        if (topSpotSignals.isNotEmpty()) {
            ErrorLogger.error(TAG, "🥇 TOP SPOT: ${topSpotSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        // Execute top LEVERAGE signals
        val topLeverageSignals = leverageSignals.sortedByDescending { it.score }.take(2)
        if (topLeverageSignals.isNotEmpty()) {
            ErrorLogger.error(TAG, "🥇 TOP LEVERAGE: ${topLeverageSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        ErrorLogger.error(TAG, "🥇 ═══════════════════════════════════════════════")
        
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
    // EXECUTION - V5.7.6: Supports SPOT and LEVERAGE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeSignal(signal: MetalSignal, positionMap: ConcurrentHashMap<String, MetalPosition>, typeLabel: String) {
        if (paperBalance < POSITION_SIZE_SOL) {
            ErrorLogger.warn(TAG, "🥇 Insufficient balance for ${signal.market.symbol}")
            return
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
            size = POSITION_SIZE_SOL,
            leverage = signal.leverage,
            takeProfit = tp,
            stopLoss = sl,
            reasons = signal.reasons
        )
        
        positionMap[position.id] = position
        paperBalance -= POSITION_SIZE_SOL
        
        ErrorLogger.error(TAG, "🥇 OPENED: $typeLabel ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | size=${POSITION_SIZE_SOL}◎ | score=${signal.score}")
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
        val pnl = position.getPnlSol()
        val pnlPct = position.getPnlPercent()
        val isWin = pnl >= 0
        
        paperBalance += position.size + pnl
        positionMap.remove(position.id)
        
        val typeLabel = if (position.leverage == 1.0) "💰" else "⚡"
        val emoji = if (isWin) "✅" else "❌"
        ErrorLogger.error(TAG, "🥇 CLOSED: $typeLabel $emoji ${position.market.symbol} | PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎ | $reason")
        
        // Record to FluidLearningAI for unified learning
        try {
            FluidLearningAI.recordPaperTrade(isWin)
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
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getSpotPositions(): List<MetalPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<MetalPosition> = leveragePositions.values.toList()
    fun getAllPositions(): List<MetalPosition> = spotPositions.values.toList() + leveragePositions.values.toList()
    fun getBalance(): Double = paperBalance
    
    // V5.7.6b: Set balance for paper trading
    fun setBalance(balance: Double) {
        paperBalance = balance
        ErrorLogger.info(TAG, "🥇 MetalsTrader balance set to ${"%.2f".format(balance)} SOL")
    }
    
    fun isRunning(): Boolean = isRunning.get()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.6b: LIVE TRADING MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var liveWalletBalance = 0.0
    
    fun isLiveMode(): Boolean = !isPaperMode.get()
    fun isPaperMode(): Boolean = isPaperMode.get()
    
    fun setLiveMode(live: Boolean) {
        isPaperMode.set(!live)
        ErrorLogger.info(TAG, "🥇 MetalsTrader mode: ${if (live) "🔴 LIVE" else "📄 PAPER"}")
    }
    
    fun updateLiveBalance(balanceSol: Double) {
        liveWalletBalance = balanceSol
    }
    
    fun getEffectiveBalance(): Double = if (isPaperMode.get()) paperBalance else liveWalletBalance
}

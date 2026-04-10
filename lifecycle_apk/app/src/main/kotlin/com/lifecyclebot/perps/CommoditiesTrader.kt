package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.FluidLearningAI
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * V5.7.6: COMMODITIES TRADER
 * Dedicated trading engine for commodities:
 * - Energy: Oil (Brent, WTI), Natural Gas, Gasoline, Heating Oil
 * - Agricultural: Corn, Wheat, Soybeans, Coffee, Sugar, etc.
 * 
 * 24/7 trading via Pyth Oracle price feeds
 */
object CommoditiesTrader {
    private const val TAG = "CommoditiesTrader"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val MAX_POSITIONS = 20
    private const val SCAN_INTERVAL_MS = 20_000L  // 20 seconds
    private const val POSITION_SIZE_SOL = 3.0
    private const val TP_PERCENT_SPOT = 4.0       // Tighter TP for spot
    private const val SL_PERCENT_SPOT = 3.0       // Tighter SL for spot
    private const val TP_PERCENT_LEVERAGE = 8.0   // Wider for leverage
    private const val SL_PERCENT_LEVERAGE = 5.0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val spotPositions = ConcurrentHashMap<String, CommodityPosition>()      // SPOT (1x)
    private val leveragePositions = ConcurrentHashMap<String, CommodityPosition>()  // LEVERAGE (5x)
    private val isRunning = AtomicBoolean(false)
    private val isEnabled = AtomicBoolean(true)
    private val isPaperMode = AtomicBoolean(true)
    private val scanCount = AtomicInteger(0)
    
    private var paperBalance = 50.0  // 50 SOL for commodities
    private var engineJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class TradeType(val emoji: String, val leverage: Double) {
        SPOT("💰", 1.0),        // No leverage - simple buy/sell
        LEVERAGE("⚡", 5.0)     // 5x leverage
    }
    
    data class CommodityPosition(
        val id: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val tradeType: TradeType,
        val entryPrice: Double,
        var currentPrice: Double,
        val size: Double,
        val takeProfit: Double,
        val stopLoss: Double,
        val openTime: Long = System.currentTimeMillis(),
        var closeTime: Long? = null,
        var closePrice: Double? = null,
        var realizedPnl: Double? = null,
        val reasons: List<String> = emptyList()
    ) {
        val leverage: Double get() = tradeType.leverage
        val isSpot: Boolean get() = tradeType == TradeType.SPOT
        
        fun getPnlPercent(): Double {
            val priceDiff = currentPrice - entryPrice
            val dir = if (direction == PerpsDirection.LONG) 1 else -1
            return (priceDiff / entryPrice) * 100.0 * dir * leverage
        }
        
        fun getPnlSol(): Double = size * (getPnlPercent() / 100.0)
        
        fun shouldTakeProfit(): Boolean {
            val tp = if (isSpot) TP_PERCENT_SPOT else TP_PERCENT_LEVERAGE
            return getPnlPercent() >= tp
        }
        
        fun shouldStopLoss(): Boolean {
            val sl = if (isSpot) SL_PERCENT_SPOT else SL_PERCENT_LEVERAGE
            return getPnlPercent() <= -sl
        }
    }
    
    data class CommoditySignal(
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val tradeType: TradeType,
        val score: Int,
        val confidence: Int,
        val price: Double,
        val priceChange24h: Double,
        val reasons: List<String>
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ENGINE CONTROL
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun initialize() {
        ErrorLogger.info(TAG, "🛢️ CommoditiesTrader INITIALIZED")
    }
    
    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        
        engineJob = scope.launch {
            ErrorLogger.error(TAG, "🛢️🛢️🛢️ CommoditiesTrader ENGINE STARTED 🛢️🛢️🛢️")
            
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
        ErrorLogger.info(TAG, "🛢️ CommoditiesTrader STOPPED")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()
        
        val totalPositions = spotPositions.size + leveragePositions.size
        ErrorLogger.error(TAG, "🛢️ ═══════════════════════════════════════════════")
        ErrorLogger.error(TAG, "🛢️ COMMODITY SCAN #$scanNum | spot=${spotPositions.size} | leverage=${leveragePositions.size} | total=$totalPositions/$MAX_POSITIONS | balance=${"%.2f".format(paperBalance)} SOL")
        
        // Get all commodity markets
        val commodityMarkets = PerpsMarket.values().filter { it.isCommodity }
        ErrorLogger.error(TAG, "🛢️ Found ${commodityMarkets.size} commodities: ${commodityMarkets.map { it.symbol }}")
        
        val spotSignals = mutableListOf<CommoditySignal>()
        val leverageSignals = mutableListOf<CommoditySignal>()
        
        for (market in commodityMarkets) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(market)
                if (data.price <= 0) {
                    ErrorLogger.warn(TAG, "🛢️ ${market.symbol}: SKIPPED - price=0")
                    continue
                }
                
                // V5.7.6: Use FLUID thresholds from FluidLearningAI
                val spotScoreThresh = FluidLearningAI.getMarketsSpotScoreThreshold()
                val spotConfThresh = FluidLearningAI.getMarketsSpotConfThreshold()
                val levScoreThresh = FluidLearningAI.getMarketsLeverageScoreThreshold()
                val levConfThresh = FluidLearningAI.getMarketsLeverageConfThreshold()
                
                // Generate SPOT signal if no spot position
                if (!hasSpotPosition(market)) {
                    val spotSignal = analyzeMarket(market, data, TradeType.SPOT)
                    if (spotSignal != null && spotSignal.score >= spotScoreThresh && spotSignal.confidence >= spotConfThresh) {
                        spotSignals.add(spotSignal)
                    }
                }
                
                // Generate LEVERAGE signal if no leverage position
                if (!hasLeveragePosition(market)) {
                    val leverageSignal = analyzeMarket(market, data, TradeType.LEVERAGE)
                    if (leverageSignal != null && leverageSignal.score >= levScoreThresh && leverageSignal.confidence >= levConfThresh) {
                        leverageSignals.add(leverageSignal)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "🛢️ ${market.symbol} EXCEPTION: ${e.message}")
            }
        }
        
        ErrorLogger.info(TAG, "🛢️ Generated ${spotSignals.size} SPOT signals, ${leverageSignals.size} LEVERAGE signals")
        
        // Execute top SPOT signals (lower risk, more positions)
        val topSpotSignals = spotSignals.sortedByDescending { it.score }.take(4)
        if (topSpotSignals.isNotEmpty()) {
            ErrorLogger.error(TAG, "🛢️ TOP SPOT: ${topSpotSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        // Execute top LEVERAGE signals (higher risk, fewer positions)
        val topLeverageSignals = leverageSignals.sortedByDescending { it.score }.take(2)
        if (topLeverageSignals.isNotEmpty()) {
            ErrorLogger.error(TAG, "🛢️ TOP LEVERAGE: ${topLeverageSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        ErrorLogger.error(TAG, "🛢️ ═══════════════════════════════════════════════")
        
        // Execute signals
        for (signal in topSpotSignals) {
            if (spotPositions.size + leveragePositions.size >= MAX_POSITIONS) break
            executeSignal(signal)
        }
        for (signal in topLeverageSignals) {
            if (spotPositions.size + leveragePositions.size >= MAX_POSITIONS) break
            executeSignal(signal)
        }
    }
    
    private suspend fun analyzeMarket(market: PerpsMarket, data: PerpsMarketData, tradeType: TradeType): CommoditySignal? {
        val reasons = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score = 50
        var confidence = 50
        
        val change = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        // Add trade type indicator
        reasons.add("${tradeType.emoji} ${tradeType.name}")
        
        // 1. Momentum analysis
        when {
            abs(change) > 3.0 -> {
                score += 20
                confidence += 15
                reasons.add("🔥 Strong move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
            abs(change) > 1.5 -> {
                score += 10
                confidence += 10
                reasons.add("📈 Good move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
        }
        layerVotes["Momentum"] = direction
        
        // 2. Sector-specific boosts
        when {
            market.isEnergyCommodity -> {
                score += 5
                reasons.add("⛽ Energy sector")
            }
            market.isAgriCommodity -> {
                score += 5
                reasons.add("🌾 Agricultural")
            }
        }
        
        // 3. Technical analysis via PerpsAdvancedAI (FULL AI INTEGRATION)
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
        
        // 4. Volume analysis
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
        
        // 5. Support/Resistance
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
        
        // 6. Fluid Learning progress
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) {
                confidence += 5
                reasons.add("📚 Learning: ${progress.toInt()}%")
            }
        } catch (_: Exception) {}
        
        // 7. Pattern memory check
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
        
        return CommoditySignal(
            market = market,
            direction = direction,
            tradeType = tradeType,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            price = data.price,
            priceChange24h = change,
            reasons = reasons
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeSignal(signal: CommoditySignal) {
        if (paperBalance < POSITION_SIZE_SOL) {
            ErrorLogger.warn(TAG, "🛢️ Insufficient balance for ${signal.market.symbol}")
            return
        }
        
        val tpPct = if (signal.tradeType == TradeType.SPOT) TP_PERCENT_SPOT else TP_PERCENT_LEVERAGE
        val slPct = if (signal.tradeType == TradeType.SPOT) SL_PERCENT_SPOT else SL_PERCENT_LEVERAGE
        
        val tp = if (signal.direction == PerpsDirection.LONG) {
            signal.price * (1 + tpPct / 100)
        } else {
            signal.price * (1 - tpPct / 100)
        }
        
        val sl = if (signal.direction == PerpsDirection.LONG) {
            signal.price * (1 - slPct / 100)
        } else {
            signal.price * (1 + slPct / 100)
        }
        
        val position = CommodityPosition(
            id = "${signal.tradeType.name}_${signal.market.symbol}_${System.currentTimeMillis()}",
            market = signal.market,
            direction = signal.direction,
            tradeType = signal.tradeType,
            entryPrice = signal.price,
            currentPrice = signal.price,
            size = POSITION_SIZE_SOL,
            takeProfit = tp,
            stopLoss = sl,
            reasons = signal.reasons
        )
        
        // Add to appropriate map
        if (signal.tradeType == TradeType.SPOT) {
            spotPositions[position.id] = position
        } else {
            leveragePositions[position.id] = position
        }
        paperBalance -= POSITION_SIZE_SOL
        
        val leverageStr = if (signal.tradeType == TradeType.SPOT) "1x SPOT" else "${signal.tradeType.leverage.toInt()}x LEV"
        ErrorLogger.error(TAG, "🛢️ OPENED: ${signal.tradeType.emoji} ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | $leverageStr | size=${POSITION_SIZE_SOL}◎ | score=${signal.score}")
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
    
    private suspend fun monitorSinglePosition(position: CommodityPosition, positionMap: ConcurrentHashMap<String, CommodityPosition>) {
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
    
    private fun closePosition(position: CommodityPosition, positionMap: ConcurrentHashMap<String, CommodityPosition>, reason: String) {
        val pnl = position.getPnlSol()
        val pnlPct = position.getPnlPercent()
        val isWin = pnl >= 0
        
        paperBalance += position.size + pnl
        positionMap.remove(position.id)
        
        val emoji = if (isWin) "✅" else "❌"
        val typeEmoji = position.tradeType.emoji
        ErrorLogger.error(TAG, "🛢️ CLOSED: $typeEmoji $emoji ${position.market.symbol} | PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎ (${position.tradeType.name}) | $reason")
        
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
            PerpsLearningBridge.recordCommodityTrade(position.market, position.direction, isWin, pnlPct)
        } catch (_: Exception) {}
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun hasSpotPosition(market: PerpsMarket): Boolean = spotPositions.values.any { it.market == market }
    private fun hasLeveragePosition(market: PerpsMarket): Boolean = leveragePositions.values.any { it.market == market }
    
    fun getSpotPositions(): List<CommodityPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<CommodityPosition> = leveragePositions.values.toList()
    fun getAllPositions(): List<CommodityPosition> = spotPositions.values.toList() + leveragePositions.values.toList()
    fun getBalance(): Double = paperBalance
    
    // V5.7.6b: Set balance for paper trading
    fun setBalance(balance: Double) {
        paperBalance = balance
        ErrorLogger.info(TAG, "🛢️ CommoditiesTrader balance set to ${"%.2f".format(balance)} SOL")
    }
    
    fun isRunning(): Boolean = isRunning.get()
}

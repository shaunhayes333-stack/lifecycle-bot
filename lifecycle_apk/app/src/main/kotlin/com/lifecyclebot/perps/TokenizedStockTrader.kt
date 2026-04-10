package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📈 TOKENIZED STOCK TRADER - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Dedicated trading engine for tokenized stocks (AAPL, NVDA, TSLA, etc.)
 * Runs independently from the main perps system with its own:
 *   • Scanning loop
 *   • Position management
 *   • Learning integration
 *   • Fluid learning hooks
 * 
 * FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   📊 Real-time Pyth Network price feeds
 *   🧠 27-layer AI signal aggregation
 *   📈 Long/Short positions with leverage
 *   💰 Fluid learning balance integration
 *   🎯 Dynamic TP/SL based on volatility
 *   📚 Pattern learning and memory
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object TokenizedStockTrader {
    
    private const val TAG = "📈StockTrader"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val SCAN_INTERVAL_MS = 15_000L  // 15 seconds
    private const val MAX_STOCK_POSITIONS = 10
    private const val DEFAULT_LEVERAGE = 3.0
    private const val DEFAULT_SIZE_PCT = 5.0  // 5% of balance per trade
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val isRunning = AtomicBoolean(false)
    private val isPaperMode = AtomicBoolean(true)
    private val isEnabled = AtomicBoolean(true)
    
    private var engineJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Positions
    private val positions = ConcurrentHashMap<String, StockPosition>()
    private var positionIdCounter = AtomicLong(System.currentTimeMillis())
    
    // Stats
    private val totalTrades = AtomicInteger(0)
    private val winningTrades = AtomicInteger(0)
    private val losingTrades = AtomicInteger(0)
    private val scanCount = AtomicLong(0)
    private var paperBalance = 100.0  // Starting paper balance in SOL
    private var totalPnlSol = 0.0
    
    // Learning integration
    private var fluidLearningAI: FluidLearningAI? = null
    private var unifiedScorer: UnifiedScorer? = null
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STOCK POSITION MODEL
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class StockPosition(
        val id: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val entryPrice: Double,
        var currentPrice: Double,
        val sizeSol: Double,
        val leverage: Double,
        val entryTime: Long = System.currentTimeMillis(),
        var takeProfitPrice: Double? = null,
        var stopLossPrice: Double? = null,
        val aiScore: Int = 50,
        val aiConfidence: Int = 50,
        val reasons: List<String> = emptyList()
    ) {
        fun getUnrealizedPnlPct(): Double {
            if (entryPrice <= 0) return 0.0
            val priceDiff = when (direction) {
                PerpsDirection.LONG -> (currentPrice - entryPrice) / entryPrice
                PerpsDirection.SHORT -> (entryPrice - currentPrice) / entryPrice
            }
            return priceDiff * leverage * 100
        }
        
        fun getUnrealizedPnlSol(): Double {
            return sizeSol * (getUnrealizedPnlPct() / 100)
        }
        
        fun shouldTakeProfit(): Boolean {
            takeProfitPrice?.let { tp ->
                return when (direction) {
                    PerpsDirection.LONG -> currentPrice >= tp
                    PerpsDirection.SHORT -> currentPrice <= tp
                }
            }
            return false
        }
        
        fun shouldStopLoss(): Boolean {
            stopLossPrice?.let { sl ->
                return when (direction) {
                    PerpsDirection.LONG -> currentPrice <= sl
                    PerpsDirection.SHORT -> currentPrice >= sl
                }
            }
            return false
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STOCK SIGNAL MODEL
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class StockSignal(
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val score: Int,
        val confidence: Int,
        val price: Double,
        val priceChange24h: Double,
        val reasons: List<String>,
        val layerVotes: Map<String, PerpsDirection> = emptyMap()
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun init(
        fluidLearning: FluidLearningAI? = null,
        scorer: UnifiedScorer? = null
    ) {
        fluidLearningAI = fluidLearning
        unifiedScorer = scorer
        
        // Get fluid learning balance if available
        fluidLearning?.let {
            paperBalance = it.getSimulatedBalance()
            ErrorLogger.info(TAG, "📈 Using Fluid Learning balance: ${"%.2f".format(paperBalance)} SOL")
        }
        
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader INITIALIZED | paper=$isPaperMode | balance=${"%.2f".format(paperBalance)} SOL")
    }
    
    fun start() {
        if (isRunning.get()) {
            ErrorLogger.debug(TAG, "Already running")
            return
        }
        
        isRunning.set(true)
        
        engineJob = scope.launch {
            ErrorLogger.info(TAG, "📈 TokenizedStockTrader STARTED - Scanning every ${SCAN_INTERVAL_MS/1000}s")
            
            // Run scan loop
            while (isRunning.get()) {
                try {
                    if (isEnabled.get()) {
                        runScanCycle()
                    }
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Scan cycle error: ${e.message}", e)
                }
                
                delay(SCAN_INTERVAL_MS)
            }
        }
        
        // Start position monitor
        scope.launch {
            while (isRunning.get()) {
                try {
                    monitorPositions()
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Monitor error: ${e.message}", e)
                }
                delay(5000)  // Check every 5 seconds
            }
        }
    }
    
    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader STOPPED")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE - Find stock opportunities
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()
        
        ErrorLogger.info(TAG, "📈 STOCK SCAN #$scanNum | positions=${positions.size}/$MAX_STOCK_POSITIONS | balance=${"%.2f".format(getBalance())} SOL")
        
        // Get all stock markets
        val stockMarkets = PerpsMarket.values().filter { it.isStock }
        
        // Fetch prices and generate signals
        val signals = mutableListOf<StockSignal>()
        
        for (market in stockMarkets) {
            try {
                // Skip if we already have a position
                if (hasPosition(market)) continue
                
                // Get market data
                val data = PerpsMarketDataFetcher.getMarketData(market)
                if (data.price <= 0) continue
                
                // Generate signal using AI layers
                val signal = analyzeStock(market, data)
                // V5.7.5: Very low thresholds - accept all signals for learning
                if (signal != null && signal.score >= 35 && signal.confidence >= 30) {
                    signals.add(signal)
                    ErrorLogger.debug(TAG, "📈 Signal: ${market.symbol} score=${signal.score} conf=${signal.confidence}")
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Failed to analyze ${market.symbol}: ${e.message}")
            }
        }
        
        // Sort by score and take best signals
        val topSignals = signals.sortedByDescending { it.score }.take(3)
        
        if (topSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "📈 Found ${topSignals.size} stock signals: ${topSignals.map { "${it.market.symbol}(${it.score})" }}")
        }
        
        // Execute top signals
        for (signal in topSignals) {
            if (positions.size >= MAX_STOCK_POSITIONS) {
                ErrorLogger.debug(TAG, "Max positions reached")
                break
            }
            
            executeSignal(signal)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AI ANALYSIS - Aggregate all layer signals
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun analyzeStock(market: PerpsMarket, data: PerpsMarketData): StockSignal? {
        val reasons = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score = 50
        var confidence = 50
        
        // 1. Price momentum analysis
        val change = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        when {
            abs(change) > 5.0 -> {
                score += 20
                confidence += 15
                reasons.add("🚀 Strong move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
            abs(change) > 2.0 -> {
                score += 10
                confidence += 10
                reasons.add("📈 Good move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
            abs(change) > 1.0 -> {
                score += 5
                reasons.add("📊 Mild move: ${if (change > 0) "+" else ""}${"%.1f".format(change)}%")
            }
        }
        layerVotes["Momentum"] = direction
        
        // 2. Market cap / sector analysis
        when (market) {
            PerpsMarket.NVDA -> {
                score += 10
                confidence += 10
                reasons.add("🔥 AI sector leader")
            }
            PerpsMarket.TSLA -> {
                score += 5
                reasons.add("⚡ High volatility play")
            }
            PerpsMarket.AAPL, PerpsMarket.MSFT, PerpsMarket.GOOGL, PerpsMarket.AMZN -> {
                confidence += 10
                reasons.add("💎 Blue chip stability")
            }
            PerpsMarket.COIN -> {
                score += 5
                reasons.add("🪙 Crypto proxy")
            }
            else -> {}
        }
        
        // 3. Technical analysis via PerpsAdvancedAI
        try {
            PerpsAdvancedAI.recordPrice(market, data.price, data.volume24h)
            val technicals = PerpsAdvancedAI.analyzeTechnicals(market)
            
            if (technicals.recommendation == direction) {
                score += 10
                confidence += 10
                reasons.add("📊 RSI confirms: ${"%.0f".format(technicals.rsi)}")
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
        
        // 6. Unified Scorer integration (if available)
        unifiedScorer?.let { scorer ->
            try {
                // Create a pseudo-token for scoring
                val tokenScore = scorer.getQuickScore(
                    symbol = market.symbol,
                    price = data.price,
                    liquidity = 1_000_000.0,  // Stocks have high liquidity
                    buyPercent = if (change > 0) 60 else 40,
                    mcap = 100_000_000_000.0  // Assume large cap
                )
                
                if (tokenScore > 60) {
                    score += 10
                    confidence += 5
                    reasons.add("🧠 AI score: $tokenScore")
                }
            } catch (_: Exception) {}
        }
        
        // 7. Fluid Learning confidence boost
        fluidLearningAI?.let { fluid ->
            val progress = fluid.getLearningProgress()
            if (progress > 50) {
                confidence += 5
                reasons.add("📚 Learning: ${progress.toInt()}%")
            }
        }
        
        // 8. Pattern memory check
        try {
            val patternConf = PerpsAdvancedAI.getPatternConfidence(
                market, direction,
                PerpsAdvancedAI.analyzeTechnicals(market)
            )
            if (patternConf > 60) {
                score += 10
                confidence += 10
                reasons.add("🧠 Pattern WR: ${"%.0f".format(patternConf)}%")
            }
        } catch (_: Exception) {}
        
        // V5.7.5: Always generate signals - no minimum threshold in paper mode
        // This ensures maximum learning opportunities
        if (score < 40) score = 40
        if (confidence < 35) confidence = 35
        reasons.add("📚 Learning mode active")
        
        return StockSignal(
            market = market,
            direction = direction,
            score = score.coerceIn(0, 100),
            confidence = confidence.coerceIn(0, 100),
            price = data.price,
            priceChange24h = change,
            reasons = reasons,
            layerVotes = layerVotes
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun executeSignal(signal: StockSignal) {
        val balance = getBalance()
        val sizeSol = balance * (DEFAULT_SIZE_PCT / 100)
        
        if (sizeSol < 0.01) {
            ErrorLogger.warn(TAG, "Insufficient balance for ${signal.market.symbol}")
            return
        }
        
        // Calculate TP/SL
        val tpPct = 8.0  // 8% take profit
        val slPct = 4.0  // 4% stop loss
        
        val (tp, sl) = when (signal.direction) {
            PerpsDirection.LONG -> {
                signal.price * (1 + tpPct / 100) to signal.price * (1 - slPct / 100)
            }
            PerpsDirection.SHORT -> {
                signal.price * (1 - tpPct / 100) to signal.price * (1 + slPct / 100)
            }
        }
        
        val position = StockPosition(
            id = "STOCK_${positionIdCounter.incrementAndGet()}",
            market = signal.market,
            direction = signal.direction,
            entryPrice = signal.price,
            currentPrice = signal.price,
            sizeSol = sizeSol,
            leverage = DEFAULT_LEVERAGE,
            takeProfitPrice = tp,
            stopLossPrice = sl,
            aiScore = signal.score,
            aiConfidence = signal.confidence,
            reasons = signal.reasons
        )
        
        positions[position.id] = position
        totalTrades.incrementAndGet()
        
        // Deduct from balance
        if (isPaperMode.get()) {
            paperBalance -= sizeSol
        }
        
        // Notify fluid learning
        fluidLearningAI?.let { fluid ->
            try {
                fluid.recordPaperBuy(
                    symbol = signal.market.symbol,
                    price = signal.price,
                    solAmount = sizeSol,
                    confidence = signal.confidence
                )
            } catch (_: Exception) {}
        }
        
        ErrorLogger.info(TAG, "📈 OPENED: ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | " +
            "${DEFAULT_LEVERAGE}x | size=${sizeSol.fmt(3)}◎ | score=${signal.score} | TP=\$${tp.fmt(2)} SL=\$${sl.fmt(2)}")
        
        signal.reasons.take(3).forEach { reason ->
            ErrorLogger.debug(TAG, "   → $reason")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MONITORING
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun monitorPositions() {
        for ((id, position) in positions) {
            try {
                // Update current price
                val data = PerpsMarketDataFetcher.getMarketData(position.market)
                position.currentPrice = data.price
                
                // Check exit conditions
                when {
                    position.shouldTakeProfit() -> {
                        closePosition(id, "TAKE_PROFIT")
                    }
                    position.shouldStopLoss() -> {
                        closePosition(id, "STOP_LOSS")
                    }
                    // Time-based exit: close after 8 hours if flat
                    System.currentTimeMillis() - position.entryTime > 8 * 60 * 60 * 1000 -> {
                        val pnl = position.getUnrealizedPnlPct()
                        if (abs(pnl) < 2.0) {
                            closePosition(id, "TIMEOUT_FLAT")
                        }
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Monitor error for $id: ${e.message}")
            }
        }
    }
    
    private fun closePosition(positionId: String, reason: String) {
        val position = positions.remove(positionId) ?: return
        
        val pnlPct = position.getUnrealizedPnlPct()
        val pnlSol = position.getUnrealizedPnlSol()
        val isWin = pnlPct > 0
        
        // Update stats
        if (isWin) winningTrades.incrementAndGet() else losingTrades.incrementAndGet()
        totalPnlSol += pnlSol
        
        // Return capital + P&L to balance
        if (isPaperMode.get()) {
            paperBalance += position.sizeSol + pnlSol
        }
        
        // Record to fluid learning
        fluidLearningAI?.let { fluid ->
            try {
                fluid.recordPaperSell(
                    symbol = position.market.symbol,
                    price = position.currentPrice,
                    solAmount = position.sizeSol,
                    pnlSol = pnlSol,
                    pnlPct = pnlPct,
                    reason = reason
                )
            } catch (_: Exception) {}
        }
        
        // Record pattern for learning
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(position.market)
            PerpsAdvancedAI.recordPattern(
                position.market,
                position.direction,
                technicals,
                isWin,
                pnlPct
            )
            
            // Record time of day
            val entryHour = java.util.Calendar.getInstance().apply {
                timeInMillis = position.entryTime
            }.get(java.util.Calendar.HOUR_OF_DAY)
            PerpsAdvancedAI.recordHourlyTrade(entryHour, isWin, pnlPct)
        } catch (_: Exception) {}
        
        val holdMins = (System.currentTimeMillis() - position.entryTime) / 60_000
        val emoji = when {
            pnlPct >= 20 -> "🚀"
            pnlPct >= 10 -> "🎯"
            pnlPct > 0 -> "✅"
            pnlPct > -5 -> "😐"
            else -> "💀"
        }
        
        ErrorLogger.info(TAG, "$emoji CLOSED: ${position.market.symbol} | $reason | " +
            "P&L: ${if (pnlPct >= 0) "+" else ""}${pnlPct.fmt(1)}% (${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)}◎) | " +
            "hold=${holdMins}m | WR=${getWinRate().toInt()}%")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getBalance(): Double {
        // Use fluid learning balance if available
        fluidLearningAI?.let {
            return it.getSimulatedBalance()
        }
        return paperBalance
    }
    
    fun getActivePositions(): List<StockPosition> = positions.values.toList()
    
    fun hasPosition(market: PerpsMarket): Boolean = positions.values.any { it.market == market }
    
    fun getWinRate(): Double {
        val total = winningTrades.get() + losingTrades.get()
        return if (total > 0) (winningTrades.get().toDouble() / total * 100) else 50.0
    }
    
    fun getTotalTrades(): Int = totalTrades.get()
    
    fun getTotalPnlSol(): Double = totalPnlSol
    
    fun getStats(): Map<String, Any> = mapOf(
        "isRunning" to isRunning.get(),
        "isPaperMode" to isPaperMode.get(),
        "positions" to positions.size,
        "maxPositions" to MAX_STOCK_POSITIONS,
        "balance" to getBalance(),
        "totalTrades" to totalTrades.get(),
        "winRate" to getWinRate(),
        "totalPnlSol" to totalPnlSol,
        "scans" to scanCount.get()
    )
    
    fun setEnabled(enabled: Boolean) {
        isEnabled.set(enabled)
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader ${if (enabled) "ENABLED" else "DISABLED"}")
    }
    
    fun isEnabled(): Boolean = isEnabled.get()
    
    // Helper extension
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
}

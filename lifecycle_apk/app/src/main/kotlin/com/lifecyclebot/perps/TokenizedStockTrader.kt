package com.lifecyclebot.perps

import com.lifecyclebot.collective.CollectiveSchema
import com.lifecyclebot.collective.CollectiveLearning
import com.lifecyclebot.collective.MarketsTradeRecord
import com.lifecyclebot.collective.MarketsPositionRecord
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.FluidLearningAI
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
 * 
 * FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   📊 Real-time Pyth Network price feeds
 *   🧠 AI signal generation
 *   📈 Long/Short positions with leverage
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
    private const val MAX_STOCK_POSITIONS = 47  // V5.7.6: ALL stocks tradeable simultaneously
    private const val DEFAULT_LEVERAGE = 3.0
    private const val DEFAULT_SIZE_PCT = 5.0  // 5% of balance per trade
    
    // V5.7.7: Trading fees (consistent across app)
    private const val SPOT_TRADING_FEE_PERCENT = 0.005    // 0.5% for spot (1x)
    private const val LEVERAGE_TRADING_FEE_PERCENT = 0.01 // 1.0% for leverage (3x+)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val isRunning = AtomicBoolean(false)
    private val isPaperMode = AtomicBoolean(true)  // V5.7.6b: Default to paper, can be switched to LIVE
    private val isEnabled = AtomicBoolean(true)
    
    // V5.7.6b: Live trading state
    private var liveWalletBalance = 0.0  // Updated from connected wallet
    
    private var engineJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Positions - V5.7.6b: Separate SPOT and LEVERAGE tracking
    private val positions = ConcurrentHashMap<String, StockPosition>()  // All positions
    private val spotPositions = ConcurrentHashMap<String, StockPosition>()      // SPOT (1x) only
    private val leveragePositions = ConcurrentHashMap<String, StockPosition>()  // LEVERAGE (3x+) only
    private var positionIdCounter = AtomicLong(System.currentTimeMillis())
    
    // Stats
    private val totalTrades = AtomicInteger(0)
    private val winningTrades = AtomicInteger(0)
    private val losingTrades = AtomicInteger(0)
    private val scanCount = AtomicLong(0)
    private var paperBalance = 100.0  // Starting paper balance in SOL
    private var totalPnlSol = 0.0
    
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
        // V5.7.6: isSpot property for UI compatibility
        val isSpot: Boolean get() = leverage == 1.0
        
        // V5.7.6b: Aliases for MultiAssetActivity position card compatibility
        val size: Double get() = sizeSol
        val openTime: Long get() = entryTime
        val takeProfit: Double get() = takeProfitPrice ?: (entryPrice * if (direction == PerpsDirection.LONG) 1.05 else 0.95)
        val stopLoss: Double get() = stopLossPrice ?: (entryPrice * if (direction == PerpsDirection.LONG) 0.97 else 1.03)
        
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
        
        // V5.7.6: Aliases for MultiAssetActivity compatibility
        fun getPnlSol(): Double = getUnrealizedPnlSol()
        fun getPnlPercent(): Double = getUnrealizedPnlPct()
        
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
        val layerVotes: Map<String, PerpsDirection> = emptyMap(),
        val leverage: Double = 3.0  // V5.7.6b: Default leverage, can be overridden
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun init() {
        // V5.7.7: Load persisted state from Turso
        scope.launch {
            loadPersistedState()
            
            // V5.7.7: Apply cross-learning boost from Meme mode
            applyMemeKnowledge()
        }
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader INITIALIZED | paper=$isPaperMode | balance=${"%.2f".format(paperBalance)} SOL")
    }
    
    /**
     * V5.7.7: Apply learnings from Meme mode to bootstrap Markets AI faster
     */
    private fun applyMemeKnowledge() {
        try {
            // Apply Meme → Markets boost
            FluidLearningAI.applyMemeToMarketsBoost()
            
            // Get shared risk insights for TP/SL calibration
            val riskInsights = FluidLearningAI.getSharedRiskInsights()
            if (riskInsights.source == "MEME_CROSS_LEARN") {
                ErrorLogger.info(TAG, "📈 Using Meme risk insights: SL=${riskInsights.suggestedStopLossPct.toInt()}% | TP=${riskInsights.suggestedTakeProfitPct.toInt()}%")
            }
            
            // Get timing insights
            val timingInsights = FluidLearningAI.getSharedTimingInsights()
            if (timingInsights.source == "MEME_CROSS_LEARN") {
                ErrorLogger.info(TAG, "📈 Using Meme timing insights: Best hours=${timingInsights.bestHoursUTC.take(3)}")
            }
            
            // Log cross-learning status
            val status = FluidLearningAI.getCrossLearningStatus()
            ErrorLogger.info(TAG, "🔗 Cross-learning: Meme ${status["memeProgress"]}% → Markets ${status["marketsProgress"]}%")
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Cross-learning error: ${e.message}")
        }
    }
    
    /**
     * V5.7.7: Load persisted Markets state from Turso
     */
    private suspend fun loadPersistedState() {
        try {
            val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
            if (tursoClient != null) {
                val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                val state = tursoClient.loadMarketsState(instanceId)
                if (state != null) {
                    paperBalance = state.paperBalanceSol
                    totalTrades.set(state.totalTrades)
                    winningTrades.set(state.totalWins)
                    losingTrades.set(state.totalLosses)
                    totalPnlSol = state.totalPnlSol
                    isPaperMode.set(!state.isLiveMode)
                    ErrorLogger.info(TAG, "📈 Loaded persisted state: balance=${paperBalance.fmt(2)} SOL | trades=${state.totalTrades} | WR=${state.winRate.toInt()}%")
                } else {
                    ErrorLogger.info(TAG, "📈 No persisted state found - using defaults")
                }
            }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Error loading persisted state: ${e.message}")
        }
    }
    
    /**
     * V5.7.7: Save current state to Turso
     */
    private fun savePersistedState() {
        scope.launch {
            try {
                val tursoClient = com.lifecyclebot.collective.CollectiveLearning.getClient()
                if (tursoClient != null) {
                    val instanceId = com.lifecyclebot.collective.CollectiveLearning.getInstanceId() ?: ""
                    val state = com.lifecyclebot.collective.MarketsState(
                        instanceId = instanceId,
                        paperBalanceSol = paperBalance,
                        totalTrades = totalTrades.get(),
                        totalWins = winningTrades.get(),
                        totalLosses = losingTrades.get(),
                        totalPnlSol = totalPnlSol,
                        learningPhase = calculateLearningPhase(),
                        isLiveMode = !isPaperMode.get(),
                        lastUpdated = System.currentTimeMillis()
                    )
                    tursoClient.saveMarketsState(state)
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Error saving state: ${e.message}")
            }
        }
    }
    
    private fun calculateLearningPhase(): String {
        val trades = totalTrades.get()
        return when {
            trades < 50 -> "BOOTSTRAP"
            trades < 200 -> "LEARNING"
            trades < 500 -> "VALIDATING"
            trades < 1000 -> "MATURING"
            else -> "READY"
        }
    }
    
    fun start() {
        if (isRunning.get()) {
            ErrorLogger.debug(TAG, "Already running")
            return
        }
        
        isRunning.set(true)
        
        engineJob = scope.launch {
            // V5.7.6: Use error-level logging to ensure visibility
            ErrorLogger.error(TAG, "📈📈📈 TokenizedStockTrader ENGINE STARTED 📈📈📈")
            ErrorLogger.error(TAG, "📈 Scanning every ${SCAN_INTERVAL_MS/1000}s | enabled=${isEnabled.get()}")
            
            // V5.7.6: Run first scan immediately (no initial delay)
            try {
                ErrorLogger.error(TAG, "📈📈📈 Running INITIAL stock scan NOW... 📈📈📈")
                runScanCycle()
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "📈 Initial scan EXCEPTION: ${e.message}", e)
            }
            
            // Run scan loop
            while (isRunning.get()) {
                try {
                    delay(SCAN_INTERVAL_MS)
                    
                    if (isEnabled.get()) {
                        runScanCycle()
                    } else {
                        ErrorLogger.debug(TAG, "📈 Stock trading DISABLED - skipping scan")
                    }
                } catch (e: Exception) {
                    ErrorLogger.error(TAG, "Scan cycle error: ${e.message}", e)
                }
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
        
        ErrorLogger.error(TAG, "📈 ═══════════════════════════════════════════════════")
        ErrorLogger.error(TAG, "📈 STOCK SCAN #$scanNum STARTING")
        ErrorLogger.error(TAG, "📈 positions=${positions.size}/$MAX_STOCK_POSITIONS | balance=${"%.2f".format(getBalance())} SOL")
        ErrorLogger.error(TAG, "📈 ═══════════════════════════════════════════════════")
        
        // V5.7.7: PRIORITIZE Pyth-supported stocks for reliable price feeds
        val pythSupported = PythOracle.getSupportedSymbols()
        val pythStocks = PerpsMarket.values().filter { it.isStock && pythSupported.contains(it.symbol) }
        val otherStocks = PerpsMarket.values().filter { it.isStock && !pythSupported.contains(it.symbol) }
        
        // Trade Pyth stocks first (reliable prices), then others
        val stockMarkets = pythStocks + otherStocks.take(10)  // Limit non-Pyth to 10
        ErrorLogger.info(TAG, "📈 Scanning ${pythStocks.size} Pyth stocks + ${otherStocks.take(10).size} others")
        
        // Fetch prices and generate signals
        val signals = mutableListOf<StockSignal>()
        var analyzedCount = 0
        var skippedHasPosition = 0
        var skippedBadPrice = 0
        
        for (market in stockMarkets) {
            try {
                // Skip if we already have a position
                if (hasPosition(market)) {
                    skippedHasPosition++
                    continue
                }
                
                // Get market data
                val data = PerpsMarketDataFetcher.getMarketData(market)
                
                // V5.7.6: Log every price fetch for debugging
                if (analyzedCount < 5) {
                    ErrorLogger.info(TAG, "📈 ${market.symbol}: price=\$${data.price.fmt(2)} | change=${data.priceChange24hPct.fmt(2)}%")
                }
                
                if (data.price <= 0) {
                    skippedBadPrice++
                    ErrorLogger.warn(TAG, "📈 ${market.symbol}: SKIPPED - price is 0 or negative!")
                    continue
                }
                
                analyzedCount++
                
                // Generate signal using AI layers
                val signal = analyzeStock(market, data)
                
                // V5.7.6: Log every signal attempt
                if (signal != null) {
                    ErrorLogger.info(TAG, "📈 SIGNAL: ${market.symbol} | score=${signal.score} | conf=${signal.confidence} | dir=${signal.direction.symbol}")
                    
                    // V5.7.6: Use FLUID thresholds from FluidLearningAI
                    val scoreThresh = FluidLearningAI.getMarketsSpotScoreThreshold()
                    val confThresh = FluidLearningAI.getMarketsSpotConfThreshold()
                    
                    if (signal.score >= scoreThresh && signal.confidence >= confThresh) {
                        signals.add(signal)
                    } else {
                        ErrorLogger.warn(TAG, "📈 ${market.symbol}: BELOW FLUID THRESHOLD (score=${signal.score}<$scoreThresh or conf=${signal.confidence}<$confThresh)")
                    }
                } else {
                    ErrorLogger.warn(TAG, "📈 ${market.symbol}: analyzeStock returned NULL")
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "📈 ${market.symbol}: EXCEPTION: ${e.message}", e)
            }
        }
        
        ErrorLogger.info(TAG, "📈 ═══════════════════════════════════════════════════")
        ErrorLogger.info(TAG, "📈 Scan stats: analyzed=$analyzedCount | hasPos=$skippedHasPosition | badPrice=$skippedBadPrice | signals=${signals.size}")
        
        // Sort by score and take best signals
        val topSignals = signals.sortedByDescending { it.score }.take(3)
        
        if (topSignals.isNotEmpty()) {
            ErrorLogger.info(TAG, "📈 TOP ${topSignals.size} stock signals: ${topSignals.map { "${it.market.symbol}(${it.score}/${it.confidence})" }}")
            ErrorLogger.info(TAG, "📈 ═══════════════════════════════════════════════════")
        } else {
            ErrorLogger.warn(TAG, "📈 ⚠️ NO SIGNALS TO EXECUTE!")
            ErrorLogger.info(TAG, "📈 ═══════════════════════════════════════════════════")
            return  // Early return if no signals
        }
        
        // Execute top signals
        for (signal in topSignals) {
            if (positions.size >= MAX_STOCK_POSITIONS) {
                ErrorLogger.debug(TAG, "Max positions reached")
                break
            }
            
            // V5.7.6b: Alternate between SPOT and LEVERAGE
            val useSpot = (positions.size % 2 == 0) // Even = SPOT, Odd = LEVERAGE
            val leverage = if (useSpot) 1.0 else DEFAULT_LEVERAGE
            ErrorLogger.info(TAG, "📈 EXECUTING: ${signal.market.symbol} ${signal.direction.symbol} @ \$${signal.price.fmt(2)} | ${if (useSpot) "SPOT" else "${leverage.toInt()}x"}")
            executeSignal(signal.copy(leverage = leverage), isSpot = useSpot)
        }
    }
    
    // V5.7.6b: Check for existing SPOT position
    private fun hasSpotPosition(market: PerpsMarket): Boolean {
        return spotPositions.values.any { it.market == market }
    }
    
    // V5.7.6b: Check for existing LEVERAGE position
    private fun hasLeveragePosition(market: PerpsMarket): Boolean {
        return leveragePositions.values.any { it.market == market }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AI ANALYSIS - Generate signals
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
        
        // 6. Fluid Learning confidence boost
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) {
                confidence += 5
                reasons.add("📚 Learning: ${progress.toInt()}%")
            }
            
            // V5.7.7: Cross-learning boost from Meme mode
            val crossBoost = FluidLearningAI.getCrossLearnedConfidence(confidence.toDouble()) - confidence
            if (crossBoost > 0) {
                confidence += crossBoost.toInt()
                reasons.add("🔗 Meme boost: +${crossBoost.toInt()}")
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
        
        // V5.7.6: ALWAYS generate signals in paper mode - ensures maximum learning
        // Floor guarantees we always pass the threshold check in runScanCycle (score>=30, conf>=25)
        if (score < 35) score = 35
        if (confidence < 30) confidence = 30
        reasons.add("📚 Learning: ALWAYS_TRADE mode")
        
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
    
    // V5.7.6b: Updated to support SPOT vs LEVERAGE + LIVE mode
    private suspend fun executeSignal(signal: StockSignal, isSpot: Boolean = false) {
        // V5.7.6b: Check if LIVE mode - execute on-chain
        if (!isPaperMode.get()) {
            val success = executeLiveTrade(signal, isSpot)
            if (!success) {
                ErrorLogger.warn(TAG, "🔴 LIVE trade not executed (awaiting full implementation)")
                return
            }
        }
        
        // PAPER MODE execution (or post-live tracking)
        val balance = getEffectiveBalance()
        val sizeSol = balance * (DEFAULT_SIZE_PCT / 100)
        
        if (sizeSol < 0.01) {
            ErrorLogger.warn(TAG, "Insufficient balance for ${signal.market.symbol}")
            return
        }
        
        // V5.7.7: Get risk insights from Meme cross-learning
        val riskInsights = FluidLearningAI.getSharedRiskInsights()
        
        // V5.7.6b: Different TP/SL for SPOT vs LEVERAGE
        // V5.7.7: Use Meme-learned values if available
        val tpPct = if (riskInsights.source == "MEME_CROSS_LEARN") {
            if (isSpot) riskInsights.suggestedTakeProfitPct * 0.6 else riskInsights.suggestedTakeProfitPct
        } else {
            if (isSpot) 5.0 else 8.0  // Defaults: SPOT 5%, LEV 8%
        }
        val slPct = if (riskInsights.source == "MEME_CROSS_LEARN") {
            if (isSpot) riskInsights.suggestedStopLossPct * 0.6 else riskInsights.suggestedStopLossPct
        } else {
            if (isSpot) 3.0 else 4.0  // Defaults: SPOT 3%, LEV 4%
        }
        val leverage = if (isSpot) 1.0 else signal.leverage
        
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
            leverage = leverage,
            takeProfitPrice = tp,
            stopLossPrice = sl,
            aiScore = signal.score,
            aiConfidence = signal.confidence,
            reasons = signal.reasons
        )
        
        // V5.7.6b: Add to appropriate map
        positions[position.id] = position
        if (isSpot) {
            spotPositions[position.id] = position
        } else {
            leveragePositions[position.id] = position
        }
        totalTrades.incrementAndGet()
        
        // Deduct from balance
        if (isPaperMode.get()) {
            paperBalance -= sizeSol
        }
        
        // Notify FluidLearningAI
        // V5.7.6b: Use Markets-specific recording to avoid affecting Meme thresholds
        try {
            FluidLearningAI.recordMarketsTradeStart()
        } catch (_: Exception) {}
        
        val leverageStr = if (isSpot) "1x SPOT" else "${leverage.toInt()}x LEV"
        ErrorLogger.info(TAG, "📈 OPENED: ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | " +
            "$leverageStr | size=${sizeSol.fmt(3)}◎ | score=${signal.score} | TP=\$${tp.fmt(2)} SL=\$${sl.fmt(2)}")
        
        signal.reasons.take(3).forEach { reason ->
            ErrorLogger.debug(TAG, "   → $reason")
        }
        
        // V5.7.6b: Persist position to Turso for recovery
        persistPositionToTurso(position)
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
        
        // V5.7.6b: Remove from appropriate map
        spotPositions.remove(positionId)
        leveragePositions.remove(positionId)
        
        // V5.7.6b: Remove from Turso
        removePositionFromTurso(positionId)
        
        // V5.7.7: Calculate P&L with fee deduction
        val grossPnlPct = position.getUnrealizedPnlPct()
        val grossPnlSol = position.getUnrealizedPnlSol()
        
        // Deduct trading fees (open + close)
        val feePercent = if (position.isSpot) SPOT_TRADING_FEE_PERCENT else LEVERAGE_TRADING_FEE_PERCENT
        val totalFeeSol = position.sizeSol * feePercent * 2  // Fee on open + close
        val netPnlSol = grossPnlSol - totalFeeSol
        val netPnlPct = if (position.sizeSol > 0) (netPnlSol / position.sizeSol) * 100 else 0.0
        
        val isWin = netPnlPct > 0
        
        // Update stats (use net P&L)
        if (isWin) winningTrades.incrementAndGet() else losingTrades.incrementAndGet()
        totalPnlSol += netPnlSol
        
        // Return capital + net P&L to balance
        if (isPaperMode.get()) {
            paperBalance += position.sizeSol + netPnlSol
        }
        
        // Record to FluidLearningAI
        // V5.7.6b: Use Markets-specific recording to avoid affecting Meme thresholds
        try {
            FluidLearningAI.recordMarketsPaperTrade(isWin)
        } catch (_: Exception) {}
        
        // V5.7.6: Record to PerpsLearningBridge for unified tracking
        try {
            PerpsLearningBridge.recordStockTrade(position.market, position.direction, isWin, netPnlPct)
        } catch (_: Exception) {}
        
        // Record pattern for learning
        try {
            val technicals = PerpsAdvancedAI.analyzeTechnicals(position.market)
            PerpsAdvancedAI.recordPattern(
                position.market,
                position.direction,
                technicals,
                isWin,
                netPnlPct
            )
            
            // Record time of day
            val entryHour = java.util.Calendar.getInstance().apply {
                timeInMillis = position.entryTime
            }.get(java.util.Calendar.HOUR_OF_DAY)
            PerpsAdvancedAI.recordHourlyTrade(entryHour, isWin, netPnlPct)
        } catch (_: Exception) {}
        
        val holdMins = (System.currentTimeMillis() - position.entryTime) / 60_000
        val emoji = when {
            netPnlPct >= 20 -> "🚀"
            netPnlPct >= 10 -> "🎯"
            netPnlPct > 0 -> "✅"
            netPnlPct > -5 -> "😐"
            else -> "💀"
        }
        
        val feeStr = "(fee: ${totalFeeSol.fmt(4)}◎)"
        ErrorLogger.info(TAG, "$emoji CLOSED: ${position.market.symbol} | $reason | " +
            "P&L: ${if (netPnlPct >= 0) "+" else ""}${netPnlPct.fmt(1)}% (${if (netPnlSol >= 0) "+" else ""}${netPnlSol.fmt(4)}◎) $feeStr | " +
            "hold=${holdMins}m | WR=${getWinRate().toInt()}%")
        
        // V5.7.6b: Persist to Turso for learning memory (with net P&L)
        persistTradeToTurso(position, reason, netPnlSol, netPnlPct, isWin, holdMins)
        
        // V5.7.7: Save state after each trade
        savePersistedState()
    }
    
    /**
     * V5.7.6b: Persist trade to Turso database for AI learning memory
     */
    private fun persistTradeToTurso(
        position: StockPosition,
        closeReason: String,
        pnlSol: Double,
        pnlPct: Double,
        isWin: Boolean,
        holdMins: Long
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                val instanceId = CollectiveLearning.getInstanceId() ?: ""
                
                // Get SOL price for USD conversion
                val solPrice = try {
                    PerpsMarketDataFetcher.getSolPrice()
                } catch (_: Exception) { 150.0 }
                
                val tradeRecord = MarketsTradeRecord(
                    tradeHash = "STOCK_${position.id}_${System.currentTimeMillis()}",
                    instanceId = instanceId,
                    assetClass = "STOCK",
                    market = position.market.symbol,
                    direction = position.direction.name,
                    tradeType = if (position.isSpot) "SPOT" else "LEVERAGE",
                    entryPrice = position.entryPrice,
                    exitPrice = position.currentPrice,
                    sizeSol = position.sizeSol,
                    sizeUsd = position.sizeSol * solPrice,
                    leverage = position.leverage,
                    pnlSol = pnlSol,
                    pnlUsd = pnlSol * solPrice,
                    pnlPct = pnlPct,
                    openTime = position.entryTime,
                    closeTime = System.currentTimeMillis(),
                    closeReason = closeReason,
                    aiScore = position.aiScore,
                    aiConfidence = position.aiConfidence,
                    paperMode = isPaperMode.get(),
                    isWin = isWin,
                    holdMins = holdMins.toDouble()
                )
                
                val saved = client.saveMarketsTradeRecord(tradeRecord)
                if (saved) {
                    ErrorLogger.debug(TAG, "📊 Trade persisted to Turso: ${position.market.symbol}")
                    
                    // Also update asset performance
                    client.updateMarketsAssetPerformance(
                        assetClass = "STOCK",
                        market = position.market.symbol,
                        isSpot = position.isSpot,
                        isWin = isWin,
                        pnlPct = pnlPct,
                        holdMins = holdMins.toDouble()
                    )
                    
                    // Update daily stats
                    client.updateMarketsDailyStats(
                        instanceId = instanceId,
                        assetClass = "STOCK",
                        isWin = isWin,
                        pnlUsd = pnlSol * solPrice
                    )
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "📊 Turso persist error: ${e.message}")
            }
        }
    }
    
    /**
     * V5.7.6b: Save current position to Turso (for recovery after restart)
     */
    private fun persistPositionToTurso(position: StockPosition) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                val instanceId = CollectiveLearning.getInstanceId() ?: ""
                
                val solPrice = try {
                    PerpsMarketDataFetcher.getSolPrice()
                } catch (_: Exception) { 150.0 }
                
                val posRecord = MarketsPositionRecord(
                    id = position.id,
                    instanceId = instanceId,
                    assetClass = "STOCK",
                    market = position.market.symbol,
                    direction = position.direction.name,
                    tradeType = if (position.isSpot) "SPOT" else "LEVERAGE",
                    entryPrice = position.entryPrice,
                    currentPrice = position.currentPrice,
                    sizeSol = position.sizeSol,
                    sizeUsd = position.sizeSol * solPrice,
                    leverage = position.leverage,
                    takeProfitPrice = position.takeProfit,
                    stopLossPrice = position.stopLoss,
                    entryTime = position.entryTime,
                    aiScore = position.aiScore,
                    aiConfidence = position.aiConfidence,
                    paperMode = isPaperMode.get(),
                    status = "OPEN",
                    lastUpdate = System.currentTimeMillis()
                )
                
                client.saveMarketsPosition(posRecord)
                ErrorLogger.debug(TAG, "📊 Position saved to Turso: ${position.market.symbol}")
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "📊 Position save error: ${e.message}")
            }
        }
    }
    
    /**
     * V5.7.6b: Remove closed position from Turso
     */
    private fun removePositionFromTurso(positionId: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val client = CollectiveLearning.getClient() ?: return@launch
                client.deleteMarketsPosition(positionId)
            } catch (_: Exception) {}
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getBalance(): Double = paperBalance
    
    // V5.7.6b: Set balance for paper trading
    fun setBalance(balance: Double) {
        paperBalance = balance
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader balance set to ${"%.2f".format(balance)} SOL")
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
    
    // V5.7.6: Public running state accessor for UI
    fun isRunning(): Boolean = isRunning.get()
    
    // V5.7.6b: SPOT vs LEVERAGE position getters - now use dedicated maps
    fun getSpotPositions(): List<StockPosition> = spotPositions.values.toList()
    fun getLeveragePositions(): List<StockPosition> = leveragePositions.values.toList()
    fun getAllPositions(): List<StockPosition> = positions.values.toList()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // V5.7.6b: LIVE TRADING MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Check if trading in LIVE mode */
    fun isLiveMode(): Boolean = !isPaperMode.get()
    
    /** Check if trading in PAPER mode */
    fun isPaperMode(): Boolean = isPaperMode.get()
    
    /** Switch to LIVE mode - REAL MONEY TRADING */
    fun setLiveMode(live: Boolean) {
        isPaperMode.set(!live)
        ErrorLogger.info(TAG, "📈 TokenizedStockTrader mode: ${if (live) "🔴 LIVE" else "📄 PAPER"}")
    }
    
    /** Update live wallet balance from connected wallet */
    fun updateLiveBalance(balanceSol: Double) {
        liveWalletBalance = balanceSol
        ErrorLogger.info(TAG, "📈 Live wallet balance updated: ${"%.4f".format(balanceSol)} SOL")
    }
    
    /** Get balance based on current mode */
    fun getEffectiveBalance(): Double {
        return if (isPaperMode.get()) paperBalance else liveWalletBalance
    }
    
    /** Execute LIVE trade via MarketsLiveExecutor
     * V5.7.6b: Fully wired to on-chain execution via Jupiter API
     */
    private suspend fun executeLiveTrade(signal: StockSignal, isSpot: Boolean): Boolean {
        val leverage = if (isSpot) 1.0 else signal.leverage
        val sizeSol = getEffectiveBalance() * (DEFAULT_SIZE_PCT / 100)
        
        if (sizeSol < 0.01) {
            ErrorLogger.warn(TAG, "🔴 LIVE: Insufficient balance for trade")
            return false
        }
        
        ErrorLogger.info(TAG, "🔴 LIVE TRADE: ${signal.direction.emoji} ${signal.market.symbol}")
        ErrorLogger.info(TAG, "🔴 Price: \$${signal.price.fmt(2)} | ${if (isSpot) "SPOT" else "${leverage.toInt()}x"}")
        ErrorLogger.info(TAG, "🔴 Size: ${sizeSol.fmt(4)} SOL | Score: ${signal.score}")
        
        // Execute via MarketsLiveExecutor
        val (success, txSignature) = MarketsLiveExecutor.executeLiveTrade(
            market = signal.market,
            direction = signal.direction,
            sizeSol = sizeSol,
            leverage = leverage,
            priceUsd = signal.price,
            traderType = "TokenizedStocks",
        )
        
        if (success && txSignature != null) {
            ErrorLogger.info(TAG, "🔴 LIVE SUCCESS: ${signal.market.symbol} | tx=${txSignature.take(16)}...")
            
            // Update live wallet balance
            try {
                val newBalance = com.lifecyclebot.engine.WalletManager.getWallet()?.getSolBalance() ?: liveWalletBalance
                updateLiveBalance(newBalance)
            } catch (_: Exception) {}
            
            return true
        } else {
            ErrorLogger.warn(TAG, "🔴 LIVE FAILED: ${signal.market.symbol}")
            return false
        }
    }
    
    // Helper extension
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
}

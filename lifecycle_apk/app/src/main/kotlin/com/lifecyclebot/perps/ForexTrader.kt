package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scoring.FluidLearningAI
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * V5.7.6: FOREX TRADER
 * Dedicated trading engine for currency pairs:
 * - Majors: EUR/USD, GBP/USD, USD/JPY, AUD/USD, USD/CAD, USD/CHF, NZD/USD
 * - Crosses: EUR/GBP, EUR/JPY, GBP/JPY, AUD/JPY, CAD/JPY, CHF/JPY
 * - Emerging: USD/MXN, USD/BRL, USD/INR, USD/CNY, USD/ZAR, USD/TRY, USD/RUB, USD/SGD, USD/HKD, USD/KRW
 * 
 * 24/5 trading (closed weekends) via Pyth Oracle price feeds
 */
object ForexTrader {
    private const val TAG = "ForexTrader"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val MAX_POSITIONS = 25
    private const val SCAN_INTERVAL_MS = 15_000L  // 15 seconds (forex is fast)
    private const val DEFAULT_LEVERAGE = 10.0    // Higher leverage for forex
    private const val POSITION_SIZE_SOL = 2.0
    private const val TP_PERCENT = 3.0           // Tighter TP for forex
    private const val SL_PERCENT = 2.0           // Tighter SL for forex
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val positions = ConcurrentHashMap<String, ForexPosition>()
    private val isRunning = AtomicBoolean(false)
    private val isEnabled = AtomicBoolean(true)
    private val isPaperMode = AtomicBoolean(true)
    private val scanCount = AtomicInteger(0)
    
    private var paperBalance = 50.0  // 50 SOL for forex
    private var engineJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class ForexPosition(
        val id: String,
        val market: PerpsMarket,
        val direction: PerpsDirection,
        val entryPrice: Double,
        var currentPrice: Double,
        val size: Double,
        val leverage: Double,
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
    
    data class ForexSignal(
        val market: PerpsMarket,
        val direction: PerpsDirection,
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
        ErrorLogger.info(TAG, "💱 ForexTrader INITIALIZED")
    }
    
    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)
        
        engineJob = scope.launch {
            ErrorLogger.error(TAG, "💱💱💱 ForexTrader ENGINE STARTED 💱💱💱")
            
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
                delay(3000)  // Monitor forex more frequently
                monitorPositions()
            }
        }
    }
    
    fun stop() {
        isRunning.set(false)
        engineJob?.cancel()
        ErrorLogger.info(TAG, "💱 ForexTrader STOPPED")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN CYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()
        
        ErrorLogger.error(TAG, "💱 ═══════════════════════════════════════════════")
        ErrorLogger.error(TAG, "💱 FOREX SCAN #$scanNum | positions=${positions.size}/$MAX_POSITIONS | balance=${"%.2f".format(paperBalance)} SOL")
        
        // Get all forex markets
        val forexMarkets = PerpsMarket.values().filter { it.isForex }
        ErrorLogger.error(TAG, "💱 Found ${forexMarkets.size} forex pairs: ${forexMarkets.map { it.symbol }}")
        
        val signals = mutableListOf<ForexSignal>()
        
        for (market in forexMarkets) {
            try {
                if (hasPosition(market)) continue
                
                val data = PerpsMarketDataFetcher.getMarketData(market)
                if (data.price <= 0) {
                    ErrorLogger.warn(TAG, "💱 ${market.symbol}: SKIPPED - price=0")
                    continue
                }
                
                val signal = analyzeMarket(market, data)
                if (signal != null && signal.score >= 30 && signal.confidence >= 25) {
                    signals.add(signal)
                    ErrorLogger.info(TAG, "💱 SIGNAL: ${market.symbol} @ ${data.price} | score=${signal.score} | ${signal.direction.symbol}")
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "💱 ${market.symbol} EXCEPTION: ${e.message}")
            }
        }
        
        // Execute top signals
        val topSignals = signals.sortedByDescending { it.score }.take(4)
        if (topSignals.isNotEmpty()) {
            ErrorLogger.error(TAG, "💱 TOP ${topSignals.size} forex signals: ${topSignals.map { "${it.market.symbol}(${it.score})" }}")
        } else {
            ErrorLogger.error(TAG, "💱 NO SIGNALS - all markets below threshold or no price data")
        }
        ErrorLogger.error(TAG, "💱 ═══════════════════════════════════════════════")
        
        for (signal in topSignals) {
            if (positions.size >= MAX_POSITIONS) break
            executeSignal(signal)
        }
    }
    
    private suspend fun analyzeMarket(market: PerpsMarket, data: PerpsMarketData): ForexSignal? {
        val reasons = mutableListOf<String>()
        val layerVotes = mutableMapOf<String, PerpsDirection>()
        var score = 50
        var confidence = 50
        
        val change = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        // 1. Momentum analysis (forex moves less, so lower thresholds)
        when {
            abs(change) > 1.0 -> {
                score += 25
                confidence += 20
                reasons.add("🔥 Strong forex move: ${if (change > 0) "+" else ""}${"%.2f".format(change)}%")
            }
            abs(change) > 0.5 -> {
                score += 15
                confidence += 10
                reasons.add("📈 Good forex move: ${if (change > 0) "+" else ""}${"%.2f".format(change)}%")
            }
            abs(change) > 0.2 -> {
                score += 5
                reasons.add("📊 Mild forex move: ${if (change > 0) "+" else ""}${"%.2f".format(change)}%")
            }
        }
        layerVotes["Momentum"] = direction
        
        // 2. Major pair boost (more liquid, safer)
        val majorPairs = listOf("EURUSD", "GBPUSD", "USDJPY", "AUDUSD", "USDCAD", "USDCHF", "NZDUSD")
        if (market.symbol in majorPairs) {
            score += 10
            confidence += 10
            reasons.add("💎 Major pair - high liquidity")
        }
        
        // 3. Cross pair analysis
        val crossPairs = listOf("EURGBP", "EURJPY", "GBPJPY", "AUDJPY", "CADJPY", "CHFJPY")
        if (market.symbol in crossPairs) {
            score += 5
            reasons.add("🔀 Cross pair")
        }
        
        // 4. Emerging market pairs (higher volatility, higher risk/reward)
        val emPairs = listOf("USDMXN", "USDBRL", "USDINR", "USDCNY", "USDZAR", "USDTRY", "USDRUB", "USDSGD", "USDHKD", "USDKRW")
        if (market.symbol in emPairs) {
            score += 5
            reasons.add("🌍 EM pair - high volatility")
        }
        
        // 5. Special pair boosts
        when (market.symbol) {
            "EURUSD" -> {
                score += 5
                reasons.add("🇪🇺 EUR/USD - Most traded pair")
            }
            "USDJPY" -> {
                score += 5
                reasons.add("🇯🇵 USD/JPY - Carry trade favorite")
            }
            "GBPJPY" -> {
                score += 5
                reasons.add("🇬🇧 GBP/JPY - Dragon/Widow maker")
            }
        }
        
        // 6. Technical analysis via PerpsAdvancedAI (FULL AI INTEGRATION)
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
        
        // 7. Volume analysis
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
        
        // 8. Support/Resistance
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
        
        // 9. Fluid Learning progress
        try {
            val progress = FluidLearningAI.getLearningProgress()
            if (progress > 50) {
                confidence += 5
                reasons.add("📚 Learning: ${progress.toInt()}%")
            }
        } catch (_: Exception) {}
        
        // 10. Pattern memory check
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
        
        return ForexSignal(
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
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeSignal(signal: ForexSignal) {
        if (paperBalance < POSITION_SIZE_SOL) {
            ErrorLogger.warn(TAG, "💱 Insufficient balance for ${signal.market.symbol}")
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
        
        val position = ForexPosition(
            id = "${signal.market.symbol}_${System.currentTimeMillis()}",
            market = signal.market,
            direction = signal.direction,
            entryPrice = signal.price,
            currentPrice = signal.price,
            size = POSITION_SIZE_SOL,
            leverage = DEFAULT_LEVERAGE,
            takeProfit = tp,
            stopLoss = sl,
            reasons = signal.reasons
        )
        
        positions[position.id] = position
        paperBalance -= POSITION_SIZE_SOL
        
        ErrorLogger.error(TAG, "💱 OPENED: ${signal.direction.emoji} ${signal.market.symbol} @ ${signal.price.fmt(5)} | ${DEFAULT_LEVERAGE.toInt()}x | size=${POSITION_SIZE_SOL}◎ | score=${signal.score}")
    }
    
    private suspend fun monitorPositions() {
        positions.values.toList().forEach { position ->
            try {
                val data = PerpsMarketDataFetcher.getMarketData(position.market)
                if (data.price > 0) {
                    position.currentPrice = data.price
                    
                    if (position.shouldTakeProfit()) {
                        closePosition(position, "TP HIT")
                    } else if (position.shouldStopLoss()) {
                        closePosition(position, "SL HIT")
                    }
                }
            } catch (_: Exception) {}
        }
    }
    
    private fun closePosition(position: ForexPosition, reason: String) {
        val pnl = position.getPnlSol()
        val pnlPct = position.getPnlPercent()
        val isWin = pnl >= 0
        
        paperBalance += position.size + pnl
        positions.remove(position.id)
        
        val emoji = if (isWin) "✅" else "❌"
        ErrorLogger.error(TAG, "💱 CLOSED: $emoji ${position.market.symbol} | PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎ | $reason")
        
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
            PerpsLearningBridge.recordForexTrade(position.market, position.direction, isWin, pnlPct)
        } catch (_: Exception) {}
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun hasPosition(market: PerpsMarket): Boolean = positions.values.any { it.market == market }
    fun getActivePositions(): List<ForexPosition> = positions.values.toList()
    fun getBalance(): Double = paperBalance
    fun isRunning(): Boolean = isRunning.get()
    
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
}

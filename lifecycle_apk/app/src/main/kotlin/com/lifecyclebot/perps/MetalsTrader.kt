package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
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
    private const val DEFAULT_LEVERAGE = 5.0
    private const val POSITION_SIZE_SOL = 4.0
    private const val TP_PERCENT = 5.0
    private const val SL_PERCENT = 3.0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val positions = ConcurrentHashMap<String, MetalPosition>()
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
    
    data class MetalSignal(
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
    // SCAN CYCLE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun runScanCycle() {
        scanCount.incrementAndGet()
        val scanNum = scanCount.get()
        
        ErrorLogger.error(TAG, "🥇 ═══════════════════════════════════════════════")
        ErrorLogger.error(TAG, "🥇 METALS SCAN #$scanNum | positions=${positions.size}/$MAX_POSITIONS | balance=${"%.2f".format(paperBalance)} SOL")
        
        // Get all metal markets
        val metalMarkets = PerpsMarket.values().filter { it.isMetal }
        ErrorLogger.error(TAG, "🥇 Found ${metalMarkets.size} metals: ${metalMarkets.map { it.symbol }}")
        
        val signals = mutableListOf<MetalSignal>()
        
        for (market in metalMarkets) {
            try {
                if (hasPosition(market)) continue
                
                val data = PerpsMarketDataFetcher.getMarketData(market)
                if (data.price <= 0) {
                    ErrorLogger.warn(TAG, "🥇 ${market.symbol}: SKIPPED - price=0")
                    continue
                }
                
                val signal = analyzeMarket(market, data)
                if (signal != null && signal.score >= 30 && signal.confidence >= 25) {
                    signals.add(signal)
                    ErrorLogger.info(TAG, "🥇 SIGNAL: ${market.symbol} @ \$${data.price} | score=${signal.score} | ${signal.direction.symbol}")
                }
            } catch (e: Exception) {
                ErrorLogger.error(TAG, "🥇 ${market.symbol} EXCEPTION: ${e.message}")
            }
        }
        
        // Execute top signals
        val topSignals = signals.sortedByDescending { it.score }.take(3)
        if (topSignals.isNotEmpty()) {
            ErrorLogger.error(TAG, "🥇 TOP ${topSignals.size} metal signals: ${topSignals.map { "${it.market.symbol}(${it.score})" }}")
        } else {
            ErrorLogger.error(TAG, "🥇 NO SIGNALS - all markets below threshold or no price data")
        }
        ErrorLogger.error(TAG, "🥇 ═══════════════════════════════════════════════")
        
        for (signal in topSignals) {
            if (positions.size >= MAX_POSITIONS) break
            executeSignal(signal)
        }
    }
    
    private suspend fun analyzeMarket(market: PerpsMarket, data: PerpsMarketData): MetalSignal? {
        val reasons = mutableListOf<String>()
        var score = 50
        var confidence = 50
        
        val change = data.priceChange24hPct
        val direction = if (change >= 0) PerpsDirection.LONG else PerpsDirection.SHORT
        
        // Momentum analysis
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
        
        // Metal-specific boosts
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
        
        // Special metals
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
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private suspend fun executeSignal(signal: MetalSignal) {
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
        
        ErrorLogger.error(TAG, "🥇 OPENED: ${signal.direction.emoji} ${signal.market.symbol} @ \$${signal.price.fmt(2)} | ${DEFAULT_LEVERAGE.toInt()}x | size=${POSITION_SIZE_SOL}◎ | score=${signal.score}")
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
    
    private fun closePosition(position: MetalPosition, reason: String) {
        val pnl = position.getPnlSol()
        paperBalance += position.size + pnl
        positions.remove(position.id)
        
        val emoji = if (pnl >= 0) "✅" else "❌"
        ErrorLogger.error(TAG, "🥇 CLOSED: $emoji ${position.market.symbol} | PnL: ${if (pnl >= 0) "+" else ""}${"%.4f".format(pnl)}◎ | $reason")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun hasPosition(market: PerpsMarket): Boolean = positions.values.any { it.market == market }
    fun getActivePositions(): List<MetalPosition> = positions.values.toList()
    fun getBalance(): Double = paperBalance
    fun isRunning(): Boolean = isRunning.get()
    
    private fun Double.fmt(decimals: Int): String = "%.${decimals}f".format(this)
}

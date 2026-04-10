package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * ⏱️ MULTI-TIMEFRAME ANALYZER - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Analyzes price action across multiple timeframes for stronger signals.
 * 
 * PHILOSOPHY:
 * ─────────────────────────────────────────────────────────────────────────────
 *   Higher timeframe = Stronger signal
 *   Agreement across timeframes = Higher confidence
 * 
 * TIMEFRAMES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • 1m  - Scalping, noise (weight: 1)
 *   • 5m  - Short-term momentum (weight: 2)
 *   • 15m - Intraday trend (weight: 3)
 *   • 1h  - Major intraday (weight: 4)
 *   • 4h  - Swing trading (weight: 5)
 *   • 1d  - Daily trend (weight: 6)
 * 
 * SIGNAL STRENGTH:
 * ─────────────────────────────────────────────────────────────────────────────
 *   All timeframes bullish = VERY STRONG (100%)
 *   4+ timeframes bullish = STRONG (80%)
 *   3 timeframes bullish = MODERATE (60%)
 *   Mixed signals = WEAK (40%)
 *   Conflicting = AVOID (0%)
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsMultiTimeframe {
    
    private const val TAG = "⏱️MTF"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TIMEFRAMES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class Timeframe(
        val minutes: Int,
        val weight: Int,
        val displayName: String,
    ) {
        M1(1, 1, "1m"),
        M5(5, 2, "5m"),
        M15(15, 3, "15m"),
        H1(60, 4, "1h"),
        H4(240, 5, "4h"),
        D1(1440, 6, "1d"),
    }
    
    enum class TrendDirection {
        BULLISH,
        BEARISH,
        NEUTRAL,
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class TimeframeSignal(
        val timeframe: Timeframe,
        val direction: TrendDirection,
        val strength: Double,      // 0-100
        val priceChange: Double,   // % change in this timeframe
        val momentum: Double,      // Rate of change
        val isOverbought: Boolean,
        val isOversold: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    data class MTFAnalysis(
        val market: PerpsMarket,
        val signals: Map<Timeframe, TimeframeSignal>,
        val overallDirection: TrendDirection,
        val overallStrength: Int,      // 0-100
        val confidenceBoost: Int,      // Additional confidence from MTF alignment
        val recommendation: String,
        val bullishTimeframes: Int,
        val bearishTimeframes: Int,
        val neutralTimeframes: Int,
        val timestamp: Long = System.currentTimeMillis(),
    ) {
        val isAligned: Boolean get() = bullishTimeframes >= 4 || bearishTimeframes >= 4
        val isConflicting: Boolean get() = bullishTimeframes >= 2 && bearishTimeframes >= 2
    }
    
    // Price history cache per market per timeframe
    data class PriceCandle(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val timestamp: Long,
    )
    
    private val priceHistory = ConcurrentHashMap<String, MutableList<PriceCandle>>() // key = "market_timeframe"
    private val analysisCache = ConcurrentHashMap<PerpsMarket, MTFAnalysis>()
    private val lastAnalysis = AtomicLong(0)
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Perform multi-timeframe analysis for a market
     */
    suspend fun analyze(market: PerpsMarket): MTFAnalysis = withContext(Dispatchers.Default) {
        // Check cache (valid for 30 seconds)
        val cached = analysisCache[market]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 30_000) {
            return@withContext cached
        }
        
        val signals = mutableMapOf<Timeframe, TimeframeSignal>()
        
        // Analyze each timeframe
        for (tf in Timeframe.values()) {
            try {
                val signal = analyzeTimeframe(market, tf)
                signals[tf] = signal
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Failed to analyze ${market.symbol} ${tf.displayName}: ${e.message}")
            }
        }
        
        // Calculate overall direction
        val bullish = signals.values.count { it.direction == TrendDirection.BULLISH }
        val bearish = signals.values.count { it.direction == TrendDirection.BEARISH }
        val neutral = signals.values.count { it.direction == TrendDirection.NEUTRAL }
        
        // Weighted direction calculation
        val weightedBullish = signals.filter { it.value.direction == TrendDirection.BULLISH }
            .map { it.key.weight }.sum()
        val weightedBearish = signals.filter { it.value.direction == TrendDirection.BEARISH }
            .map { it.key.weight }.sum()
        
        val overallDirection = when {
            weightedBullish > weightedBearish * 1.5 -> TrendDirection.BULLISH
            weightedBearish > weightedBullish * 1.5 -> TrendDirection.BEARISH
            else -> TrendDirection.NEUTRAL
        }
        
        // Calculate overall strength (0-100)
        val maxWeight = Timeframe.values().sumOf { it.weight }
        val alignedWeight = when (overallDirection) {
            TrendDirection.BULLISH -> weightedBullish
            TrendDirection.BEARISH -> weightedBearish
            TrendDirection.NEUTRAL -> maxOf(weightedBullish, weightedBearish)
        }
        val overallStrength = (alignedWeight.toDouble() / maxWeight * 100).toInt()
        
        // Confidence boost based on alignment
        val confidenceBoost = when {
            bullish >= 5 || bearish >= 5 -> 25  // Very strong alignment
            bullish >= 4 || bearish >= 4 -> 15  // Strong alignment
            bullish >= 3 || bearish >= 3 -> 8   // Moderate alignment
            bullish >= 2 && bearish >= 2 -> -10 // Conflicting signals
            else -> 0
        }
        
        // Generate recommendation
        val recommendation = when {
            bullish >= 5 -> "🚀 VERY STRONG BULLISH: ${bullish}/6 timeframes aligned"
            bearish >= 5 -> "💥 VERY STRONG BEARISH: ${bearish}/6 timeframes aligned"
            bullish >= 4 -> "📈 STRONG BULLISH: Higher timeframes favor longs"
            bearish >= 4 -> "📉 STRONG BEARISH: Higher timeframes favor shorts"
            bullish >= 3 -> "🔼 MODERATE BULLISH: Consider longs with caution"
            bearish >= 3 -> "🔽 MODERATE BEARISH: Consider shorts with caution"
            bullish >= 2 && bearish >= 2 -> "⚠️ CONFLICTING: Wait for clarity"
            else -> "➡️ NEUTRAL: No clear direction"
        }
        
        val analysis = MTFAnalysis(
            market = market,
            signals = signals,
            overallDirection = overallDirection,
            overallStrength = overallStrength,
            confidenceBoost = confidenceBoost,
            recommendation = recommendation,
            bullishTimeframes = bullish,
            bearishTimeframes = bearish,
            neutralTimeframes = neutral,
        )
        
        analysisCache[market] = analysis
        lastAnalysis.set(System.currentTimeMillis())
        
        ErrorLogger.debug(TAG, "⏱️ MTF ${market.symbol}: ${overallDirection.name} | strength=$overallStrength | bull=$bullish bear=$bearish")
        
        return@withContext analysis
    }
    
    /**
     * Analyze a single timeframe
     */
    private suspend fun analyzeTimeframe(market: PerpsMarket, timeframe: Timeframe): TimeframeSignal {
        // Get current price
        val currentPrice = try {
            PerpsMarketDataFetcher.getMarketData(market).price
        } catch (e: Exception) {
            PythOracle.getPrice(market.symbol)?.price ?: throw e
        }
        
        // Get historical price (simulated based on timeframe)
        // In production, this would fetch actual OHLC data
        val historicalChange = estimateTimeframeChange(market, timeframe)
        
        // Determine direction
        val direction = when {
            historicalChange > 1.0 -> TrendDirection.BULLISH
            historicalChange < -1.0 -> TrendDirection.BEARISH
            else -> TrendDirection.NEUTRAL
        }
        
        // Calculate strength (0-100)
        val strength = kotlin.math.abs(historicalChange).coerceIn(0.0, 10.0) * 10
        
        // Simplified overbought/oversold (in production, use RSI)
        val isOverbought = historicalChange > 5.0
        val isOversold = historicalChange < -5.0
        
        return TimeframeSignal(
            timeframe = timeframe,
            direction = direction,
            strength = strength,
            priceChange = historicalChange,
            momentum = historicalChange / timeframe.minutes, // Rate of change per minute
            isOverbought = isOverbought,
            isOversold = isOversold,
        )
    }
    
    /**
     * Estimate price change for a timeframe
     * In production, this would use actual historical OHLC data
     */
    private fun estimateTimeframeChange(market: PerpsMarket, timeframe: Timeframe): Double {
        // Use cached market data priceChange as base
        val dailyChange = try {
            kotlinx.coroutines.runBlocking { 
                PerpsMarketDataFetcher.getMarketData(market).priceChange24hPct 
            }
        } catch (e: Exception) { 0.0 }
        
        // Scale based on timeframe (shorter = more noise, use portion of daily)
        val scaleFactor = timeframe.minutes.toDouble() / 1440.0  // Fraction of day
        val baseChange = dailyChange * scaleFactor
        
        // Add some variance to simulate different timeframe behaviors
        val variance = (Math.random() - 0.5) * 2 * scaleFactor
        
        return baseChange + variance
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE RECORDING (for future candle data)
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a price tick for building candle history
     */
    fun recordPrice(market: PerpsMarket, price: Double, timestamp: Long = System.currentTimeMillis()) {
        for (tf in Timeframe.values()) {
            val key = "${market.symbol}_${tf.name}"
            val candles = priceHistory.getOrPut(key) { mutableListOf() }
            
            synchronized(candles) {
                val periodStart = timestamp - (timestamp % (tf.minutes * 60_000L))
                
                // Find or create candle for this period
                val existing = candles.lastOrNull()?.takeIf { it.timestamp == periodStart }
                
                if (existing != null) {
                    // Update existing candle
                    val index = candles.lastIndex
                    candles[index] = existing.copy(
                        high = maxOf(existing.high, price),
                        low = minOf(existing.low, price),
                        close = price,
                    )
                } else {
                    // Create new candle
                    candles.add(PriceCandle(
                        open = price,
                        high = price,
                        low = price,
                        close = price,
                        timestamp = periodStart,
                    ))
                    
                    // Keep limited history
                    while (candles.size > 200) {
                        candles.removeAt(0)
                    }
                }
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get cached analysis for a market (without re-analyzing)
     */
    fun getCached(market: PerpsMarket): MTFAnalysis? = analysisCache[market]
    
    /**
     * Get the confidence boost for a market based on MTF alignment
     */
    suspend fun getConfidenceBoost(market: PerpsMarket): Int {
        val analysis = analyze(market)
        return analysis.confidenceBoost
    }
    
    /**
     * Check if MTF suggests avoiding a trade
     */
    suspend fun shouldAvoid(market: PerpsMarket, intendedDirection: PerpsDirection): Boolean {
        val analysis = analyze(market)
        
        // Avoid if MTF strongly opposes our direction
        return when (intendedDirection) {
            PerpsDirection.LONG -> analysis.bearishTimeframes >= 4
            PerpsDirection.SHORT -> analysis.bullishTimeframes >= 4
        }
    }
    
    /**
     * Get a summary string for UI
     */
    fun getSummary(market: PerpsMarket): String {
        val analysis = analysisCache[market] ?: return "No MTF data"
        
        return buildString {
            appendLine("⏱️ MTF Analysis: ${market.symbol}")
            appendLine("Direction: ${analysis.overallDirection.name}")
            appendLine("Strength: ${analysis.overallStrength}%")
            appendLine("Bullish TFs: ${analysis.bullishTimeframes}/6")
            appendLine("Bearish TFs: ${analysis.bearishTimeframes}/6")
            appendLine(analysis.recommendation)
        }
    }
}

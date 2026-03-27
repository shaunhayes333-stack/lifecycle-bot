package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * SmartChartScanner — Multi-timeframe chart pattern detection.
 * 
 * Analyzes price action across multiple timeframes to detect:
 * - Candlestick patterns (Hammer, Doji, Engulfing, etc.)
 * - Chart patterns (Double Bottom, Breakout, etc.)
 * - Volume analysis
 * - Holder dynamics
 * 
 * DEFENSIVE DESIGN:
 * - Static object, no initialization
 * - All methods wrapped in try/catch
 * - Thread-safe data structures
 * - Feeds insights to SuperBrainEnhancements
 */
object SmartChartScanner {
    
    private const val TAG = "SmartChart"
    
    // ═══════════════════════════════════════════════════════════════════
    // TIMEFRAMES
    // ═══════════════════════════════════════════════════════════════════
    
    enum class Timeframe(val label: String, val minutes: Int) {
        M1("1m", 1),
        M5("5m", 5),
        M15("15m", 15),
        H1("1h", 60),
        H4("4h", 240),
        D1("1D", 1440),
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PATTERN TYPES
    // ═══════════════════════════════════════════════════════════════════
    
    enum class CandlePattern(val emoji: String, val bullish: Boolean) {
        HAMMER("🔨", true),
        INVERTED_HAMMER("🔨", true),
        BULLISH_ENGULFING("🌊", true),
        MORNING_STAR("⭐", true),
        PIERCING_LINE("📈", true),
        DOJI("✝️", false),  // Neutral
        HANGING_MAN("🪢", false),
        SHOOTING_STAR("💫", false),
        BEARISH_ENGULFING("🌊", false),
        EVENING_STAR("🌙", false),
        DARK_CLOUD("☁️", false),
    }
    
    enum class ChartPattern(val emoji: String, val bullish: Boolean) {
        DOUBLE_BOTTOM("W", true),
        INVERSE_HEAD_SHOULDERS("👤", true),
        BULLISH_FLAG("🏁", true),
        CUP_HANDLE("☕", true),
        ASCENDING_TRIANGLE("△", true),
        BREAKOUT("🚀", true),
        DOUBLE_TOP("M", false),
        HEAD_SHOULDERS("👤", false),
        BEARISH_FLAG("🚩", false),
        DESCENDING_TRIANGLE("▽", false),
        BREAKDOWN("📉", false),
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SCAN RESULTS
    // ═══════════════════════════════════════════════════════════════════
    
    data class ScanResult(
        val mint: String,
        val symbol: String,
        val timeframe: Timeframe,
        val candlePatterns: List<CandlePattern>,
        val chartPatterns: List<ChartPattern>,
        val volumeSignal: VolumeSignal,
        val holderSignal: HolderSignal,
        val overallBias: String,  // "BULLISH", "BEARISH", "NEUTRAL"
        val confidence: Double,   // 0-100
        val timestampMs: Long,
    )
    
    enum class VolumeSignal {
        SURGE,        // Volume spike (>2x average)
        INCREASING,   // Above average
        NORMAL,       // Around average
        DECREASING,   // Below average
        DRY,          // Very low volume
    }
    
    enum class HolderSignal {
        ACCUMULATION,  // Holders increasing
        DISTRIBUTION,  // Holders decreasing
        STABLE,        // No significant change
        UNKNOWN,
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PRICE DATA CACHE
    // ═══════════════════════════════════════════════════════════════════
    
    data class PriceCandle(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double,
        val timestampMs: Long,
    ) {
        val body: Double get() = abs(close - open)
        val upperWick: Double get() = high - maxOf(open, close)
        val lowerWick: Double get() = minOf(open, close) - low
        val range: Double get() = high - low
        val isBullish: Boolean get() = close > open
        val isBearish: Boolean get() = close < open
        val isDoji: Boolean get() = body < range * 0.1
    }
    
    private val priceCache = ConcurrentHashMap<String, MutableList<PriceCandle>>()
    private const val MAX_CANDLES = 200
    
    // ═══════════════════════════════════════════════════════════════════
    // MAIN SCAN FUNCTION
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Scan a token across all timeframes.
     * 
     * @param ts TokenState with price history
     * @return List of ScanResults for each timeframe
     */
    fun scan(ts: TokenState): List<ScanResult> {
        return try {
            val results = mutableListOf<ScanResult>()
            
            // Build candles from token history
            val candles = buildCandles(ts)
            if (candles.size < 5) {
                return emptyList()  // Not enough data
            }
            
            // Cache for later analysis
            priceCache[ts.mint] = candles.toMutableList()
            
            // Analyze each timeframe we have data for
            Timeframe.values().forEach { tf ->
                val tfCandles = aggregateToTimeframe(candles, tf)
                if (tfCandles.size >= 3) {
                    val result = analyzeTimeframe(ts.mint, ts.symbol, tf, tfCandles)
                    results.add(result)
                    
                    // Record insights to SuperBrain
                    recordInsights(result)
                }
            }
            
            results
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "scan error: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Quick scan for a specific timeframe.
     */
    fun quickScan(ts: TokenState, timeframe: Timeframe = Timeframe.M5): ScanResult? {
        return try {
            val candles = buildCandles(ts)
            if (candles.size < 5) return null
            
            val tfCandles = aggregateToTimeframe(candles, timeframe)
            if (tfCandles.size < 3) return null
            
            analyzeTimeframe(ts.mint, ts.symbol, timeframe, tfCandles)
        } catch (e: Exception) {
            null
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // CANDLE BUILDING
    // ═══════════════════════════════════════════════════════════════════
    
    private fun buildCandles(ts: TokenState): List<PriceCandle> {
        return try {
            ts.history.mapNotNull { candle ->
                try {
                    // Use actual OHLC if available, otherwise derive from priceUsd
                    val open = if (candle.openUsd > 0) candle.openUsd else candle.priceUsd
                    val high = if (candle.highUsd > 0) candle.highUsd else candle.priceUsd * 1.005
                    val low = if (candle.lowUsd > 0) candle.lowUsd else candle.priceUsd * 0.995
                    val close = candle.priceUsd
                    
                    PriceCandle(
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        volume = candle.vol,
                        timestampMs = candle.ts,
                    )
                } catch (e: Exception) {
                    null
                }
            }.takeLast(MAX_CANDLES)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private fun aggregateToTimeframe(candles: List<PriceCandle>, tf: Timeframe): List<PriceCandle> {
        return try {
            if (candles.isEmpty()) return emptyList()
            
            val periodMs = tf.minutes * 60_000L
            val grouped = candles.groupBy { it.timestampMs / periodMs }
            
            grouped.map { (_, group) ->
                PriceCandle(
                    open = group.first().open,
                    high = group.maxOf { it.high },
                    low = group.minOf { it.low },
                    close = group.last().close,
                    volume = group.sumOf { it.volume },
                    timestampMs = group.first().timestampMs,
                )
            }.sortedBy { it.timestampMs }
        } catch (e: Exception) {
            candles
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PATTERN DETECTION
    // ═══════════════════════════════════════════════════════════════════
    
    private fun analyzeTimeframe(
        mint: String,
        symbol: String,
        tf: Timeframe,
        candles: List<PriceCandle>,
    ): ScanResult {
        val candlePatterns = detectCandlePatterns(candles)
        val chartPatterns = detectChartPatterns(candles)
        val volumeSignal = analyzeVolume(candles)
        val holderSignal = HolderSignal.UNKNOWN  // Would need holder data
        
        // Calculate overall bias
        var bullishScore = 0
        var bearishScore = 0
        
        candlePatterns.forEach { p ->
            if (p.bullish) bullishScore++ else bearishScore++
        }
        chartPatterns.forEach { p ->
            if (p.bullish) bullishScore += 2 else bearishScore += 2
        }
        
        when (volumeSignal) {
            VolumeSignal.SURGE -> bullishScore++
            VolumeSignal.DRY -> bearishScore++
            else -> {}
        }
        
        val total = bullishScore + bearishScore
        val bias = when {
            total == 0 -> "NEUTRAL"
            bullishScore > bearishScore * 1.5 -> "BULLISH"
            bearishScore > bullishScore * 1.5 -> "BEARISH"
            else -> "NEUTRAL"
        }
        
        val confidence = if (total > 0) {
            maxOf(bullishScore, bearishScore).toDouble() / total * 100
        } else 50.0
        
        return ScanResult(
            mint = mint,
            symbol = symbol,
            timeframe = tf,
            candlePatterns = candlePatterns,
            chartPatterns = chartPatterns,
            volumeSignal = volumeSignal,
            holderSignal = holderSignal,
            overallBias = bias,
            confidence = confidence.coerceIn(0.0, 100.0),
            timestampMs = System.currentTimeMillis(),
        )
    }
    
    private fun detectCandlePatterns(candles: List<PriceCandle>): List<CandlePattern> {
        if (candles.size < 3) return emptyList()
        
        val patterns = mutableListOf<CandlePattern>()
        val last = candles.last()
        val prev = candles[candles.size - 2]
        
        try {
            // Doji
            if (last.isDoji) {
                patterns.add(CandlePattern.DOJI)
            }
            
            // Hammer (small body at top, long lower wick)
            if (last.lowerWick > last.body * 2 && last.upperWick < last.body * 0.5) {
                if (last.isBullish || last.isDoji) {
                    patterns.add(CandlePattern.HAMMER)
                }
            }
            
            // Shooting Star (small body at bottom, long upper wick)
            if (last.upperWick > last.body * 2 && last.lowerWick < last.body * 0.5) {
                if (last.isBearish || last.isDoji) {
                    patterns.add(CandlePattern.SHOOTING_STAR)
                }
            }
            
            // Bullish Engulfing
            if (prev.isBearish && last.isBullish && 
                last.open < prev.close && last.close > prev.open) {
                patterns.add(CandlePattern.BULLISH_ENGULFING)
            }
            
            // Bearish Engulfing
            if (prev.isBullish && last.isBearish && 
                last.open > prev.close && last.close < prev.open) {
                patterns.add(CandlePattern.BEARISH_ENGULFING)
            }
            
        } catch (e: Exception) {
            // Ignore pattern detection errors
        }
        
        return patterns
    }
    
    private fun detectChartPatterns(candles: List<PriceCandle>): List<ChartPattern> {
        if (candles.size < 10) return emptyList()
        
        val patterns = mutableListOf<ChartPattern>()
        
        try {
            val prices = candles.map { it.close }
            val recent = prices.takeLast(20)
            
            if (recent.size < 10) return patterns
            
            val high = recent.max()
            val low = recent.min()
            val last = recent.last()
            val avgPrice = recent.average()
            
            // Breakout detection
            val priorHigh = prices.dropLast(5).takeLast(20).maxOrNull() ?: high
            if (last > priorHigh * 1.05) {
                patterns.add(ChartPattern.BREAKOUT)
            }
            
            // Breakdown detection
            val priorLow = prices.dropLast(5).takeLast(20).minOrNull() ?: low
            if (last < priorLow * 0.95) {
                patterns.add(ChartPattern.BREAKDOWN)
            }
            
            // Double bottom (simplified)
            val firstHalf = recent.take(recent.size / 2)
            val secondHalf = recent.drop(recent.size / 2)
            val firstLow = firstHalf.min()
            val secondLow = secondHalf.min()
            
            if (abs(firstLow - secondLow) < avgPrice * 0.03 && last > avgPrice) {
                patterns.add(ChartPattern.DOUBLE_BOTTOM)
            }
            
            // Double top (simplified)
            val firstHigh = firstHalf.max()
            val secondHigh = secondHalf.max()
            
            if (abs(firstHigh - secondHigh) < avgPrice * 0.03 && last < avgPrice) {
                patterns.add(ChartPattern.DOUBLE_TOP)
            }
            
        } catch (e: Exception) {
            // Ignore pattern detection errors
        }
        
        return patterns
    }
    
    private fun analyzeVolume(candles: List<PriceCandle>): VolumeSignal {
        return try {
            if (candles.size < 5) return VolumeSignal.NORMAL
            
            val volumes = candles.map { it.volume }
            val avgVolume = volumes.dropLast(1).average()
            val lastVolume = volumes.last()
            
            if (avgVolume <= 0) return VolumeSignal.UNKNOWN
            
            val ratio = lastVolume / avgVolume
            
            when {
                ratio >= 2.0 -> VolumeSignal.SURGE
                ratio >= 1.2 -> VolumeSignal.INCREASING
                ratio >= 0.8 -> VolumeSignal.NORMAL
                ratio >= 0.3 -> VolumeSignal.DECREASING
                else -> VolumeSignal.DRY
            }
        } catch (e: Exception) {
            VolumeSignal.NORMAL
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // SUPERBRAIN INTEGRATION
    // ═══════════════════════════════════════════════════════════════════
    
    private fun recordInsights(result: ScanResult) {
        try {
            // Record candle patterns
            result.candlePatterns.forEach { pattern ->
                SuperBrainEnhancements.recordChartInsight(
                    mint = result.mint,
                    symbol = result.symbol,
                    pattern = pattern.name,
                    timeframe = result.timeframe.label,
                    confidence = result.confidence,
                    priceAtDetection = 0.0,  // Not tracked here
                )
            }
            
            // Record chart patterns
            result.chartPatterns.forEach { pattern ->
                SuperBrainEnhancements.recordChartInsight(
                    mint = result.mint,
                    symbol = result.symbol,
                    pattern = pattern.name,
                    timeframe = result.timeframe.label,
                    confidence = result.confidence + 10,  // Chart patterns are higher confidence
                    priceAtDetection = 0.0,
                )
            }
            
            // Record aggregated signal
            val signalType = when (result.overallBias) {
                "BULLISH" -> "BULLISH"
                "BEARISH" -> "BEARISH"
                else -> "NEUTRAL"
            }
            SuperBrainEnhancements.recordSignal(
                mint = result.mint,
                symbol = result.symbol,
                source = "SmartChart_${result.timeframe.label}",
                signalType = signalType,
            )
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "recordInsights error: ${e.message}")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PUBLIC HELPERS
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Get summary for display.
     */
    fun getScanSummary(mint: String): String {
        return try {
            val cache = priceCache[mint] ?: return "No data"
            
            val lastCandle = cache.lastOrNull() ?: return "No candles"
            val patterns = detectCandlePatterns(cache)
            val volume = analyzeVolume(cache)
            
            buildString {
                if (patterns.isNotEmpty()) {
                    append(patterns.joinToString(" ") { it.emoji })
                    append(" ")
                }
                append("Vol: ${volume.name.lowercase()}")
            }
        } catch (e: Exception) {
            "Scan error"
        }
    }
    
    /**
     * Get bias emoji for quick display.
     */
    fun getBiasEmoji(bias: String): String {
        return when (bias.uppercase()) {
            "BULLISH" -> "🟢"
            "BEARISH" -> "🔴"
            else -> "🟡"
        }
    }
    
    /**
     * Clear cache for a token.
     */
    fun clearCache(mint: String) {
        priceCache.remove(mint)
    }
    
    /**
     * Clear all caches.
     */
    fun clearAllCaches() {
        priceCache.clear()
    }
}

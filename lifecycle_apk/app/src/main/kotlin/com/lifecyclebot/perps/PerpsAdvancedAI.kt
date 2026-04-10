package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🧠 PERPS ADVANCED AI - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Comprehensive AI enhancement module for maximum trading success:
 * 
 * ENTRY TIMING:
 *   • RSI/MACD Scanner - Technical indicator alignment
 *   • Volume Spike Detection - Institutional interest signals
 *   • Support/Resistance AI - Key price level detection
 * 
 * MARKET SELECTION:
 *   • Momentum Ranking - Score and rank all assets
 *   • Sector Rotation AI - Detect hot sectors
 *   • Correlation Filter - Avoid correlated positions
 * 
 * EXIT OPTIMIZATION:
 *   • Dynamic TP/SL - ATR-based adaptive targets
 *   • Partial Profit Taking - Scale out strategy
 *   • Time-based Exit - Stale position management
 * 
 * LEARNING ENHANCEMENTS:
 *   • Pattern Memory - Remember winning/losing setups
 *   • Time-of-Day Analysis - Optimal trading hours
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsAdvancedAI {
    
    private const val TAG = "🧠AdvancedAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE HISTORY CACHE (for technical indicators)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val priceHistory = ConcurrentHashMap<PerpsMarket, MutableList<PricePoint>>()
    private const val MAX_HISTORY_SIZE = 100  // Keep last 100 price points per asset
    
    data class PricePoint(
        val price: Double,
        val timestamp: Long,
        val volume: Double = 0.0
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PATTERN MEMORY (learn from past trades)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val patternMemory = ConcurrentHashMap<String, PatternRecord>()
    
    data class PatternRecord(
        val pattern: String,
        var wins: Int = 0,
        var losses: Int = 0,
        var totalPnlPct: Double = 0.0,
        var lastUsed: Long = System.currentTimeMillis()
    ) {
        val winRate: Double get() = if (wins + losses > 0) wins.toDouble() / (wins + losses) * 100 else 50.0
        val avgPnl: Double get() = if (wins + losses > 0) totalPnlPct / (wins + losses) else 0.0
        val isReliable: Boolean get() = wins + losses >= 5
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TIME-OF-DAY PERFORMANCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val hourlyPerformance = ConcurrentHashMap<Int, HourlyStats>()
    
    data class HourlyStats(
        val hour: Int,
        var trades: Int = 0,
        var wins: Int = 0,
        var totalPnl: Double = 0.0
    ) {
        val winRate: Double get() = if (trades > 0) wins.toDouble() / trades * 100 else 50.0
        val avgPnl: Double get() = if (trades > 0) totalPnl / trades else 0.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SECTOR DEFINITIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class Sector(val emoji: String, val displayName: String) {
        MEGA_TECH("💻", "Mega Tech"),
        SEMICONDUCTORS("🔌", "Semiconductors"),
        GROWTH_TECH("🚀", "Growth Tech"),
        FINTECH("💳", "Fintech"),
        CONSUMER("🛍️", "Consumer"),
        ENERGY("⛽", "Energy"),
        CRYPTO("🪙", "Crypto"),
        COMMODITIES("🏆", "Commodities"),
        FOREX("💱", "Forex")
    }
    
    private val marketSectors = mapOf(
        // Mega Tech
        PerpsMarket.AAPL to Sector.MEGA_TECH,
        PerpsMarket.MSFT to Sector.MEGA_TECH,
        PerpsMarket.GOOGL to Sector.MEGA_TECH,
        PerpsMarket.AMZN to Sector.MEGA_TECH,
        PerpsMarket.META to Sector.MEGA_TECH,
        // Semiconductors
        PerpsMarket.NVDA to Sector.SEMICONDUCTORS,
        PerpsMarket.AMD to Sector.SEMICONDUCTORS,
        PerpsMarket.INTC to Sector.SEMICONDUCTORS,
        PerpsMarket.QCOM to Sector.SEMICONDUCTORS,
        PerpsMarket.AVGO to Sector.SEMICONDUCTORS,
        PerpsMarket.MU to Sector.SEMICONDUCTORS,
        // Growth Tech
        PerpsMarket.TSLA to Sector.GROWTH_TECH,
        PerpsMarket.NFLX to Sector.GROWTH_TECH,
        PerpsMarket.CRM to Sector.GROWTH_TECH,
        PerpsMarket.ORCL to Sector.GROWTH_TECH,
        PerpsMarket.PLTR to Sector.GROWTH_TECH,
        PerpsMarket.SNOW to Sector.GROWTH_TECH,
        PerpsMarket.SHOP to Sector.GROWTH_TECH,
        // Fintech
        PerpsMarket.COIN to Sector.FINTECH,
        PerpsMarket.PYPL to Sector.FINTECH,
        PerpsMarket.V to Sector.FINTECH,
        PerpsMarket.MA to Sector.FINTECH,
        PerpsMarket.JPM to Sector.FINTECH,
        PerpsMarket.GS to Sector.FINTECH,
        // Consumer
        PerpsMarket.DIS to Sector.CONSUMER,
        PerpsMarket.NKE to Sector.CONSUMER,
        PerpsMarket.SBUX to Sector.CONSUMER,
        PerpsMarket.MCD to Sector.CONSUMER,
        PerpsMarket.WMT to Sector.CONSUMER,
        PerpsMarket.COST to Sector.CONSUMER,
        PerpsMarket.HD to Sector.CONSUMER,
        // Energy
        PerpsMarket.XOM to Sector.ENERGY,
        PerpsMarket.CVX to Sector.ENERGY,
        // Crypto
        PerpsMarket.SOL to Sector.CRYPTO,
        PerpsMarket.BTC to Sector.CRYPTO,
        PerpsMarket.ETH to Sector.CRYPTO,
        // Commodities
        PerpsMarket.XAU to Sector.COMMODITIES,
        PerpsMarket.XAG to Sector.COMMODITIES,
        PerpsMarket.BRENT to Sector.COMMODITIES,
        PerpsMarket.WTI to Sector.COMMODITIES
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 1. RSI/MACD SCANNER - Technical Indicator Alignment
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class TechnicalSignal(
        val rsi: Double,
        val macdSignal: MacdSignal,
        val isOversold: Boolean,
        val isOverbought: Boolean,
        val trendStrength: Double,  // 0-100
        val recommendation: PerpsDirection?
    )
    
    enum class MacdSignal { BULLISH_CROSS, BEARISH_CROSS, BULLISH, BEARISH, NEUTRAL }
    
    fun analyzeTechnicals(market: PerpsMarket): TechnicalSignal {
        val history = priceHistory[market] ?: return TechnicalSignal(
            rsi = 50.0, macdSignal = MacdSignal.NEUTRAL,
            isOversold = false, isOverbought = false,
            trendStrength = 50.0, recommendation = null
        )
        
        if (history.size < 14) {
            return TechnicalSignal(
                rsi = 50.0, macdSignal = MacdSignal.NEUTRAL,
                isOversold = false, isOverbought = false,
                trendStrength = 50.0, recommendation = null
            )
        }
        
        val prices = history.takeLast(50).map { it.price }
        
        // Calculate RSI (14-period)
        val rsi = calculateRSI(prices, 14)
        
        // Calculate MACD
        val macdSignal = calculateMACD(prices)
        
        // Determine conditions
        val isOversold = rsi < 30
        val isOverbought = rsi > 70
        
        // Trend strength (based on price momentum)
        val trendStrength = calculateTrendStrength(prices)
        
        // Generate recommendation
        val recommendation = when {
            isOversold && macdSignal in listOf(MacdSignal.BULLISH_CROSS, MacdSignal.BULLISH) -> PerpsDirection.LONG
            isOverbought && macdSignal in listOf(MacdSignal.BEARISH_CROSS, MacdSignal.BEARISH) -> PerpsDirection.SHORT
            rsi < 40 && macdSignal == MacdSignal.BULLISH_CROSS -> PerpsDirection.LONG
            rsi > 60 && macdSignal == MacdSignal.BEARISH_CROSS -> PerpsDirection.SHORT
            else -> null
        }
        
        ErrorLogger.debug(TAG, "📊 ${market.symbol} RSI=${"%.1f".format(rsi)} MACD=$macdSignal trend=${"%.0f".format(trendStrength)}")
        
        return TechnicalSignal(
            rsi = rsi,
            macdSignal = macdSignal,
            isOversold = isOversold,
            isOverbought = isOverbought,
            trendStrength = trendStrength,
            recommendation = recommendation
        )
    }
    
    private fun calculateRSI(prices: List<Double>, period: Int): Double {
        if (prices.size < period + 1) return 50.0
        
        var gains = 0.0
        var losses = 0.0
        
        for (i in 1..period) {
            val change = prices[prices.size - i] - prices[prices.size - i - 1]
            if (change > 0) gains += change
            else losses += abs(change)
        }
        
        val avgGain = gains / period
        val avgLoss = losses / period
        
        if (avgLoss == 0.0) return 100.0
        
        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }
    
    private fun calculateMACD(prices: List<Double>): MacdSignal {
        if (prices.size < 26) return MacdSignal.NEUTRAL
        
        val ema12 = calculateEMA(prices, 12)
        val ema26 = calculateEMA(prices, 26)
        val macd = ema12 - ema26
        
        // Signal line (9-period EMA of MACD) - simplified
        val prevEma12 = calculateEMA(prices.dropLast(1), 12)
        val prevEma26 = calculateEMA(prices.dropLast(1), 26)
        val prevMacd = prevEma12 - prevEma26
        
        return when {
            macd > 0 && prevMacd <= 0 -> MacdSignal.BULLISH_CROSS
            macd < 0 && prevMacd >= 0 -> MacdSignal.BEARISH_CROSS
            macd > 0 -> MacdSignal.BULLISH
            macd < 0 -> MacdSignal.BEARISH
            else -> MacdSignal.NEUTRAL
        }
    }
    
    private fun calculateEMA(prices: List<Double>, period: Int): Double {
        if (prices.isEmpty()) return 0.0
        if (prices.size < period) return prices.average()
        
        val multiplier = 2.0 / (period + 1)
        var ema = prices.take(period).average()
        
        for (i in period until prices.size) {
            ema = (prices[i] - ema) * multiplier + ema
        }
        
        return ema
    }
    
    private fun calculateTrendStrength(prices: List<Double>): Double {
        if (prices.size < 10) return 50.0
        
        val shortMA = prices.takeLast(5).average()
        val longMA = prices.takeLast(20).average()
        
        val diff = (shortMA - longMA) / longMA * 100
        return (50 + diff * 5).coerceIn(0.0, 100.0)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 2. VOLUME SPIKE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class VolumeSignal(
        val currentVolume: Double,
        val avgVolume: Double,
        val volumeRatio: Double,
        val isSpike: Boolean,
        val spikeStrength: String  // "NONE", "MILD", "STRONG", "EXTREME"
    )
    
    fun analyzeVolume(market: PerpsMarket): VolumeSignal {
        val history = priceHistory[market] ?: return VolumeSignal(
            currentVolume = 0.0, avgVolume = 0.0, volumeRatio = 1.0,
            isSpike = false, spikeStrength = "NONE"
        )
        
        if (history.size < 10) {
            return VolumeSignal(
                currentVolume = 0.0, avgVolume = 0.0, volumeRatio = 1.0,
                isSpike = false, spikeStrength = "NONE"
            )
        }
        
        val currentVolume = history.last().volume
        val avgVolume = history.takeLast(20).map { it.volume }.average()
        
        if (avgVolume <= 0) {
            return VolumeSignal(
                currentVolume = currentVolume, avgVolume = 1.0, volumeRatio = 1.0,
                isSpike = false, spikeStrength = "NONE"
            )
        }
        
        val volumeRatio = currentVolume / avgVolume
        
        val (isSpike, strength) = when {
            volumeRatio >= 3.0 -> true to "EXTREME"
            volumeRatio >= 2.0 -> true to "STRONG"
            volumeRatio >= 1.5 -> true to "MILD"
            else -> false to "NONE"
        }
        
        if (isSpike) {
            ErrorLogger.info(TAG, "📊 VOLUME SPIKE: ${market.symbol} | ratio=${"%.1f".format(volumeRatio)}x | $strength")
        }
        
        return VolumeSignal(
            currentVolume = currentVolume,
            avgVolume = avgVolume,
            volumeRatio = volumeRatio,
            isSpike = isSpike,
            spikeStrength = strength
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 3. SUPPORT/RESISTANCE AI
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class SupportResistance(
        val support: Double,
        val resistance: Double,
        val currentPrice: Double,
        val nearSupport: Boolean,
        val nearResistance: Boolean,
        val distanceToSupport: Double,  // percentage
        val distanceToResistance: Double  // percentage
    )
    
    fun analyzeSupportResistance(market: PerpsMarket, currentPrice: Double): SupportResistance {
        val history = priceHistory[market] ?: return SupportResistance(
            support = currentPrice * 0.95,
            resistance = currentPrice * 1.05,
            currentPrice = currentPrice,
            nearSupport = false,
            nearResistance = false,
            distanceToSupport = 5.0,
            distanceToResistance = 5.0
        )
        
        if (history.size < 20) {
            return SupportResistance(
                support = currentPrice * 0.95,
                resistance = currentPrice * 1.05,
                currentPrice = currentPrice,
                nearSupport = false,
                nearResistance = false,
                distanceToSupport = 5.0,
                distanceToResistance = 5.0
            )
        }
        
        val prices = history.map { it.price }
        
        // Find local minima (support) and maxima (resistance)
        val support = findLocalMin(prices)
        val resistance = findLocalMax(prices)
        
        val distanceToSupport = if (support > 0) (currentPrice - support) / currentPrice * 100 else 5.0
        val distanceToResistance = if (resistance > 0) (resistance - currentPrice) / currentPrice * 100 else 5.0
        
        val nearSupport = distanceToSupport < 2.0  // Within 2%
        val nearResistance = distanceToResistance < 2.0
        
        return SupportResistance(
            support = support,
            resistance = resistance,
            currentPrice = currentPrice,
            nearSupport = nearSupport,
            nearResistance = nearResistance,
            distanceToSupport = distanceToSupport,
            distanceToResistance = distanceToResistance
        )
    }
    
    private fun findLocalMin(prices: List<Double>): Double {
        if (prices.size < 5) return prices.minOrNull() ?: 0.0
        
        val lows = mutableListOf<Double>()
        for (i in 2 until prices.size - 2) {
            if (prices[i] < prices[i-1] && prices[i] < prices[i-2] &&
                prices[i] < prices[i+1] && prices[i] < prices[i+2]) {
                lows.add(prices[i])
            }
        }
        
        return lows.minOrNull() ?: prices.minOrNull() ?: 0.0
    }
    
    private fun findLocalMax(prices: List<Double>): Double {
        if (prices.size < 5) return prices.maxOrNull() ?: 0.0
        
        val highs = mutableListOf<Double>()
        for (i in 2 until prices.size - 2) {
            if (prices[i] > prices[i-1] && prices[i] > prices[i-2] &&
                prices[i] > prices[i+1] && prices[i] > prices[i+2]) {
                highs.add(prices[i])
            }
        }
        
        return highs.maxOrNull() ?: prices.maxOrNull() ?: 0.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 4. MOMENTUM RANKING - Score all assets, trade top movers
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class MomentumScore(
        val market: PerpsMarket,
        val score: Double,
        val rank: Int,
        val priceChange: Double,
        val volumeScore: Double,
        val technicalScore: Double,
        val isTopMover: Boolean
    )
    
    suspend fun getMomentumRanking(markets: List<PerpsMarket> = PerpsMarket.values().toList()): List<MomentumScore> {
        val scores = markets.mapNotNull { market ->
            try {
                val data = PerpsMarketDataFetcher.getMarketData(market)
                val technical = analyzeTechnicals(market)
                val volume = analyzeVolume(market)
                
                val priceScore = abs(data.priceChange24hPct) * 10  // Higher change = higher score
                val volScore = if (volume.isSpike) volume.volumeRatio * 20 else 10.0
                val techScore = technical.trendStrength
                
                val totalScore = (priceScore * 0.4 + volScore * 0.3 + techScore * 0.3).coerceIn(0.0, 100.0)
                
                MomentumScore(
                    market = market,
                    score = totalScore,
                    rank = 0,  // Will be set after sorting
                    priceChange = data.priceChange24hPct,
                    volumeScore = volScore,
                    technicalScore = techScore,
                    isTopMover = false  // Will be set after sorting
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.score }
        
        // Assign ranks and mark top movers
        return scores.mapIndexed { index, score ->
            score.copy(
                rank = index + 1,
                isTopMover = index < 5  // Top 5 are "top movers"
            )
        }
    }
    
    suspend fun getTopMovers(count: Int = 5): List<PerpsMarket> {
        return getMomentumRanking().take(count).map { it.market }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 5. SECTOR ROTATION AI - Detect hot sectors
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class SectorStrength(
        val sector: Sector,
        val strength: Double,  // 0-100
        val avgChange: Double,
        val marketCount: Int,
        val isHot: Boolean
    )
    
    suspend fun getSectorRotation(): List<SectorStrength> {
        val sectorScores = mutableMapOf<Sector, MutableList<Double>>()

        for ((market, sector) in marketSectors) {
            try {
                val data = PerpsMarketDataFetcher.getMarketData(market)
                sectorScores.getOrPut(sector) { mutableListOf() }.add(data.priceChange24hPct)
            } catch (_: Exception) {}
        }
        
        return sectorScores.map { (sector, changes) ->
            val avgChange = changes.average()
            val strength = (50 + avgChange * 10).coerceIn(0.0, 100.0)
            
            SectorStrength(
                sector = sector,
                strength = strength,
                avgChange = avgChange,
                marketCount = changes.size,
                isHot = strength > 60
            )
        }.sortedByDescending { it.strength }
    }
    
    suspend fun getHotSectors(): List<Sector> {
        return getSectorRotation().filter { it.isHot }.map { it.sector }
    }

    suspend fun isInHotSector(market: PerpsMarket): Boolean {
        val sector = marketSectors[market] ?: return false
        return sector in getHotSectors()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 6. CORRELATION FILTER - Avoid correlated positions
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Pre-defined high correlations (>0.7)
    private val highCorrelations = setOf(
        setOf(PerpsMarket.AAPL, PerpsMarket.MSFT),
        setOf(PerpsMarket.AAPL, PerpsMarket.GOOGL),
        setOf(PerpsMarket.MSFT, PerpsMarket.GOOGL),
        setOf(PerpsMarket.NVDA, PerpsMarket.AMD),
        setOf(PerpsMarket.NVDA, PerpsMarket.AVGO),
        setOf(PerpsMarket.BTC, PerpsMarket.ETH),
        setOf(PerpsMarket.SOL, PerpsMarket.ETH),
        setOf(PerpsMarket.XOM, PerpsMarket.CVX),
        setOf(PerpsMarket.V, PerpsMarket.MA),
        setOf(PerpsMarket.WMT, PerpsMarket.COST),
        setOf(PerpsMarket.XAU, PerpsMarket.XAG),
        setOf(PerpsMarket.BRENT, PerpsMarket.WTI)
    )
    
    fun isHighlyCorrelated(market1: PerpsMarket, market2: PerpsMarket): Boolean {
        return highCorrelations.any { it.contains(market1) && it.contains(market2) }
    }
    
    fun getCorrelatedPositions(currentPositions: List<PerpsMarket>, newMarket: PerpsMarket): List<PerpsMarket> {
        return currentPositions.filter { isHighlyCorrelated(it, newMarket) }
    }
    
    fun shouldAvoidDueToCorrelation(currentPositions: List<PerpsMarket>, newMarket: PerpsMarket): Boolean {
        return getCorrelatedPositions(currentPositions, newMarket).isNotEmpty()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 7. DYNAMIC TP/SL - ATR-based adaptive targets
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class DynamicTargets(
        val takeProfit: Double,
        val stopLoss: Double,
        val takeProfitPct: Double,
        val stopLossPct: Double,
        val atr: Double,
        val volatility: String  // "LOW", "MEDIUM", "HIGH"
    )
    
    fun calculateDynamicTargets(
        market: PerpsMarket,
        entryPrice: Double,
        direction: PerpsDirection,
        leverage: Double
    ): DynamicTargets {
        val history = priceHistory[market]
        val atr = if (history != null && history.size >= 14) {
            calculateATR(history.map { it.price }, 14)
        } else {
            entryPrice * 0.02  // Default 2% ATR
        }
        
        val atrPct = (atr / entryPrice * 100)
        
        // Volatility classification
        val volatility = when {
            atrPct > 5.0 -> "HIGH"
            atrPct > 2.0 -> "MEDIUM"
            else -> "LOW"
        }
        
        // Dynamic TP/SL based on volatility and leverage
        val baseTpMultiplier = when (volatility) {
            "HIGH" -> 2.5
            "MEDIUM" -> 2.0
            else -> 1.5
        }
        
        val baseSlMultiplier = when (volatility) {
            "HIGH" -> 1.5
            "MEDIUM" -> 1.2
            else -> 1.0
        }
        
        // Adjust for leverage (higher leverage = tighter stops)
        val leverageAdjust = 1.0 / sqrt(leverage)
        
        val tpPct = (atrPct * baseTpMultiplier * leverageAdjust).coerceIn(2.0, 15.0)
        val slPct = (atrPct * baseSlMultiplier * leverageAdjust).coerceIn(1.0, 8.0)
        
        val (tp, sl) = when (direction) {
            PerpsDirection.LONG -> {
                entryPrice * (1 + tpPct / 100) to entryPrice * (1 - slPct / 100)
            }
            PerpsDirection.SHORT -> {
                entryPrice * (1 - tpPct / 100) to entryPrice * (1 + slPct / 100)
            }
        }
        
        ErrorLogger.debug(TAG, "🎯 ${market.symbol} Dynamic TP=${"%.2f".format(tpPct)}% SL=${"%.2f".format(slPct)}% ($volatility vol)")
        
        return DynamicTargets(
            takeProfit = tp,
            stopLoss = sl,
            takeProfitPct = tpPct,
            stopLossPct = slPct,
            atr = atr,
            volatility = volatility
        )
    }
    
    private fun calculateATR(prices: List<Double>, period: Int): Double {
        if (prices.size < period + 1) return prices.lastOrNull()?.times(0.02) ?: 1.0
        
        val trueRanges = mutableListOf<Double>()
        for (i in 1 until prices.size) {
            val high = max(prices[i], prices[i-1])
            val low = min(prices[i], prices[i-1])
            trueRanges.add(high - low)
        }
        
        return trueRanges.takeLast(period).average()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 8. PARTIAL PROFIT TAKING - Scale out strategy
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PartialExitPlan(
        val levels: List<PartialExitLevel>,
        val totalExitPct: Double
    )
    
    data class PartialExitLevel(
        val pnlTrigger: Double,  // P&L % to trigger
        val exitPct: Double,     // % of position to close
        val description: String
    )
    
    fun getPartialExitPlan(volatility: String = "MEDIUM"): PartialExitPlan {
        val levels = when (volatility) {
            "HIGH" -> listOf(
                PartialExitLevel(5.0, 25.0, "Quick 25% at +5%"),
                PartialExitLevel(10.0, 25.0, "Another 25% at +10%"),
                PartialExitLevel(20.0, 25.0, "Scale out at +20%"),
                PartialExitLevel(0.0, 25.0, "Let 25% ride")
            )
            "LOW" -> listOf(
                PartialExitLevel(3.0, 50.0, "Half at +3%"),
                PartialExitLevel(6.0, 50.0, "Rest at +6%")
            )
            else -> listOf(
                PartialExitLevel(5.0, 50.0, "Half at +5%"),
                PartialExitLevel(10.0, 30.0, "Scale at +10%"),
                PartialExitLevel(0.0, 20.0, "Let 20% ride")
            )
        }
        
        return PartialExitPlan(levels = levels, totalExitPct = 100.0)
    }
    
    fun shouldPartialExit(pnlPct: Double, alreadyExitedPct: Double, volatility: String = "MEDIUM"): Double {
        val plan = getPartialExitPlan(volatility)
        
        var exitPct = 0.0
        for (level in plan.levels) {
            if (level.pnlTrigger > 0 && pnlPct >= level.pnlTrigger) {
                exitPct += level.exitPct
            }
        }
        
        return (exitPct - alreadyExitedPct).coerceAtLeast(0.0)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 9. TIME-BASED EXIT - Stale position management
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class TimeExitSignal(
        val shouldExit: Boolean,
        val reason: String,
        val holdMinutes: Long
    )
    
    fun checkTimeBasedExit(
        entryTime: Long,
        pnlPct: Double,
        market: PerpsMarket
    ): TimeExitSignal {
        val holdMinutes = (System.currentTimeMillis() - entryTime) / 60_000
        
        // Different thresholds for different asset types
        val isCrypto = market.symbol in listOf("SOL", "BTC", "ETH", "PEPE", "WIF", "BONK", "DOGE", "SHIB", "AVAX", "LINK", "UNI", "AAVE", "ARB", "OP", "SUI", "APT", "INJ", "TIA", "JUP", "PYTH", "JTO", "RNDR", "FET", "TAO", "WLD", "ONDO")
        val maxHoldMinutes = when {
            market.isStock -> 480  // 8 hours for stocks
            isCrypto -> 240  // 4 hours for crypto
            else -> 360  // 6 hours for commodities/forex
        }
        
        val shouldExit = when {
            // Exit if held too long with minimal P&L
            holdMinutes > maxHoldMinutes && abs(pnlPct) < 2.0 -> true
            // Exit if held very long even with small profit
            holdMinutes > maxHoldMinutes * 2 && pnlPct < 5.0 -> true
            // Exit if stuck flat for 2+ hours
            holdMinutes > 120 && abs(pnlPct) < 0.5 -> true
            else -> false
        }
        
        val reason = when {
            holdMinutes > maxHoldMinutes * 2 -> "Extended hold timeout"
            holdMinutes > maxHoldMinutes -> "Max hold time reached"
            holdMinutes > 120 && abs(pnlPct) < 0.5 -> "Flat position timeout"
            else -> "N/A"
        }
        
        return TimeExitSignal(
            shouldExit = shouldExit,
            reason = reason,
            holdMinutes = holdMinutes
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 10. PATTERN MEMORY - Remember winning/losing setups
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun recordPattern(
        market: PerpsMarket,
        direction: PerpsDirection,
        technicalSignal: TechnicalSignal,
        wasWin: Boolean,
        pnlPct: Double
    ) {
        val pattern = generatePatternKey(market, direction, technicalSignal)
        
        val record = patternMemory.getOrPut(pattern) {
            PatternRecord(pattern = pattern)
        }
        
        if (wasWin) record.wins++ else record.losses++
        record.totalPnlPct += pnlPct
        record.lastUsed = System.currentTimeMillis()
        
        ErrorLogger.info(TAG, "📝 Pattern recorded: $pattern | win=$wasWin | WR=${"%.0f".format(record.winRate)}%")
    }
    
    fun getPatternConfidence(
        market: PerpsMarket,
        direction: PerpsDirection,
        technicalSignal: TechnicalSignal
    ): Double {
        val pattern = generatePatternKey(market, direction, technicalSignal)
        val record = patternMemory[pattern] ?: return 50.0  // Neutral if no history
        
        if (!record.isReliable) return 50.0  // Not enough data
        
        return record.winRate
    }
    
    private fun generatePatternKey(
        market: PerpsMarket,
        direction: PerpsDirection,
        technical: TechnicalSignal
    ): String {
        val rsiZone = when {
            technical.rsi < 30 -> "oversold"
            technical.rsi > 70 -> "overbought"
            technical.rsi < 45 -> "weak"
            technical.rsi > 55 -> "strong"
            else -> "neutral"
        }
        
        return "${market.symbol}_${direction.name}_${rsiZone}_${technical.macdSignal.name}"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // 11. TIME-OF-DAY ANALYSIS - Best trading hours
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun recordHourlyTrade(hour: Int, wasWin: Boolean, pnlPct: Double) {
        val stats = hourlyPerformance.getOrPut(hour) { HourlyStats(hour = hour) }
        stats.trades++
        if (wasWin) stats.wins++
        stats.totalPnl += pnlPct
    }
    
    fun getBestTradingHours(): List<Int> {
        return hourlyPerformance.values
            .filter { it.trades >= 5 }  // Minimum sample size
            .sortedByDescending { it.winRate }
            .take(5)
            .map { it.hour }
    }
    
    fun isGoodTradingHour(): Boolean {
        val currentHour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val bestHours = getBestTradingHours()
        
        return if (bestHours.isEmpty()) {
            true  // No data yet, allow trading
        } else {
            currentHour in bestHours
        }
    }
    
    fun getHourlyWinRate(hour: Int): Double {
        return hourlyPerformance[hour]?.winRate ?: 50.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE HISTORY MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun recordPrice(market: PerpsMarket, price: Double, volume: Double = 0.0) {
        val history = priceHistory.getOrPut(market) { mutableListOf() }
        
        history.add(PricePoint(
            price = price,
            timestamp = System.currentTimeMillis(),
            volume = volume
        ))
        
        // Keep only last N points
        while (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(0)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // COMPREHENSIVE ENTRY ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class EntryAnalysis(
        val market: PerpsMarket,
        val recommendedDirection: PerpsDirection?,
        val confidence: Double,  // 0-100
        val shouldTrade: Boolean,
        val reasons: List<String>,
        val warnings: List<String>,
        val technicals: TechnicalSignal,
        val volume: VolumeSignal,
        val supportResistance: SupportResistance,
        val isTopMover: Boolean,
        val isInHotSector: Boolean,
        val patternConfidence: Double
    )
    
    suspend fun analyzeEntry(
        market: PerpsMarket,
        currentPrice: Double,
        currentPositions: List<PerpsMarket> = emptyList()
    ): EntryAnalysis {
        // Record current price
        recordPrice(market, currentPrice)
        
        val technicals = analyzeTechnicals(market)
        val volume = analyzeVolume(market)
        val sr = analyzeSupportResistance(market, currentPrice)
        val isTopMover = market in getTopMovers()
        val isHotSector = isInHotSector(market)
        
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var confidence = 50.0
        
        // Technical alignment
        technicals.recommendation?.let { dir ->
            confidence += 15
            reasons.add("📊 Tech: ${if (dir == PerpsDirection.LONG) "Bullish" else "Bearish"} (RSI=${"%.0f".format(technicals.rsi)})")
        }
        
        // Volume spike
        if (volume.isSpike) {
            confidence += when (volume.spikeStrength) {
                "EXTREME" -> 20
                "STRONG" -> 15
                "MILD" -> 10
                else -> 0
            }
            reasons.add("📈 Volume spike: ${volume.spikeStrength}")
        }
        
        // Support/Resistance
        if (sr.nearSupport && technicals.recommendation == PerpsDirection.LONG) {
            confidence += 10
            reasons.add("📍 Near support level")
        }
        if (sr.nearResistance && technicals.recommendation == PerpsDirection.SHORT) {
            confidence += 10
            reasons.add("📍 Near resistance level")
        }
        
        // Momentum ranking
        if (isTopMover) {
            confidence += 10
            reasons.add("🔥 Top mover")
        }
        
        // Sector rotation
        if (isHotSector) {
            confidence += 5
            reasons.add("🌡️ Hot sector")
        }
        
        // Pattern memory
        val patternConf = technicals.recommendation?.let {
            getPatternConfidence(market, it, technicals)
        } ?: 50.0
        
        if (patternConf > 60) {
            confidence += 10
            reasons.add("🧠 Pattern win rate: ${"%.0f".format(patternConf)}%")
        } else if (patternConf < 40) {
            confidence -= 10
            warnings.add("⚠️ Pattern historically weak")
        }
        
        // Time of day
        if (!isGoodTradingHour()) {
            confidence -= 5
            warnings.add("⏰ Not optimal trading hour")
        }
        
        // Correlation check
        if (shouldAvoidDueToCorrelation(currentPositions, market)) {
            confidence -= 15
            warnings.add("🔗 Correlated with existing position")
        }
        
        // Final decision
        val shouldTrade = confidence >= 60 && technicals.recommendation != null && warnings.size < 2
        
        return EntryAnalysis(
            market = market,
            recommendedDirection = technicals.recommendation,
            confidence = confidence.coerceIn(0.0, 100.0),
            shouldTrade = shouldTrade,
            reasons = reasons,
            warnings = warnings,
            technicals = technicals,
            volume = volume,
            supportResistance = sr,
            isTopMover = isTopMover,
            isInHotSector = isHotSector,
            patternConfidence = patternConf
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getStats(): Map<String, Any> {
        return mapOf(
            "patternsLearned" to patternMemory.size,
            "reliablePatterns" to patternMemory.values.count { it.isReliable },
            "bestPatternWinRate" to (patternMemory.values.maxOfOrNull { it.winRate } ?: 0.0),
            "hoursTracked" to hourlyPerformance.size,
            "bestHours" to getBestTradingHours(),
            "hotSectors" to getHotSectors().map { it.displayName },
            "priceHistoryMarkets" to priceHistory.size
        )
    }
}

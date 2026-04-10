package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 📊 CORRELATION SCANNER - V5.7.7
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Detects price correlations between related assets for trading signals:
 *   - Gold miners vs XAU (gold price)
 *   - Silver miners vs XAG (silver price)
 *   - China ADRs vs USDCNY
 *   - EV stocks vs Lithium
 *   - Crypto stocks vs BTC
 * 
 * FEATURES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   📈 Pearson correlation coefficient calculation
 *   🔍 Divergence detection (when correlated assets decouple)
 *   📊 Rolling correlation over configurable window
 *   ⚡ Real-time correlation updates
 *   🎯 Trade signals from correlation breakdowns
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object CorrelationScanner {
    
    private const val TAG = "📊Correlation"
    
    // Price history for correlation calculation
    private val priceHistory = ConcurrentHashMap<String, MutableList<PricePoint>>()
    private const val MAX_HISTORY_SIZE = 100  // Keep last 100 price points
    private const val MIN_POINTS_FOR_CORRELATION = 10
    
    // Correlation cache
    private val correlationCache = ConcurrentHashMap<String, CorrelationResult>()
    private var lastCalculationTime = 0L
    private const val CACHE_TTL_MS = 60_000L  // 1 minute cache
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA MODELS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PricePoint(
        val price: Double,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    data class CorrelationResult(
        val asset1: PerpsMarket,
        val asset2: PerpsMarket,
        val correlation: Double,      // -1.0 to 1.0
        val sampleSize: Int,
        val isDiverging: Boolean,     // True if correlation broke down
        val divergenceStrength: Double, // How much they've diverged
        val signal: CorrelationSignal,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        fun getCorrelationStrength(): String = when {
            correlation >= 0.8 -> "STRONG_POSITIVE"
            correlation >= 0.5 -> "MODERATE_POSITIVE"
            correlation >= 0.2 -> "WEAK_POSITIVE"
            correlation >= -0.2 -> "NO_CORRELATION"
            correlation >= -0.5 -> "WEAK_NEGATIVE"
            correlation >= -0.8 -> "MODERATE_NEGATIVE"
            else -> "STRONG_NEGATIVE"
        }
        
        fun getDisplayCorrelation(): String = "${"%.2f".format(correlation * 100)}%"
    }
    
    enum class CorrelationSignal(val emoji: String, val action: String) {
        LONG_LAGGARD("📈", "Buy the lagging asset"),
        SHORT_LEADER("📉", "Short the leading asset"),
        PAIR_TRADE("🔄", "Long laggard / Short leader"),
        NO_SIGNAL("➡️", "No actionable signal"),
        BREAKDOWN("⚠️", "Correlation breakdown - caution"),
    }
    
    data class CorrelationPair(
        val base: PerpsMarket,        // The benchmark (XAU, BTC, etc.)
        val correlated: List<PerpsMarket>, // Assets that should correlate
        val expectedCorrelation: Double,   // Expected correlation (0.5-1.0)
        val name: String
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PREDEFINED CORRELATION PAIRS
    // ═══════════════════════════════════════════════════════════════════════════
    
    val GOLD_MINERS_PAIR = CorrelationPair(
        base = PerpsMarket.XAU,
        correlated = listOf(
            PerpsMarket.NEM, PerpsMarket.GOLD, PerpsMarket.AEM, PerpsMarket.FNV,
            PerpsMarket.KGC, PerpsMarket.AGI, PerpsMarket.EGO, PerpsMarket.BTG,
            PerpsMarket.NGD, PerpsMarket.DRD, PerpsMarket.HMY, PerpsMarket.AU
        ),
        expectedCorrelation = 0.7,
        name = "Gold Miners vs Gold"
    )
    
    val SILVER_MINERS_PAIR = CorrelationPair(
        base = PerpsMarket.XAG,
        correlated = listOf(
            PerpsMarket.WPM, PerpsMarket.HL, PerpsMarket.PAAS, PerpsMarket.AG,
            PerpsMarket.CDE, PerpsMarket.FSM, PerpsMarket.MAG, PerpsMarket.SVM,
            PerpsMarket.GATO, PerpsMarket.SILV, PerpsMarket.SSRM
        ),
        expectedCorrelation = 0.65,
        name = "Silver Miners vs Silver"
    )
    
    val CHINA_FOREX_PAIR = CorrelationPair(
        base = PerpsMarket.USDCNY,
        correlated = listOf(
            PerpsMarket.BABA, PerpsMarket.BIDU, PerpsMarket.JD, PerpsMarket.NIO,
            PerpsMarket.XPEV, PerpsMarket.LI, PerpsMarket.PDD, PerpsMarket.TCEHY,
            PerpsMarket.BILI, PerpsMarket.TME
        ),
        expectedCorrelation = -0.5, // Negative: strong CNY = strong China stocks
        name = "China ADRs vs USD/CNY"
    )
    
    val CRYPTO_STOCKS_PAIR = CorrelationPair(
        base = PerpsMarket.BTC,
        correlated = listOf(
            PerpsMarket.COIN, PerpsMarket.MSTR, PerpsMarket.HOOD
        ),
        expectedCorrelation = 0.8,
        name = "Crypto Stocks vs Bitcoin"
    )
    
    val EV_LITHIUM_PAIR = CorrelationPair(
        base = PerpsMarket.LITHIUM,
        correlated = listOf(
            PerpsMarket.TSLA, PerpsMarket.RIVN, PerpsMarket.LCID,
            PerpsMarket.NIO, PerpsMarket.XPEV, PerpsMarket.LI
        ),
        expectedCorrelation = 0.5,
        name = "EV Stocks vs Lithium"
    )
    
    val JAPAN_FOREX_PAIR = CorrelationPair(
        base = PerpsMarket.USDJPY,
        correlated = listOf(
            PerpsMarket.SONY, PerpsMarket.TM, PerpsMarket.NTDOY,
            PerpsMarket.MUFG, PerpsMarket.HMC
        ),
        expectedCorrelation = 0.4, // Weak JPY can boost exporters
        name = "Japan ADRs vs USD/JPY"
    )
    
    val ALL_PAIRS = listOf(
        GOLD_MINERS_PAIR,
        SILVER_MINERS_PAIR,
        CHINA_FOREX_PAIR,
        CRYPTO_STOCKS_PAIR,
        EV_LITHIUM_PAIR,
        JAPAN_FOREX_PAIR
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE RECORDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a price point for correlation tracking
     * Call this from the main price polling loop
     */
    fun recordPrice(market: PerpsMarket, price: Double) {
        if (price <= 0) return
        
        val history = priceHistory.getOrPut(market.symbol) { mutableListOf() }
        
        synchronized(history) {
            history.add(PricePoint(price))
            
            // Trim to max size
            while (history.size > MAX_HISTORY_SIZE) {
                history.removeAt(0)
            }
        }
    }
    
    /**
     * Record prices for multiple markets at once
     */
    fun recordPrices(prices: Map<PerpsMarket, Double>) {
        prices.forEach { (market, price) -> recordPrice(market, price) }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORRELATION CALCULATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate Pearson correlation coefficient between two assets
     */
    fun calculateCorrelation(asset1: PerpsMarket, asset2: PerpsMarket): Double? {
        val history1 = priceHistory[asset1.symbol] ?: return null
        val history2 = priceHistory[asset2.symbol] ?: return null
        
        // Get aligned price series (same timestamps, roughly)
        val (prices1, prices2) = alignPriceSeries(history1, history2)
        
        if (prices1.size < MIN_POINTS_FOR_CORRELATION) {
            return null
        }
        
        return pearsonCorrelation(prices1, prices2)
    }
    
    /**
     * Pearson correlation coefficient
     * Returns value between -1.0 (perfect negative) and 1.0 (perfect positive)
     */
    private fun pearsonCorrelation(x: List<Double>, y: List<Double>): Double {
        if (x.size != y.size || x.isEmpty()) return 0.0
        
        val n = x.size
        val sumX = x.sum()
        val sumY = y.sum()
        val sumXY = x.zip(y).sumOf { it.first * it.second }
        val sumX2 = x.sumOf { it * it }
        val sumY2 = y.sumOf { it * it }
        
        val numerator = n * sumXY - sumX * sumY
        val denominator = sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY))
        
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }
    
    /**
     * Align two price series by timestamp (within 5 minute tolerance)
     */
    private fun alignPriceSeries(
        history1: List<PricePoint>,
        history2: List<PricePoint>
    ): Pair<List<Double>, List<Double>> {
        val tolerance = 5 * 60 * 1000L // 5 minutes
        
        val aligned1 = mutableListOf<Double>()
        val aligned2 = mutableListOf<Double>()
        
        for (p1 in history1) {
            val match = history2.find { kotlin.math.abs(it.timestamp - p1.timestamp) < tolerance }
            if (match != null) {
                aligned1.add(p1.price)
                aligned2.add(match.price)
            }
        }
        
        return aligned1 to aligned2
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DIVERGENCE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze a correlation pair for trading signals
     */
    suspend fun analyzeCorrelationPair(pair: CorrelationPair): List<CorrelationResult> {
        val results = mutableListOf<CorrelationResult>()
        
        // Get base asset current data
        val baseData = try {
            PerpsMarketDataFetcher.getMarketData(pair.base)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Failed to get base data for ${pair.base.symbol}")
            return emptyList()
        }
        
        // Record base price
        recordPrice(pair.base, baseData.price)
        
        for (correlated in pair.correlated) {
            try {
                val correlatedData = PerpsMarketDataFetcher.getMarketData(correlated)
                recordPrice(correlated, correlatedData.price)
                
                val correlation = calculateCorrelation(pair.base, correlated)
                
                if (correlation != null) {
                    // Check for divergence
                    val expectedSign = if (pair.expectedCorrelation >= 0) 1 else -1
                    val actualSign = if (correlation >= 0) 1 else -1
                    val isDiverging = expectedSign != actualSign || 
                        kotlin.math.abs(correlation) < kotlin.math.abs(pair.expectedCorrelation) * 0.5
                    
                    // Calculate divergence based on recent price moves
                    val baseChange = baseData.priceChange24hPct
                    val correlatedChange = correlatedData.priceChange24hPct
                    val expectedChange = baseChange * pair.expectedCorrelation
                    val divergenceStrength = kotlin.math.abs(correlatedChange - expectedChange)
                    
                    // Generate signal
                    val signal = generateSignal(
                        baseChange = baseChange,
                        correlatedChange = correlatedChange,
                        expectedCorrelation = pair.expectedCorrelation,
                        actualCorrelation = correlation,
                        isDiverging = isDiverging
                    )
                    
                    results.add(CorrelationResult(
                        asset1 = pair.base,
                        asset2 = correlated,
                        correlation = correlation,
                        sampleSize = priceHistory[correlated.symbol]?.size ?: 0,
                        isDiverging = isDiverging,
                        divergenceStrength = divergenceStrength,
                        signal = signal
                    ))
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Error analyzing ${correlated.symbol}: ${e.message}")
            }
        }
        
        return results.sortedByDescending { kotlin.math.abs(it.divergenceStrength) }
    }
    
    /**
     * Generate trading signal from correlation analysis
     */
    private fun generateSignal(
        baseChange: Double,
        correlatedChange: Double,
        expectedCorrelation: Double,
        actualCorrelation: Double,
        isDiverging: Boolean
    ): CorrelationSignal {
        // If correlation broke down significantly, flag caution
        if (isDiverging && kotlin.math.abs(actualCorrelation - expectedCorrelation) > 0.5) {
            return CorrelationSignal.BREAKDOWN
        }
        
        // For positive correlation pairs
        if (expectedCorrelation > 0) {
            // Base up, correlated lagging = buy correlated
            if (baseChange > 2.0 && correlatedChange < baseChange * 0.5) {
                return CorrelationSignal.LONG_LAGGARD
            }
            // Base down, correlated holding = short correlated  
            if (baseChange < -2.0 && correlatedChange > baseChange * 0.5) {
                return CorrelationSignal.SHORT_LEADER
            }
        }
        
        // For negative correlation pairs (e.g., USD/CNY vs China stocks)
        if (expectedCorrelation < 0) {
            // Base up (USD strong), correlated should be down, if holding = short
            if (baseChange > 1.0 && correlatedChange > 0) {
                return CorrelationSignal.SHORT_LEADER
            }
            // Base down (USD weak), correlated should be up, if lagging = buy
            if (baseChange < -1.0 && correlatedChange < 0) {
                return CorrelationSignal.LONG_LAGGARD
            }
        }
        
        return CorrelationSignal.NO_SIGNAL
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN SCAN METHODS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Scan all predefined correlation pairs
     */
    suspend fun scanAllCorrelations(): Map<String, List<CorrelationResult>> {
        ErrorLogger.info(TAG, "📊 Scanning all correlation pairs...")
        
        val results = mutableMapOf<String, List<CorrelationResult>>()
        
        for (pair in ALL_PAIRS) {
            try {
                val pairResults = analyzeCorrelationPair(pair)
                results[pair.name] = pairResults
                
                // Log significant findings
                val diverging = pairResults.filter { it.isDiverging }
                val signals = pairResults.filter { it.signal != CorrelationSignal.NO_SIGNAL }
                
                if (diverging.isNotEmpty()) {
                    ErrorLogger.info(TAG, "📊 ${pair.name}: ${diverging.size} diverging assets")
                }
                if (signals.isNotEmpty()) {
                    ErrorLogger.info(TAG, "📊 ${pair.name}: ${signals.size} trade signals")
                }
            } catch (e: Exception) {
                ErrorLogger.debug(TAG, "Error scanning ${pair.name}: ${e.message}")
            }
        }
        
        return results
    }
    
    /**
     * Get actionable signals only
     */
    suspend fun getActionableSignals(): List<CorrelationResult> {
        val allResults = scanAllCorrelations()
        return allResults.values.flatten()
            .filter { it.signal != CorrelationSignal.NO_SIGNAL }
            .sortedByDescending { it.divergenceStrength }
    }
    
    /**
     * Quick check: Gold miners correlation
     */
    suspend fun checkGoldMinersCorrelation(): List<CorrelationResult> {
        return analyzeCorrelationPair(GOLD_MINERS_PAIR)
    }
    
    /**
     * Quick check: Silver miners correlation
     */
    suspend fun checkSilverMinersCorrelation(): List<CorrelationResult> {
        return analyzeCorrelationPair(SILVER_MINERS_PAIR)
    }
    
    /**
     * Quick check: China stocks vs CNY
     */
    suspend fun checkChinaCorrelation(): List<CorrelationResult> {
        return analyzeCorrelationPair(CHINA_FOREX_PAIR)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // STATISTICS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get current data points count per asset
     */
    fun getDataPointCounts(): Map<String, Int> {
        return priceHistory.mapValues { it.value.size }
    }
    
    /**
     * Check if we have enough data for correlation analysis
     */
    fun hasEnoughData(asset: PerpsMarket): Boolean {
        return (priceHistory[asset.symbol]?.size ?: 0) >= MIN_POINTS_FOR_CORRELATION
    }
    
    /**
     * Clear all price history (for testing/reset)
     */
    fun clearHistory() {
        priceHistory.clear()
        correlationCache.clear()
        ErrorLogger.info(TAG, "📊 Correlation history cleared")
    }
    
    /**
     * Get summary stats
     */
    fun getStats(): Map<String, Any> = mapOf(
        "trackedAssets" to priceHistory.size,
        "totalDataPoints" to priceHistory.values.sumOf { it.size },
        "correlationPairs" to ALL_PAIRS.size,
        "assetsWithEnoughData" to priceHistory.count { it.value.size >= MIN_POINTS_FOR_CORRELATION }
    )
}

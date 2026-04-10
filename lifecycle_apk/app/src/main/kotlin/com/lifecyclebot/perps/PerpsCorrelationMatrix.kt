package com.lifecyclebot.perps

import com.lifecyclebot.engine.ErrorLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * 🔗 CORRELATION MATRIX - V5.7.5
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Tracks correlations between assets to avoid overexposure.
 * 
 * PHILOSOPHY:
 * ─────────────────────────────────────────────────────────────────────────────
 *   Diversification reduces risk.
 *   Highly correlated assets = false diversification.
 * 
 * CORRELATION RANGES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   +0.7 to +1.0 = Strong positive (move together)
 *   +0.3 to +0.7 = Moderate positive
 *   -0.3 to +0.3 = Low correlation (good diversification)
 *   -0.7 to -0.3 = Moderate negative (hedge potential)
 *   -1.0 to -0.7 = Strong negative (natural hedge)
 * 
 * USE CASES:
 * ─────────────────────────────────────────────────────────────────────────────
 *   • Avoid opening NVDA long when already long TSLA (correlated tech)
 *   • Consider Gold when portfolio heavy in equities (hedge)
 *   • Reduce position size when adding correlated asset
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object PerpsCorrelationMatrix {
    
    private const val TAG = "🔗Correlation"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    // Pre-defined correlation groups (known relationships)
    enum class AssetGroup(val displayName: String) {
        MEGA_TECH("Mega Tech"),           // AAPL, MSFT, GOOGL, AMZN, META
        SEMICONDUCTORS("Semiconductors"), // NVDA, AMD, INTC, QCOM
        FINTECH("Fintech"),               // COIN, PYPL, V, MA
        CRYPTO_MAJORS("Crypto Majors"),   // BTC, ETH, SOL
        MEME_COINS("Meme Coins"),         // PEPE, WIF, BONK, DOGE, SHIB
        PRECIOUS_METALS("Precious Metals"), // XAU, XAG, XPT
        ENERGY("Energy"),                 // XOM, CVX, BRENT, WTI
        CONSUMER("Consumer"),             // DIS, NKE, SBUX, MCD
        HEALTHCARE("Healthcare"),         // JNJ, PFE, UNH
        FOREX_USD("Forex USD"),           // EUR/USD, GBP/USD inverse correlated
    }
    
    // Market to group mapping
    private val marketGroups = mapOf(
        // Mega Tech
        PerpsMarket.AAPL to AssetGroup.MEGA_TECH,
        PerpsMarket.MSFT to AssetGroup.MEGA_TECH,
        PerpsMarket.GOOGL to AssetGroup.MEGA_TECH,
        PerpsMarket.AMZN to AssetGroup.MEGA_TECH,
        PerpsMarket.META to AssetGroup.MEGA_TECH,
        
        // Semiconductors
        PerpsMarket.NVDA to AssetGroup.SEMICONDUCTORS,
        PerpsMarket.AMD to AssetGroup.SEMICONDUCTORS,
        
        // Crypto
        PerpsMarket.SOL to AssetGroup.CRYPTO_MAJORS,
        PerpsMarket.BTC to AssetGroup.CRYPTO_MAJORS,
        PerpsMarket.ETH to AssetGroup.CRYPTO_MAJORS,
        
        // Meme coins
        PerpsMarket.PEPE to AssetGroup.MEME_COINS,
        PerpsMarket.WIF to AssetGroup.MEME_COINS,
        PerpsMarket.BONK to AssetGroup.MEME_COINS,
        PerpsMarket.DOGE to AssetGroup.MEME_COINS,
        PerpsMarket.SHIB to AssetGroup.MEME_COINS,
        
        // Precious metals
        PerpsMarket.XAU to AssetGroup.PRECIOUS_METALS,
        PerpsMarket.XAG to AssetGroup.PRECIOUS_METALS,
        PerpsMarket.XPT to AssetGroup.PRECIOUS_METALS,
        
        // Energy
        PerpsMarket.XOM to AssetGroup.ENERGY,
        PerpsMarket.CVX to AssetGroup.ENERGY,
        PerpsMarket.BRENT to AssetGroup.ENERGY,
        PerpsMarket.WTI to AssetGroup.ENERGY,
        
        // Consumer
        PerpsMarket.DIS to AssetGroup.CONSUMER,
        PerpsMarket.NKE to AssetGroup.CONSUMER,
        PerpsMarket.SBUX to AssetGroup.CONSUMER,
        PerpsMarket.MCD to AssetGroup.CONSUMER,
        
        // Healthcare
        PerpsMarket.JNJ to AssetGroup.HEALTHCARE,
        PerpsMarket.PFE to AssetGroup.HEALTHCARE,
        PerpsMarket.UNH to AssetGroup.HEALTHCARE,
        
        // Fintech
        PerpsMarket.COIN to AssetGroup.FINTECH,
        PerpsMarket.PYPL to AssetGroup.FINTECH,
        PerpsMarket.V to AssetGroup.FINTECH,
        PerpsMarket.MA to AssetGroup.FINTECH,
    )
    
    // Intra-group correlation (same group)
    private const val SAME_GROUP_CORRELATION = 0.75
    
    // Cross-group correlations (estimated)
    private val crossGroupCorrelations = mapOf(
        // Tech sectors are correlated
        Pair(AssetGroup.MEGA_TECH, AssetGroup.SEMICONDUCTORS) to 0.65,
        Pair(AssetGroup.MEGA_TECH, AssetGroup.FINTECH) to 0.50,
        
        // Crypto correlations
        Pair(AssetGroup.CRYPTO_MAJORS, AssetGroup.MEME_COINS) to 0.60,
        Pair(AssetGroup.CRYPTO_MAJORS, AssetGroup.FINTECH) to 0.40, // COIN correlation
        
        // Precious metals inverse to equities
        Pair(AssetGroup.PRECIOUS_METALS, AssetGroup.MEGA_TECH) to -0.30,
        Pair(AssetGroup.PRECIOUS_METALS, AssetGroup.SEMICONDUCTORS) to -0.25,
        
        // Energy somewhat independent
        Pair(AssetGroup.ENERGY, AssetGroup.MEGA_TECH) to 0.20,
        
        // Consumer defensive
        Pair(AssetGroup.CONSUMER, AssetGroup.MEGA_TECH) to 0.35,
        Pair(AssetGroup.HEALTHCARE, AssetGroup.MEGA_TECH) to 0.25,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRICE TRACKING FOR DYNAMIC CORRELATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PriceReturn(
        val timestamp: Long,
        val returnPct: Double,
    )
    
    private val priceReturns = ConcurrentHashMap<PerpsMarket, MutableList<PriceReturn>>()
    private const val MAX_RETURNS_HISTORY = 100
    
    /**
     * Record a price return for dynamic correlation calculation
     */
    fun recordReturn(market: PerpsMarket, returnPct: Double) {
        val returns = priceReturns.getOrPut(market) { mutableListOf() }
        synchronized(returns) {
            returns.add(PriceReturn(System.currentTimeMillis(), returnPct))
            while (returns.size > MAX_RETURNS_HISTORY) {
                returns.removeAt(0)
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORRELATION QUERIES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get correlation between two markets
     */
    fun getCorrelation(market1: PerpsMarket, market2: PerpsMarket): Double {
        if (market1 == market2) return 1.0
        
        // Try dynamic correlation first
        val dynamic = calculateDynamicCorrelation(market1, market2)
        if (dynamic != null) return dynamic
        
        // Fall back to pre-defined correlations
        return getPreDefinedCorrelation(market1, market2)
    }
    
    /**
     * Get pre-defined correlation based on groups
     */
    private fun getPreDefinedCorrelation(market1: PerpsMarket, market2: PerpsMarket): Double {
        val group1 = marketGroups[market1]
        val group2 = marketGroups[market2]
        
        // Same group = high correlation
        if (group1 != null && group1 == group2) {
            return SAME_GROUP_CORRELATION
        }
        
        // Check cross-group correlation
        if (group1 != null && group2 != null) {
            val pair1 = Pair(group1, group2)
            val pair2 = Pair(group2, group1)
            
            return crossGroupCorrelations[pair1] 
                ?: crossGroupCorrelations[pair2] 
                ?: 0.1 // Default low correlation for unrelated assets
        }
        
        return 0.0 // Unknown
    }
    
    /**
     * Calculate dynamic correlation from historical returns
     */
    private fun calculateDynamicCorrelation(market1: PerpsMarket, market2: PerpsMarket): Double? {
        val returns1 = priceReturns[market1] ?: return null
        val returns2 = priceReturns[market2] ?: return null
        
        if (returns1.size < 20 || returns2.size < 20) return null
        
        // Align returns by timestamp (within 1 minute tolerance)
        val paired = mutableListOf<Pair<Double, Double>>()
        
        synchronized(returns1) {
            synchronized(returns2) {
                for (r1 in returns1) {
                    val r2 = returns2.find { kotlin.math.abs(it.timestamp - r1.timestamp) < 60_000 }
                    if (r2 != null) {
                        paired.add(Pair(r1.returnPct, r2.returnPct))
                    }
                }
            }
        }
        
        if (paired.size < 20) return null
        
        // Calculate Pearson correlation
        return pearsonCorrelation(
            paired.map { it.first },
            paired.map { it.second }
        )
    }
    
    /**
     * Pearson correlation coefficient
     */
    private fun pearsonCorrelation(x: List<Double>, y: List<Double>): Double {
        if (x.size != y.size || x.isEmpty()) return 0.0
        
        val n = x.size
        val meanX = x.average()
        val meanY = y.average()
        
        var numerator = 0.0
        var denomX = 0.0
        var denomY = 0.0
        
        for (i in x.indices) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            numerator += dx * dy
            denomX += dx * dx
            denomY += dy * dy
        }
        
        val denominator = sqrt(denomX) * sqrt(denomY)
        return if (denominator > 0) numerator / denominator else 0.0
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PORTFOLIO ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PortfolioCorrelationResult(
        val avgCorrelation: Double,          // Average pairwise correlation
        val maxCorrelation: Double,          // Highest correlation pair
        val maxCorrelationPair: Pair<PerpsMarket, PerpsMarket>?,
        val diversificationScore: Int,       // 0-100 (higher = more diversified)
        val riskLevel: String,               // LOW, MODERATE, HIGH, CONCENTRATED
        val warnings: List<String>,
        val groupExposure: Map<AssetGroup, Int>, // Count of positions per group
    )
    
    /**
     * Analyze correlation of current portfolio
     */
    fun analyzePortfolio(positions: List<PerpsMarket>): PortfolioCorrelationResult {
        if (positions.isEmpty()) {
            return PortfolioCorrelationResult(
                avgCorrelation = 0.0,
                maxCorrelation = 0.0,
                maxCorrelationPair = null,
                diversificationScore = 100,
                riskLevel = "LOW",
                warnings = emptyList(),
                groupExposure = emptyMap(),
            )
        }
        
        if (positions.size == 1) {
            return PortfolioCorrelationResult(
                avgCorrelation = 1.0,
                maxCorrelation = 1.0,
                maxCorrelationPair = Pair(positions[0], positions[0]),
                diversificationScore = 50,
                riskLevel = "MODERATE",
                warnings = listOf("Single position - consider diversifying"),
                groupExposure = mapOf(marketGroups[positions[0]] to 1).filterKeys { it != null } as Map<AssetGroup, Int>,
            )
        }
        
        // Calculate pairwise correlations
        val correlations = mutableListOf<Triple<PerpsMarket, PerpsMarket, Double>>()
        
        for (i in positions.indices) {
            for (j in i + 1 until positions.size) {
                val corr = getCorrelation(positions[i], positions[j])
                correlations.add(Triple(positions[i], positions[j], corr))
            }
        }
        
        val avgCorrelation = correlations.map { it.third }.average()
        val maxCorr = correlations.maxByOrNull { it.third }
        
        // Group exposure
        val groupExposure = positions
            .mapNotNull { marketGroups[it] }
            .groupBy { it }
            .mapValues { it.value.size }
        
        // Warnings
        val warnings = mutableListOf<String>()
        
        // Check for highly correlated pairs
        correlations.filter { it.third > 0.7 }.forEach { (m1, m2, corr) ->
            warnings.add("⚠️ ${m1.symbol} & ${m2.symbol} highly correlated (${(corr * 100).toInt()}%)")
        }
        
        // Check for overconcentration in a group
        groupExposure.filter { it.value >= 3 }.forEach { (group, count) ->
            warnings.add("⚠️ Concentrated in ${group.displayName}: $count positions")
        }
        
        // Diversification score (inverse of average correlation)
        val diversificationScore = ((1 - avgCorrelation) * 100).toInt().coerceIn(0, 100)
        
        // Risk level
        val riskLevel = when {
            avgCorrelation > 0.7 -> "CONCENTRATED"
            avgCorrelation > 0.5 -> "HIGH"
            avgCorrelation > 0.3 -> "MODERATE"
            else -> "LOW"
        }
        
        return PortfolioCorrelationResult(
            avgCorrelation = avgCorrelation,
            maxCorrelation = maxCorr?.third ?: 0.0,
            maxCorrelationPair = maxCorr?.let { Pair(it.first, it.second) },
            diversificationScore = diversificationScore,
            riskLevel = riskLevel,
            warnings = warnings,
            groupExposure = groupExposure,
        )
    }
    
    /**
     * Check if adding a new position would create overexposure
     */
    fun checkOverexposure(
        existingPositions: List<PerpsMarket>,
        newMarket: PerpsMarket,
        maxGroupExposure: Int = 3,
        maxCorrelation: Double = 0.8,
    ): OverexposureResult {
        val warnings = mutableListOf<String>()
        var shouldReduce = false
        var sizePenalty = 1.0  // Multiplier for position size
        
        // Check group exposure
        val newGroup = marketGroups[newMarket]
        if (newGroup != null) {
            val existingInGroup = existingPositions.count { marketGroups[it] == newGroup }
            if (existingInGroup >= maxGroupExposure) {
                warnings.add("Already have $existingInGroup ${newGroup.displayName} positions")
                shouldReduce = true
                sizePenalty *= 0.5
            }
        }
        
        // Check correlation with existing positions
        for (existing in existingPositions) {
            val corr = getCorrelation(existing, newMarket)
            if (corr > maxCorrelation) {
                warnings.add("${newMarket.symbol} highly correlated with ${existing.symbol} (${(corr * 100).toInt()}%)")
                shouldReduce = true
                sizePenalty *= (1 - corr / 2)  // Higher correlation = smaller position
            }
        }
        
        return OverexposureResult(
            isOverexposed = warnings.isNotEmpty(),
            shouldReduceSize = shouldReduce,
            sizePenalty = sizePenalty.coerceIn(0.25, 1.0),
            warnings = warnings,
        )
    }
    
    data class OverexposureResult(
        val isOverexposed: Boolean,
        val shouldReduceSize: Boolean,
        val sizePenalty: Double,      // Multiply position size by this
        val warnings: List<String>,
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun getMarketGroup(market: PerpsMarket): AssetGroup? = marketGroups[market]
    
    fun getGroupMembers(group: AssetGroup): List<PerpsMarket> {
        return marketGroups.filter { it.value == group }.keys.toList()
    }
    
    /**
     * Get correlation matrix as 2D map for UI display
     */
    fun getCorrelationMatrix(markets: List<PerpsMarket>): Map<Pair<PerpsMarket, PerpsMarket>, Double> {
        val matrix = mutableMapOf<Pair<PerpsMarket, PerpsMarket>, Double>()
        
        for (m1 in markets) {
            for (m2 in markets) {
                matrix[Pair(m1, m2)] = getCorrelation(m1, m2)
            }
        }
        
        return matrix
    }
}

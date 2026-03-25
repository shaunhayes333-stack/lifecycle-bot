package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * OrthogonalSignals - Ensures AI layers provide INDEPENDENT information
 * 
 * Problem: Multiple AI layers can be correlated (all look at price/volume).
 * Solution: Define signal categories and ensure each category is represented
 * by ONE primary signal, with correlation-aware weighting.
 * 
 * ORTHOGONAL SIGNAL CATEGORIES:
 * 1. PRICE_ACTION    - Price momentum, trends (ONE signal)
 * 2. VOLUME_FLOW     - Volume patterns, buy/sell pressure (ONE signal)
 * 3. LIQUIDITY       - Pool depth, slippage risk (ONE signal)
 * 4. ON_CHAIN        - Wallet movements, holder distribution (ONE signal)
 * 5. TEMPORAL        - Time-of-day, day-of-week patterns (ONE signal)
 * 6. NARRATIVE       - LLM-based text/name analysis (ONE signal - merged)
 * 7. PATTERN_MEMORY  - Historical similarity to winners (ONE signal)
 * 8. MARKET_CONTEXT  - Cross-token correlation, regime (ONE signal)
 * 
 * Each category gets ONE vote. No double-counting.
 */
object OrthogonalSignals {
    
    // ═══════════════════════════════════════════════════════════════════════
    // SIGNAL CATEGORIES - Mutually exclusive, collectively exhaustive
    // ═══════════════════════════════════════════════════════════════════════
    
    enum class SignalCategory {
        PRICE_ACTION,    // MomentumPredictorAI
        VOLUME_FLOW,     // Buy pressure, volume analysis
        LIQUIDITY,       // LiquidityDepthAI
        ON_CHAIN,        // WhaleTrackerAI + holder entropy
        TEMPORAL,        // TimeOptimizationAI
        NARRATIVE,       // Unified LLM (Groq + Gemini merged)
        PATTERN_MEMORY,  // TokenWinMemory
        MARKET_CONTEXT,  // MarketRegimeAI + cross-token correlation
    }
    
    /**
     * Signal with its category and independence score
     */
    data class OrthogonalSignal(
        val category: SignalCategory,
        val name: String,
        val score: Double,          // -100 to +100 (negative = bearish)
        val confidence: Double,     // 0-100
        val rawValue: Any? = null,  // Original value for debugging
    )
    
    /**
     * Combined orthogonal assessment
     */
    data class OrthogonalAssessment(
        val signals: Map<SignalCategory, OrthogonalSignal>,
        val compositeScore: Double,           // Weighted average
        val agreementRatio: Double,           // % of signals agreeing on direction
        val strongestBullish: SignalCategory?,
        val strongestBearish: SignalCategory?,
        val missingCategories: List<SignalCategory>,
    )
    
    // ═══════════════════════════════════════════════════════════════════════
    // CORRELATION MATRIX - Measured correlation between raw signals
    // Used to down-weight redundant information
    // Values: 0.0 = independent, 1.0 = perfectly correlated
    // ═══════════════════════════════════════════════════════════════════════
    
    private val correlationMatrix = mapOf(
        // Price action correlates with volume (both react to same events)
        Pair(SignalCategory.PRICE_ACTION, SignalCategory.VOLUME_FLOW) to 0.6,
        
        // Liquidity correlates with volume (high volume = deep liquidity usually)
        Pair(SignalCategory.LIQUIDITY, SignalCategory.VOLUME_FLOW) to 0.4,
        
        // On-chain is mostly independent (different data source entirely)
        Pair(SignalCategory.ON_CHAIN, SignalCategory.PRICE_ACTION) to 0.2,
        
        // Temporal is fully independent (time doesn't correlate with price)
        Pair(SignalCategory.TEMPORAL, SignalCategory.PRICE_ACTION) to 0.0,
        
        // Narrative is independent (text analysis vs numbers)
        Pair(SignalCategory.NARRATIVE, SignalCategory.PRICE_ACTION) to 0.1,
        
        // Pattern memory has some correlation (winners had good price action)
        Pair(SignalCategory.PATTERN_MEMORY, SignalCategory.PRICE_ACTION) to 0.3,
        
        // Market context correlates with price (regime affects individual tokens)
        Pair(SignalCategory.MARKET_CONTEXT, SignalCategory.PRICE_ACTION) to 0.5,
    )
    
    /**
     * Get correlation between two categories (symmetric)
     */
    fun getCorrelation(a: SignalCategory, b: SignalCategory): Double {
        if (a == b) return 1.0
        return correlationMatrix[Pair(a, b)] 
            ?: correlationMatrix[Pair(b, a)] 
            ?: 0.1  // Default low correlation for unmapped pairs
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // NEW ORTHOGONAL SIGNALS - Truly independent information
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Holder Distribution Entropy - Measures concentration vs distribution
     * High entropy = well distributed (good)
     * Low entropy = concentrated in few wallets (rug risk)
     * 
     * This is ORTHOGONAL to price/volume - it's pure on-chain structure
     */
    fun calculateHolderEntropy(
        topHolderPcts: List<Double>,  // e.g., [45.0, 15.0, 8.0, 5.0, 3.0]
    ): Double {
        if (topHolderPcts.isEmpty()) return 0.0
        
        // Normalize to probabilities
        val total = topHolderPcts.sum().coerceAtLeast(0.01)
        val probs = topHolderPcts.map { (it / total).coerceIn(0.001, 0.999) }
        
        // Shannon entropy: -Σ p(x) * log(p(x))
        val entropy = -probs.sumOf { p -> p * ln(p) }
        
        // Normalize to 0-100 scale (max entropy for 10 holders = ln(10) ≈ 2.3)
        val maxEntropy = ln(probs.size.toDouble().coerceAtLeast(2.0))
        return ((entropy / maxEntropy) * 100).coerceIn(0.0, 100.0)
    }
    
    /**
     * Cross-Token Correlation - Is this token moving WITH or AGAINST the market?
     * 
     * Tokens that pump AGAINST a down market = genuine demand (bullish)
     * Tokens that pump WITH an up market = riding the wave (neutral)
     * Tokens that dump harder than market = weak (bearish)
     * 
     * This is ORTHOGONAL - same price data but different interpretation
     */
    fun calculateCrossTokenCorrelation(
        tokenReturns: List<Double>,    // Last N candle returns for this token
        marketReturns: List<Double>,   // Last N candle returns for SOL/market
    ): Double {
        if (tokenReturns.size < 3 || marketReturns.size < 3) return 0.0
        
        val n = minOf(tokenReturns.size, marketReturns.size)
        val tokenSlice = tokenReturns.takeLast(n)
        val marketSlice = marketReturns.takeLast(n)
        
        // Pearson correlation coefficient
        val tokenMean = tokenSlice.average()
        val marketMean = marketSlice.average()
        
        var numerator = 0.0
        var tokenVar = 0.0
        var marketVar = 0.0
        
        for (i in 0 until n) {
            val tokenDev = tokenSlice[i] - tokenMean
            val marketDev = marketSlice[i] - marketMean
            numerator += tokenDev * marketDev
            tokenVar += tokenDev * tokenDev
            marketVar += marketDev * marketDev
        }
        
        val denominator = sqrt(tokenVar) * sqrt(marketVar)
        if (denominator < 0.0001) return 0.0
        
        // Correlation: -1 to +1, scale to -100 to +100
        return ((numerator / denominator) * 100).coerceIn(-100.0, 100.0)
    }
    
    /**
     * Contract Age Pattern Score - Fresh vs established tokens
     * 
     * Very fresh (<5 min) = high risk but high reward potential
     * Sweet spot (5-30 min) = enough data, still early
     * Established (>1 hour) = safer but less upside
     * 
     * ORTHOGONAL to price - age doesn't predict direction, just risk profile
     */
    fun calculateAgePatternScore(
        ageMinutes: Int,
        hasGraduated: Boolean,  // Graduated from pump.fun
    ): Double {
        val baseScore = when {
            ageMinutes < 2 -> 30.0    // Too fresh, no data
            ageMinutes < 5 -> 60.0    // Early but risky
            ageMinutes < 15 -> 90.0   // Sweet spot
            ageMinutes < 30 -> 80.0   // Good
            ageMinutes < 60 -> 60.0   // Okay
            ageMinutes < 180 -> 40.0  // Getting stale
            else -> 20.0              // Old news
        }
        
        // Graduation bonus (survived pump.fun)
        val gradBonus = if (hasGraduated) 15.0 else 0.0
        
        return (baseScore + gradBonus).coerceIn(0.0, 100.0)
    }
    
    /**
     * Volume Anomaly Score - Is volume unusual for this token's age/mcap?
     * 
     * High volume relative to mcap = genuine interest (bullish)
     * Low volume relative to mcap = illiquid, slippage risk (bearish)
     * 
     * ORTHOGONAL to raw volume - it's volume RELATIVE to context
     */
    fun calculateVolumeAnomaly(
        volume24h: Double,
        marketCap: Double,
        ageMinutes: Int,
    ): Double {
        if (marketCap <= 0 || volume24h <= 0) return 0.0
        
        // Volume-to-mcap ratio (healthy is 0.1-0.5 for meme coins)
        val volumeRatio = volume24h / marketCap
        
        // Age-adjusted expectation (newer tokens should have higher ratio)
        val expectedRatio = when {
            ageMinutes < 30 -> 0.5   // New tokens: expect high turnover
            ageMinutes < 120 -> 0.3
            ageMinutes < 360 -> 0.2
            else -> 0.1
        }
        
        // Score based on deviation from expectation
        val deviation = volumeRatio / expectedRatio
        
        return when {
            deviation > 3.0 -> 100.0  // Extremely high activity
            deviation > 2.0 -> 85.0
            deviation > 1.5 -> 70.0
            deviation > 1.0 -> 55.0   // Meeting expectations
            deviation > 0.5 -> 40.0
            deviation > 0.25 -> 25.0
            else -> 10.0              // Dead volume
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // CORRELATION-AWARE WEIGHTING
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate weights that account for signal correlation.
     * Correlated signals get reduced weight to avoid double-counting.
     */
    fun calculateCorrelationAwareWeights(
        signals: Map<SignalCategory, OrthogonalSignal>
    ): Map<SignalCategory, Double> {
        val categories = signals.keys.toList()
        val weights = mutableMapOf<SignalCategory, Double>()
        
        for (cat in categories) {
            // Start with base weight of 1.0
            var weight = 1.0
            
            // Reduce weight based on correlation with OTHER present signals
            for (otherCat in categories) {
                if (cat != otherCat) {
                    val correlation = getCorrelation(cat, otherCat)
                    // Reduce weight by half the correlation
                    // (if two signals are 0.6 correlated, each loses 0.3 weight)
                    weight -= correlation * 0.5
                }
            }
            
            // Also weight by signal confidence
            val confidence = signals[cat]?.confidence ?: 50.0
            weight *= (confidence / 100.0)
            
            weights[cat] = weight.coerceAtLeast(0.1)  // Minimum 10% weight
        }
        
        // Normalize weights to sum to 1.0
        val totalWeight = weights.values.sum()
        return weights.mapValues { it.value / totalWeight }
    }
    
    /**
     * Compute final orthogonal assessment from all signals
     */
    fun computeAssessment(
        signals: Map<SignalCategory, OrthogonalSignal>
    ): OrthogonalAssessment {
        if (signals.isEmpty()) {
            return OrthogonalAssessment(
                signals = emptyMap(),
                compositeScore = 0.0,
                agreementRatio = 0.0,
                strongestBullish = null,
                strongestBearish = null,
                missingCategories = SignalCategory.values().toList()
            )
        }
        
        // Get correlation-aware weights
        val weights = calculateCorrelationAwareWeights(signals)
        
        // Calculate weighted composite score
        var compositeScore = 0.0
        for ((cat, signal) in signals) {
            val weight = weights[cat] ?: 0.0
            compositeScore += signal.score * weight
        }
        
        // Calculate agreement ratio (what % of signals agree on direction?)
        val bullishCount = signals.values.count { it.score > 10 }
        val bearishCount = signals.values.count { it.score < -10 }
        val totalOpinionated = bullishCount + bearishCount
        val agreementRatio = if (totalOpinionated > 0) {
            maxOf(bullishCount, bearishCount).toDouble() / totalOpinionated
        } else 0.5
        
        // Find strongest signals
        val strongestBullish = signals.entries
            .filter { it.value.score > 0 }
            .maxByOrNull { it.value.score * (it.value.confidence / 100.0) }
            ?.key
            
        val strongestBearish = signals.entries
            .filter { it.value.score < 0 }
            .minByOrNull { it.value.score * (it.value.confidence / 100.0) }
            ?.key
        
        // Find missing categories
        val presentCategories = signals.keys
        val missingCategories = SignalCategory.values().filter { it !in presentCategories }
        
        return OrthogonalAssessment(
            signals = signals,
            compositeScore = compositeScore,
            agreementRatio = agreementRatio,
            strongestBullish = strongestBullish,
            strongestBearish = strongestBearish,
            missingCategories = missingCategories,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // SIGNAL COLLECTORS - Map existing AI layers to orthogonal categories
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Collect all orthogonal signals for a token.
     * This is the main entry point - call this instead of individual AI layers.
     */
    fun collectSignals(
        ts: TokenState,
        // Existing AI layer results (we'll map them to categories)
        momentumScore: Double? = null,
        liquidityScore: Double? = null,
        whaleSignal: Double? = null,
        timeScore: Double? = null,
        narrativeScore: Double? = null,
        patternMatchScore: Double? = null,
        marketRegimeScore: Double? = null,
        // Additional data for new orthogonal signals
        topHolderPcts: List<Double> = emptyList(),
        tokenReturns: List<Double> = emptyList(),
        marketReturns: List<Double> = emptyList(),
    ): OrthogonalAssessment {
        
        val signals = mutableMapOf<SignalCategory, OrthogonalSignal>()
        
        // 1. PRICE_ACTION - from MomentumPredictorAI
        momentumScore?.let {
            signals[SignalCategory.PRICE_ACTION] = OrthogonalSignal(
                category = SignalCategory.PRICE_ACTION,
                name = "MomentumPredictor",
                score = (it - 50) * 2,  // Convert 0-100 to -100 to +100
                confidence = 70.0,
                rawValue = it,
            )
        }
        
        // 2. VOLUME_FLOW - calculated from token state
        val volumeAnomaly = calculateVolumeAnomaly(
            volume24h = ts.history.lastOrNull()?.volume24h ?: 0.0,
            marketCap = ts.history.lastOrNull()?.marketCap ?: 0.0,
            ageMinutes = ((System.currentTimeMillis() - ts.addedToWatchlistAt) / 60000).toInt(),
        )
        signals[SignalCategory.VOLUME_FLOW] = OrthogonalSignal(
            category = SignalCategory.VOLUME_FLOW,
            name = "VolumeAnomaly",
            score = (volumeAnomaly - 50) * 2,
            confidence = 65.0,
            rawValue = volumeAnomaly,
        )
        
        // 3. LIQUIDITY - from LiquidityDepthAI
        liquidityScore?.let {
            signals[SignalCategory.LIQUIDITY] = OrthogonalSignal(
                category = SignalCategory.LIQUIDITY,
                name = "LiquidityDepth",
                score = (it - 50) * 2,
                confidence = 75.0,
                rawValue = it,
            )
        }
        
        // 4. ON_CHAIN - Whale signal + holder entropy (combined)
        val holderEntropy = if (topHolderPcts.isNotEmpty()) {
            calculateHolderEntropy(topHolderPcts)
        } else null
        
        val onChainScore = when {
            whaleSignal != null && holderEntropy != null -> {
                // Combine whale signal (50% weight) with entropy (50% weight)
                (whaleSignal * 0.5) + (holderEntropy * 0.5)
            }
            whaleSignal != null -> whaleSignal
            holderEntropy != null -> holderEntropy
            else -> null
        }
        
        onChainScore?.let {
            signals[SignalCategory.ON_CHAIN] = OrthogonalSignal(
                category = SignalCategory.ON_CHAIN,
                name = "OnChainAnalysis",
                score = (it - 50) * 2,
                confidence = 80.0,  // High confidence - hard data
                rawValue = mapOf("whale" to whaleSignal, "entropy" to holderEntropy),
            )
        }
        
        // 5. TEMPORAL - from TimeOptimizationAI
        timeScore?.let {
            signals[SignalCategory.TEMPORAL] = OrthogonalSignal(
                category = SignalCategory.TEMPORAL,
                name = "TimeOptimization",
                score = (it - 50) * 2,
                confidence = 60.0,  // Lower confidence - historical patterns
                rawValue = it,
            )
        }
        
        // 6. NARRATIVE - from unified LLM (Groq + Gemini merged)
        narrativeScore?.let {
            signals[SignalCategory.NARRATIVE] = OrthogonalSignal(
                category = SignalCategory.NARRATIVE,
                name = "UnifiedNarrative",
                score = (it - 50) * 2,
                confidence = 55.0,  // LLMs can hallucinate
                rawValue = it,
            )
        }
        
        // 7. PATTERN_MEMORY - from TokenWinMemory
        patternMatchScore?.let {
            signals[SignalCategory.PATTERN_MEMORY] = OrthogonalSignal(
                category = SignalCategory.PATTERN_MEMORY,
                name = "PatternMemory",
                score = (it - 50) * 2,
                confidence = 65.0,
                rawValue = it,
            )
        }
        
        // 8. MARKET_CONTEXT - Regime + cross-token correlation
        val crossCorrelation = if (tokenReturns.isNotEmpty() && marketReturns.isNotEmpty()) {
            calculateCrossTokenCorrelation(tokenReturns, marketReturns)
        } else null
        
        val marketContextScore = when {
            marketRegimeScore != null && crossCorrelation != null -> {
                // If token is negatively correlated with market AND regime is bearish,
                // that's actually bullish for the token (it's independent)
                val independenceBonus = if (crossCorrelation < -20) 10.0 else 0.0
                (marketRegimeScore * 0.6) + ((50 - abs(crossCorrelation) / 2) * 0.4) + independenceBonus
            }
            marketRegimeScore != null -> marketRegimeScore
            else -> null
        }
        
        marketContextScore?.let {
            signals[SignalCategory.MARKET_CONTEXT] = OrthogonalSignal(
                category = SignalCategory.MARKET_CONTEXT,
                name = "MarketContext",
                score = (it - 50) * 2,
                confidence = 70.0,
                rawValue = mapOf("regime" to marketRegimeScore, "correlation" to crossCorrelation),
            )
        }
        
        return computeAssessment(signals)
    }
    
    /**
     * Quick diagnostic - shows which signals are truly contributing
     */
    fun diagnose(assessment: OrthogonalAssessment): String {
        val sb = StringBuilder()
        sb.appendLine("═══ ORTHOGONAL SIGNAL DIAGNOSIS ═══")
        sb.appendLine("Composite Score: ${assessment.compositeScore.toInt()}")
        sb.appendLine("Agreement Ratio: ${(assessment.agreementRatio * 100).toInt()}%")
        sb.appendLine("Missing Categories: ${assessment.missingCategories.joinToString(", ")}")
        sb.appendLine("")
        
        val weights = calculateCorrelationAwareWeights(assessment.signals)
        
        for ((cat, signal) in assessment.signals.entries.sortedByDescending { abs(it.value.score) }) {
            val weight = weights[cat] ?: 0.0
            val contribution = signal.score * weight
            val dir = if (signal.score > 0) "📈" else if (signal.score < 0) "📉" else "➡️"
            sb.appendLine("$dir ${cat.name}: ${signal.score.toInt()} × ${(weight * 100).toInt()}% = ${contribution.toInt()}")
        }
        
        assessment.strongestBullish?.let { sb.appendLine("\n✅ Strongest Bullish: $it") }
        assessment.strongestBearish?.let { sb.appendLine("❌ Strongest Bearish: $it") }
        
        return sb.toString()
    }
}

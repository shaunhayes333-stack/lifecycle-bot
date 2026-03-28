package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * V3.2 Order Flow Imbalance AI
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Detects buy/sell pressure imbalance BEFORE price moves:
 * 
 * IMBALANCE TYPES:
 * - STRONG_BUY_PRESSURE:  Buy vol >> Sell vol, price likely to rise
 * - BUY_PRESSURE:         Moderate buy dominance
 * - BALANCED:             Equal pressure, no edge
 * - SELL_PRESSURE:        Moderate sell dominance  
 * - STRONG_SELL_PRESSURE: Sell vol >> Buy vol, price likely to drop
 * 
 * SPECIAL PATTERNS:
 * - ABSORPTION: Large buys being absorbed (sellers soaking up demand) = BEARISH
 * - EXHAUSTION: Buying slowing after pump = top forming
 * - ACCUMULATION: Quiet buying in narrow range = bullish setup
 * - DISTRIBUTION: Hidden selling in narrow range = bearish setup
 * 
 * CROSS-TALK INTEGRATION:
 * - WhaleTrackerAI: Whale sells + distribution = COORDINATED DUMP
 * - LiquidityDepthAI: Sell pressure + thin liquidity = DANGER
 * - MomentumPredictorAI: Strong buy flow + momentum = PUMP CONFIRMATION
 * 
 * DATA SOURCES:
 * - Volume delta (buy vol - sell vol) from candle data
 * - Trade count delta if available
 * - Price vs volume divergence detection
 */
object OrderFlowImbalanceAI {
    
    private const val TAG = "OrderFlowAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class FlowState {
        STRONG_BUY_PRESSURE,   // Delta > +40%
        BUY_PRESSURE,          // Delta +15% to +40%
        BALANCED,              // Delta -15% to +15%
        SELL_PRESSURE,         // Delta -40% to -15%
        STRONG_SELL_PRESSURE   // Delta < -40%
    }
    
    enum class FlowPattern {
        NORMAL,           // Standard flow
        ABSORPTION,       // Large buys being absorbed (bearish)
        EXHAUSTION,       // Buying slowing after pump (top)
        ACCUMULATION,     // Quiet buying in range (bullish)
        DISTRIBUTION,     // Hidden selling in range (bearish)
        CLIMAX_BUY,       // Extreme buy volume spike (often top)
        CLIMAX_SELL,      // Extreme sell volume spike (often bottom)
        DIVERGENCE_BULL,  // Price down but buying increasing
        DIVERGENCE_BEAR   // Price up but selling increasing
    }
    
    data class FlowSignal(
        val state: FlowState,
        val pattern: FlowPattern,
        val buyVolumePct: Double,        // Buy volume as % of total
        val sellVolumePct: Double,       // Sell volume as % of total
        val deltaScore: Double,          // -100 to +100
        val absorptionLevel: Double,     // 0-100, higher = more absorption
        val cumulativeDelta: Double,     // Running sum of deltas
        val entryBoost: Int,             // Score adjustment
        val exitUrgency: Double,         // 0-100, higher = exit faster
        val reason: String
    )
    
    // Per-token flow tracking
    private data class FlowHistory(
        val deltas: ArrayDeque<Double> = ArrayDeque(100),
        val volumes: ArrayDeque<Double> = ArrayDeque(100),
        val prices: ArrayDeque<Double> = ArrayDeque(100),
        var cumulativeDelta: Double = 0.0,
        var lastState: FlowState = FlowState.BALANCED,
        var absorptionCandles: Int = 0,
        var lastUpdate: Long = 0
    )
    private val tokenFlow = ConcurrentHashMap<String, FlowHistory>()
    
    // Stats
    private var totalAnalyses = 0
    private var absorptionsDetected = 0
    private var divergencesDetected = 0
    private var climaxesDetected = 0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze order flow for a token.
     * 
     * @param buyVolumes Recent buy volumes per candle
     * @param sellVolumes Recent sell volumes per candle (or total - buy)
     * @param prices Recent close prices
     */
    fun analyze(
        mint: String,
        symbol: String,
        buyVolumes: List<Double>,
        sellVolumes: List<Double>,
        prices: List<Double>
    ): FlowSignal {
        totalAnalyses++
        
        if (buyVolumes.size < 3 || sellVolumes.size < 3) {
            return defaultSignal("Insufficient data")
        }
        
        val history = tokenFlow.getOrPut(mint) { FlowHistory() }
        history.lastUpdate = System.currentTimeMillis()
        
        // Calculate volume delta for each candle
        val deltas = buyVolumes.zip(sellVolumes).map { (buy, sell) ->
            val total = buy + sell
            if (total > 0) ((buy - sell) / total) * 100.0 else 0.0
        }
        
        // Update history
        deltas.forEach { delta ->
            history.deltas.addLast(delta)
            if (history.deltas.size > 100) history.deltas.removeFirst()
            history.cumulativeDelta += delta
        }
        
        prices.forEach { price ->
            history.prices.addLast(price)
            if (history.prices.size > 100) history.prices.removeFirst()
        }
        
        (buyVolumes.zip(sellVolumes)).forEach { (buy, sell) ->
            history.volumes.addLast(buy + sell)
            if (history.volumes.size > 100) history.volumes.removeFirst()
        }
        
        // Current state
        val recentDeltas = history.deltas.toList().takeLast(5)
        val avgDelta = recentDeltas.average()
        val state = classifyState(avgDelta)
        
        // Detect patterns
        val pattern = detectPattern(history, avgDelta, prices)
        
        // Calculate metrics
        val totalRecent = buyVolumes.takeLast(5).sum() + sellVolumes.takeLast(5).sum()
        val buyPct = if (totalRecent > 0) (buyVolumes.takeLast(5).sum() / totalRecent) * 100.0 else 50.0
        val sellPct = 100.0 - buyPct
        
        // Absorption level
        val absorptionLevel = calculateAbsorption(history, buyVolumes.takeLast(5), prices.takeLast(5))
        
        // Track absorption
        if (absorptionLevel > 60) {
            history.absorptionCandles++
            if (history.absorptionCandles >= 3) {
                absorptionsDetected++
            }
        } else {
            history.absorptionCandles = 0
        }
        
        history.lastState = state
        
        // Calculate trading signals
        val entryBoost = calculateEntryBoost(state, pattern, absorptionLevel)
        val exitUrgency = calculateExitUrgency(state, pattern, absorptionLevel)
        
        val reason = buildReason(state, pattern, avgDelta, absorptionLevel)
        
        if (pattern in listOf(FlowPattern.ABSORPTION, FlowPattern.DISTRIBUTION, FlowPattern.CLIMAX_SELL)) {
            ErrorLogger.warn(TAG, "⚠️ FLOW WARNING: $symbol | ${pattern.name} | delta=${avgDelta.toInt()}%")
        }
        
        return FlowSignal(
            state = state,
            pattern = pattern,
            buyVolumePct = buyPct,
            sellVolumePct = sellPct,
            deltaScore = avgDelta,
            absorptionLevel = absorptionLevel,
            cumulativeDelta = history.cumulativeDelta,
            entryBoost = entryBoost,
            exitUrgency = exitUrgency,
            reason = reason
        )
    }
    
    /**
     * Quick flow check for a token.
     */
    fun getFlowState(mint: String): FlowState {
        return tokenFlow[mint]?.lastState ?: FlowState.BALANCED
    }
    
    /**
     * Check cumulative delta trend.
     */
    fun getCumulativeDelta(mint: String): Double {
        return tokenFlow[mint]?.cumulativeDelta ?: 0.0
    }
    
    /**
     * Check if absorption is happening (bearish).
     */
    fun isAbsorbing(mint: String): Boolean {
        return (tokenFlow[mint]?.absorptionCandles ?: 0) >= 3
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun classifyState(delta: Double): FlowState {
        return when {
            delta > 40 -> FlowState.STRONG_BUY_PRESSURE
            delta > 15 -> FlowState.BUY_PRESSURE
            delta > -15 -> FlowState.BALANCED
            delta > -40 -> FlowState.SELL_PRESSURE
            else -> FlowState.STRONG_SELL_PRESSURE
        }
    }
    
    private fun detectPattern(history: FlowHistory, delta: Double, prices: List<Double>): FlowPattern {
        val recentDeltas = history.deltas.toList().takeLast(10)
        val recentPrices = history.prices.toList().takeLast(10)
        val recentVolumes = history.volumes.toList().takeLast(10)
        
        if (recentDeltas.size < 5 || recentPrices.size < 5) return FlowPattern.NORMAL
        
        // Absorption: High buy delta but price not moving up
        val priceChange = if (recentPrices.first() > 0) {
            ((recentPrices.last() - recentPrices.first()) / recentPrices.first()) * 100
        } else 0.0
        
        if (delta > 25 && priceChange < 2) {
            return FlowPattern.ABSORPTION
        }
        
        // Climax detection: Extreme volume with extreme delta
        val avgVol = recentVolumes.dropLast(2).average()
        val recentVol = recentVolumes.takeLast(2).average()
        if (avgVol > 0 && recentVol / avgVol > 3.0) {
            climaxesDetected++
            return if (delta > 30) FlowPattern.CLIMAX_BUY else if (delta < -30) FlowPattern.CLIMAX_SELL else FlowPattern.NORMAL
        }
        
        // Exhaustion: Delta declining after being positive
        val firstHalfDelta = recentDeltas.take(5).average()
        val secondHalfDelta = recentDeltas.takeLast(5).average()
        if (firstHalfDelta > 20 && secondHalfDelta < firstHalfDelta - 15) {
            return FlowPattern.EXHAUSTION
        }
        
        // Divergence: Price and delta moving opposite
        val priceTrend = if (recentPrices.size >= 5) {
            recentPrices.takeLast(3).average() - recentPrices.take(3).average()
        } else 0.0
        
        val deltaTrend = if (recentDeltas.size >= 5) {
            recentDeltas.takeLast(3).average() - recentDeltas.take(3).average()
        } else 0.0
        
        if (priceTrend < 0 && deltaTrend > 10) {
            divergencesDetected++
            return FlowPattern.DIVERGENCE_BULL  // Price down but buying increasing
        }
        
        if (priceTrend > 0 && deltaTrend < -10) {
            divergencesDetected++
            return FlowPattern.DIVERGENCE_BEAR  // Price up but selling increasing
        }
        
        // Accumulation: Low volatility, slightly positive delta
        val priceRange = recentPrices.maxOrNull()?.minus(recentPrices.minOrNull() ?: 0.0) ?: 0.0
        val avgPrice = recentPrices.average()
        val rangeRatio = if (avgPrice > 0) priceRange / avgPrice * 100 else 0.0
        
        if (rangeRatio < 5 && delta in 5.0..25.0) {
            return FlowPattern.ACCUMULATION
        }
        
        // Distribution: Low volatility, slightly negative delta
        if (rangeRatio < 5 && delta in -25.0..-5.0) {
            return FlowPattern.DISTRIBUTION
        }
        
        return FlowPattern.NORMAL
    }
    
    private fun calculateAbsorption(history: FlowHistory, recentBuys: List<Double>, recentPrices: List<Double>): Double {
        if (recentBuys.size < 3 || recentPrices.size < 3) return 0.0
        
        val buyTotal = recentBuys.sum()
        val priceChange = if (recentPrices.first() > 0) {
            ((recentPrices.last() - recentPrices.first()) / recentPrices.first()) * 100
        } else 0.0
        
        // High buying but low price movement = absorption
        // Normalize buy volume against expected price movement
        val expectedMove = buyTotal / 1000.0  // Simplified expectation
        val actualMove = priceChange
        
        if (expectedMove > 0 && actualMove < expectedMove * 0.3) {
            // Less than 30% of expected move = absorption
            return ((1.0 - (actualMove / expectedMove).coerceAtLeast(0.0)) * 100).coerceIn(0.0, 100.0)
        }
        
        return 0.0
    }
    
    private fun calculateEntryBoost(state: FlowState, pattern: FlowPattern, absorptionLevel: Double): Int {
        var boost = 0
        
        // State-based adjustment
        boost += when (state) {
            FlowState.STRONG_BUY_PRESSURE -> 8
            FlowState.BUY_PRESSURE -> 4
            FlowState.BALANCED -> 0
            FlowState.SELL_PRESSURE -> -6
            FlowState.STRONG_SELL_PRESSURE -> -12
        }
        
        // Pattern adjustments
        boost += when (pattern) {
            FlowPattern.ACCUMULATION -> 6
            FlowPattern.DIVERGENCE_BULL -> 5
            FlowPattern.ABSORPTION -> -8   // Buys being absorbed = bad
            FlowPattern.DISTRIBUTION -> -8
            FlowPattern.EXHAUSTION -> -6
            FlowPattern.CLIMAX_BUY -> -4   // Often a top
            FlowPattern.CLIMAX_SELL -> 4   // Often a bottom
            FlowPattern.DIVERGENCE_BEAR -> -6
            FlowPattern.NORMAL -> 0
        }
        
        // Absorption penalty
        if (absorptionLevel > 50) {
            boost -= (absorptionLevel / 20).toInt()
        }
        
        return boost.coerceIn(-20, 15)
    }
    
    private fun calculateExitUrgency(state: FlowState, pattern: FlowPattern, absorptionLevel: Double): Double {
        var urgency = 0.0
        
        // State urgency
        urgency += when (state) {
            FlowState.STRONG_SELL_PRESSURE -> 40.0
            FlowState.SELL_PRESSURE -> 20.0
            else -> 0.0
        }
        
        // Pattern urgency
        urgency += when (pattern) {
            FlowPattern.DISTRIBUTION -> 30.0
            FlowPattern.ABSORPTION -> 25.0
            FlowPattern.EXHAUSTION -> 20.0
            FlowPattern.DIVERGENCE_BEAR -> 25.0
            FlowPattern.CLIMAX_BUY -> 15.0  // Potential top
            else -> 0.0
        }
        
        // Absorption adds urgency
        if (absorptionLevel > 50) {
            urgency += absorptionLevel * 0.3
        }
        
        return urgency.coerceIn(0.0, 100.0)
    }
    
    private fun buildReason(state: FlowState, pattern: FlowPattern, delta: Double, absorption: Double): String {
        val parts = mutableListOf<String>()
        
        parts += "${state.name.lowercase().replace("_", " ")} (${delta.toInt()}%)"
        
        if (pattern != FlowPattern.NORMAL) {
            parts += pattern.name.lowercase().replace("_", " ")
        }
        
        if (absorption > 40) {
            parts += "absorption=${absorption.toInt()}"
        }
        
        return parts.joinToString(" | ")
    }
    
    private fun defaultSignal(reason: String): FlowSignal {
        return FlowSignal(
            state = FlowState.BALANCED,
            pattern = FlowPattern.NORMAL,
            buyVolumePct = 50.0,
            sellVolumePct = 50.0,
            deltaScore = 0.0,
            absorptionLevel = 0.0,
            cumulativeDelta = 0.0,
            entryBoost = 0,
            exitUrgency = 0.0,
            reason = reason
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SCORING MODULE INTERFACE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Score for UnifiedScorer integration.
     */
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent {
        // Get volume data from extra
        @Suppress("UNCHECKED_CAST")
        val buyVolumes = candidate.extra["buyVolumes"] as? List<Double> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val sellVolumes = candidate.extra["sellVolumes"] as? List<Double> ?: emptyList()
        @Suppress("UNCHECKED_CAST")
        val prices = candidate.extra["recentCloses"] as? List<Double> ?: emptyList()
        
        val signal = analyze(candidate.mint, candidate.symbol, buyVolumes, sellVolumes, prices)
        
        return ScoreComponent(
            name = "orderflow",
            value = signal.entryBoost,
            reason = signal.reason
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            put("totalAnalyses", totalAnalyses)
            put("absorptionsDetected", absorptionsDetected)
            put("divergencesDetected", divergencesDetected)
            put("climaxesDetected", climaxesDetected)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            totalAnalyses = json.optInt("totalAnalyses", 0)
            absorptionsDetected = json.optInt("absorptionsDetected", 0)
            divergencesDetected = json.optInt("divergencesDetected", 0)
            climaxesDetected = json.optInt("climaxesDetected", 0)
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Load error: ${e.message}")
        }
    }
    
    fun getStats(): String {
        return "OrderFlowAI: $totalAnalyses analyses | absorptions=$absorptionsDetected divergences=$divergencesDetected climaxes=$climaxesDetected"
    }
    
    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleThreshold = 30 * 60 * 1000L
        tokenFlow.entries.removeIf { (_, v) -> now - v.lastUpdate > staleThreshold }
    }
}

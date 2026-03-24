package com.lifecyclebot.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MomentumPredictorAI - Predict Token Pumps Before They Happen
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 * CONCEPT: Detect early momentum signals that precede big pumps
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Big pumps don't happen randomly. They have SIGNATURES:
 * 
 * 1. VOLUME ACCELERATION
 *    - Volume increasing faster than price
 *    - Buyers absorbing all sells without price dropping
 * 
 * 2. COILING PATTERN
 *    - Price range tightening (consolidation)
 *    - Volume building up (accumulation)
 *    - Like a spring being compressed before release
 * 
 * 3. SMART MONEY FOOTPRINT
 *    - Large buys in small chunks (iceberg orders)
 *    - Consistent buying over time (accumulation)
 * 
 * 4. SOCIAL VELOCITY
 *    - Mentions accelerating on Twitter/Telegram
 *    - Not yet viral, but building momentum
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 * HOW IT WORKS:
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * 1. COLLECT DATA
 *    - Price/volume every minute for first 30 mins
 *    - Buy vs sell pressure
 *    - Transaction sizes
 * 
 * 2. DETECT PATTERNS
 *    - Volume acceleration score
 *    - Coiling score (price compression)
 *    - Accumulation score
 * 
 * 3. COMBINE INTO MOMENTUM SCORE
 *    - 0-100 scale
 *    - >70 = STRONG MOMENTUM (likely pump)
 *    - >50 = BUILDING MOMENTUM (watch closely)
 *    - <30 = WEAK/DISTRIBUTION (avoid)
 * 
 * 4. LEARN FROM OUTCOMES
 *    - Track which patterns led to pumps
 *    - Adjust weights based on results
 */
object MomentumPredictorAI {
    
    private const val TAG = "MomentumPredictor"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA COLLECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class PricePoint(
        val timestamp: Long,
        val price: Double,
        val volume: Double,
        val buyPressure: Double,    // 0-100
        val txCount: Int,
    )
    
    data class TokenMomentum(
        val mint: String,
        val symbol: String,
        val priceHistory: MutableList<PricePoint> = mutableListOf(),
        var firstSeenMs: Long = System.currentTimeMillis(),
        var lastUpdateMs: Long = System.currentTimeMillis(),
        var peakPrice: Double = 0.0,
        var peakTime: Long = 0L,
        
        // Calculated scores (0-100)
        var volumeAccelerationScore: Double = 0.0,
        var coilingScore: Double = 0.0,
        var accumulationScore: Double = 0.0,
        var momentumScore: Double = 0.0,
        
        // Pattern flags
        var hasVolumeAcceleration: Boolean = false,
        var hasCoilingPattern: Boolean = false,
        var hasAccumulation: Boolean = false,
        var hasMomentumBreakout: Boolean = false,
        
        // Prediction
        var prediction: MomentumPrediction = MomentumPrediction.NEUTRAL,
        var predictionConfidence: Double = 0.0,
    )
    
    enum class MomentumPrediction(val label: String, val entryBonus: Double) {
        STRONG_PUMP("STRONG PUMP LIKELY", 20.0),       // Add 20 to entry score
        PUMP_BUILDING("PUMP BUILDING", 10.0),          // Add 10 to entry score
        NEUTRAL("NEUTRAL", 0.0),
        WEAK("WEAK MOMENTUM", -10.0),                  // Subtract 10 from entry score
        DISTRIBUTION("DISTRIBUTION", -20.0),           // Subtract 20 from entry score
    }
    
    // Token momentum tracking
    private val tokenMomentum = ConcurrentHashMap<String, TokenMomentum>()
    
    // Learning weights
    private data class Weights(
        var volumeAccelWeight: Double = 0.35,
        var coilingWeight: Double = 0.25,
        var accumulationWeight: Double = 0.25,
        var buyPressureWeight: Double = 0.15,
        var totalPredictions: Int = 0,
        var correctPredictions: Int = 0,
    )
    private var weights = Weights()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA RECORDING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record a price/volume point for a token.
     * Call this every tick for tokens on watchlist.
     */
    fun recordPricePoint(
        mint: String,
        symbol: String,
        price: Double,
        volume: Double,
        buyPressure: Double,
        txCount: Int,
    ) {
        val momentum = tokenMomentum.getOrPut(mint) {
            TokenMomentum(mint = mint, symbol = symbol)
        }
        
        val point = PricePoint(
            timestamp = System.currentTimeMillis(),
            price = price,
            volume = volume,
            buyPressure = buyPressure,
            txCount = txCount,
        )
        
        momentum.priceHistory.add(point)
        momentum.lastUpdateMs = System.currentTimeMillis()
        
        // Track peak
        if (price > momentum.peakPrice) {
            momentum.peakPrice = price
            momentum.peakTime = System.currentTimeMillis()
        }
        
        // Keep only last 60 data points (30 mins at 30s intervals)
        while (momentum.priceHistory.size > 60) {
            momentum.priceHistory.removeAt(0)
        }
        
        // Recalculate scores if we have enough data (5+ points)
        if (momentum.priceHistory.size >= 5) {
            calculateMomentumScores(momentum)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PATTERN DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun calculateMomentumScores(momentum: TokenMomentum) {
        val history = momentum.priceHistory
        if (history.size < 5) return
        
        // 1. VOLUME ACCELERATION
        // Compare recent volume to earlier volume
        val recentVol = history.takeLast(3).map { it.volume }.average()
        val earlierVol = history.take(3).map { it.volume }.average()
        val volAcceleration = if (earlierVol > 0) (recentVol / earlierVol - 1) * 100 else 0.0
        momentum.volumeAccelerationScore = (volAcceleration * 2).coerceIn(0.0, 100.0)
        momentum.hasVolumeAcceleration = volAcceleration > 50  // 50%+ increase
        
        // 2. COILING PATTERN (price compression)
        // Calculate price range as % of average price
        val prices = history.map { it.price }
        val avgPrice = prices.average()
        val priceRange = (prices.maxOrNull() ?: 0.0) - (prices.minOrNull() ?: 0.0)
        val rangePercent = if (avgPrice > 0) priceRange / avgPrice * 100 else 0.0
        
        // Coiling = tight range with volume = spring compression
        val avgVol = history.map { it.volume }.average()
        val coilingRaw = if (rangePercent > 0) avgVol / rangePercent else 0.0
        momentum.coilingScore = (coilingRaw * 10).coerceIn(0.0, 100.0)
        momentum.hasCoilingPattern = rangePercent < 10 && avgVol > 1000  // <10% range, decent volume
        
        // 3. ACCUMULATION PATTERN
        // Consistent buying pressure over time
        val buyPressures = history.map { it.buyPressure }
        val avgBuyPressure = buyPressures.average()
        val consistentBuying = buyPressures.count { it > 50 }.toDouble() / buyPressures.size * 100
        momentum.accumulationScore = ((avgBuyPressure + consistentBuying) / 2).coerceIn(0.0, 100.0)
        momentum.hasAccumulation = avgBuyPressure > 55 && consistentBuying > 60
        
        // 4. MOMENTUM BREAKOUT
        // Recent price breaking above earlier highs with volume
        val recentPrice = history.takeLast(3).map { it.price }.average()
        val earlierHigh = history.dropLast(3).map { it.price }.maxOrNull() ?: recentPrice
        val breakoutPct = if (earlierHigh > 0) (recentPrice / earlierHigh - 1) * 100 else 0.0
        momentum.hasMomentumBreakout = breakoutPct > 5 && momentum.hasVolumeAcceleration
        
        // 5. COMBINE INTO OVERALL MOMENTUM SCORE
        momentum.momentumScore = (
            momentum.volumeAccelerationScore * weights.volumeAccelWeight +
            momentum.coilingScore * weights.coilingWeight +
            momentum.accumulationScore * weights.accumulationWeight +
            avgBuyPressure * weights.buyPressureWeight
        ).coerceIn(0.0, 100.0)
        
        // 6. MAKE PREDICTION
        val (prediction, confidence) = makePrediction(momentum)
        momentum.prediction = prediction
        momentum.predictionConfidence = confidence
    }
    
    private fun makePrediction(momentum: TokenMomentum): Pair<MomentumPrediction, Double> {
        val score = momentum.momentumScore
        val patterns = listOf(
            momentum.hasVolumeAcceleration,
            momentum.hasCoilingPattern,
            momentum.hasAccumulation,
            momentum.hasMomentumBreakout,
        ).count { it }
        
        return when {
            // Strong pump signals
            score >= 75 && patterns >= 3 -> MomentumPrediction.STRONG_PUMP to (score * 0.9)
            score >= 70 && momentum.hasMomentumBreakout -> MomentumPrediction.STRONG_PUMP to (score * 0.85)
            
            // Building momentum
            score >= 55 && patterns >= 2 -> MomentumPrediction.PUMP_BUILDING to (score * 0.8)
            score >= 50 && momentum.hasVolumeAcceleration -> MomentumPrediction.PUMP_BUILDING to (score * 0.75)
            
            // Weak/Distribution
            score < 30 && !momentum.hasAccumulation -> MomentumPrediction.DISTRIBUTION to (70.0)
            score < 40 -> MomentumPrediction.WEAK to (60.0)
            
            // Neutral
            else -> MomentumPrediction.NEUTRAL to (50.0)
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get momentum analysis for a token.
     */
    fun getMomentum(mint: String): TokenMomentum? = tokenMomentum[mint]
    
    /**
     * Get momentum prediction for a token.
     */
    fun getPrediction(mint: String): MomentumPrediction {
        return tokenMomentum[mint]?.prediction ?: MomentumPrediction.NEUTRAL
    }
    
    /**
     * Get entry score bonus/penalty based on momentum.
     */
    fun getEntryScoreAdjustment(mint: String): Double {
        return tokenMomentum[mint]?.prediction?.entryBonus ?: 0.0
    }
    
    /**
     * Get momentum score (0-100).
     */
    fun getMomentumScore(mint: String): Double {
        return tokenMomentum[mint]?.momentumScore ?: 50.0
    }
    
    /**
     * Check if token has strong momentum signal.
     */
    fun hasStrongMomentum(mint: String): Boolean {
        val momentum = tokenMomentum[mint] ?: return false
        return momentum.prediction in listOf(MomentumPrediction.STRONG_PUMP, MomentumPrediction.PUMP_BUILDING)
    }
    
    /**
     * Check if should avoid token (weak/distribution).
     */
    fun shouldAvoid(mint: String): Boolean {
        val momentum = tokenMomentum[mint] ?: return false
        return momentum.prediction in listOf(MomentumPrediction.WEAK, MomentumPrediction.DISTRIBUTION)
    }
    
    /**
     * Get tokens with strong momentum (for discovery).
     */
    fun getStrongMomentumTokens(): List<TokenMomentum> {
        return tokenMomentum.values
            .filter { it.prediction in listOf(MomentumPrediction.STRONG_PUMP, MomentumPrediction.PUMP_BUILDING) }
            .sortedByDescending { it.momentumScore }
    }
    
    /**
     * Get detailed momentum report for a token.
     */
    fun getMomentumReport(mint: String): String {
        val m = tokenMomentum[mint] ?: return "No momentum data for $mint"
        
        return buildString {
            appendLine("═══════════════════════════════════════")
            appendLine("  MOMENTUM: ${m.symbol}")
            appendLine("═══════════════════════════════════════")
            appendLine("  Prediction: ${m.prediction.label} (${m.predictionConfidence.toInt()}%)")
            appendLine("  Overall Score: ${m.momentumScore.toInt()}/100")
            appendLine("")
            appendLine("  SCORES:")
            appendLine("  • Volume Acceleration: ${m.volumeAccelerationScore.toInt()}")
            appendLine("  • Coiling Pattern: ${m.coilingScore.toInt()}")
            appendLine("  • Accumulation: ${m.accumulationScore.toInt()}")
            appendLine("")
            appendLine("  PATTERNS DETECTED:")
            if (m.hasVolumeAcceleration) appendLine("  ✓ Volume Acceleration")
            if (m.hasCoilingPattern) appendLine("  ✓ Coiling (Spring)")
            if (m.hasAccumulation) appendLine("  ✓ Accumulation")
            if (m.hasMomentumBreakout) appendLine("  ✓ Momentum Breakout")
            appendLine("")
            appendLine("  Entry Adjustment: ${m.prediction.entryBonus.toInt()}")
            appendLine("═══════════════════════════════════════")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // LEARNING
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Record trade outcome for learning.
     */
    fun recordOutcome(mint: String, pnlPct: Double, peakPnlPct: Double) {
        val momentum = tokenMomentum[mint] ?: return
        
        weights.totalPredictions++
        
        val wasCorrect = when (momentum.prediction) {
            MomentumPrediction.STRONG_PUMP -> peakPnlPct >= 100  // Expected 2x+
            MomentumPrediction.PUMP_BUILDING -> peakPnlPct >= 50  // Expected 50%+
            MomentumPrediction.NEUTRAL -> pnlPct >= -10 && pnlPct <= 50  // Expected sideways
            MomentumPrediction.WEAK -> pnlPct < 20  // Expected no pump
            MomentumPrediction.DISTRIBUTION -> pnlPct < 0  // Expected dump
        }
        
        if (wasCorrect) {
            weights.correctPredictions++
        }
        
        // Adjust weights based on what worked
        val adjustment = if (wasCorrect) 0.01 else -0.01
        
        if (momentum.hasVolumeAcceleration && peakPnlPct > 50) {
            weights.volumeAccelWeight = (weights.volumeAccelWeight + adjustment).coerceIn(0.1, 0.5)
        }
        if (momentum.hasCoilingPattern && peakPnlPct > 100) {
            weights.coilingWeight = (weights.coilingWeight + adjustment).coerceIn(0.1, 0.5)
        }
        if (momentum.hasAccumulation && pnlPct > 0) {
            weights.accumulationWeight = (weights.accumulationWeight + adjustment).coerceIn(0.1, 0.5)
        }
        
        // Normalize weights
        val total = weights.volumeAccelWeight + weights.coilingWeight + 
                   weights.accumulationWeight + weights.buyPressureWeight
        weights.volumeAccelWeight /= total
        weights.coilingWeight /= total
        weights.accumulationWeight /= total
        weights.buyPressureWeight /= total
        
        ErrorLogger.debug(TAG, "📊 Outcome recorded: ${momentum.symbol} ${if (wasCorrect) "✓" else "✗"} | " +
            "pnl=${pnlPct.toInt()}% peak=${peakPnlPct.toInt()}% | pred=${momentum.prediction.label}")
    }
    
    /**
     * Get prediction accuracy.
     */
    fun getPredictionAccuracy(): Double {
        return if (weights.totalPredictions > 0) {
            weights.correctPredictions.toDouble() / weights.totalPredictions * 100
        } else 50.0
    }
    
    /**
     * Get stats for logging.
     */
    fun getStats(): String {
        val tracked = tokenMomentum.size
        val strong = tokenMomentum.values.count { 
            it.prediction in listOf(MomentumPrediction.STRONG_PUMP, MomentumPrediction.PUMP_BUILDING) 
        }
        val accuracy = getPredictionAccuracy().toInt()
        return "MomentumAI: $tracked tracked, $strong strong signals, $accuracy% accuracy"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLEANUP & PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Clean up old data.
     */
    fun cleanup() {
        val thirtyMinsAgo = System.currentTimeMillis() - (30 * 60 * 1000L)
        tokenMomentum.entries.removeIf { it.value.lastUpdateMs < thirtyMinsAgo }
    }
    
    /**
     * Clear all momentum data.
     */
    fun clear() {
        tokenMomentum.clear()
    }
    
    fun saveToJson(): JSONObject {
        val json = JSONObject()
        json.put("volumeAccelWeight", weights.volumeAccelWeight)
        json.put("coilingWeight", weights.coilingWeight)
        json.put("accumulationWeight", weights.accumulationWeight)
        json.put("buyPressureWeight", weights.buyPressureWeight)
        json.put("totalPredictions", weights.totalPredictions)
        json.put("correctPredictions", weights.correctPredictions)
        json.put("savedAt", System.currentTimeMillis())
        return json
    }
    
    fun loadFromJson(json: JSONObject) {
        weights.volumeAccelWeight = json.optDouble("volumeAccelWeight", 0.35)
        weights.coilingWeight = json.optDouble("coilingWeight", 0.25)
        weights.accumulationWeight = json.optDouble("accumulationWeight", 0.25)
        weights.buyPressureWeight = json.optDouble("buyPressureWeight", 0.15)
        weights.totalPredictions = json.optInt("totalPredictions", 0)
        weights.correctPredictions = json.optInt("correctPredictions", 0)
        
        val accuracy = getPredictionAccuracy().toInt()
        ErrorLogger.info(TAG, "📊 Loaded momentum weights: ${weights.totalPredictions} predictions, $accuracy% accuracy")
    }
}

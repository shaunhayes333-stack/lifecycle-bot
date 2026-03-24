package com.lifecyclebot.engine

import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * MarketRegimeAI - Detect and Adapt to Market Conditions
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 * CONCEPT: Different strategies work in different market conditions
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * BULL MARKET (Crypto going up)
 * - Be aggressive with entries
 * - Use looser stops (let winners run)
 * - Hold positions longer
 * - Take more trades
 * 
 * BEAR MARKET (Crypto going down)
 * - Be selective with entries
 * - Use tighter stops (protect capital)
 * - Take profits quicker
 * - Reduce position sizes
 * 
 * CRAB MARKET (Sideways/choppy)
 * - Only trade high-conviction setups
 * - Quick in-and-out trades
 * - Tight stops
 * - Lower position sizes
 * 
 * VOLATILITY SPIKE (Fear/uncertainty)
 * - Pause trading or very selective
 * - Very tight risk management
 * - Quick exits
 * 
 * ═══════════════════════════════════════════════════════════════════════════════
 * HOW IT DETECTS REGIME:
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * 1. SOL PRICE TREND (primary indicator)
 *    - 24h change, 7d change, 30d change
 *    - Trend direction and strength
 * 
 * 2. MEME COIN SENTIMENT
 *    - Average performance of recent meme launches
 *    - Number of 2x+ vs rugs in last 24h
 * 
 * 3. VOLATILITY
 *    - ATR of SOL and major meme coins
 *    - Spike detection
 * 
 * 4. VOLUME
 *    - Overall DEX volume trend
 *    - New token creation rate
 */
object MarketRegimeAI {
    
    private const val TAG = "MarketRegime"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REGIME TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class Regime(
        val label: String,
        val confidenceMultiplier: Double,    // Adjusts entry confidence threshold
        val positionSizeMultiplier: Double,  // Adjusts position sizing
        val trailMultiplier: Double,         // Adjusts trailing stop looseness
        val holdTimeMultiplier: Double,      // Adjusts max hold time
        val minEntryScore: Double,           // Minimum entry score required
    ) {
        STRONG_BULL(
            label = "STRONG BULL",
            confidenceMultiplier = 0.7,      // 30% lower confidence needed
            positionSizeMultiplier = 1.5,    // 50% larger positions
            trailMultiplier = 1.5,           // 50% looser trails
            holdTimeMultiplier = 2.0,        // 2x longer holds
            minEntryScore = 20.0,            // Lower bar for entries
        ),
        BULL(
            label = "BULL",
            confidenceMultiplier = 0.85,
            positionSizeMultiplier = 1.2,
            trailMultiplier = 1.2,
            holdTimeMultiplier = 1.5,
            minEntryScore = 25.0,
        ),
        NEUTRAL(
            label = "NEUTRAL",
            confidenceMultiplier = 1.0,
            positionSizeMultiplier = 1.0,
            trailMultiplier = 1.0,
            holdTimeMultiplier = 1.0,
            minEntryScore = 30.0,
        ),
        BEAR(
            label = "BEAR",
            confidenceMultiplier = 1.2,      // 20% higher confidence needed
            positionSizeMultiplier = 0.7,    // 30% smaller positions
            trailMultiplier = 0.8,           // 20% tighter trails
            holdTimeMultiplier = 0.7,        // 30% shorter holds
            minEntryScore = 40.0,            // Higher bar for entries
        ),
        STRONG_BEAR(
            label = "STRONG BEAR",
            confidenceMultiplier = 1.5,      // 50% higher confidence needed
            positionSizeMultiplier = 0.5,    // 50% smaller positions
            trailMultiplier = 0.6,           // 40% tighter trails
            holdTimeMultiplier = 0.5,        // 50% shorter holds
            minEntryScore = 50.0,            // Only best setups
        ),
        HIGH_VOLATILITY(
            label = "HIGH VOLATILITY",
            confidenceMultiplier = 1.3,
            positionSizeMultiplier = 0.6,
            trailMultiplier = 1.3,           // Looser trails (volatility = noise)
            holdTimeMultiplier = 0.6,
            minEntryScore = 45.0,
        ),
        CRAB(
            label = "CRAB",
            confidenceMultiplier = 1.1,
            positionSizeMultiplier = 0.8,
            trailMultiplier = 0.9,
            holdTimeMultiplier = 0.8,
            minEntryScore = 35.0,
        ),
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MARKET DATA
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class MarketSnapshot(
        val timestamp: Long = System.currentTimeMillis(),
        val solPrice: Double = 0.0,
        val solChange24h: Double = 0.0,
        val solChange7d: Double = 0.0,
        val avgMemePerformance: Double = 0.0,    // Avg % change of recent memes
        val successfulLaunches: Int = 0,          // 2x+ in last 24h
        val ruggedLaunches: Int = 0,              // -80%+ in last 24h
        val volatilityIndex: Double = 0.0,        // 0-100, higher = more volatile
        val overallSentiment: Double = 50.0,      // 0-100, 50 = neutral
    )
    
    // Historical snapshots for trend analysis
    private val snapshots = ConcurrentLinkedDeque<MarketSnapshot>()
    private const val MAX_SNAPSHOTS = 288  // 24 hours at 5-min intervals
    
    // Current regime
    @Volatile private var currentRegime: Regime = Regime.NEUTRAL
    @Volatile private var regimeConfidence: Double = 50.0  // 0-100
    @Volatile private var regimeStartTime: Long = System.currentTimeMillis()
    
    // Learning: track regime accuracy
    private var regimeTradeOutcomes = mutableMapOf<Regime, MutableList<Double>>()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REGIME DETECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Update market data and recalculate regime.
     * Call this every 5 minutes from BotService.
     */
    fun updateMarketData(
        solPrice: Double,
        solChange24h: Double,
        solChange7d: Double = 0.0,
        avgMemePerformance: Double = 0.0,
        successfulLaunches: Int = 0,
        ruggedLaunches: Int = 0,
        volatilityIndex: Double = 0.0,
    ) {
        val snapshot = MarketSnapshot(
            solPrice = solPrice,
            solChange24h = solChange24h,
            solChange7d = solChange7d,
            avgMemePerformance = avgMemePerformance,
            successfulLaunches = successfulLaunches,
            ruggedLaunches = ruggedLaunches,
            volatilityIndex = volatilityIndex,
            overallSentiment = calculateSentiment(solChange24h, avgMemePerformance, successfulLaunches, ruggedLaunches),
        )
        
        snapshots.addLast(snapshot)
        while (snapshots.size > MAX_SNAPSHOTS) {
            snapshots.removeFirst()
        }
        
        // Detect new regime
        val (newRegime, confidence) = detectRegime(snapshot)
        
        if (newRegime != currentRegime) {
            ErrorLogger.info(TAG, "📊 REGIME CHANGE: ${currentRegime.label} → ${newRegime.label} (confidence: ${confidence.toInt()}%)")
            regimeStartTime = System.currentTimeMillis()
        }
        
        currentRegime = newRegime
        regimeConfidence = confidence
    }
    
    private fun calculateSentiment(
        solChange24h: Double,
        avgMemePerformance: Double,
        successfulLaunches: Int,
        ruggedLaunches: Int,
    ): Double {
        var sentiment = 50.0
        
        // SOL price influence (±20)
        sentiment += (solChange24h / 5.0).coerceIn(-20.0, 20.0)
        
        // Meme performance influence (±15)
        sentiment += (avgMemePerformance / 10.0).coerceIn(-15.0, 15.0)
        
        // Success/rug ratio influence (±15)
        val total = successfulLaunches + ruggedLaunches
        if (total > 0) {
            val ratio = successfulLaunches.toDouble() / total
            sentiment += ((ratio - 0.5) * 30).coerceIn(-15.0, 15.0)
        }
        
        return sentiment.coerceIn(0.0, 100.0)
    }
    
    private fun detectRegime(snapshot: MarketSnapshot): Pair<Regime, Double> {
        val sol24h = snapshot.solChange24h
        val sentiment = snapshot.overallSentiment
        val volatility = snapshot.volatilityIndex
        
        // High volatility overrides other signals
        if (volatility >= 70) {
            return Regime.HIGH_VOLATILITY to volatility
        }
        
        // Calculate trend strength
        val trendStrength = (sol24h.coerceIn(-20.0, 20.0) / 20.0 * 50 + 
                           (sentiment - 50)).coerceIn(-100.0, 100.0)
        
        val regime = when {
            trendStrength >= 60 -> Regime.STRONG_BULL
            trendStrength >= 25 -> Regime.BULL
            trendStrength <= -60 -> Regime.STRONG_BEAR
            trendStrength <= -25 -> Regime.BEAR
            volatility >= 40 && kotlin.math.abs(trendStrength) < 15 -> Regime.CRAB
            else -> Regime.NEUTRAL
        }
        
        val confidence = when (regime) {
            Regime.STRONG_BULL, Regime.STRONG_BEAR -> kotlin.math.abs(trendStrength)
            Regime.BULL, Regime.BEAR -> 50 + kotlin.math.abs(trendStrength) / 2
            Regime.HIGH_VOLATILITY -> volatility
            else -> 50.0
        }
        
        return regime to confidence.coerceIn(30.0, 95.0)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get current market regime.
     */
    fun getCurrentRegime(): Regime = currentRegime
    
    /**
     * Get regime confidence (0-100).
     */
    fun getRegimeConfidence(): Double = regimeConfidence
    
    /**
     * Get confidence multiplier for current regime.
     * Use this to adjust entry confidence thresholds.
     */
    fun getConfidenceMultiplier(): Double = currentRegime.confidenceMultiplier
    
    /**
     * Get position size multiplier for current regime.
     * Use this to adjust position sizing.
     */
    fun getPositionSizeMultiplier(): Double = currentRegime.positionSizeMultiplier
    
    /**
     * Get trail multiplier for current regime.
     * Use this to adjust trailing stop looseness.
     */
    fun getTrailMultiplier(): Double = currentRegime.trailMultiplier
    
    /**
     * Get hold time multiplier for current regime.
     * Use this to adjust max hold times.
     */
    fun getHoldTimeMultiplier(): Double = currentRegime.holdTimeMultiplier
    
    /**
     * Get minimum entry score for current regime.
     */
    fun getMinEntryScore(): Double = currentRegime.minEntryScore
    
    /**
     * Check if market is favorable for new entries.
     */
    fun isFavorableForEntry(): Boolean {
        return currentRegime in listOf(Regime.STRONG_BULL, Regime.BULL, Regime.NEUTRAL)
    }
    
    /**
     * Check if should be extra cautious (reduce exposure).
     */
    fun shouldReduceExposure(): Boolean {
        return currentRegime in listOf(Regime.STRONG_BEAR, Regime.HIGH_VOLATILITY)
    }
    
    /**
     * Get regime duration in minutes.
     */
    fun getRegimeDurationMinutes(): Long {
        return (System.currentTimeMillis() - regimeStartTime) / 60_000
    }
    
    /**
     * Record trade outcome for regime learning.
     */
    fun recordTradeOutcome(pnlPct: Double) {
        regimeTradeOutcomes.getOrPut(currentRegime) { mutableListOf() }.add(pnlPct)
        
        // Keep only last 100 trades per regime
        regimeTradeOutcomes[currentRegime]?.let { outcomes ->
            while (outcomes.size > 100) outcomes.removeAt(0)
        }
    }
    
    /**
     * Get win rate for current regime.
     */
    fun getCurrentRegimeWinRate(): Double {
        val outcomes = regimeTradeOutcomes[currentRegime] ?: return 50.0
        if (outcomes.isEmpty()) return 50.0
        return outcomes.count { it > 0 }.toDouble() / outcomes.size * 100
    }
    
    /**
     * Get stats for logging.
     */
    fun getStats(): String {
        val duration = getRegimeDurationMinutes()
        val winRate = getCurrentRegimeWinRate().toInt()
        return "MarketRegime: ${currentRegime.label} (${regimeConfidence.toInt()}% conf, ${duration}m duration, ${winRate}% wr)"
    }
    
    /**
     * Get detailed regime info for UI.
     */
    fun getRegimeInfo(): String {
        return buildString {
            appendLine("═══════════════════════════════════════")
            appendLine("  MARKET REGIME: ${currentRegime.label}")
            appendLine("═══════════════════════════════════════")
            appendLine("  Confidence: ${regimeConfidence.toInt()}%")
            appendLine("  Duration: ${getRegimeDurationMinutes()} minutes")
            appendLine("  Win Rate (this regime): ${getCurrentRegimeWinRate().toInt()}%")
            appendLine("")
            appendLine("  ADJUSTMENTS:")
            appendLine("  • Confidence threshold: ${((1 - currentRegime.confidenceMultiplier) * 100).toInt()}%")
            appendLine("  • Position size: ${((currentRegime.positionSizeMultiplier - 1) * 100).toInt()}%")
            appendLine("  • Trail looseness: ${((currentRegime.trailMultiplier - 1) * 100).toInt()}%")
            appendLine("  • Hold time: ${((currentRegime.holdTimeMultiplier - 1) * 100).toInt()}%")
            appendLine("  • Min entry score: ${currentRegime.minEntryScore.toInt()}")
            appendLine("═══════════════════════════════════════")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        val json = JSONObject()
        json.put("currentRegime", currentRegime.name)
        json.put("regimeConfidence", regimeConfidence)
        json.put("regimeStartTime", regimeStartTime)
        
        // Save regime outcomes for learning
        val outcomesJson = JSONObject()
        regimeTradeOutcomes.forEach { (regime, outcomes) ->
            outcomesJson.put(regime.name, outcomes.takeLast(50).joinToString(","))
        }
        json.put("outcomes", outcomesJson)
        json.put("savedAt", System.currentTimeMillis())
        
        return json
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            currentRegime = Regime.valueOf(json.optString("currentRegime", "NEUTRAL"))
            regimeConfidence = json.optDouble("regimeConfidence", 50.0)
            regimeStartTime = json.optLong("regimeStartTime", System.currentTimeMillis())
            
            val outcomesJson = json.optJSONObject("outcomes")
            if (outcomesJson != null) {
                Regime.values().forEach { regime ->
                    val outcomeStr = outcomesJson.optString(regime.name, "")
                    if (outcomeStr.isNotEmpty()) {
                        val outcomes = outcomeStr.split(",").mapNotNull { it.toDoubleOrNull() }
                        regimeTradeOutcomes[regime] = outcomes.toMutableList()
                    }
                }
            }
            
            ErrorLogger.info(TAG, "📊 Loaded regime: ${currentRegime.label} (${regimeConfidence.toInt()}% confidence)")
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Failed to load regime: ${e.message}")
        }
    }
}

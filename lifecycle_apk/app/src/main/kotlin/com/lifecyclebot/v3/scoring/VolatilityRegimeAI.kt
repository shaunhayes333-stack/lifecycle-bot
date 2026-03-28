package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * V3.2 Volatility Regime AI
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Detects volatility regimes and adjusts entry/exit parameters dynamically:
 * 
 * VOLATILITY REGIMES:
 * - CALM:     ATR < 3%   → Tighter stops, smaller targets, look for squeeze breakouts
 * - NORMAL:   ATR 3-8%   → Standard parameters
 * - ELEVATED: ATR 8-15%  → Wider stops, bigger targets
 * - EXTREME:  ATR > 15%  → Reduce size, very wide stops, high reward targets
 * 
 * SPECIAL PATTERNS:
 * - VOLATILITY_SQUEEZE: Compression followed by expansion (pre-breakout)
 * - VOLATILITY_SPIKE: Sudden regime change (caution advised)
 * - VOLATILITY_COMPRESSION: Declining volatility (setup building)
 * 
 * CROSS-TALK INTEGRATION:
 * - MarketRegimeAI: Bull + Low vol = breakout setup
 * - MomentumPredictorAI: High momentum + low vol = explosive potential
 * - LiquidityDepthAI: Low vol + thin liquidity = dangerous
 * 
 * PERSISTENCE:
 * - Saves regime history and learned thresholds
 * - Adapts to market conditions over time
 */
object VolatilityRegimeAI {
    
    private const val TAG = "VolatilityAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // DATA STRUCTURES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class VolatilityRegime {
        CALM,       // ATR < 3% - Low volatility, tight ranges
        NORMAL,     // ATR 3-8% - Standard market conditions
        ELEVATED,   // ATR 8-15% - Higher volatility, wider swings
        EXTREME     // ATR > 15% - Very high volatility, dangerous
    }
    
    enum class VolatilityPattern {
        STABLE,              // No significant change
        SQUEEZE,             // Compression building (bullish setup)
        EXPANSION,           // Volatility expanding
        SPIKE,               // Sudden volatility jump
        COMPRESSION,         // Gradual vol decline
        REGIME_TRANSITION    // Changing between regimes
    }
    
    data class VolatilitySignal(
        val regime: VolatilityRegime,
        val pattern: VolatilityPattern,
        val currentAtr: Double,           // Current ATR %
        val avgAtr: Double,               // 20-period average ATR
        val atrRatio: Double,             // Current / Avg ratio
        val squeezeScore: Double,         // 0-100, higher = more compressed
        val recommendedStopMult: Double,  // Multiplier for stop distance
        val recommendedTargetMult: Double,// Multiplier for target distance  
        val recommendedSizeMult: Double,  // Position size multiplier
        val entryBoost: Int,              // Score adjustment
        val reason: String
    )
    
    // Regime thresholds (learned over time)
    private var calmThreshold = 3.0
    private var normalThreshold = 8.0
    private var elevatedThreshold = 15.0
    
    // Squeeze detection
    private const val SQUEEZE_LOOKBACK = 20
    private const val SQUEEZE_THRESHOLD = 0.6  // Current ATR < 60% of avg = squeeze
    
    // Cache for per-token volatility history
    private data class VolHistory(
        val atrValues: ArrayDeque<Double> = ArrayDeque(50),
        var lastRegime: VolatilityRegime = VolatilityRegime.NORMAL,
        var squeezeCandles: Int = 0,
        var lastUpdate: Long = 0
    )
    private val tokenVolatility = ConcurrentHashMap<String, VolHistory>()
    
    // Stats
    private var totalAnalyses = 0
    private var squeezeBreakouts = 0
    private var regimeTransitions = 0
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze volatility regime for a token.
     * Call with recent candle data.
     */
    fun analyze(
        mint: String,
        symbol: String,
        recentHighs: List<Double>,
        recentLows: List<Double>,
        recentCloses: List<Double>
    ): VolatilitySignal {
        totalAnalyses++
        
        if (recentHighs.size < 5 || recentLows.size < 5 || recentCloses.size < 5) {
            return defaultSignal("Insufficient data")
        }
        
        // Calculate ATR (Average True Range as %)
        val atrValues = calculateAtrPct(recentHighs, recentLows, recentCloses)
        val currentAtr = atrValues.lastOrNull() ?: 5.0
        val avgAtr = if (atrValues.size >= 10) atrValues.takeLast(20).average() else currentAtr
        
        // Update history
        val history = tokenVolatility.getOrPut(mint) { VolHistory() }
        history.atrValues.addLast(currentAtr)
        if (history.atrValues.size > 50) history.atrValues.removeFirst()
        history.lastUpdate = System.currentTimeMillis()
        
        // Determine regime
        val regime = classifyRegime(currentAtr)
        
        // Detect pattern
        val atrRatio = if (avgAtr > 0) currentAtr / avgAtr else 1.0
        val pattern = detectPattern(history, atrRatio, regime)
        
        // Calculate squeeze score
        val squeezeScore = calculateSqueezeScore(history, atrRatio)
        
        // Track regime transitions
        if (regime != history.lastRegime) {
            regimeTransitions++
            history.lastRegime = regime
        }
        
        // Calculate trading adjustments
        val (stopMult, targetMult, sizeMult) = getRegimeAdjustments(regime, pattern, squeezeScore)
        
        // Calculate entry score adjustment
        val entryBoost = calculateEntryBoost(regime, pattern, squeezeScore)
        
        val reason = buildReason(regime, pattern, currentAtr, squeezeScore)
        
        if (pattern == VolatilityPattern.SQUEEZE && squeezeScore > 70) {
            ErrorLogger.info(TAG, "🔥 SQUEEZE DETECTED: $symbol | ATR=${currentAtr.toInt()}% | squeeze=$squeezeScore")
        }
        
        return VolatilitySignal(
            regime = regime,
            pattern = pattern,
            currentAtr = currentAtr,
            avgAtr = avgAtr,
            atrRatio = atrRatio,
            squeezeScore = squeezeScore,
            recommendedStopMult = stopMult,
            recommendedTargetMult = targetMult,
            recommendedSizeMult = sizeMult,
            entryBoost = entryBoost,
            reason = reason
        )
    }
    
    /**
     * Quick regime check without full analysis.
     */
    fun getRegime(mint: String): VolatilityRegime {
        return tokenVolatility[mint]?.lastRegime ?: VolatilityRegime.NORMAL
    }
    
    /**
     * Check if token is in a squeeze setup.
     */
    fun isInSqueeze(mint: String): Boolean {
        val history = tokenVolatility[mint] ?: return false
        return history.squeezeCandles >= 3
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CALCULATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun calculateAtrPct(highs: List<Double>, lows: List<Double>, closes: List<Double>): List<Double> {
        val atrValues = mutableListOf<Double>()
        
        for (i in 1 until minOf(highs.size, lows.size, closes.size)) {
            val high = highs[i]
            val low = lows[i]
            val prevClose = closes[i - 1]
            
            if (prevClose <= 0) continue
            
            // True Range = max(H-L, |H-prevC|, |L-prevC|)
            val tr = maxOf(
                high - low,
                abs(high - prevClose),
                abs(low - prevClose)
            )
            
            // Convert to percentage
            val trPct = (tr / prevClose) * 100.0
            atrValues.add(trPct)
        }
        
        return atrValues
    }
    
    private fun classifyRegime(atr: Double): VolatilityRegime {
        return when {
            atr < calmThreshold -> VolatilityRegime.CALM
            atr < normalThreshold -> VolatilityRegime.NORMAL
            atr < elevatedThreshold -> VolatilityRegime.ELEVATED
            else -> VolatilityRegime.EXTREME
        }
    }
    
    private fun detectPattern(history: VolHistory, atrRatio: Double, currentRegime: VolatilityRegime): VolatilityPattern {
        val recentAtrs = history.atrValues.toList().takeLast(10)
        if (recentAtrs.size < 5) return VolatilityPattern.STABLE
        
        // Squeeze detection: Current ATR significantly below average
        if (atrRatio < SQUEEZE_THRESHOLD) {
            history.squeezeCandles++
            if (history.squeezeCandles >= 3) {
                return VolatilityPattern.SQUEEZE
            }
        } else {
            history.squeezeCandles = 0
        }
        
        // Spike detection: Sudden jump in ATR
        val prevAvg = recentAtrs.dropLast(2).average()
        val recent = recentAtrs.takeLast(2).average()
        if (prevAvg > 0 && recent / prevAvg > 2.0) {
            return VolatilityPattern.SPIKE
        }
        
        // Compression: Declining ATR trend
        val firstHalf = recentAtrs.take(5).average()
        val secondHalf = recentAtrs.takeLast(5).average()
        if (firstHalf > 0 && secondHalf / firstHalf < 0.7) {
            return VolatilityPattern.COMPRESSION
        }
        
        // Expansion: Increasing ATR trend
        if (firstHalf > 0 && secondHalf / firstHalf > 1.5) {
            return VolatilityPattern.EXPANSION
        }
        
        // Regime transition
        if (currentRegime != history.lastRegime) {
            return VolatilityPattern.REGIME_TRANSITION
        }
        
        return VolatilityPattern.STABLE
    }
    
    private fun calculateSqueezeScore(history: VolHistory, atrRatio: Double): Double {
        // Squeeze score 0-100
        // Lower atrRatio = higher squeeze
        // More consecutive squeeze candles = higher score
        
        val ratioScore = ((1.0 - atrRatio.coerceIn(0.3, 1.0)) / 0.7) * 50  // 0-50 from ratio
        val candleScore = (history.squeezeCandles.coerceAtMost(10) / 10.0) * 50  // 0-50 from duration
        
        return (ratioScore + candleScore).coerceIn(0.0, 100.0)
    }
    
    private fun getRegimeAdjustments(
        regime: VolatilityRegime,
        pattern: VolatilityPattern,
        squeezeScore: Double
    ): Triple<Double, Double, Double> {
        // Returns (stopMult, targetMult, sizeMult)
        
        val baseAdjustments = when (regime) {
            VolatilityRegime.CALM -> Triple(0.7, 0.8, 1.1)      // Tighter stops, smaller targets, can size up
            VolatilityRegime.NORMAL -> Triple(1.0, 1.0, 1.0)    // Standard
            VolatilityRegime.ELEVATED -> Triple(1.4, 1.5, 0.85) // Wider stops, bigger targets, smaller size
            VolatilityRegime.EXTREME -> Triple(2.0, 2.5, 0.6)   // Very wide, very big targets, much smaller size
        }
        
        // Adjust for squeeze pattern (can be more aggressive on breakout)
        if (pattern == VolatilityPattern.SQUEEZE && squeezeScore > 60) {
            return Triple(
                baseAdjustments.first * 0.9,   // Slightly tighter stop for squeeze
                baseAdjustments.second * 1.3,  // Bigger target for breakout
                baseAdjustments.third * 1.15   // Can size up slightly
            )
        }
        
        // Adjust for spike (be more cautious)
        if (pattern == VolatilityPattern.SPIKE) {
            return Triple(
                baseAdjustments.first * 1.5,   // Much wider stop
                baseAdjustments.second * 0.8,  // Smaller target
                baseAdjustments.third * 0.7    // Smaller size
            )
        }
        
        return baseAdjustments
    }
    
    private fun calculateEntryBoost(
        regime: VolatilityRegime,
        pattern: VolatilityPattern,
        squeezeScore: Double
    ): Int {
        var boost = 0
        
        // Squeeze setups are bullish
        if (pattern == VolatilityPattern.SQUEEZE) {
            boost += when {
                squeezeScore > 80 -> 10  // Strong squeeze
                squeezeScore > 60 -> 6   // Moderate squeeze
                squeezeScore > 40 -> 3   // Early squeeze
                else -> 0
            }
        }
        
        // Regime adjustments
        boost += when (regime) {
            VolatilityRegime.CALM -> 2      // Low vol = controlled risk
            VolatilityRegime.NORMAL -> 0
            VolatilityRegime.ELEVATED -> -3 // Higher vol = more risk
            VolatilityRegime.EXTREME -> -8  // Extreme vol = dangerous
        }
        
        // Pattern adjustments
        boost += when (pattern) {
            VolatilityPattern.COMPRESSION -> 3  // Building for breakout
            VolatilityPattern.SPIKE -> -5       // Sudden vol = caution
            VolatilityPattern.EXPANSION -> -2   // Vol rising
            else -> 0
        }
        
        return boost.coerceIn(-15, 15)
    }
    
    private fun buildReason(
        regime: VolatilityRegime,
        pattern: VolatilityPattern,
        atr: Double,
        squeezeScore: Double
    ): String {
        val parts = mutableListOf<String>()
        
        parts += "${regime.name} vol (ATR=${atr.toInt()}%)"
        
        if (pattern != VolatilityPattern.STABLE) {
            parts += pattern.name.lowercase().replace("_", " ")
        }
        
        if (squeezeScore > 50) {
            parts += "squeeze=${squeezeScore.toInt()}"
        }
        
        return parts.joinToString(" | ")
    }
    
    private fun defaultSignal(reason: String): VolatilitySignal {
        return VolatilitySignal(
            regime = VolatilityRegime.NORMAL,
            pattern = VolatilityPattern.STABLE,
            currentAtr = 5.0,
            avgAtr = 5.0,
            atrRatio = 1.0,
            squeezeScore = 0.0,
            recommendedStopMult = 1.0,
            recommendedTargetMult = 1.0,
            recommendedSizeMult = 1.0,
            entryBoost = 0,
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
        // Get recent price data from extra
        val recentHighs = candidate.extra["recentHighs"] as? List<Double> ?: emptyList()
        val recentLows = candidate.extra["recentLows"] as? List<Double> ?: emptyList()
        val recentCloses = candidate.extra["recentCloses"] as? List<Double> ?: emptyList()
        
        val signal = analyze(candidate.mint, candidate.symbol, recentHighs, recentLows, recentCloses)
        
        return ScoreComponent(
            name = "volatility",
            value = signal.entryBoost,
            reason = signal.reason
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun saveToJson(): JSONObject {
        return JSONObject().apply {
            put("calmThreshold", calmThreshold)
            put("normalThreshold", normalThreshold)
            put("elevatedThreshold", elevatedThreshold)
            put("totalAnalyses", totalAnalyses)
            put("squeezeBreakouts", squeezeBreakouts)
            put("regimeTransitions", regimeTransitions)
        }
    }
    
    fun loadFromJson(json: JSONObject) {
        try {
            calmThreshold = json.optDouble("calmThreshold", 3.0)
            normalThreshold = json.optDouble("normalThreshold", 8.0)
            elevatedThreshold = json.optDouble("elevatedThreshold", 15.0)
            totalAnalyses = json.optInt("totalAnalyses", 0)
            squeezeBreakouts = json.optInt("squeezeBreakouts", 0)
            regimeTransitions = json.optInt("regimeTransitions", 0)
        } catch (e: Exception) {
            ErrorLogger.error(TAG, "Load error: ${e.message}")
        }
    }
    
    fun getStats(): String {
        return "VolatilityAI: $totalAnalyses analyses | squeezes=$squeezeBreakouts transitions=$regimeTransitions"
    }
    
    fun cleanup() {
        val now = System.currentTimeMillis()
        val staleThreshold = 30 * 60 * 1000L // 30 minutes
        tokenVolatility.entries.removeIf { (_, v) -> now - v.lastUpdate > staleThreshold }
    }
}

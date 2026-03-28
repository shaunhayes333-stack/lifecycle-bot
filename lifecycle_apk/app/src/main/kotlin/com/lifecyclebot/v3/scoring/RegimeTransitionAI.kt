package com.lifecyclebot.v3.scoring

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * RegimeTransitionAI - Cross-Regime Arbitrage Detection
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Detects when tokens are TRANSITIONING between market regimes and captures
 * regime-shift alpha before other traders recognize the structural change.
 * 
 * REGIME TRANSITIONS WORTH TRADING:
 *   - MEME → MID_CAP (graduation, legitimization)
 *   - MEME → DEAD (rug detection, early exit)
 *   - MID_CAP → MAJOR (breakout to blue chip)
 *   - MEAN_REV → TREND (range breakout)
 *   - TREND → MEAN_REV (trend exhaustion)
 *   - ANY → PERPS_STYLE (leverage cascade building)
 * 
 * SIGNAL TYPES:
 *   - GRADUATION_FORMING: Meme showing mid-cap characteristics
 *   - RUG_FORMING: Meme showing collapse patterns
 *   - BREAKOUT_TO_MAJOR: Mid-cap gaining major-tier liquidity
 *   - RANGE_BREAKOUT: Mean reversion turning to trend
 *   - TREND_EXHAUSTION: Trend turning to range
 *   - SQUEEZE_BUILDING: Leverage cascade forming
 */
object RegimeTransitionAI {
    
    private const val TAG = "RegimeTransitionAI"
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRANSITION TYPES
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class TransitionType(
        val emoji: String,
        val description: String,
        val alphaPotential: Int,  // 1-10 scale
        val urgency: Int,         // 1-10 scale (10 = act now)
    ) {
        // Bullish transitions
        GRADUATION_FORMING("🎓", "Meme → MidCap legitimization", 8, 7),
        BREAKOUT_TO_MAJOR("🏛️", "MidCap → Major tier", 7, 6),
        RANGE_BREAKOUT("📈", "MeanRev → Trend breakout", 7, 8),
        SQUEEZE_BUILDING("🎯", "Leverage cascade forming", 9, 9),
        ACCUMULATION_PHASE("🐋", "Smart money loading", 6, 5),
        
        // Bearish transitions (exit signals)
        RUG_FORMING("🚨", "Meme → Dead (rug)", 0, 10),
        TREND_EXHAUSTION("📉", "Trend → Range (exit)", 0, 8),
        DISTRIBUTION_PHASE("🔻", "Smart money unloading", 0, 7),
        LIQUIDITY_DRAIN("💧", "Liquidity collapsing", 0, 9),
        
        // Neutral/information
        REGIME_STABLE("➖", "No transition detected", 0, 0),
        REGIME_UNCLEAR("❓", "Mixed signals", 0, 0),
    }
    
    data class TransitionSignal(
        val type: TransitionType,
        val confidence: Double,       // 0-100
        val fromRegime: String,
        val toRegime: String,
        val signals: List<String>,
        val recommendedAction: String,
        val timeHorizon: String,      // "immediate", "hours", "days"
        val timestamp: Long = System.currentTimeMillis(),
    )
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REGIME HISTORY TRACKING
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class RegimeSnapshot(
        val regime: String,
        val liquidity: Double,
        val volatility: Double,
        val momentum: Double,
        val holderCount: Int,
        val volume24h: Double,
        val timestamp: Long,
    )
    
    // Token → Recent regime snapshots (for transition detection)
    private val regimeHistory = ConcurrentHashMap<String, MutableList<RegimeSnapshot>>()
    private const val MAX_HISTORY_PER_TOKEN = 50
    
    // Detected transitions cache
    private val transitionCache = ConcurrentHashMap<String, TransitionSignal>()
    private const val CACHE_TTL_MS = 60_000L  // 1 minute
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN DETECTION API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Analyze a token for regime transition signals.
     * Call this periodically for tokens in watchlist/shadow mode.
     */
    fun analyzeTransition(
        mint: String,
        symbol: String,
        currentRegime: String,
        liquidity: Double,
        volatility: Double,
        momentum: Double,
        holderCount: Int,
        volume24h: Double,
    ): TransitionSignal {
        
        // Check cache
        val cached = transitionCache[mint]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached
        }
        
        // Record current snapshot
        val snapshot = RegimeSnapshot(
            regime = currentRegime,
            liquidity = liquidity,
            volatility = volatility,
            momentum = momentum,
            holderCount = holderCount,
            volume24h = volume24h,
            timestamp = System.currentTimeMillis(),
        )
        
        val history = regimeHistory.getOrPut(mint) { mutableListOf() }
        synchronized(history) {
            history.add(snapshot)
            if (history.size > MAX_HISTORY_PER_TOKEN) {
                history.removeAt(0)
            }
        }
        
        // Need minimum history for transition detection
        if (history.size < 5) {
            return TransitionSignal(
                type = TransitionType.REGIME_UNCLEAR,
                confidence = 0.0,
                fromRegime = currentRegime,
                toRegime = currentRegime,
                signals = listOf("Insufficient history for transition analysis"),
                recommendedAction = "WAIT",
                timeHorizon = "hours",
            )
        }
        
        // Analyze for transitions
        val signals = mutableListOf<String>()
        var bestTransition = TransitionType.REGIME_STABLE
        var bestConfidence = 0.0
        var toRegime = currentRegime
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: GRADUATION (Meme → MidCap)
        // ─────────────────────────────────────────────────────────────────────
        if (currentRegime.contains("MEME", ignoreCase = true)) {
            val gradScore = detectGraduation(history, liquidity, holderCount, volume24h, signals)
            if (gradScore > bestConfidence) {
                bestConfidence = gradScore
                bestTransition = TransitionType.GRADUATION_FORMING
                toRegime = "MID_CAPS"
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: RUG FORMING (Meme → Dead)
        // ─────────────────────────────────────────────────────────────────────
        if (currentRegime.contains("MEME", ignoreCase = true)) {
            val rugScore = detectRugForming(history, liquidity, holderCount, signals)
            if (rugScore > bestConfidence && rugScore > 60) {
                bestConfidence = rugScore
                bestTransition = TransitionType.RUG_FORMING
                toRegime = "DEAD"
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: BREAKOUT TO MAJOR (MidCap → Major)
        // ─────────────────────────────────────────────────────────────────────
        if (currentRegime.contains("MID", ignoreCase = true)) {
            val majorScore = detectBreakoutToMajor(history, liquidity, volume24h, signals)
            if (majorScore > bestConfidence) {
                bestConfidence = majorScore
                bestTransition = TransitionType.BREAKOUT_TO_MAJOR
                toRegime = "MAJORS"
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: RANGE BREAKOUT (MeanRev → Trend)
        // ─────────────────────────────────────────────────────────────────────
        if (currentRegime.contains("MEAN", ignoreCase = true)) {
            val breakoutScore = detectRangeBreakout(history, momentum, volatility, signals)
            if (breakoutScore > bestConfidence) {
                bestConfidence = breakoutScore
                bestTransition = TransitionType.RANGE_BREAKOUT
                toRegime = "TREND_REGIME"
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: TREND EXHAUSTION (Trend → MeanRev)
        // ─────────────────────────────────────────────────────────────────────
        if (currentRegime.contains("TREND", ignoreCase = true)) {
            val exhaustionScore = detectTrendExhaustion(history, momentum, volatility, signals)
            if (exhaustionScore > bestConfidence) {
                bestConfidence = exhaustionScore
                bestTransition = TransitionType.TREND_EXHAUSTION
                toRegime = "MEAN_REVERSION"
            }
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: SQUEEZE BUILDING (Any → Perps leverage cascade)
        // ─────────────────────────────────────────────────────────────────────
        val squeezeScore = detectSqueezeBuild(history, volatility, volume24h, signals)
        if (squeezeScore > bestConfidence && squeezeScore > 50) {
            bestConfidence = squeezeScore
            bestTransition = TransitionType.SQUEEZE_BUILDING
            toRegime = "PERPS_STYLE"
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: LIQUIDITY DRAIN (Any → Exit)
        // ─────────────────────────────────────────────────────────────────────
        val drainScore = detectLiquidityDrain(history, liquidity, signals)
        if (drainScore > 70 && drainScore > bestConfidence) {
            bestConfidence = drainScore
            bestTransition = TransitionType.LIQUIDITY_DRAIN
            toRegime = "EXIT"
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: ACCUMULATION PHASE
        // ─────────────────────────────────────────────────────────────────────
        val accumScore = detectAccumulation(history, holderCount, volume24h, signals)
        if (accumScore > bestConfidence && accumScore > 50) {
            bestConfidence = accumScore
            bestTransition = TransitionType.ACCUMULATION_PHASE
        }
        
        // ─────────────────────────────────────────────────────────────────────
        // CHECK: DISTRIBUTION PHASE
        // ─────────────────────────────────────────────────────────────────────
        val distScore = detectDistribution(history, holderCount, volume24h, signals)
        if (distScore > 60 && distScore > bestConfidence) {
            bestConfidence = distScore
            bestTransition = TransitionType.DISTRIBUTION_PHASE
            toRegime = "EXIT"
        }
        
        // Build recommended action
        val action = when (bestTransition) {
            TransitionType.GRADUATION_FORMING -> "UPGRADE_POSITION_SIZE"
            TransitionType.BREAKOUT_TO_MAJOR -> "ADD_TO_POSITION"
            TransitionType.RANGE_BREAKOUT -> "ENTER_TREND_MODE"
            TransitionType.SQUEEZE_BUILDING -> "POSITION_FOR_SQUEEZE"
            TransitionType.ACCUMULATION_PHASE -> "SCALE_IN"
            TransitionType.RUG_FORMING -> "EMERGENCY_EXIT"
            TransitionType.TREND_EXHAUSTION -> "TAKE_PROFITS"
            TransitionType.DISTRIBUTION_PHASE -> "EXIT_POSITION"
            TransitionType.LIQUIDITY_DRAIN -> "EMERGENCY_EXIT"
            TransitionType.REGIME_STABLE -> "HOLD"
            TransitionType.REGIME_UNCLEAR -> "WAIT"
        }
        
        // Build time horizon
        val horizon = when (bestTransition) {
            TransitionType.RUG_FORMING, TransitionType.LIQUIDITY_DRAIN -> "immediate"
            TransitionType.SQUEEZE_BUILDING, TransitionType.RANGE_BREAKOUT -> "hours"
            TransitionType.GRADUATION_FORMING, TransitionType.BREAKOUT_TO_MAJOR -> "days"
            else -> "hours"
        }
        
        val result = TransitionSignal(
            type = bestTransition,
            confidence = bestConfidence,
            fromRegime = currentRegime,
            toRegime = toRegime,
            signals = signals,
            recommendedAction = action,
            timeHorizon = horizon,
        )
        
        // Cache result
        transitionCache[mint] = result
        
        // Log significant transitions
        if (bestConfidence > 50 && bestTransition != TransitionType.REGIME_STABLE) {
            Log.i(TAG, "${bestTransition.emoji} $symbol: ${bestTransition.description} | " +
                "conf=${bestConfidence.toInt()}% | $currentRegime → $toRegime | $action")
        }
        
        return result
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TRANSITION DETECTORS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private fun detectGraduation(
        history: List<RegimeSnapshot>,
        liquidity: Double,
        holderCount: Int,
        volume24h: Double,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Liquidity growth trajectory
        val oldLiq = history.take(5).map { it.liquidity }.average()
        val newLiq = history.takeLast(5).map { it.liquidity }.average()
        val liqGrowth = if (oldLiq > 0) (newLiq - oldLiq) / oldLiq * 100 else 0.0
        
        if (liqGrowth > 50) {
            score += 30.0
            signals.add("GRAD: Liquidity +${liqGrowth.toInt()}%")
        }
        
        // Holder count growth
        val oldHolders = history.take(5).map { it.holderCount }.average()
        val newHolders = history.takeLast(5).map { it.holderCount }.average()
        val holderGrowth = if (oldHolders > 0) (newHolders - oldHolders) / oldHolders * 100 else 0.0
        
        if (holderGrowth > 30) {
            score += 25.0
            signals.add("GRAD: Holders +${holderGrowth.toInt()}%")
        }
        
        // Volatility decreasing (stabilizing)
        val oldVol = history.take(5).map { it.volatility }.average()
        val newVol = history.takeLast(5).map { it.volatility }.average()
        
        if (newVol < oldVol * 0.7) {
            score += 20.0
            signals.add("GRAD: Volatility stabilizing")
        }
        
        // Absolute liquidity threshold
        if (liquidity > 50_000) {
            score += 15.0
            signals.add("GRAD: MidCap liquidity threshold")
        }
        
        // Sustained volume
        if (volume24h > 100_000) {
            score += 10.0
        }
        
        return score
    }
    
    private fun detectRugForming(
        history: List<RegimeSnapshot>,
        liquidity: Double,
        holderCount: Int,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Liquidity declining rapidly
        val peakLiq = history.maxOfOrNull { it.liquidity } ?: 0.0
        val liqDrop = if (peakLiq > 0) (peakLiq - liquidity) / peakLiq * 100 else 0.0
        
        if (liqDrop > 50) {
            score += 40.0
            signals.add("RUG: Liquidity -${liqDrop.toInt()}% from peak")
        }
        
        // Holder count dropping
        val peakHolders = history.maxOfOrNull { it.holderCount } ?: 0
        val holderDrop = if (peakHolders > 0) (peakHolders - holderCount).toDouble() / peakHolders * 100 else 0.0
        
        if (holderDrop > 30) {
            score += 30.0
            signals.add("RUG: Holders -${holderDrop.toInt()}% from peak")
        }
        
        // Volume spike then collapse (dump pattern)
        val recentVol = history.takeLast(3).map { it.volume24h }.average()
        val priorVol = history.dropLast(3).takeLast(5).map { it.volume24h }.average()
        
        if (priorVol > 0 && recentVol < priorVol * 0.3) {
            score += 20.0
            signals.add("RUG: Volume collapsed")
        }
        
        // Very low absolute liquidity
        if (liquidity < 2_000) {
            score += 15.0
            signals.add("RUG: Critical liquidity")
        }
        
        return score
    }
    
    private fun detectBreakoutToMajor(
        history: List<RegimeSnapshot>,
        liquidity: Double,
        volume24h: Double,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Approaching major liquidity threshold
        if (liquidity > 1_000_000) {
            score += 40.0
            signals.add("MAJOR: $1M+ liquidity")
        } else if (liquidity > 500_000) {
            score += 25.0
            signals.add("MAJOR: Approaching major threshold")
        }
        
        // Sustained high volume
        if (volume24h > 500_000) {
            score += 25.0
            signals.add("MAJOR: High sustained volume")
        }
        
        // Liquidity growth trajectory
        val oldLiq = history.take(5).map { it.liquidity }.average()
        val newLiq = history.takeLast(5).map { it.liquidity }.average()
        
        if (oldLiq > 0 && newLiq > oldLiq * 1.5) {
            score += 20.0
            signals.add("MAJOR: Strong liquidity growth")
        }
        
        // Volatility compressing (institutionalizing)
        val oldVol = history.take(5).map { it.volatility }.average()
        val newVol = history.takeLast(5).map { it.volatility }.average()
        
        if (newVol < oldVol * 0.6) {
            score += 15.0
        }
        
        return score
    }
    
    private fun detectRangeBreakout(
        history: List<RegimeSnapshot>,
        momentum: Double,
        volatility: Double,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Strong momentum emerging
        if (abs(momentum) > 15) {
            score += 35.0
            signals.add("BREAKOUT: Strong momentum ${momentum.toInt()}%")
        }
        
        // Volatility expansion (after compression)
        val oldVol = history.take(5).map { it.volatility }.average()
        val newVol = history.takeLast(3).map { it.volatility }.average()
        
        if (oldVol > 0 && newVol > oldVol * 1.5) {
            score += 30.0
            signals.add("BREAKOUT: Vol expansion ${((newVol/oldVol - 1) * 100).toInt()}%")
        }
        
        // Sustained directional move
        val recentMomentums = history.takeLast(5).map { it.momentum }
        val sameDirection = recentMomentums.all { it > 0 } || recentMomentums.all { it < 0 }
        
        if (sameDirection) {
            score += 25.0
            signals.add("BREAKOUT: Sustained direction")
        }
        
        return score
    }
    
    private fun detectTrendExhaustion(
        history: List<RegimeSnapshot>,
        momentum: Double,
        volatility: Double,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Momentum slowing
        val priorMom = history.dropLast(3).takeLast(5).map { it.momentum }.average()
        val recentMom = history.takeLast(3).map { it.momentum }.average()
        
        if (abs(priorMom) > 10 && abs(recentMom) < abs(priorMom) * 0.5) {
            score += 35.0
            signals.add("EXHAUST: Momentum fading")
        }
        
        // Volatility without direction
        if (volatility > 10 && abs(momentum) < 5) {
            score += 25.0
            signals.add("EXHAUST: High vol, no direction")
        }
        
        // Volume declining
        val priorVol = history.dropLast(3).takeLast(5).map { it.volume24h }.average()
        val recentVol = history.takeLast(3).map { it.volume24h }.average()
        
        if (priorVol > 0 && recentVol < priorVol * 0.5) {
            score += 20.0
            signals.add("EXHAUST: Volume declining")
        }
        
        return score
    }
    
    private fun detectSqueezeBuild(
        history: List<RegimeSnapshot>,
        volatility: Double,
        volume24h: Double,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Very low volatility (compression)
        val avgVol = history.map { it.volatility }.average()
        if (volatility < avgVol * 0.5) {
            score += 30.0
            signals.add("SQUEEZE: Vol compression")
        }
        
        // Volume building during compression
        val priorVolume = history.dropLast(3).takeLast(5).map { it.volume24h }.average()
        if (priorVolume > 0 && volume24h > priorVolume * 1.3) {
            score += 25.0
            signals.add("SQUEEZE: Volume building")
        }
        
        // Tightening price range would need price data
        // Using momentum as proxy - very low momentum = tight range
        val recentMom = history.takeLast(5).map { abs(it.momentum) }.average()
        if (recentMom < 3) {
            score += 20.0
            signals.add("SQUEEZE: Tight range")
        }
        
        return score
    }
    
    private fun detectLiquidityDrain(
        history: List<RegimeSnapshot>,
        liquidity: Double,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Rapid liquidity decline
        val peakLiq = history.maxOfOrNull { it.liquidity } ?: 0.0
        val liqDrop = if (peakLiq > 0) (peakLiq - liquidity) / peakLiq * 100 else 0.0
        
        if (liqDrop > 60) {
            score += 50.0
            signals.add("DRAIN: Liquidity -${liqDrop.toInt()}%")
        } else if (liqDrop > 40) {
            score += 30.0
            signals.add("DRAIN: Significant liq loss")
        }
        
        // Accelerating drain
        val recentDrain = history.takeLast(3).map { it.liquidity }
        val accelerating = recentDrain.zipWithNext { a, b -> b < a * 0.9 }.all { it }
        
        if (accelerating && recentDrain.size >= 2) {
            score += 30.0
            signals.add("DRAIN: Accelerating")
        }
        
        // Critical absolute level
        if (liquidity < 5_000) {
            score += 20.0
            signals.add("DRAIN: Critical level")
        }
        
        return score
    }
    
    private fun detectAccumulation(
        history: List<RegimeSnapshot>,
        holderCount: Int,
        volume24h: Double,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Holder count growing steadily
        val holderGrowths = history.map { it.holderCount }.zipWithNext { a, b -> 
            if (a > 0) (b - a).toDouble() / a * 100 else 0.0 
        }
        val consistentGrowth = holderGrowths.count { it > 0 } > holderGrowths.size * 0.6
        
        if (consistentGrowth) {
            score += 30.0
            signals.add("ACCUM: Steady holder growth")
        }
        
        // Volume without price increase (absorption)
        val volumeGrowing = history.takeLast(5).map { it.volume24h }.zipWithNext { a, b -> b > a }.count { it } >= 3
        val priceFlat = history.takeLast(5).map { abs(it.momentum) }.average() < 5
        
        if (volumeGrowing && priceFlat) {
            score += 25.0
            signals.add("ACCUM: Volume absorbed")
        }
        
        // Liquidity stable/growing
        val liqStable = history.takeLast(5).all { it.liquidity > history.first().liquidity * 0.9 }
        if (liqStable) {
            score += 20.0
        }
        
        return score
    }
    
    private fun detectDistribution(
        history: List<RegimeSnapshot>,
        holderCount: Int,
        volume24h: Double,
        signals: MutableList<String>
    ): Double {
        var score = 0.0
        
        // Holder count declining
        val peakHolders = history.maxOfOrNull { it.holderCount } ?: 0
        val holderDrop = if (peakHolders > 0) (peakHolders - holderCount).toDouble() / peakHolders * 100 else 0.0
        
        if (holderDrop > 20) {
            score += 35.0
            signals.add("DIST: Holders -${holderDrop.toInt()}%")
        }
        
        // High volume with price decline
        val priorVol = history.dropLast(3).takeLast(5).map { it.volume24h }.average()
        val recentMom = history.takeLast(3).map { it.momentum }.average()
        
        if (volume24h > priorVol * 1.5 && recentMom < -5) {
            score += 30.0
            signals.add("DIST: Selling volume")
        }
        
        // Liquidity declining
        val liqDeclining = history.takeLast(5).map { it.liquidity }.zipWithNext { a, b -> b < a }.count { it } >= 3
        if (liqDeclining) {
            score += 20.0
            signals.add("DIST: Liquidity leaving")
        }
        
        return score
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // API
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Get all tokens currently showing significant transitions.
     */
    fun getActiveTransitions(): List<Pair<String, TransitionSignal>> {
        return transitionCache
            .filter { it.value.confidence > 50 && it.value.type != TransitionType.REGIME_STABLE }
            .toList()
    }
    
    /**
     * Clear history for a token (call when position closed).
     */
    fun clearHistory(mint: String) {
        regimeHistory.remove(mint)
        transitionCache.remove(mint)
    }
    
    /**
     * Get status for logging.
     */
    fun getStatus(): String {
        val activeCount = transitionCache.count { it.value.confidence > 50 }
        return "RegimeTransitionAI: tracking=${regimeHistory.size} | active_transitions=$activeCount"
    }
}

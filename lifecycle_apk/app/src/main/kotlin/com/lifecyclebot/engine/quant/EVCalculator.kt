package com.lifecyclebot.engine.quant

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ErrorLogger

/**
 * Expected Value (EV) Calculator
 * 
 * Calculates probability-weighted expected outcomes for each trade opportunity.
 * Only positive EV trades should be executed.
 * 
 * EV = Σ (P_i × Outcome_i) for all scenarios
 * 
 * Scenarios modeled:
 *   1. MOON      (10x+)     - Rare but high impact
 *   2. GOOD_WIN  (3-10x)    - Strong performer
 *   3. SMALL_WIN (1.5-3x)   - Decent profit
 *   4. BREAKEVEN (0.9-1.1x) - Wash trade
 *   5. SMALL_LOSS(-10-30%)  - Normal loss
 *   6. STOP_LOSS (-30-50%)  - Stop triggered
 *   7. RUG       (-80-100%) - Rug pull / abandoned
 * 
 * Probabilities are estimated from:
 *   - Token characteristics (liquidity, age, score)
 *   - Quality grade (A/B/C/SNIPE)
 *   - Market regime (bull/bear/neutral)
 *   - Historical win rate
 */
object EVCalculator {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // OUTCOME SCENARIOS
    // ═══════════════════════════════════════════════════════════════════════════
    
    enum class Scenario(val label: String, val minReturn: Double, val maxReturn: Double) {
        MOON("Moon", 10.0, 50.0),           // 10x to 50x (rare)
        GOOD_WIN("Good Win", 3.0, 10.0),    // 3x to 10x
        SMALL_WIN("Small Win", 1.5, 3.0),   // 1.5x to 3x
        BREAKEVEN("Breakeven", 0.9, 1.1),   // -10% to +10%
        SMALL_LOSS("Small Loss", 0.7, 0.9), // -10% to -30%
        STOP_LOSS("Stop Loss", 0.5, 0.7),   // -30% to -50%
        RUG("Rug Pull", 0.0, 0.2),          // -80% to -100%
    }
    
    data class ScenarioProbability(
        val scenario: Scenario,
        val probability: Double,        // 0.0 to 1.0
        val expectedReturn: Double,     // Midpoint of scenario range
    ) {
        val weightedReturn: Double get() = probability * expectedReturn
    }
    
    data class EVResult(
        val expectedValue: Double,      // EV as multiplier (1.0 = breakeven)
        val expectedPnlPct: Double,     // EV as percentage (-100 to +inf)
        val winProbability: Double,     // P(outcome > 1.0)
        val lossProbability: Double,    // P(outcome < 1.0)
        val rugProbability: Double,     // P(rug pull)
        val scenarios: List<ScenarioProbability>,
        val kellyFraction: Double,      // Optimal position size (Kelly Criterion)
        val isPositiveEV: Boolean,      // EV > 1.0
        val confidence: Double,         // Confidence in estimate (0-100)
        val riskRewardRatio: Double,    // Potential upside / potential downside
    ) {
        fun summary(): String = buildString {
            append("EV=${String.format("%.2f", expectedPnlPct)}% ")
            append("Win=${String.format("%.0f", winProbability * 100)}% ")
            append("Rug=${String.format("%.0f", rugProbability * 100)}% ")
            append("Kelly=${String.format("%.1f", kellyFraction * 100)}% ")
            append(if (isPositiveEV) "✅+EV" else "❌-EV")
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROBABILITY ESTIMATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Calculate EV for a potential trade
     */
    fun calculate(
        ts: TokenState,
        entryScore: Double,
        quality: String,
        marketRegime: String = "NEUTRAL",
        historicalWinRate: Double = 0.55,  // Default 55% win rate
    ): EVResult {
        
        // Base probabilities (will be adjusted)
        var pMoon = 0.02        // 2% chance of 10x+
        var pGoodWin = 0.08     // 8% chance of 3-10x
        var pSmallWin = 0.25    // 25% chance of 1.5-3x
        var pBreakeven = 0.20   // 20% chance of wash
        var pSmallLoss = 0.25   // 25% chance of small loss
        var pStopLoss = 0.12    // 12% chance of stop loss
        var pRug = 0.08         // 8% chance of rug
        
        // ═══════════════════════════════════════════════════════════════════════
        // ADJUSTMENT 1: Token Quality (from entry score)
        // Higher score = better probability distribution
        // ═══════════════════════════════════════════════════════════════════════
        
        val scoreMultiplier = when {
            entryScore >= 80 -> 1.4   // Excellent setup
            entryScore >= 65 -> 1.2   // Good setup
            entryScore >= 50 -> 1.0   // Average
            entryScore >= 35 -> 0.8   // Below average
            else -> 0.6               // Poor setup
        }
        
        pMoon *= scoreMultiplier
        pGoodWin *= scoreMultiplier
        pSmallWin *= scoreMultiplier
        pRug /= scoreMultiplier.coerceAtLeast(0.5)  // Better score = lower rug chance
        
        // ═══════════════════════════════════════════════════════════════════════
        // ADJUSTMENT 2: Quality Grade
        // A/B quality = shift probability toward wins
        // ═══════════════════════════════════════════════════════════════════════
        
        when (quality) {
            "A+", "A" -> {
                pMoon *= 1.5
                pGoodWin *= 1.3
                pRug *= 0.5
                pStopLoss *= 0.7
            }
            "B" -> {
                pGoodWin *= 1.1
                pSmallWin *= 1.1
                pRug *= 0.8
            }
            "C" -> {
                pSmallLoss *= 1.2
                pStopLoss *= 1.1
            }
            "SNIPE" -> {
                // Early snipe - higher variance
                pMoon *= 1.8      // More moon potential
                pRug *= 1.3       // But also more rug risk
                pGoodWin *= 1.2
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // ADJUSTMENT 3: Liquidity Health
        // Low liquidity = higher rug probability
        // ═══════════════════════════════════════════════════════════════════════
        
        val liquidity = ts.lastLiquidityUsd
        when {
            liquidity < 5_000 -> {
                pRug *= 1.5
                pStopLoss *= 1.2
                pMoon *= 0.7  // Harder to exit at high prices
            }
            liquidity < 10_000 -> {
                pRug *= 1.2
            }
            liquidity > 50_000 -> {
                pRug *= 0.7
                pMoon *= 0.9  // More established = less moon potential
                pSmallWin *= 1.2
            }
            liquidity > 100_000 -> {
                pRug *= 0.5
                pMoon *= 0.6
                pSmallWin *= 1.3
                pBreakeven *= 1.2
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // ADJUSTMENT 4: Market Regime
        // Bull market = higher win probabilities
        // Bear market = higher loss probabilities
        // ═══════════════════════════════════════════════════════════════════════
        
        when (marketRegime.uppercase()) {
            "BULL", "BULLISH" -> {
                pMoon *= 1.4
                pGoodWin *= 1.3
                pSmallWin *= 1.2
                pSmallLoss *= 0.8
                pStopLoss *= 0.8
            }
            "BEAR", "BEARISH" -> {
                pMoon *= 0.5
                pGoodWin *= 0.7
                pSmallLoss *= 1.3
                pStopLoss *= 1.4
                pRug *= 1.2
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // ADJUSTMENT 5: Historical Performance
        // Shift probabilities based on historical win rate
        // ═══════════════════════════════════════════════════════════════════════
        
        val winRateAdjust = historicalWinRate / 0.55  // Normalize to 55% baseline
        pSmallWin *= winRateAdjust
        pGoodWin *= winRateAdjust
        pSmallLoss *= (2.0 - winRateAdjust).coerceAtLeast(0.5)
        
        // ═══════════════════════════════════════════════════════════════════════
        // ADJUSTMENT 6: Token Age
        // Newer tokens = higher variance (more moon, more rug)
        // ═══════════════════════════════════════════════════════════════════════
        
        val tokenAgeMinutes = if (ts.history.isNotEmpty()) {
            val firstCandleTime = ts.history.minOfOrNull { it.ts } ?: System.currentTimeMillis()
            (System.currentTimeMillis() - firstCandleTime) / 60_000.0
        } else 60.0  // Default to 1 hour if unknown
        
        when {
            tokenAgeMinutes < 10 -> {
                // Very fresh - high variance
                pMoon *= 2.0
                pRug *= 1.5
                pBreakeven *= 0.7
            }
            tokenAgeMinutes < 30 -> {
                pMoon *= 1.5
                pGoodWin *= 1.2
                pRug *= 1.2
            }
            tokenAgeMinutes > 120 -> {
                // Older token - lower variance
                pMoon *= 0.5
                pGoodWin *= 0.8
                pRug *= 0.7
                pBreakeven *= 1.3
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════════
        // NORMALIZE PROBABILITIES (must sum to 1.0)
        // ═══════════════════════════════════════════════════════════════════════
        
        val totalP = pMoon + pGoodWin + pSmallWin + pBreakeven + pSmallLoss + pStopLoss + pRug
        pMoon /= totalP
        pGoodWin /= totalP
        pSmallWin /= totalP
        pBreakeven /= totalP
        pSmallLoss /= totalP
        pStopLoss /= totalP
        pRug /= totalP
        
        // ═══════════════════════════════════════════════════════════════════════
        // BUILD SCENARIO LIST
        // ═══════════════════════════════════════════════════════════════════════
        
        val scenarios = listOf(
            ScenarioProbability(Scenario.MOON, pMoon, 
                (Scenario.MOON.minReturn + Scenario.MOON.maxReturn) / 2),
            ScenarioProbability(Scenario.GOOD_WIN, pGoodWin,
                (Scenario.GOOD_WIN.minReturn + Scenario.GOOD_WIN.maxReturn) / 2),
            ScenarioProbability(Scenario.SMALL_WIN, pSmallWin,
                (Scenario.SMALL_WIN.minReturn + Scenario.SMALL_WIN.maxReturn) / 2),
            ScenarioProbability(Scenario.BREAKEVEN, pBreakeven,
                (Scenario.BREAKEVEN.minReturn + Scenario.BREAKEVEN.maxReturn) / 2),
            ScenarioProbability(Scenario.SMALL_LOSS, pSmallLoss,
                (Scenario.SMALL_LOSS.minReturn + Scenario.SMALL_LOSS.maxReturn) / 2),
            ScenarioProbability(Scenario.STOP_LOSS, pStopLoss,
                (Scenario.STOP_LOSS.minReturn + Scenario.STOP_LOSS.maxReturn) / 2),
            ScenarioProbability(Scenario.RUG, pRug,
                (Scenario.RUG.minReturn + Scenario.RUG.maxReturn) / 2),
        )
        
        // ═══════════════════════════════════════════════════════════════════════
        // CALCULATE EV
        // ═══════════════════════════════════════════════════════════════════════
        
        val expectedValue = scenarios.sumOf { it.weightedReturn }
        val expectedPnlPct = (expectedValue - 1.0) * 100.0
        
        val winProbability = pMoon + pGoodWin + pSmallWin + (pBreakeven * 0.5)
        val lossProbability = pSmallLoss + pStopLoss + pRug + (pBreakeven * 0.5)
        
        // ═══════════════════════════════════════════════════════════════════════
        // KELLY CRITERION
        // f* = (p × b - q) / b
        // ═══════════════════════════════════════════════════════════════════════
        
        val avgWinReturn = if (winProbability > 0) {
            (pMoon * 30.0 + pGoodWin * 6.5 + pSmallWin * 2.25 + pBreakeven * 0.5 * 1.05) / winProbability
        } else 1.0
        
        val avgLossReturn = if (lossProbability > 0) {
            (pSmallLoss * 0.8 + pStopLoss * 0.6 + pRug * 0.1 + pBreakeven * 0.5 * 0.95) / lossProbability
        } else 1.0
        
        val avgWinPct = avgWinReturn - 1.0
        val avgLossPct = 1.0 - avgLossReturn
        
        val kellyFraction = if (avgLossPct > 0 && avgWinPct > 0) {
            val b = avgWinPct / avgLossPct
            val p = winProbability
            val q = lossProbability
            ((p * b - q) / b).coerceIn(0.0, 0.25)
        } else 0.0
        
        // ═══════════════════════════════════════════════════════════════════════
        // RISK/REWARD RATIO
        // ═══════════════════════════════════════════════════════════════════════
        
        val expectedUpside = pMoon * 29.0 + pGoodWin * 5.5 + pSmallWin * 1.25
        val expectedDownside = pSmallLoss * 0.2 + pStopLoss * 0.4 + pRug * 0.9
        val riskRewardRatio = if (expectedDownside > 0) expectedUpside / expectedDownside else 10.0
        
        // ═══════════════════════════════════════════════════════════════════════
        // CONFIDENCE IN ESTIMATE
        // ═══════════════════════════════════════════════════════════════════════
        
        var confidence = 50.0
        
        // More price history = more confidence
        if (ts.history.size >= 10) confidence += 15.0
        if (ts.history.size >= 20) confidence += 10.0
        
        // High liquidity = more confidence
        if (liquidity > 20_000) confidence += 10.0
        if (liquidity > 50_000) confidence += 10.0
        
        // High entry score = more confidence
        if (entryScore > 70) confidence += 5.0
        
        confidence = confidence.coerceIn(0.0, 100.0)
        
        // ═══════════════════════════════════════════════════════════════════════
        // BUILD RESULT
        // ═══════════════════════════════════════════════════════════════════════
        
        val result = EVResult(
            expectedValue = expectedValue,
            expectedPnlPct = expectedPnlPct,
            winProbability = winProbability,
            lossProbability = lossProbability,
            rugProbability = pRug,
            scenarios = scenarios,
            kellyFraction = kellyFraction,
            isPositiveEV = expectedValue > 1.0,
            confidence = confidence,
            riskRewardRatio = riskRewardRatio,
        )
        
        ErrorLogger.debug("EV", "📊 ${ts.symbol}: ${result.summary()}")
        
        return result
    }
    
    /**
     * Quick check if a trade has positive EV
     */
    fun isPositiveEV(
        ts: TokenState,
        entryScore: Double,
        quality: String,
    ): Boolean {
        val ev = calculate(ts, entryScore, quality)
        return ev.isPositiveEV && ev.rugProbability < 0.15
    }
    
    /**
     * Get recommended position size based on Kelly Criterion
     */
    fun getKellySize(
        ts: TokenState,
        entryScore: Double,
        quality: String,
        maxKelly: Double = 0.10,
    ): Double {
        val ev = calculate(ts, entryScore, quality)
        val halfKelly = ev.kellyFraction * 0.5
        
        val rugAdjust = if (ev.rugProbability > 0.10) {
            1.0 - (ev.rugProbability - 0.10) * 2
        } else 1.0
        
        return (halfKelly * rugAdjust).coerceIn(0.0, maxKelly)
    }
}

package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.AICrossTalk
import com.lifecyclebot.engine.GeminiCopilot
import com.lifecyclebot.engine.MomentumPredictorAI
import com.lifecyclebot.engine.LiquidityDepthAI
import com.lifecyclebot.engine.SuperBrainEnhancements
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * FlowImbalanceModel - Detects order-flow vs price divergence
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * LOGIC:
 * Trigger when:
 * - Buy pressure strong (65%+)
 * - Volume rising/expanding
 * - Momentum positive
 * - Price still hasn't moved enough (lagging)
 * - Liquidity not collapsing
 * - No strong distribution flags
 * 
 * This is probably the BEST arb type for existing infrastructure because:
 * - Uses existing AI signals (Momentum, Liquidity, WhaleTracker)
 * - Data already available from scanner
 * - Clear invalidation conditions
 * 
 * FLOW:
 * Strong buying detected → Price hasn't reacted yet → Enter anticipating move
 */
object FlowImbalanceModel {
    
    private const val TAG = "FlowImbalance"
    
    // Thresholds  
    private const val MIN_LIQUIDITY = 8_000.0       // $8k floor (lower than venue lag)
    private const val MIN_BUY_PRESSURE = 65.0       // 65% buy pressure required
    private const val STRONG_BUY_PRESSURE = 72.0    // Bonus threshold
    private const val MIN_VOLUME_1M = 2_000.0       // $2k 1m volume minimum
    
    // Expected move targets
    private const val EXPECTED_MOVE_PCT = 5.0
    private const val TARGET_PROFIT_PCT = 3.5
    private const val STOP_LOSS_PCT = 2.5
    private const val MAX_HOLD_SECONDS = 90
    
    // Stats
    @Volatile private var evaluationsRun = 0
    @Volatile private var opportunitiesFound = 0
    
    /**
     * Evaluate a candidate for flow imbalance opportunity.
     * Integrates with existing AI layers for comprehensive analysis.
     */
    fun evaluate(candidate: CandidateSnapshot): ArbEvaluation? {
        evaluationsRun++
        
        try {
            val mint = candidate.mint
            val symbol = candidate.symbol
            
            // ═══════════════════════════════════════════════════════════════════
            // BASIC FILTERS
            // ═══════════════════════════════════════════════════════════════════
            
            // Check liquidity
            if (candidate.liquidityUsd < MIN_LIQUIDITY) return null
            
            // Check buy pressure - core requirement
            if (candidate.buyPressurePct < MIN_BUY_PRESSURE) return null
            
            // Check minimum volume
            if (candidate.volume1mUsd < MIN_VOLUME_1M) return null
            
            // ═══════════════════════════════════════════════════════════════════
            // SIGNAL EXTRACTION FROM EXISTING AI LAYERS
            // ═══════════════════════════════════════════════════════════════════
            
            // Get momentum prediction
            val momentum = try { 
                MomentumPredictorAI.getPrediction(mint) 
            } catch (_: Exception) { null }
            
            val isMomentumUp = momentum in listOf(
                MomentumPredictorAI.MomentumPrediction.STRONG_PUMP,
                MomentumPredictorAI.MomentumPrediction.PUMP_BUILDING
            )
            
            // Must have positive momentum
            if (!isMomentumUp) return null
            
            // Get liquidity signal
            val liquiditySignal = try {
                LiquidityDepthAI.getSignal(mint, symbol, isOpenPosition = false)
            } catch (_: Exception) { null }
            
            val isLiquidityDraining = liquiditySignal?.signal in listOf(
                LiquidityDepthAI.SignalType.LIQUIDITY_COLLAPSE,
                LiquidityDepthAI.SignalType.LIQUIDITY_DRAINING
            )
            
            // Don't enter if liquidity is collapsing on low liquidity
            if (isLiquidityDraining && candidate.liquidityUsd < 15_000) return null
            
            // ═══════════════════════════════════════════════════════════════════
            // EXTRACT ADDITIONAL SIGNALS FROM CANDIDATE EXTRA
            // These are populated by the scanner and V3 pipeline
            // ═══════════════════════════════════════════════════════════════════
            
            val volumeExpanding = candidate.extraBoolean("volumeExpanding") ||
                                  candidate.extraBoolean("volumeGrowth") ||
                                  candidate.volume5mUsd > candidate.volume1mUsd * 4
            
            val sellCluster = candidate.extraBoolean("sellCluster") ||
                              candidate.extraBoolean("distributionDetected")
            
            // Don't enter if sell cluster detected
            if (sellCluster) {
                ErrorLogger.debug(TAG, "[ARB] ${symbol} has sell cluster - skip")
                return null
            }
            
            // Price lagging check - buy pressure high but price hasn't moved proportionally
            val recentPriceChange = candidate.extraDouble("priceChange5m")
            val priceLagging = candidate.buyPressurePct >= 70 && recentPriceChange < 8.0
            
            // ═══════════════════════════════════════════════════════════════════
            // AI DEGRADATION CHECK
            // ═══════════════════════════════════════════════════════════════════
            val isAIDegraded = try { GeminiCopilot.isAIDegraded() } catch (_: Exception) { false }
            
            // ═══════════════════════════════════════════════════════════════════
            // SCORE CALCULATION
            // Base 45 + bonuses
            // ═══════════════════════════════════════════════════════════════════
            var score = 45
            
            // Strong buy pressure bonus
            if (candidate.buyPressurePct >= STRONG_BUY_PRESSURE) score += 10
            if (candidate.buyPressurePct >= 78) score += 5
            
            // Volume expanding bonus
            if (volumeExpanding) score += 8
            
            // Good liquidity bonus
            if (candidate.liquidityUsd >= 20_000) score += 6
            if (candidate.liquidityUsd >= 35_000) score += 4
            
            // Price lagging bonus (flow ahead of price)
            if (priceLagging) score += 7
            
            // Strong momentum bonus
            if (momentum == MomentumPredictorAI.MomentumPrediction.STRONG_PUMP) score += 8
            
            // Liquidity growing bonus
            if (liquiditySignal?.signal in listOf(
                LiquidityDepthAI.SignalType.LIQUIDITY_SPIKE,
                LiquidityDepthAI.SignalType.LIQUIDITY_GROWING
            )) {
                score += 6
            }
            
            // CrossTalk integration - check for correlated bullish signals
            val crossTalk = try {
                AICrossTalk.analyzeCrossTalk(mint, symbol, isOpenPosition = false)
            } catch (_: Exception) { null }
            
            if (crossTalk != null) {
                // Boost for smart money pump detection
                if (crossTalk.signalType == AICrossTalk.SignalType.SMART_MONEY_PUMP) {
                    score += 12
                }
                // General entry boost
                if (crossTalk.entryBoost > 5) {
                    score += (crossTalk.entryBoost * 0.4).toInt().coerceAtMost(8)
                }
                // Penalty for coordinated dump
                if (crossTalk.signalType == AICrossTalk.SignalType.COORDINATED_DUMP) {
                    ErrorLogger.debug(TAG, "[ARB] ${symbol} coordinated dump detected - skip")
                    return null
                }
            }
            
            // SuperBrain sentiment integration
            val sentiment = try { SuperBrainEnhancements.getCurrentSentiment() } catch (_: Exception) { "UNKNOWN" }
            if (sentiment in listOf("STRONG_BULL", "BULL")) score += 5
            if (sentiment in listOf("STRONG_BEAR", "BEAR")) score -= 5
            
            // AI degradation penalty (but don't block if score very strong)
            if (isAIDegraded && score < 70) {
                ErrorLogger.debug(TAG, "[ARB] ${symbol} AI degraded and score not strong enough")
                return null
            }
            
            // Cap score
            score = score.coerceAtMost(85)
            
            // ═══════════════════════════════════════════════════════════════════
            // CONFIDENCE CALCULATION
            // ═══════════════════════════════════════════════════════════════════
            var confidence = 45
            
            // Buy pressure confidence
            confidence += ((candidate.buyPressurePct - 65) / 2).toInt().coerceIn(0, 12)
            
            // Volume confidence
            if (volumeExpanding) confidence += 5
            if (candidate.volume1mUsd >= 10_000) confidence += 5
            
            // Momentum confidence
            if (momentum == MomentumPredictorAI.MomentumPrediction.STRONG_PUMP) confidence += 8
            
            // CrossTalk confidence boost
            if (crossTalk != null && crossTalk.confidenceBoost > 0) {
                confidence += (crossTalk.confidenceBoost * 0.5).toInt().coerceAtMost(8)
            }
            
            // AI degradation penalty
            if (isAIDegraded) confidence -= 10
            
            // Cap confidence
            confidence = confidence.coerceIn(0, 75)
            
            // ═══════════════════════════════════════════════════════════════════
            // BAND DETERMINATION
            // ═══════════════════════════════════════════════════════════════════
            val band = when {
                score >= 60 && confidence >= ArbThresholds.ARB_STANDARD_MIN_CONF -> ArbDecisionBand.ARB_STANDARD
                score >= 50 && confidence >= ArbThresholds.ARB_MICRO_MIN_CONF -> ArbDecisionBand.ARB_MICRO
                score >= 45 -> ArbDecisionBand.ARB_WATCH
                else -> ArbDecisionBand.ARB_REJECT
            }
            
            if (band != ArbDecisionBand.ARB_REJECT && band != ArbDecisionBand.ARB_WATCH) {
                opportunitiesFound++
            }
            
            ErrorLogger.info(TAG, "[ARB] FLOW_IMBALANCE $symbol | score=$score conf=$confidence | " +
                "bp=${candidate.buyPressurePct.toInt()}% mom=$momentum vol=${volumeExpanding} | band=$band")
            
            return ArbEvaluation(
                arbType = ArbType.FLOW_IMBALANCE,
                score = score,
                confidence = confidence,
                band = band,
                expectedMovePct = EXPECTED_MOVE_PCT,
                maxHoldSeconds = MAX_HOLD_SECONDS,
                reason = "Order-flow imbalance exceeds price response (bp=${candidate.buyPressurePct.toInt()}%, momentum=$momentum)",
                targetProfitPct = TARGET_PROFIT_PCT,
                stopLossPct = STOP_LOSS_PCT,
                notes = listOf(
                    "buyPressure=${candidate.buyPressurePct.toInt()}%",
                    "momentum=$momentum",
                    "volumeExpanding=$volumeExpanding",
                    "crossTalk=${crossTalk?.signalType ?: "NONE"}"
                )
            )
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "evaluate error: ${e.message}")
            return null
        }
    }
    
    /**
     * Get stats for logging.
     */
    fun getStats(): String {
        return "FlowImbalance: $evaluationsRun evaluated | $opportunitiesFound opportunities"
    }
}

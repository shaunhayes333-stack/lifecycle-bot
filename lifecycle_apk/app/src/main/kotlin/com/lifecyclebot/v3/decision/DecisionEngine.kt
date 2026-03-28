package com.lifecyclebot.v3.decision

import com.lifecyclebot.v3.core.DecisionBand
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.learning.LearningMetrics
import com.lifecyclebot.v3.risk.FatalRiskResult
import com.lifecyclebot.v3.scoring.ScoreCard
import kotlin.math.roundToInt

/**
 * V3 Confidence Breakdown
 */
data class ConfidenceBreakdown(
    val statistical: Int,
    val structural: Int,
    val operational: Int,
    val effective: Int
)

/**
 * V3 Ops Metrics
 */
data class OpsMetrics(
    val apiHealthy: Boolean = true,
    val feedsHealthy: Boolean = true,
    val walletHealthy: Boolean = true,
    val latencyMs: Long = 100
)

/**
 * V3 Confidence Engine
 * Computes confidence from statistical, structural, and operational factors
 */
class ConfidenceEngine {
    
    /**
     * Compute confidence breakdown
     * Effective = 50% Statistical + 35% Structural + 15% Operational
     */
    fun compute(
        scoreCard: ScoreCard,
        metrics: LearningMetrics,
        ops: OpsMetrics
    ): ConfidenceBreakdown {
        val statistical = computeStatistical(metrics)
        val structural = computeStructural(scoreCard)
        val operational = computeOperational(ops)
        
        val effective = (
            0.50 * statistical +
            0.35 * structural +
            0.15 * operational
        ).roundToInt().coerceIn(0, 100)
        
        return ConfidenceBreakdown(
            statistical = statistical,
            structural = structural,
            operational = operational,
            effective = effective
        )
    }
    
    private fun computeStatistical(metrics: LearningMetrics): Int {
        var score = 10
        
        // More classified trades = more confidence
        score += (metrics.classifiedTrades / 10).coerceAtMost(20)
        
        // Win rate adjustment
        score += ((metrics.last20WinRatePct - 50.0) / 5.0).toInt().coerceIn(-10, 10)
        
        // Payoff ratio
        score += ((metrics.payoffRatio - 1.0) * 10.0).toInt().coerceIn(-10, 10)
        
        // Penalize false blocks and missed winners
        score -= (metrics.falseBlockRatePct / 10.0).toInt().coerceIn(0, 10)
        score -= (metrics.missedWinnerRatePct / 10.0).toInt().coerceIn(0, 10)
        
        return score.coerceIn(0, 100)
    }
    
    private fun computeStructural(scoreCard: ScoreCard): Int {
        var score = 30
        
        // Total score contribution
        score += (scoreCard.total / 2).coerceIn(-20, 35)
        
        // Positive signals boost confidence
        score += (scoreCard.positiveCount() * 2)
        
        // Negative signals reduce confidence
        score -= scoreCard.negativeCount()
        
        return score.coerceIn(0, 100)
    }
    
    private fun computeOperational(ops: OpsMetrics): Int {
        var score = 50
        
        if (ops.apiHealthy) score += 15 else score -= 20
        if (ops.feedsHealthy) score += 15 else score -= 20
        if (ops.walletHealthy) score += 10 else score -= 20
        
        // Latency penalty
        when {
            ops.latencyMs > 2_000 -> score -= 20
            ops.latencyMs > 750 -> score -= 10
        }
        
        return score.coerceIn(0, 100)
    }
}

/**
 * V3 Decision Result
 */
data class DecisionResult(
    val band: DecisionBand,
    val finalScore: Int,
    val statisticalConfidence: Int,
    val structuralConfidence: Int,
    val operationalConfidence: Int,
    val effectiveConfidence: Int,
    val reasons: List<String>,
    val fatalReason: String? = null
)

/**
 * V3 Final Decision Engine
 * Maps score + confidence to decision band
 * 
 * Now integrates with V3ConfidenceConfig for user-adjustable thresholds:
 * - AGGRESSIVE mode: Lower thresholds, more trades
 * - STANDARD mode: Default thresholds
 * - CONSERVATIVE mode: Higher thresholds, fewer trades
 */
class FinalDecisionEngine(
    private val config: TradingConfigV3
) {
    /**
     * Make final decision based on score, confidence, and fatal check
     */
    fun decide(
        scoreCard: ScoreCard,
        confidence: ConfidenceBreakdown,
        fatal: FatalRiskResult
    ): DecisionResult {
        // Fatal block overrides everything
        if (fatal.blocked) {
            return DecisionResult(
                band = DecisionBand.BLOCK_FATAL,
                finalScore = scoreCard.total,
                statisticalConfidence = confidence.statistical,
                structuralConfidence = confidence.structural,
                operationalConfidence = confidence.operational,
                effectiveConfidence = confidence.effective,
                reasons = listOf("Fatal block"),
                fatalReason = fatal.reason
            )
        }
        
        val score = scoreCard.total
        val conf = confidence.effective
        
        // Get adjusted thresholds from V3ConfidenceConfig
        val minScoreForExecute = try {
            com.lifecyclebot.engine.V3ConfidenceConfig.getMinScoreForExecute(config.executeStandardMin)
        } catch (e: Exception) {
            config.executeStandardMin
        }
        
        val minConfForExecute = try {
            com.lifecyclebot.engine.V3ConfidenceConfig.getMinConfidenceForExecute(45)
        } catch (e: Exception) {
            45
        }
        
        // Band selection with adjusted thresholds
        val band = when {
            score >= (minScoreForExecute * 1.3).toInt() && conf >= minConfForExecute + 10 -> DecisionBand.EXECUTE_AGGRESSIVE
            score >= minScoreForExecute && conf >= minConfForExecute -> DecisionBand.EXECUTE_STANDARD
            score >= (minScoreForExecute * 0.7).toInt() && conf >= minConfForExecute - 15 -> DecisionBand.EXECUTE_SMALL
            score >= config.watchScoreMin -> DecisionBand.WATCH
            else -> DecisionBand.REJECT
        }
        
        return DecisionResult(
            band = band,
            finalScore = score,
            statisticalConfidence = confidence.statistical,
            structuralConfidence = confidence.structural,
            operationalConfidence = confidence.operational,
            effectiveConfidence = conf,
            reasons = scoreCard.components.map { "${it.name}:${it.value} (${it.reason})" }
        )
    }
}

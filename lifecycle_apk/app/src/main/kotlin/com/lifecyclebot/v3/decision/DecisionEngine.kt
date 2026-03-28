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
 * V3 SELECTIVITY TUNING:
 * - Compound weakness veto: C-grade + low conf + negative memory/narrative → WATCH
 * - AI degradation penalty: degraded AI = confidence cap
 * - Tighter C-grade thresholds: requires conf >= 40 for execute
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
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: Extract component scores for compound weakness check
        // ═══════════════════════════════════════════════════════════════════
        val memoryScore = scoreCard.components.find { it.name == "memory" }?.value ?: 0
        val narrativeScore = scoreCard.components.find { it.name == "narrative" }?.value ?: 0
        val suppressionScore = scoreCard.components.find { it.name == "suppression" }?.value ?: 0
        
        // Check for AI degradation (ops.apiHealthy = false means degraded)
        val isAIDegraded = confidence.operational < 50
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: Compound weakness veto
        // 
        // If ALL of these are true:
        //   - Score is in C-grade range (below standard threshold)
        //   - Confidence < 35
        //   - Memory negative (< 0)
        //   - Narrative negative (< 0)
        // Then: WATCH only, do not execute
        //
        // This prevents trading weak setups with stacked negatives.
        // ═══════════════════════════════════════════════════════════════════
        val minScoreForExecute = try {
            com.lifecyclebot.engine.V3ConfidenceConfig.getMinScoreForExecute(config.executeStandardMin)
        } catch (e: Exception) {
            config.executeStandardMin
        }
        
        val isCGrade = score < minScoreForExecute
        val isLowConfidence = conf < 35
        val hasNegativeMemory = memoryScore < 0
        val hasNegativeNarrative = narrativeScore < 0
        val hasSuppression = suppressionScore < -10
        
        // Count stacked weaknesses
        val weaknessCount = listOf(
            isCGrade,
            isLowConfidence,
            hasNegativeMemory,
            hasNegativeNarrative,
            hasSuppression,
            isAIDegraded
        ).count { it }
        
        // COMPOUND WEAKNESS VETO: 3+ weaknesses = WATCH only
        if (weaknessCount >= 3 && score < minScoreForExecute) {
            return DecisionResult(
                band = DecisionBand.WATCH,
                finalScore = score,
                statisticalConfidence = confidence.statistical,
                structuralConfidence = confidence.structural,
                operationalConfidence = confidence.operational,
                effectiveConfidence = conf,
                reasons = listOf("COMPOUND_WEAKNESS: weaknesses=$weaknessCount (mem=$memoryScore narr=$narrativeScore conf=$conf)")
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: AI degradation cap
        // 
        // If AI is degraded, cap effective confidence and be more conservative.
        // C-grade setups with degraded AI → WATCH only
        // ═══════════════════════════════════════════════════════════════════
        val effectiveConf = if (isAIDegraded && isCGrade) {
            // Degraded AI + C-grade = force WATCH
            return DecisionResult(
                band = DecisionBand.WATCH,
                finalScore = score,
                statisticalConfidence = confidence.statistical,
                structuralConfidence = confidence.structural,
                operationalConfidence = confidence.operational,
                effectiveConfidence = conf,
                reasons = listOf("AI_DEGRADED: C-grade with degraded AI → WATCH")
            )
        } else if (isAIDegraded) {
            // Degraded AI = confidence cap at 50
            minOf(conf, 50)
        } else {
            conf
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: Tighter C-grade thresholds
        // 
        // C-grade (score below standard) can only execute if:
        //   - Confidence >= 40 (was 30)
        //   - No major negative memory/narrative hit (combined > -10)
        //   - Liquidity reasonable (handled by scoring)
        // ═══════════════════════════════════════════════════════════════════
        val minConfForExecute = try {
            com.lifecyclebot.engine.V3ConfidenceConfig.getMinConfidenceForExecute(45)
        } catch (e: Exception) {
            45
        }
        
        // C-grade requires HIGHER confidence threshold (40 instead of 30)
        val cGradeMinConf = 40
        val combinedSentiment = memoryScore + narrativeScore
        
        if (isCGrade && (effectiveConf < cGradeMinConf || combinedSentiment < -10)) {
            return DecisionResult(
                band = DecisionBand.WATCH,
                finalScore = score,
                statisticalConfidence = confidence.statistical,
                structuralConfidence = confidence.structural,
                operationalConfidence = confidence.operational,
                effectiveConfidence = effectiveConf,
                reasons = listOf("C_GRADE_FILTER: conf=$effectiveConf<$cGradeMinConf or sentiment=$combinedSentiment<-10")
            )
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 BAND SELECTION: Standard thresholds for B+ grade setups
        // ═══════════════════════════════════════════════════════════════════
        val band = when {
            score >= (minScoreForExecute * 1.3).toInt() && effectiveConf >= minConfForExecute + 10 -> DecisionBand.EXECUTE_AGGRESSIVE
            score >= minScoreForExecute && effectiveConf >= minConfForExecute -> DecisionBand.EXECUTE_STANDARD
            score >= (minScoreForExecute * 0.7).toInt() && effectiveConf >= cGradeMinConf -> DecisionBand.EXECUTE_SMALL
            score >= config.watchScoreMin -> DecisionBand.WATCH
            else -> DecisionBand.REJECT
        }
        
        return DecisionResult(
            band = band,
            finalScore = score,
            statisticalConfidence = confidence.statistical,
            structuralConfidence = confidence.structural,
            operationalConfidence = confidence.operational,
            effectiveConfidence = effectiveConf,
            reasons = scoreCard.components.map { "${it.name}:${it.value} (${it.reason})" }
        )
    }
}

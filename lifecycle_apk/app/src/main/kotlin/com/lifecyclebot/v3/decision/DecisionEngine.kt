package com.lifecyclebot.v3.decision

import com.lifecyclebot.v3.core.DecisionBand
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.learning.LearningMetrics
import com.lifecyclebot.v3.risk.FatalRiskResult
import com.lifecyclebot.v3.scoring.ScoreCard
import com.lifecyclebot.engine.ToxicModeCircuitBreaker
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
     * 
     * V3.2 ADDITION: ToxicModeCircuitBreaker integration
     * Checks for hard-disabled modes and liquidity floors BEFORE scoring
     */
    fun decide(
        scoreCard: ScoreCard,
        confidence: ConfidenceBreakdown,
        fatal: FatalRiskResult,
        tradingMode: String = "",
        source: String = "",
        phase: String = "",
        isAIDegraded: Boolean = false
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
        // V3 SELECTIVITY: Extract component scores and context
        // ═══════════════════════════════════════════════════════════════════
        val memoryScore = scoreCard.components.find { it.name == "memory" }?.value ?: 0
        val narrativeScore = scoreCard.components.find { it.name == "narrative" }?.value ?: 0
        val suppressionScore = scoreCard.components.find { it.name == "suppression" }?.value ?: 0
        val liquidityUsd = scoreCard.components.find { it.name == "liquidity" }?.let { 
            // Extract raw liquidity from reason string if available
            it.reason.substringAfter("liq=").substringBefore(" ").toDoubleOrNull()
        } ?: 0.0
        
        // Extract phase from scoring - check entry module reason
        val extractedPhase = scoreCard.components.find { it.name == "entry" }?.reason?.let {
            when {
                it.contains("early_unknown") -> "early_unknown"
                it.contains("pre_pump") -> "pre_pump"
                it.contains("pump_building") -> "pump_building"
                it.contains("accumulation") -> "accumulation"
                else -> "unknown"
            }
        } ?: "unknown"
        
        // Use extracted phase if not provided
        val effectivePhase = if (phase.isNotBlank()) phase else extractedPhase
        
        // Check for AI degradation (ops.apiHealthy = false means degraded)
        val effectiveAIDegraded = isAIDegraded || confidence.operational < 50
        
        // ═══════════════════════════════════════════════════════════════════
        // V3.2 TOXIC MODE CIRCUIT BREAKER CHECK
        // 
        // CRITICAL: This check happens BEFORE any scoring-based decisions.
        // If the circuit breaker blocks entry, we return WATCH immediately.
        // 
        // Blocks:
        // - COPY_TRADE mode (hard disabled after -92% loss)
        // - WHALE_FOLLOW below $15k liquidity
        // - Any mode below liquidity floor
        // - Frozen modes (circuit breaker tripped)
        // ═══════════════════════════════════════════════════════════════════
        if (tradingMode.isNotBlank()) {
            val circuitBlockReason = ToxicModeCircuitBreaker.checkEntryAllowed(
                mode = tradingMode,
                source = source,
                liquidityUsd = liquidityUsd,
                phase = effectivePhase,
                memoryScore = memoryScore,
                isAIDegraded = effectiveAIDegraded,
                confidence = conf
            )
            
            if (circuitBlockReason != null) {
                return DecisionResult(
                    band = DecisionBand.WATCH,  // Block to WATCH, not REJECT
                    finalScore = score,
                    statisticalConfidence = confidence.statistical,
                    structuralConfidence = confidence.structural,
                    operationalConfidence = confidence.operational,
                    effectiveConfidence = conf,
                    reasons = listOf("CIRCUIT_BREAKER: $circuitBlockReason", "mode=$tradingMode", "liq=$liquidityUsd"),
                    fatalReason = "ToxicModeCircuitBreaker: $circuitBlockReason"
                )
            }
        }
        
        val minScoreForExecute = try {
            com.lifecyclebot.engine.V3ConfidenceConfig.getMinScoreForExecute(config.executeStandardMin)
        } catch (e: Exception) {
            config.executeStandardMin
        }
        
        val isCGrade = score < minScoreForExecute
        val isBGrade = score >= minScoreForExecute
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: HARD C-GRADE EXECUTION BAN
        // 
        // V3.2: LOOSENED THRESHOLDS - previous settings blocked EVERYTHING
        // 
        // For quality=C, require ALL of:
        //   - conf >= 25 (was 35)
        //   - memory > -12 (was -8)
        //   - AI not degraded
        //   - phase is not early_unknown
        //
        // If ANY fail → WATCH ONLY, SHADOW TRACK, NO BUY
        // ═══════════════════════════════════════════════════════════════════
        if (isCGrade) {
            val cGradeBlockReasons = mutableListOf<String>()
            
            if (conf < 25) {  // V3.2: Lowered from 35
                cGradeBlockReasons.add("conf=$conf<25")
            }
            if (memoryScore <= -12) {  // V3.2: Lowered from -8
                cGradeBlockReasons.add("memory=$memoryScore<=-12")
            }
            if (effectiveAIDegraded) {
                cGradeBlockReasons.add("AI_DEGRADED")
            }
            // NOTE: liquidityUsd comes from candidate in BotOrchestrator, not scoreCard
            // We check this in BotOrchestrator before calling decide()
            if (effectivePhase == "early_unknown") {
                cGradeBlockReasons.add("phase=early_unknown")
            }
            
            if (cGradeBlockReasons.isNotEmpty()) {
                return DecisionResult(
                    band = DecisionBand.WATCH,
                    finalScore = score,
                    statisticalConfidence = confidence.statistical,
                    structuralConfidence = confidence.structural,
                    operationalConfidence = confidence.operational,
                    effectiveConfidence = conf,
                    reasons = listOf("C_GRADE_BAN: ${cGradeBlockReasons.joinToString(", ")} → WATCH ONLY")
                )
            }
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: AI DEGRADATION CAP
        // 
        // If AI is degraded:
        //   - C-grade = WATCH only (handled above in C_GRADE_BAN)
        //   - B+ grade = can still execute but confidence capped at 50
        // ═══════════════════════════════════════════════════════════════════
        val effectiveConf = if (effectiveAIDegraded) {
            minOf(conf, 50)
        } else {
            conf
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 BAND SELECTION
        // 
        // At this point, C-grade trash has been filtered by C_GRADE_BAN above.
        // Only legitimate setups reach here.
        // ═══════════════════════════════════════════════════════════════════
        val minConfForExecute = try {
            com.lifecyclebot.engine.V3ConfidenceConfig.getMinConfidenceForExecute(35)  // V3.2: Lowered from 45
        } catch (e: Exception) {
            35  // V3.2: Lowered from 45
        }
        
        val cGradeMinConf = 28  // V3.2: Lowered from 35 (C-grade can execute with conf >= 28)
        
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

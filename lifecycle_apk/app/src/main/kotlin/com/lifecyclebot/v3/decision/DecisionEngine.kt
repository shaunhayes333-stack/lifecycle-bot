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
        isAIDegraded: Boolean = false,
        isPaperMode: Boolean = false  // V5.2: Paper mode bypasses liquidity floors
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
                confidence = conf,
                isPaperMode = isPaperMode  // V5.2: Paper mode bypasses liquidity floors
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
            // V3.3: Use FLUID thresholds from FluidLearningAI during bootstrap
            val fluidMinScore = com.lifecyclebot.v3.scoring.FluidLearningAI.getMinScoreThreshold()
            val configMinScore = com.lifecyclebot.engine.V3ConfidenceConfig.getMinScoreForExecute(config.executeStandardMin)
            // Use the LOWER of fluid vs config during bootstrap (3% learning = use fluid)
            minOf(fluidMinScore, configMinScore)
        } catch (e: Exception) {
            config.executeStandardMin
        }
        
        val isCGrade = score < minScoreForExecute
        val isBGrade = score >= minScoreForExecute
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: HARD C-GRADE EXECUTION BAN
        // 
        // V3.3: FLUID THRESHOLDS - looser during bootstrap to learn
        // 
        // For quality=C, require ALL of:
        //   - conf >= 18 at bootstrap → 25 at mature
        //   - memory > -15 at bootstrap → -12 at mature
        //   - AI not degraded
        //   - phase is not early_unknown (ONLY for mature - allow early learning)
        //
        // If ANY fail → WATCH ONLY, SHADOW TRACK, NO BUY
        // ═══════════════════════════════════════════════════════════════════
        val cGradeProgress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (e: Exception) { 0.0 }
        
        // Fluid thresholds for C-grade
        // V5.5: Hard min 25 confidence — no C-grade token enters below 25% confidence
        val cGradeConfFloor = (15 + (cGradeProgress * 15)).toInt().coerceIn(25, 30)
        val cGradeMemoryFloor = (-25 + (cGradeProgress * 10)).toInt().coerceIn(-25, -15)

        // Extract momentum and volume for weak-signal veto
        val momentumScoreV = scoreCard.components.find { it.name == "momentum" }?.value ?: 0
        val volumeScoreV   = scoreCard.components.find { it.name == "volume" }?.value ?: 0

        if (isCGrade) {
            val cGradeBlockReasons = mutableListOf<String>()

            if (conf < cGradeConfFloor) {
                cGradeBlockReasons.add("conf=$conf<$cGradeConfFloor")
            }
            if (memoryScore <= cGradeMemoryFloor) {
                cGradeBlockReasons.add("memory=$memoryScore<=$cGradeMemoryFloor")
            }
            if (effectiveAIDegraded) {
                cGradeBlockReasons.add("AI_DEGRADED")
            }
            // V3.3: Only block early_unknown phase when mature (>30% learning)
            // During bootstrap, allow early_unknown to let bot learn
            if (effectivePhase == "early_unknown" && cGradeProgress > 0.3) {
                cGradeBlockReasons.add("phase=early_unknown")
            }
            // V5.4: Weak-signal veto — if score < 20 AND both momentum AND volume are flat/negative,
            // there is no directional edge. Block regardless of learning phase.
            if (score < 20 && momentumScoreV <= 0 && volumeScoreV <= 0) {
                cGradeBlockReasons.add("no_momentum_no_volume_score=$score")
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
        //
        // V3.3: FLUID THRESHOLDS based on learning progress
        // Bootstrap (0-20% learning): Much looser - take more shots to learn
        // Mature (50%+ learning): Tighter - only high-conviction trades
        // ═══════════════════════════════════════════════════════════════════
        val learningProgress = try {
            com.lifecyclebot.v3.scoring.FluidLearningAI.getLearningProgress()
        } catch (e: Exception) { 0.0 }

        // Fluid confidence threshold: 18% at bootstrap → 40% at mature
        // V5.4: Raised from (10+progress*20) to reduce low-conviction junk trades
        val fluidMinConfForExecute = (18 + (learningProgress * 22)).toInt().coerceIn(18, 40)

        val minConfForExecute = try {
            val configMinConf = com.lifecyclebot.engine.V3ConfidenceConfig.getMinConfidenceForExecute(35)
            // Use fluid (lower) threshold during bootstrap
            minOf(fluidMinConfForExecute, configMinConf)
        } catch (e: Exception) {
            fluidMinConfForExecute
        }

        // C-grade confidence floor for EXECUTE_SMALL: 15% at bootstrap → 30% at mature
        // V5.4: Raised from (8+progress*12) so weak-conf meme coins stop bleeding
        val cGradeMinConf = (15 + (learningProgress * 15)).toInt().coerceIn(15, 30)

        // ═══════════════════════════════════════════════════════════════════
        // V5.5: DIRECTIONAL GATE — block only when BOTH signals are actively
        // negative (< 0). A score of 0 means "no data / neutral" for new tokens
        // and should not block execution. Only a confirmed declining momentum
        // AND declining volume means no directional edge.
        // ═══════════════════════════════════════════════════════════════════
        val hasMomentumOrVolume = !(momentumScoreV < 0 && volumeScoreV < 0)

        val band = when {
            hasMomentumOrVolume && score >= (minScoreForExecute * 1.3).toInt() && effectiveConf >= minConfForExecute + 10 -> DecisionBand.EXECUTE_AGGRESSIVE
            hasMomentumOrVolume && score >= minScoreForExecute && effectiveConf >= minConfForExecute -> DecisionBand.EXECUTE_STANDARD
            hasMomentumOrVolume && score >= (minScoreForExecute * 0.7).toInt() && effectiveConf >= cGradeMinConf -> DecisionBand.EXECUTE_SMALL
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

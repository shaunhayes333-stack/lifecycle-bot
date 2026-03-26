package com.lifecyclebot.engine

import com.lifecyclebot.data.BotStatus

/**
 * ClosedLoopFeedback — Self-Representing Trading Agent (CONSTRAINED)
 * 
 * Creates a CONTROLLED feedback loop:
 *   1. Visual state influences decisions (DAMPED)
 *   2. Decisions update visual state  
 *   3. System learns from both
 * 
 * CRITICAL CONSTRAINTS to prevent runaway:
 *   - Separate Truth vs Perception
 *   - Risk-based damping
 *   - Hard confidence cap (85%)
 *   - EMA smoothing (lag)
 */
object ClosedLoopFeedback {
    
    // ═══════════════════════════════════════════════════════════════════
    // CONFIGURATION — Tuneable safety limits
    // ═══════════════════════════════════════════════════════════════════
    
    private const val CONFIDENCE_CAP = 85.0           // HARD LIMIT - never exceed
    private const val MAX_FEEDBACK_INFLUENCE = 15.0   // Max +/-15% (reduced from +/-30%)
    private const val EMA_SMOOTHING_FACTOR = 0.2      // Slow adaptation (5-tick effective period)
    private const val HIGH_RISK_THRESHOLD = 60.0      // Above this, dampen feedback heavily
    
    // ═══════════════════════════════════════════════════════════════════
    // STATE LAYERS — Truth vs Perception vs Feedback
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * TrueState — Raw AI outputs (scores, signals)
     * This is the SOURCE OF TRUTH, never modified by feedback
     */
    data class TrueState(
        val aiConfidence: Double,      // Raw AI confidence (0-100)
        val winRate: Double,           // Actual win rate
        val consecutiveLosses: Int,    // Actual loss streak
        val totalExposureSol: Double,  // Actual exposure
        val unrealizedPnlPct: Double,  // Actual PnL
    )
    
    /**
     * VisualState — Rendered interpretation (what UI shows)
     * Derived from TrueState, may include smoothing/formatting
     */
    data class VisualState(
        val displayConfidence: Double,
        val displayWinRate: Double,
        val riskLevel: String,         // "LOW", "MEDIUM", "HIGH", "CRITICAL"
        val riskScore: Double,         // 0-100, used for damping
        val brainPhase: String,
        val marketRegime: String,
        val capturedAt: Long = System.currentTimeMillis(),
    )
    
    /**
     * FeedbackState — Dampened, lagged signal that feeds back
     * This is the ONLY thing that can influence decisions
     */
    data class FeedbackState(
        val smoothedInfluence: Double, // EMA-smoothed influence
        val dampingFactor: Double,     // How much feedback is suppressed (0-1)
        val cappedBoost: Double,       // Final boost after all constraints
        val recommendation: String,
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // EMA STATE — For smoothing/lagging feedback
    // ═══════════════════════════════════════════════════════════════════
    
    private var emaInfluence = 0.0  // Exponential moving average of influence
    private var lastRiskScore = 50.0
    
    // ═══════════════════════════════════════════════════════════════════
    // FEEDBACK MEMORY — Learn from visual state -> outcome correlations
    // ═══════════════════════════════════════════════════════════════════
    
    data class FeedbackMemory(
        val trueState: TrueState,
        val feedbackApplied: Double,
        val decisionMade: String,
        val outcome: Double?,
        val wasCorrect: Boolean?,
    )
    
    private val feedbackHistory = mutableListOf<FeedbackMemory>()
    private const val MAX_HISTORY = 500
    private val patternSuccess = mutableMapOf<String, Pair<Int, Int>>()
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 1: CAPTURE TRUE STATE (Raw AI outputs)
    // ═══════════════════════════════════════════════════════════════════
    
    fun captureTrueState(status: BotStatus): TrueState {
        val cb = status.circuitBreaker
        return TrueState(
            aiConfidence = status.avgConfidence,
            winRate = status.winRate,
            consecutiveLosses = cb.consecutiveLosses,
            totalExposureSol = status.totalExposureSol,
            unrealizedPnlPct = status.unrealizedPnlPct,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 2: DERIVE VISUAL STATE (Rendered interpretation)
    // ═══════════════════════════════════════════════════════════════════
    
    fun deriveVisualState(trueState: TrueState, status: BotStatus): VisualState {
        // Calculate risk score (0-100) — used for damping
        val riskScore = calculateRiskScore(trueState)
        
        val riskLevel = when {
            riskScore >= 80 -> "CRITICAL"
            riskScore >= 60 -> "HIGH"
            riskScore >= 40 -> "MEDIUM"
            else -> "LOW"
        }
        
        // Brain phase from trade count
        val tradeCount = status.trades24h
        val brainPhase = when {
            tradeCount < 30 -> "bootstrap"
            tradeCount < 200 -> "learning"
            else -> "mature"
        }
        
        val marketRegime = try { 
            MarketRegimeAI.currentRegime().name.lowercase() 
        } catch (_: Exception) { "unknown" }
        
        return VisualState(
            displayConfidence = trueState.aiConfidence,
            displayWinRate = trueState.winRate,
            riskLevel = riskLevel,
            riskScore = riskScore,
            brainPhase = brainPhase,
            marketRegime = marketRegime,
        )
    }
    
    /**
     * Risk score calculation — higher = more dangerous
     */
    private fun calculateRiskScore(trueState: TrueState): Double {
        var risk = 0.0
        
        // Loss streak risk
        risk += trueState.consecutiveLosses * 15.0  // Each loss = +15 risk
        
        // Exposure risk
        if (trueState.totalExposureSol > 0.5) risk += 20.0
        if (trueState.totalExposureSol > 1.0) risk += 20.0
        
        // Unrealized loss risk
        if (trueState.unrealizedPnlPct < -10) risk += 15.0
        if (trueState.unrealizedPnlPct < -20) risk += 15.0
        
        // Low win rate risk
        if (trueState.winRate < 40) risk += 15.0
        
        return risk.coerceIn(0.0, 100.0)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 3: EXTRACT FEEDBACK (Dampened, lagged signal)
    // ═══════════════════════════════════════════════════════════════════
    
    fun extractFeedback(visual: VisualState): FeedbackState {
        // Calculate raw influence (before constraints)
        val rawInfluence = calculateRawInfluence(visual)
        
        // CONSTRAINT 1: EMA Smoothing (lag the feedback)
        // Prevents jitter decisions and emotional-style overtrading
        emaInfluence = EMA_SMOOTHING_FACTOR * rawInfluence + (1 - EMA_SMOOTHING_FACTOR) * emaInfluence
        
        // CONSTRAINT 2: Risk-based damping
        // High risk -> feedback weak | Low risk -> feedback allowed
        val dampingFactor = 1.0 - (visual.riskScore / 100.0)
        val dampedInfluence = emaInfluence * dampingFactor
        
        // CONSTRAINT 3: Hard limit on influence magnitude
        val cappedInfluence = dampedInfluence.coerceIn(-MAX_FEEDBACK_INFLUENCE, MAX_FEEDBACK_INFLUENCE)
        
        // Store risk score for trend analysis
        lastRiskScore = visual.riskScore
        
        val recommendation = when {
            visual.riskScore >= 80 -> "HALT - Risk critical, feedback disabled"
            visual.riskScore >= 60 -> "MINIMAL - High risk, feedback heavily damped"
            cappedInfluence > 5 -> "NORMAL - Conditions favorable"
            cappedInfluence < -5 -> "CAUTIOUS - Negative signals detected"
            else -> "NEUTRAL - No strong feedback signal"
        }
        
        return FeedbackState(
            smoothedInfluence = emaInfluence,
            dampingFactor = dampingFactor,
            cappedBoost = cappedInfluence,
            recommendation = recommendation,
        )
    }
    
    /**
     * Calculate raw influence before constraints
     * Returns -30 to +30 (will be constrained later)
     */
    private fun calculateRawInfluence(visual: VisualState): Double {
        var influence = 0.0
        
        // Win rate signal (subtle)
        if (visual.displayWinRate > 60 && visual.brainPhase == "mature") {
            influence += 5.0
        } else if (visual.displayWinRate < 40) {
            influence -= 8.0
        }
        
        // Market regime signal
        when (visual.marketRegime) {
            "bull" -> influence += 3.0
            "bear" -> influence -= 8.0
            "chop" -> influence -= 3.0
        }
        
        // Brain phase signal (conservative in early phases)
        when (visual.brainPhase) {
            "bootstrap" -> influence -= 5.0  // Extra conservative
            "learning" -> influence -= 2.0
            "mature" -> influence += 2.0
        }
        
        // Pattern history signal (if enough data)
        val pattern = visualToPattern(visual)
        patternSuccess[pattern]?.let { (wins, total) ->
            if (total >= 10) {  // Need more samples before trusting
                val successRate = wins.toDouble() / total
                influence += (successRate - 0.5) * 10  // -5 to +5
            }
        }
        
        return influence
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 4: APPLY TO DECISION (With confidence governor)
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Apply feedback to a base confidence score.
     * Returns adjusted confidence with HARD CAP enforced.
     */
    fun applyToConfidence(baseConfidence: Double, feedback: FeedbackState): Double {
        // Skip feedback entirely if risk is critical
        if (lastRiskScore >= 80) {
            return baseConfidence  // No modification allowed
        }
        
        // Apply capped boost
        val boosted = baseConfidence + feedback.cappedBoost
        
        // CONSTRAINT 4: Hard confidence cap (NEVER exceed)
        return boosted.coerceIn(0.0, CONFIDENCE_CAP)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 5 & 6: RECORD & LEARN
    // ═══════════════════════════════════════════════════════════════════
    
    fun recordDecision(trueState: TrueState, feedbackApplied: Double, decision: String) {
        val memory = FeedbackMemory(
            trueState = trueState,
            feedbackApplied = feedbackApplied,
            decisionMade = decision,
            outcome = null,
            wasCorrect = null,
        )
        
        synchronized(feedbackHistory) {
            feedbackHistory.add(memory)
            if (feedbackHistory.size > MAX_HISTORY) {
                feedbackHistory.removeAt(0)
            }
        }
    }
    
    fun recordOutcome(mint: String, pnlPct: Double) {
        synchronized(feedbackHistory) {
            val recent = feedbackHistory.lastOrNull { it.outcome == null }
            if (recent != null) {
                val idx = feedbackHistory.indexOf(recent)
                val wasCorrect = when (recent.decisionMade) {
                    "BUY" -> pnlPct > 0
                    "SELL" -> true
                    "BLOCKED" -> pnlPct < 0
                    else -> null
                }
                
                feedbackHistory[idx] = recent.copy(
                    outcome = pnlPct,
                    wasCorrect = wasCorrect,
                )
                
                // Update pattern learning
                val pattern = trueStateToPattern(recent.trueState)
                val (wins, total) = patternSuccess.getOrDefault(pattern, 0 to 0)
                patternSuccess[pattern] = if (wasCorrect == true) {
                    (wins + 1) to (total + 1)
                } else {
                    wins to (total + 1)
                }
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // PATTERN ENCODING
    // ═══════════════════════════════════════════════════════════════════
    
    private fun visualToPattern(visual: VisualState): String {
        val wrBucket = when {
            visual.displayWinRate < 40 -> "WR_LOW"
            visual.displayWinRate < 60 -> "WR_MED"
            else -> "WR_HIGH"
        }
        return "${wrBucket}_${visual.riskLevel}_${visual.marketRegime}_${visual.brainPhase}"
    }
    
    private fun trueStateToPattern(trueState: TrueState): String {
        val wrBucket = when {
            trueState.winRate < 40 -> "WR_LOW"
            trueState.winRate < 60 -> "WR_MED"
            else -> "WR_HIGH"
        }
        val lossBucket = when {
            trueState.consecutiveLosses == 0 -> "LOSS_NONE"
            trueState.consecutiveLosses <= 2 -> "LOSS_SOME"
            else -> "LOSS_STREAK"
        }
        val expBucket = when {
            trueState.totalExposureSol < 0.1 -> "EXP_NONE"
            trueState.totalExposureSol < 0.5 -> "EXP_LOW"
            else -> "EXP_HIGH"
        }
        return "${wrBucket}_${lossBucket}_${expBucket}"
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // CONVENIENCE: Full pipeline in one call
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Complete feedback pipeline:
     * TrueState -> VisualState -> FeedbackState -> Applied confidence
     */
    fun process(status: BotStatus, baseConfidence: Double): Pair<Double, FeedbackState> {
        val trueState = captureTrueState(status)
        val visual = deriveVisualState(trueState, status)
        val feedback = extractFeedback(visual)
        val adjustedConfidence = applyToConfidence(baseConfidence, feedback)
        
        // Record for learning
        val decisionType = if (adjustedConfidence >= 50) "POTENTIAL_BUY" else "LIKELY_SKIP"
        recordDecision(trueState, feedback.cappedBoost, decisionType)
        
        return adjustedConfidence to feedback
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STATS
    // ═══════════════════════════════════════════════════════════════════
    
    fun getStats(): Map<String, Any> {
        val resolved = feedbackHistory.filter { it.wasCorrect != null }
        val correct = resolved.count { it.wasCorrect == true }
        
        return mapOf(
            "emaInfluence" to "%.2f".format(emaInfluence),
            "lastRiskScore" to "%.1f".format(lastRiskScore),
            "totalDecisions" to feedbackHistory.size,
            "feedbackAccuracy" to if (resolved.isNotEmpty()) {
                "%.1f%%".format(correct.toDouble() / resolved.size * 100)
            } else "N/A",
            "patternsLearned" to patternSuccess.size,
            "confidenceCap" to CONFIDENCE_CAP,
            "maxInfluence" to MAX_FEEDBACK_INFLUENCE,
        )
    }
    
    fun reset() {
        feedbackHistory.clear()
        patternSuccess.clear()
        emaInfluence = 0.0
        lastRiskScore = 50.0
    }
}

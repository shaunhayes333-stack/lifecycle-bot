package com.lifecyclebot.engine

/**
 * ClosedLoopFeedback — Self-Representing Trading Agent (CONSTRAINED)
 * 
 * Creates a CONTROLLED feedback loop:
 *   1. Visual state influences decisions (DAMPED)
 *   2. Decisions update visual state  
 *   3. System learns from both
 * 
 * CRITICAL CONSTRAINTS to prevent runaway:
 *   - Risk-based damping
 *   - Hard confidence cap (85%)
 *   - EMA smoothing (lag)
 */
object ClosedLoopFeedback {
    
    private const val CONFIDENCE_CAP = 85.0
    private const val MAX_FEEDBACK_INFLUENCE = 15.0
    private const val EMA_SMOOTHING_FACTOR = 0.2
    
    data class TrueState(
        val aiConfidence: Double,
        val winRate: Double,
        val consecutiveLosses: Int,
        val totalExposureSol: Double,
        val unrealizedPnlPct: Double,
    )
    
    data class VisualState(
        val displayConfidence: Double,
        val displayWinRate: Double,
        val riskLevel: String,
        val riskScore: Double,
        val brainPhase: String,
        val marketRegime: String,
    )
    
    data class FeedbackState(
        val smoothedInfluence: Double,
        val dampingFactor: Double,
        val cappedBoost: Double,
        val recommendation: String,
    )
    
    private var emaInfluence = 0.0
    private var lastRiskScore = 50.0
    
    data class FeedbackMemory(
        val trueState: TrueState,
        val feedbackApplied: Double,
        val decisionMade: String,
        val outcome: Double?,
        val wasCorrect: Boolean?,
    )
    
    private val feedbackHistory = mutableListOf<FeedbackMemory>()
    private const val MAX_HISTORY = 500
    
    /**
     * Capture true state from explicit parameters
     */
    fun captureTrueState(
        aiConfidence: Double,
        winRate: Double,
        consecutiveLosses: Int,
        totalExposureSol: Double,
        unrealizedPnlPct: Double,
    ): TrueState {
        return TrueState(
            aiConfidence = aiConfidence,
            winRate = winRate,
            consecutiveLosses = consecutiveLosses,
            totalExposureSol = totalExposureSol,
            unrealizedPnlPct = unrealizedPnlPct,
        )
    }
    
    fun deriveVisualState(trueState: TrueState, trades24h: Int): VisualState {
        val riskScore = calculateRiskScore(trueState)
        
        val riskLevel = when {
            riskScore >= 80 -> "CRITICAL"
            riskScore >= 60 -> "HIGH"
            riskScore >= 40 -> "MEDIUM"
            else -> "LOW"
        }
        
        val brainPhase = when {
            trades24h < 30 -> "bootstrap"
            trades24h < 200 -> "learning"
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
    
    private fun calculateRiskScore(trueState: TrueState): Double {
        var risk = 0.0
        risk += trueState.consecutiveLosses * 15.0
        if (trueState.totalExposureSol > 0.5) risk += 20.0
        if (trueState.totalExposureSol > 1.0) risk += 20.0
        if (trueState.unrealizedPnlPct < -10) risk += 15.0
        if (trueState.unrealizedPnlPct < -20) risk += 15.0
        if (trueState.winRate < 40) risk += 15.0
        return risk.coerceIn(0.0, 100.0)
    }
    
    fun extractFeedback(visual: VisualState): FeedbackState {
        val rawInfluence = calculateRawInfluence(visual)
        emaInfluence = EMA_SMOOTHING_FACTOR * rawInfluence + (1 - EMA_SMOOTHING_FACTOR) * emaInfluence
        val dampingFactor = 1.0 - (visual.riskScore / 100.0)
        val dampedInfluence = emaInfluence * dampingFactor
        val cappedInfluence = dampedInfluence.coerceIn(-MAX_FEEDBACK_INFLUENCE, MAX_FEEDBACK_INFLUENCE)
        lastRiskScore = visual.riskScore
        
        val recommendation = when {
            visual.riskScore >= 80 -> "HALT - feedback disabled"
            visual.riskScore >= 60 -> "MINIMAL - heavily damped"
            cappedInfluence > 5 -> "NORMAL - favorable"
            cappedInfluence < -5 -> "CAUTIOUS - negative"
            else -> "NEUTRAL"
        }
        
        return FeedbackState(emaInfluence, dampingFactor, cappedInfluence, recommendation)
    }
    
    private fun calculateRawInfluence(visual: VisualState): Double {
        var influence = 0.0
        if (visual.displayWinRate > 60 && visual.brainPhase == "mature") influence += 5.0
        else if (visual.displayWinRate < 40) influence -= 8.0
        
        when (visual.marketRegime) {
            "bull" -> influence += 3.0
            "bear" -> influence -= 8.0
            "chop" -> influence -= 3.0
        }
        
        when (visual.brainPhase) {
            "bootstrap" -> influence -= 5.0
            "learning" -> influence -= 2.0
            "mature" -> influence += 2.0
        }
        return influence
    }
    
    fun applyToConfidence(baseConfidence: Double, feedback: FeedbackState): Double {
        if (lastRiskScore >= 80) return baseConfidence
        val boosted = baseConfidence + feedback.cappedBoost
        return boosted.coerceIn(0.0, CONFIDENCE_CAP)
    }
    
    fun recordDecision(trueState: TrueState, feedbackApplied: Double, decision: String) {
        synchronized(feedbackHistory) {
            feedbackHistory.add(FeedbackMemory(trueState, feedbackApplied, decision, null, null))
            if (feedbackHistory.size > MAX_HISTORY) feedbackHistory.removeAt(0)
        }
    }
    
    fun recordOutcome(mint: String, pnlPct: Double) {
        synchronized(feedbackHistory) {
            val recent = feedbackHistory.lastOrNull { it.outcome == null } ?: return
            val idx = feedbackHistory.indexOf(recent)
            val wasCorrect = when (recent.decisionMade) {
                "BUY" -> pnlPct > 0
                "BLOCKED" -> pnlPct < 0
                else -> null
            }
            feedbackHistory[idx] = recent.copy(outcome = pnlPct, wasCorrect = wasCorrect)
        }
    }
    
    fun getStats(): Map<String, Any> = mapOf(
        "emaInfluence" to "%.2f".format(emaInfluence),
        "lastRiskScore" to "%.1f".format(lastRiskScore),
        "confidenceCap" to CONFIDENCE_CAP,
    )
    
    fun reset() {
        feedbackHistory.clear()
        emaInfluence = 0.0
        lastRiskScore = 50.0
    }
}

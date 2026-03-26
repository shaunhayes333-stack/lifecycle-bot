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
    private const val MAX_FEEDBACK_INFLUENCE = 15.0   // Max +/-15%
    private const val EMA_SMOOTHING_FACTOR = 0.2      // Slow adaptation (5-tick period)
    
    // ═══════════════════════════════════════════════════════════════════
    // STATE LAYERS — Truth vs Perception vs Feedback
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * TrueState — Raw AI outputs (never modified by feedback)
     */
    data class TrueState(
        val aiConfidence: Double,
        val winRate: Double,
        val consecutiveLosses: Int,
        val totalExposureSol: Double,
        val unrealizedPnlPct: Double,
    )
    
    /**
     * VisualState — Rendered interpretation
     */
    data class VisualState(
        val displayConfidence: Double,
        val displayWinRate: Double,
        val riskLevel: String,
        val riskScore: Double,
        val brainPhase: String,
        val marketRegime: String,
    )
    
    /**
     * FeedbackState — Dampened, lagged signal
     */
    data class FeedbackState(
        val smoothedInfluence: Double,
        val dampingFactor: Double,
        val cappedBoost: Double,
        val recommendation: String,
    )
    
    // EMA state
    private var emaInfluence = 0.0
    private var lastRiskScore = 50.0
    
    // Learning memory
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
    // STEP 1: CAPTURE TRUE STATE
    // ═══════════════════════════════════════════════════════════════════
    
    fun captureTrueState(
        status: BotStatus,
        consecutiveLosses: Int = 0
    ): TrueState {
        return TrueState(
            aiConfidence = status.avgConfidence,
            winRate = status.winRate,
            consecutiveLosses = consecutiveLosses,
            totalExposureSol = status.totalExposureSol,
            unrealizedPnlPct = status.unrealizedPnlPct,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 2: DERIVE VISUAL STATE
    // ═══════════════════════════════════════════════════════════════════
    
    fun deriveVisualState(trueState: TrueState, status: BotStatus): VisualState {
        val riskScore = calculateRiskScore(trueState)
        
        val riskLevel = when {
            riskScore >= 80 -> "CRITICAL"
            riskScore >= 60 -> "HIGH"
            riskScore >= 40 -> "MEDIUM"
            else -> "LOW"
        }
        
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
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 3: EXTRACT FEEDBACK (Dampened, lagged)
    // ═══════════════════════════════════════════════════════════════════
    
    fun extractFeedback(visual: VisualState): FeedbackState {
        val rawInfluence = calculateRawInfluence(visual)
        
        // EMA smoothing (lag)
        emaInfluence = EMA_SMOOTHING_FACTOR * rawInfluence + (1 - EMA_SMOOTHING_FACTOR) * emaInfluence
        
        // Risk-based damping
        val dampingFactor = 1.0 - (visual.riskScore / 100.0)
        val dampedInfluence = emaInfluence * dampingFactor
        
        // Hard limit
        val cappedInfluence = dampedInfluence.coerceIn(-MAX_FEEDBACK_INFLUENCE, MAX_FEEDBACK_INFLUENCE)
        
        lastRiskScore = visual.riskScore
        
        val recommendation = when {
            visual.riskScore >= 80 -> "HALT - feedback disabled"
            visual.riskScore >= 60 -> "MINIMAL - heavily damped"
            cappedInfluence > 5 -> "NORMAL - favorable"
            cappedInfluence < -5 -> "CAUTIOUS - negative"
            else -> "NEUTRAL"
        }
        
        return FeedbackState(
            smoothedInfluence = emaInfluence,
            dampingFactor = dampingFactor,
            cappedBoost = cappedInfluence,
            recommendation = recommendation,
        )
    }
    
    private fun calculateRawInfluence(visual: VisualState): Double {
        var influence = 0.0
        
        if (visual.displayWinRate > 60 && visual.brainPhase == "mature") {
            influence += 5.0
        } else if (visual.displayWinRate < 40) {
            influence -= 8.0
        }
        
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
    
    // ═══════════════════════════════════════════════════════════════════
    // STEP 4: APPLY WITH CONFIDENCE CAP
    // ═══════════════════════════════════════════════════════════════════
    
    fun applyToConfidence(baseConfidence: Double, feedback: FeedbackState): Double {
        if (lastRiskScore >= 80) return baseConfidence
        val boosted = baseConfidence + feedback.cappedBoost
        return boosted.coerceIn(0.0, CONFIDENCE_CAP)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // RECORD & LEARN
    // ═══════════════════════════════════════════════════════════════════
    
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
        patternSuccess.clear()
        emaInfluence = 0.0
        lastRiskScore = 50.0
    }
}

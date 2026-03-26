package com.lifecyclebot.engine

import com.lifecyclebot.data.BotStatus
import kotlin.math.abs

/**
 * ClosedLoopFeedback — Self-Representing Trading Agent
 * 
 * Creates a feedback loop where:
 *   1. Visual state influences decisions
 *   2. Decisions update visual state  
 *   3. System learns from both
 * 
 * The bot becomes aware of its own representation and uses that
 * awareness to make better decisions. This is metacognition for trading.
 */
object ClosedLoopFeedback {
    
    // ═══════════════════════════════════════════════════════════════════
    // VISUAL STATE SNAPSHOT — What the UI is showing right now
    // ═══════════════════════════════════════════════════════════════════
    
    data class VisualState(
        // Quick stats bar
        val trades24h: Int,
        val winRate: Double,           // 0-100
        val openPositions: Int,
        val aiConfidence: Double,      // 0-100
        
        // Brain learning state
        val brainPhase: String,        // "bootstrap", "learning", "mature"
        val brainProgress: Double,     // 0-100
        
        // Circuit breaker state
        val isPaused: Boolean,
        val isHalted: Boolean,
        val consecutiveLosses: Int,
        val dailyLossSol: Double,
        
        // Position state
        val totalExposureSol: Double,
        val unrealizedPnlPct: Double,
        
        // Market regime
        val marketRegime: String,      // "bull", "bear", "chop"
        val narrativeHeat: Double,     // 0-100
        
        // Timestamp
        val capturedAt: Long = System.currentTimeMillis(),
    )
    
    // ═══════════════════════════════════════════════════════════════════
    // FEEDBACK MEMORY — Learn from visual state → outcome correlations
    // ═══════════════════════════════════════════════════════════════════
    
    data class FeedbackMemory(
        val visualState: VisualState,
        val decisionMade: String,      // "BUY", "SELL", "HOLD", "BLOCKED"
        val outcome: Double?,          // PnL % (null if not yet closed)
        val wasCorrect: Boolean?,      // Did decision align with outcome?
    )
    
    private val feedbackHistory = mutableListOf<FeedbackMemory>()
    private const val MAX_HISTORY = 500
    
    // Learned correlations: visual pattern → success rate
    private val patternSuccess = mutableMapOf<String, Pair<Int, Int>>()  // pattern → (wins, total)
    
    // ═══════════════════════════════════════════════════════════════════
    // CAPTURE — Snapshot current visual state
    // ═══════════════════════════════════════════════════════════════════
    
    fun captureVisualState(status: BotStatus): VisualState {
        val brain = try { BotBrain.getSnapshot() } catch (_: Exception) { null }
        val cb = status.circuitBreaker
        
        return VisualState(
            trades24h = status.trades24h,
            winRate = status.winRate,
            openPositions = status.openPositionCount,
            aiConfidence = status.avgConfidence,
            brainPhase = brain?.phase ?: "unknown",
            brainProgress = brain?.progressPct ?: 0.0,
            isPaused = cb.isPaused,
            isHalted = cb.isHalted,
            consecutiveLosses = cb.consecutiveLosses,
            dailyLossSol = cb.dailyLossSol,
            totalExposureSol = status.totalExposureSol,
            unrealizedPnlPct = status.unrealizedPnlPct,
            marketRegime = MarketRegimeAI.currentRegime().name.lowercase(),
            narrativeHeat = NarrativeDetectorAI.globalHeat(),
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // INFLUENCE — Visual state affects decision confidence
    // ═══════════════════════════════════════════════════════════════════
    
    /**
     * Returns a confidence modifier (-30 to +30) based on visual state patterns.
     * Positive = visual state suggests favorable conditions
     * Negative = visual state suggests caution
     */
    fun getVisualInfluence(state: VisualState): Double {
        var influence = 0.0
        
        // ── Pattern 1: Win streak momentum ──
        // High win rate + mature brain = trust the system
        if (state.winRate > 60 && state.brainPhase == "mature") {
            influence += 10.0
        } else if (state.winRate < 40 && state.brainProgress > 50) {
            influence -= 15.0  // System is learning but losing — be cautious
        }
        
        // ── Pattern 2: Loss recovery mode ──
        // After consecutive losses, be more selective
        if (state.consecutiveLosses >= 2) {
            influence -= (state.consecutiveLosses * 5.0).coerceAtMost(20.0)
        }
        
        // ── Pattern 3: Exposure awareness ──
        // High exposure = reduce new position confidence
        if (state.openPositions >= 3) {
            influence -= (state.openPositions - 2) * 5.0
        }
        if (state.totalExposureSol > 0.5) {
            influence -= 10.0
        }
        
        // ── Pattern 4: Unrealized PnL momentum ──
        // If currently winning, slight confidence boost
        // If currently losing, reduce confidence
        if (state.unrealizedPnlPct > 10) {
            influence += 5.0
        } else if (state.unrealizedPnlPct < -10) {
            influence -= 10.0
        }
        
        // ── Pattern 5: Market regime alignment ──
        if (state.marketRegime == "bull" && state.narrativeHeat > 60) {
            influence += 8.0  // Hot market, narratives pumping
        } else if (state.marketRegime == "bear") {
            influence -= 12.0  // Bear market, extra caution
        }
        
        // ── Pattern 6: Brain confidence ──
        // Mature brain with high AI confidence = trust signals
        if (state.brainPhase == "mature" && state.aiConfidence > 70) {
            influence += 7.0
        } else if (state.brainPhase == "bootstrap") {
            influence -= 5.0  // Still learning, be conservative
        }
        
        // ── Pattern 7: Learned pattern matching ──
        val pattern = stateToPattern(state)
        patternSuccess[pattern]?.let { (wins, total) ->
            if (total >= 5) {
                val successRate = wins.toDouble() / total
                influence += (successRate - 0.5) * 20  // -10 to +10 based on history
            }
        }
        
        return influence.coerceIn(-30.0, 30.0)
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // RECORD — Store decision with visual context for learning
    // ═══════════════════════════════════════════════════════════════════
    
    fun recordDecision(state: VisualState, decision: String) {
        val memory = FeedbackMemory(
            visualState = state,
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
    
    /**
     * Update the most recent decision for a mint with its outcome
     */
    fun recordOutcome(mint: String, pnlPct: Double) {
        synchronized(feedbackHistory) {
            // Find the most recent unresolved decision
            val recent = feedbackHistory.lastOrNull { it.outcome == null }
            if (recent != null) {
                val idx = feedbackHistory.indexOf(recent)
                val wasCorrect = when (recent.decisionMade) {
                    "BUY" -> pnlPct > 0
                    "SELL" -> true  // Exiting is usually correct if we chose to
                    "BLOCKED" -> pnlPct < 0  // Blocking was correct if it would have lost
                    else -> null
                }
                
                feedbackHistory[idx] = recent.copy(
                    outcome = pnlPct,
                    wasCorrect = wasCorrect,
                )
                
                // Update pattern learning
                val pattern = stateToPattern(recent.visualState)
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
    // PATTERN ENCODING — Convert visual state to learnable pattern
    // ═══════════════════════════════════════════════════════════════════
    
    private fun stateToPattern(state: VisualState): String {
        // Discretize continuous values into buckets for pattern matching
        val wrBucket = when {
            state.winRate < 40 -> "WR_LOW"
            state.winRate < 60 -> "WR_MED"
            else -> "WR_HIGH"
        }
        
        val expBucket = when {
            state.openPositions == 0 -> "EXP_NONE"
            state.openPositions <= 2 -> "EXP_LOW"
            else -> "EXP_HIGH"
        }
        
        val pnlBucket = when {
            state.unrealizedPnlPct < -5 -> "PNL_NEG"
            state.unrealizedPnlPct > 5 -> "PNL_POS"
            else -> "PNL_FLAT"
        }
        
        val lossBucket = when {
            state.consecutiveLosses == 0 -> "LOSS_NONE"
            state.consecutiveLosses <= 2 -> "LOSS_SOME"
            else -> "LOSS_STREAK"
        }
        
        return "${wrBucket}_${expBucket}_${pnlBucket}_${lossBucket}_${state.marketRegime}_${state.brainPhase}"
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // INTROSPECTION — Bot can query its own state
    // ═══════════════════════════════════════════════════════════════════
    
    data class SelfAwareness(
        val currentPattern: String,
        val patternSuccessRate: Double?,
        val recentDecisions: Int,
        val recentWinRate: Double,
        val visualInfluence: Double,
        val recommendation: String,
    )
    
    fun introspect(state: VisualState): SelfAwareness {
        val pattern = stateToPattern(state)
        val (wins, total) = patternSuccess.getOrDefault(pattern, 0 to 0)
        val successRate = if (total >= 3) wins.toDouble() / total else null
        
        val recentResolved = feedbackHistory.filter { it.wasCorrect != null }.takeLast(20)
        val recentWins = recentResolved.count { it.wasCorrect == true }
        val recentWinRate = if (recentResolved.isNotEmpty()) {
            recentWins.toDouble() / recentResolved.size * 100
        } else 0.0
        
        val influence = getVisualInfluence(state)
        
        val recommendation = when {
            influence > 15 -> "AGGRESSIVE — Visual state strongly favorable"
            influence > 5 -> "NORMAL — Conditions look good"
            influence > -5 -> "CAUTIOUS — Mixed signals"
            influence > -15 -> "DEFENSIVE — Visual state suggests caution"
            else -> "MINIMAL — Strong negative visual signals"
        }
        
        return SelfAwareness(
            currentPattern = pattern,
            patternSuccessRate = successRate,
            recentDecisions = feedbackHistory.size,
            recentWinRate = recentWinRate,
            visualInfluence = influence,
            recommendation = recommendation,
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // STATS — For UI display
    // ═══════════════════════════════════════════════════════════════════
    
    fun getStats(): Map<String, Any> {
        val resolved = feedbackHistory.filter { it.wasCorrect != null }
        val correct = resolved.count { it.wasCorrect == true }
        
        return mapOf(
            "totalDecisions" to feedbackHistory.size,
            "resolvedDecisions" to resolved.size,
            "correctDecisions" to correct,
            "feedbackAccuracy" to if (resolved.isNotEmpty()) {
                "%.1f%%".format(correct.toDouble() / resolved.size * 100)
            } else "N/A",
            "patternsLearned" to patternSuccess.size,
            "topPatterns" to patternSuccess.entries
                .filter { it.value.second >= 5 }
                .sortedByDescending { it.value.first.toDouble() / it.value.second }
                .take(3)
                .map { "${it.key}: ${it.value.first}/${it.value.second}" },
        )
    }
    
    fun reset() {
        feedbackHistory.clear()
        patternSuccess.clear()
    }
}

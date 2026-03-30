package com.lifecyclebot.v3.scoring

import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot
import com.lifecyclebot.v3.scanner.SourceType

/**
 * V3 Score Component
 * Individual contribution to total score
 */
data class ScoreComponent(
    val name: String,
    val value: Int,
    val reason: String,
    val fatal: Boolean = false
)

/**
 * V3 Score Card
 * Aggregated scores from all AI modules
 */
data class ScoreCard(
    val components: List<ScoreComponent>
) {
    val total: Int get() = components.sumOf { it.value }
    
    fun byName(name: String): ScoreComponent? = components.find { it.name == name }
    
    fun positiveCount(): Int = components.count { it.value > 0 }
    fun negativeCount(): Int = components.count { it.value < 0 }
    
    fun hasFatal(): Boolean = components.any { it.fatal }
}

/**
 * V3 Scoring Module Interface
 * All AI modules implement this
 */
interface ScoringModule {
    val name: String
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): ScoreComponent
}

/**
 * Source Type Scoring
 * 
 * V4.1: Now includes source timing lag penalty for late signals.
 * Tokens only discovered on trending sources (not seen on new-pool first)
 * get penalized because we're late to the party.
 */
fun sourceScore(source: SourceType): ScoreComponent = when (source) {
    SourceType.DEX_BOOSTED -> ScoreComponent("source", 4, "Boosted visibility")
    SourceType.RAYDIUM_NEW_POOL -> ScoreComponent("source", 7, "Fresh pool discovery")
    SourceType.PUMP_FUN_GRADUATE -> ScoreComponent("source", 5, "Pump graduate candidate")
    SourceType.DEX_TRENDING -> ScoreComponent("source", 3, "Trending visibility")
}

/**
 * V4.1 SOURCE TIMING LAG SCORING (P2)
 * 
 * Applies penalty for tokens discovered late (only on trending, not on new-pool).
 * Call this WITH mint to check historical source timing data.
 */
fun sourceScoreWithTiming(source: SourceType, mint: String): ScoreComponent {
    val baseScore = when (source) {
        SourceType.DEX_BOOSTED -> 4
        SourceType.RAYDIUM_NEW_POOL -> 7
        SourceType.PUMP_FUN_GRADUATE -> 5
        SourceType.DEX_TRENDING -> 3
    }
    
    // Apply source timing penalty
    val (timingPenalty, timingReason) = com.lifecyclebot.v3.arb.SourceTimingRegistry.getSourceTimingPenalty(mint)
    
    val finalScore = baseScore + timingPenalty
    val reason = when {
        timingPenalty == 0 -> source.name
        timingPenalty <= -15 -> "⚠️ LATE: $timingReason"
        else -> "${source.name} ($timingReason)"
    }
    
    return ScoreComponent("source", finalScore, reason)
}

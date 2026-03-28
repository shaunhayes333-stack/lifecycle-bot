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
 */
fun sourceScore(source: SourceType): ScoreComponent = when (source) {
    SourceType.DEX_BOOSTED -> ScoreComponent("source", 4, "Boosted visibility")
    SourceType.RAYDIUM_NEW_POOL -> ScoreComponent("source", 7, "Fresh pool discovery")
    SourceType.PUMP_FUN_GRADUATE -> ScoreComponent("source", 5, "Pump graduate candidate")
    SourceType.DEX_TRENDING -> ScoreComponent("source", 3, "Trending visibility")
}

package com.lifecyclebot.v3.learning

import com.lifecyclebot.v3.core.DecisionBand

/**
 * V3 Learning Metrics
 * Aggregated statistics from classified trades
 */
data class LearningMetrics(
    val classifiedTrades: Int = 0,
    val last20WinRatePct: Double = 0.0,
    val payoffRatio: Double = 1.0,
    val falseBlockRatePct: Double = 0.0,
    val missedWinnerRatePct: Double = 0.0
)

/**
 * V3 Learning Event
 * Single trade outcome for learning
 */
data class LearningEvent(
    val mint: String,
    val symbol: String,
    val decisionBand: DecisionBand,
    val finalScore: Int,
    val confidence: Int,
    val outcomeLabel: String,
    val pnlPct: Double? = null,
    val maxRunupPct: Double? = null,
    val maxDrawdownPct: Double? = null,
    val holdingTimeSec: Int? = null,
    val features: Map<String, Any?> = emptyMap()
)

/**
 * V3 Learning Store
 * Stores and retrieves learning events
 */
class LearningStore {
    private val events = mutableListOf<LearningEvent>()
    private var shadowBlockedCount    = 0L
    private var shadowBlockedWouldWin = 0L
    private var shadowPassedCount     = 0L
    private var shadowPassedWins      = 0L
    fun recordShadowBlock(wouldHaveWon: Boolean) { shadowBlockedCount++; if (wouldHaveWon) shadowBlockedWouldWin++ }
    fun recordShadowPass(wasWin: Boolean) { shadowPassedCount++; if (wasWin) shadowPassedWins++ }
    private fun computeFalseBlockRate() = if (shadowBlockedCount > 0) (shadowBlockedWouldWin.toDouble() / shadowBlockedCount * 100.0).coerceIn(0.0, 100.0) else 0.0
    private fun computeMissedWinnerRate() = if (shadowPassedCount > 0) (shadowPassedWins.toDouble() / shadowPassedCount * 100.0).coerceIn(0.0, 100.0) else 0.0
    
    fun record(event: LearningEvent) {
        events.add(event)
    }
    
    fun recentEvents(count: Int): List<LearningEvent> {
        return events.takeLast(count)
    }
    
    fun computeMetrics(): LearningMetrics {
        if (events.isEmpty()) return LearningMetrics()
        
        val recent20 = events.takeLast(20)
        val wins = recent20.count { (it.pnlPct ?: 0.0) > 0 }
        val losses = recent20.count { (it.pnlPct ?: 0.0) < 0 }
        
        val avgWin = recent20.filter { (it.pnlPct ?: 0.0) > 0 }
            .map { it.pnlPct ?: 0.0 }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgLoss = recent20.filter { (it.pnlPct ?: 0.0) < 0 }
            .map { kotlin.math.abs(it.pnlPct ?: 0.0) }.average().takeIf { !it.isNaN() } ?: 1.0
        
        return LearningMetrics(
            classifiedTrades = events.size,
            last20WinRatePct = if (recent20.isNotEmpty()) (wins.toDouble() / recent20.size) * 100 else 0.0,
            payoffRatio = if (avgLoss > 0) avgWin / avgLoss else 1.0,
            falseBlockRatePct = computeFalseBlockRate(),
            missedWinnerRatePct = computeMissedWinnerRate()
        )
    }
    
    fun clear() {
        events.clear()
    }
}

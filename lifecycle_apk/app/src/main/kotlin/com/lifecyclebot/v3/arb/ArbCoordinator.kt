package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * ArbCoordinator - Central orchestrator for all arb models
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * Evaluates a candidate through all three arb models and returns the best
 * opportunity (highest score wins).
 * 
 * FLOW:
 * Candidate → [VenueLag, FlowImbalance, PanicReversion] → Best Evaluation
 */
object ArbCoordinator {
    
    private const val TAG = "ArbCoordinator"
    
    // Stats
    @Volatile private var totalEvaluations = 0
    @Volatile private var opportunitiesFound = 0
    @Volatile private var venueLagWins = 0
    @Volatile private var flowImbalanceWins = 0
    @Volatile private var panicReversionWins = 0
    
    /**
     * Evaluate a candidate through all arb models.
     * Returns the highest-scoring evaluation, or null if no opportunity.
     */
    fun evaluate(candidate: CandidateSnapshot): ArbEvaluation? {
        totalEvaluations++
        
        try {
            // Run all models
            val results = listOfNotNull(
                VenueLagModel.evaluate(candidate),
                FlowImbalanceModel.evaluate(candidate),
                PanicReversionModel.evaluate(candidate)
            )
            
            if (results.isEmpty()) return null
            
            // Filter to actionable bands only
            val actionable = results.filter { 
                it.band in listOf(
                    ArbDecisionBand.ARB_MICRO,
                    ArbDecisionBand.ARB_STANDARD,
                    ArbDecisionBand.ARB_FAST_EXIT_ONLY
                )
            }
            
            if (actionable.isEmpty()) {
                // Return best WATCH if any
                return results.maxByOrNull { it.score }?.takeIf { 
                    it.band == ArbDecisionBand.ARB_WATCH 
                }
            }
            
            // Return highest scoring actionable
            val best = actionable.maxByOrNull { it.score } ?: return null
            
            opportunitiesFound++
            when (best.arbType) {
                ArbType.VENUE_LAG -> venueLagWins++
                ArbType.FLOW_IMBALANCE -> flowImbalanceWins++
                ArbType.PANIC_REVERSION -> panicReversionWins++
            }
            
            ErrorLogger.info(TAG, "[ARB_DECISION] ${candidate.symbol} | ${best.arbType} | " +
                "score=${best.score} conf=${best.confidence} | band=${best.band} | ${best.reason.take(50)}")
            
            return best
            
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "evaluate error: ${e.message}")
            return null
        }
    }
    
    /**
     * Evaluate with preference for a specific arb type.
     * Used when you want to prioritize a certain strategy.
     */
    fun evaluateWithPreference(
        candidate: CandidateSnapshot, 
        preferredType: ArbType
    ): ArbEvaluation? {
        val best = evaluate(candidate) ?: return null
        
        // If best matches preference, return it
        if (best.arbType == preferredType) return best
        
        // Otherwise, check if preferred type has a decent score
        val preferred = when (preferredType) {
            ArbType.VENUE_LAG -> VenueLagModel.evaluate(candidate)
            ArbType.FLOW_IMBALANCE -> FlowImbalanceModel.evaluate(candidate)
            ArbType.PANIC_REVERSION -> PanicReversionModel.evaluate(candidate)
        }
        
        // Use preferred if it's within 15% of best score
        if (preferred != null && preferred.score >= best.score * 0.85) {
            return preferred
        }
        
        return best
    }
    
    /**
     * Check if ANY arb opportunity exists for a candidate.
     * Faster than full evaluate - returns immediately on first hit.
     */
    fun hasOpportunity(candidate: CandidateSnapshot): Boolean {
        // Check flow imbalance first (most common)
        FlowImbalanceModel.evaluate(candidate)?.let {
            if (it.band in listOf(ArbDecisionBand.ARB_MICRO, ArbDecisionBand.ARB_STANDARD)) {
                return true
            }
        }
        
        // Check venue lag
        VenueLagModel.evaluate(candidate)?.let {
            if (it.band in listOf(ArbDecisionBand.ARB_MICRO, ArbDecisionBand.ARB_STANDARD)) {
                return true
            }
        }
        
        // Check panic reversion last (most restrictive)
        PanicReversionModel.evaluate(candidate)?.let {
            if (it.band == ArbDecisionBand.ARB_FAST_EXIT_ONLY) {
                return true
            }
        }
        
        return false
    }
    
    /**
     * Get stats for logging.
     */
    fun getStats(): String {
        val breakdown = if (opportunitiesFound > 0) {
            " (VL:$venueLagWins FI:$flowImbalanceWins PR:$panicReversionWins)"
        } else ""
        return "ArbCoordinator: $totalEvaluations evaluated | $opportunitiesFound opportunities$breakdown"
    }
    
    /**
     * Get detailed stats.
     */
    fun getDetailedStats(): String {
        return buildString {
            appendLine("=== ARB COORDINATOR STATS ===")
            appendLine("Total Evaluations: $totalEvaluations")
            appendLine("Opportunities Found: $opportunitiesFound")
            appendLine("By Type:")
            appendLine("  - VenueLag: $venueLagWins")
            appendLine("  - FlowImbalance: $flowImbalanceWins")
            appendLine("  - PanicReversion: $panicReversionWins")
            appendLine("Sub-model Stats:")
            appendLine("  ${VenueLagModel.getStats()}")
            appendLine("  ${FlowImbalanceModel.getStats()}")
            appendLine("  ${PanicReversionModel.getStats()}")
            appendLine("  ${SourceTimingRegistry.getStats()}")
        }
    }
    
    /**
     * Reset stats.
     */
    fun resetStats() {
        totalEvaluations = 0
        opportunitiesFound = 0
        venueLagWins = 0
        flowImbalanceWins = 0
        panicReversionWins = 0
    }
}

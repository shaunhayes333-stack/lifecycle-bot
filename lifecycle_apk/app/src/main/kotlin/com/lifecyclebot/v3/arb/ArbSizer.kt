package com.lifecyclebot.v3.arb

import com.lifecyclebot.engine.ErrorLogger

/**
 * ArbSizer - Position sizing for arb trades
 * ═══════════════════════════════════════════════════════════════════════════════
 * 
 * RULE: Arb trades should ALMOST ALWAYS be smaller than directional trades.
 * 
 * This is because:
 * 1. Short hold times mean less time to recover from bad entries
 * 2. Arb setups are more speculative than confirmed trends
 * 3. We're extracting small moves, not riding runners
 * 
 * SIZE MULTIPLIERS:
 * - ARB_MICRO: 35% of normal arb size
 * - ARB_STANDARD: 60% of normal arb size  
 * - ARB_FAST_EXIT_ONLY: 40% of normal arb size
 */
object ArbSizer {
    
    private const val TAG = "ArbSizer"
    
    // Base multipliers relative to normal position size
    private const val MICRO_MULT = 0.35
    private const val STANDARD_MULT = 0.60
    private const val FAST_EXIT_MULT = 0.40
    
    // Minimum and maximum SOL sizes for arb
    private const val MIN_ARB_SIZE_SOL = 0.01
    private const val MAX_ARB_SIZE_SOL = 0.5  // Cap arb positions
    
    /**
     * Calculate arb position size.
     * 
     * @param band The arb decision band
     * @param normalSizeSol The normal position size in SOL
     * @param evaluation Optional evaluation for score-based adjustments
     * @return Position size in SOL
     */
    fun size(
        band: ArbDecisionBand,
        normalSizeSol: Double,
        evaluation: ArbEvaluation? = null
    ): Double {
        // Base multiplier from band
        val baseMult = when (band) {
            ArbDecisionBand.ARB_MICRO -> MICRO_MULT
            ArbDecisionBand.ARB_STANDARD -> STANDARD_MULT
            ArbDecisionBand.ARB_FAST_EXIT_ONLY -> FAST_EXIT_MULT
            ArbDecisionBand.ARB_WATCH -> 0.0  // No execution for WATCH
            ArbDecisionBand.ARB_REJECT -> 0.0  // No execution for REJECT
        }
        
        if (baseMult == 0.0) return 0.0
        
        // Score-based adjustment (optional)
        var adjustedMult = baseMult
        if (evaluation != null) {
            // High score bonus (up to +20%)
            if (evaluation.score >= 70) {
                adjustedMult *= 1.0 + ((evaluation.score - 70) / 100.0).coerceAtMost(0.2)
            }
            
            // High confidence bonus (up to +15%)
            if (evaluation.confidence >= 60) {
                adjustedMult *= 1.0 + ((evaluation.confidence - 60) / 100.0).coerceAtMost(0.15)
            }
            
            // Arb type adjustments
            when (evaluation.arbType) {
                ArbType.VENUE_LAG -> {
                    // Venue lag is cleaner signal - slight boost
                    adjustedMult *= 1.05
                }
                ArbType.FLOW_IMBALANCE -> {
                    // Flow imbalance is reliable - keep as is
                }
                ArbType.PANIC_REVERSION -> {
                    // Panic reversion is riskier - slight reduction
                    adjustedMult *= 0.90
                }
            }
        }
        
        // Calculate final size
        var sizeSol = normalSizeSol * adjustedMult
        
        // Apply bounds
        sizeSol = sizeSol.coerceIn(MIN_ARB_SIZE_SOL, MAX_ARB_SIZE_SOL)
        
        ErrorLogger.debug(TAG, "[ARB_SIZE] band=$band baseMult=$baseMult adjMult=${String.format("%.2f", adjustedMult)} " +
            "normal=${String.format("%.3f", normalSizeSol)} final=${String.format("%.4f", sizeSol)}")
        
        return sizeSol
    }
    
    /**
     * Get size multiplier for a band (without applying to a base size).
     */
    fun getMultiplier(band: ArbDecisionBand): Double {
        return when (band) {
            ArbDecisionBand.ARB_MICRO -> MICRO_MULT
            ArbDecisionBand.ARB_STANDARD -> STANDARD_MULT
            ArbDecisionBand.ARB_FAST_EXIT_ONLY -> FAST_EXIT_MULT
            else -> 0.0
        }
    }
    
    /**
     * Check if a size is valid for arb trading.
     */
    fun isValidSize(sizeSol: Double): Boolean {
        return sizeSol >= MIN_ARB_SIZE_SOL && sizeSol <= MAX_ARB_SIZE_SOL
    }
    
    /**
     * Get maximum allowed arb size.
     */
    fun getMaxArbSize(): Double = MAX_ARB_SIZE_SOL
    
    /**
     * Get minimum allowed arb size.
     */
    fun getMinArbSize(): Double = MIN_ARB_SIZE_SOL
}

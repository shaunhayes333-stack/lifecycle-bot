package com.lifecyclebot.v3.risk

import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * V3 Fatal Risk Result
 * Only truly fatal conditions block here
 */
data class FatalRiskResult(
    val blocked: Boolean,
    val reason: String? = null
)

/**
 * V3 Rug Model
 * Scores rug probability
 */
class RugModel {
    fun score(candidate: CandidateSnapshot, ctx: TradingContext): Int {
        var score = candidate.rawRiskScore ?: 0
        
        if (candidate.extraBoolean("zeroHolders")) score += 20
        if (candidate.extraBoolean("pureSellPressure")) score += 25
        if (candidate.extraBoolean("liquidityDraining")) score += 10
        if (candidate.extraBoolean("unsellableSignal")) score += 40
        
        return score.coerceIn(0, 100)
    }
}

/**
 * V3 Sellability Check
 * Validates pair is tradeable
 */
class SellabilityCheck {
    fun pairValid(candidate: CandidateSnapshot): Boolean {
        return candidate.mint.isNotBlank() && candidate.symbol.isNotBlank()
    }
}

/**
 * V3 Fatal Risk Checker
 * ONLY hard blocks for truly fatal conditions
 * Everything else is scoring
 * 
 * V3 MIGRATION: Added fatal suppression check for rugged/honeypot tokens.
 * Non-fatal suppressions (COPY_TRADE_INVALIDATION, WHALE_INVALIDATION)
 * are now handled as score penalties in SuppressionAI.
 * 
 * V3 SELECTIVITY: Added EXTREME_RUG_CRITICAL block for rugcheck score ≤ 5.
 * This is the ONLY place where rug critical blocks should happen.
 * Strategy PRE-BLOCK path has been removed.
 */
class FatalRiskChecker(
    private val config: TradingConfigV3,
    private val rugModel: RugModel = RugModel(),
    private val sellabilityCheck: SellabilityCheck = SellabilityCheck()
) {
    /**
     * Check for fatal conditions
     * Returns blocked=true only for:
     * - Liquidity collapsed
     * - Unsellable
     * - Invalid pair
     * - Extreme rug score (90+)
     * - Rug critical (score ≤ 5) - MOVED FROM STRATEGY PRE-BLOCK
     * - FATAL suppression (rugged/honeypot - V3 MIGRATION)
     */
    fun check(candidate: CandidateSnapshot, ctx: TradingContext): FatalRiskResult {
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: EXTREME_RUG_CRITICAL block for rugcheck score ≤ 5
        // This replaces the Strategy PRE-BLOCK path for rugcheck critical.
        // Placement in FatalRiskChecker is the correct architecture.
        // ═══════════════════════════════════════════════════════════════════
        val rawRugcheckScore = candidate.rawRiskScore ?: 100
        if (rawRugcheckScore in 0..5) {
            return FatalRiskResult(true, "EXTREME_RUG_CRITICAL_score=$rawRugcheckScore")
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 MIGRATION: Check for FATAL suppressions (rugged/honeypot/unsellable)
        // Non-fatal suppressions are handled in SuppressionAI as score penalties
        // ═══════════════════════════════════════════════════════════════════
        try {
            if (com.lifecyclebot.engine.DistributionFadeAvoider.isFatalSuppression(candidate.mint)) {
                return FatalRiskResult(true, "FATAL_SUPPRESSION")
            }
        } catch (e: Exception) {
            // Ignore if DistributionFadeAvoider not available
        }
        
        // Liquidity collapsed = can't exit
        if (candidate.liquidityUsd <= 250.0) {
            return FatalRiskResult(true, "LIQUIDITY_COLLAPSED")
        }
        
        // Explicitly marked unsellable
        if (candidate.isSellable == false) {
            return FatalRiskResult(true, "UNSELLABLE")
        }
        
        // Invalid pair data
        if (!sellabilityCheck.pairValid(candidate)) {
            return FatalRiskResult(true, "PAIR_INVALID")
        }
        
        // Extreme rug risk only (RugModel calculation)
        val rugScore = rugModel.score(candidate, ctx)
        if (rugScore >= config.fatalRugThreshold) {
            return FatalRiskResult(true, "EXTREME_RUG_RISK_$rugScore")
        }
        
        // Not fatal - let scoring handle it
        return FatalRiskResult(false)
    }
}

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
        // V5.9.412 — Free-range learning bypass for rug-score fatals.
        // The user's pre-markets-pre-LLM proven meme edge included taking
        // entries on tokens that rugcheck-style scorers flag as risky;
        // those entries were managed via small position sizes + fast exits.
        // V3's strict 0..5 / ≥90 fatal rules were nuking ~every fresh meme
        // launch the moment rugcheck.xyz returned a non-trivial number,
        // even when a hard FATAL_SUPPRESSION (rugged/honeypot) was NOT
        // confirmed.  In free-range mode we skip the *score-based* fatal
        // blocks but keep the truly un-tradeable ones (liquidity collapse,
        // unsellable, invalid pair, confirmed fatal suppression).
        val wideOpen = try {
            com.lifecyclebot.engine.FreeRangeMode.isWideOpen()
        } catch (_: Throwable) { false }

        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: EXTREME_RUG_CRITICAL block for rugcheck score ≤ 5
        // This replaces the Strategy PRE-BLOCK path for rugcheck critical.
        // Placement in FatalRiskChecker is the correct architecture.
        // ═══════════════════════════════════════════════════════════════════
        val rawRugcheckScore = candidate.rawRiskScore ?: 100
        // V5.9.417 — only bypass the MIDDLE band (3..5) under FreeRangeMode.
        // Score 0..2 means rugcheck is screaming 'rugged/honeypot' and the
        // user's -48 % Day-1 PnL on V5.9.412 showed unconditional bypass
        // was too aggressive. Keep the absolute-bottom block always on.
        // V5.9.662 — see below for the paper-learning bypass on rugScore
        // (RugModel) — same isPaperLearning + rugFlagsClean carveout
        // applies to the 3..5 raw rugcheck band. Score 0..2 still blocks
        // unconditionally because that's rugcheck signalling confirmed
        // honeypot/rugged and the operator's -48% Day-1 ground truth
        // showed bypassing it was a mistake.
        val isPaperLearningRC = (ctx.mode == com.lifecyclebot.v3.core.V3BotMode.PAPER ||
                                 ctx.mode == com.lifecyclebot.v3.core.V3BotMode.LEARNING)
        val rugFlagsCleanRC = !candidate.extraBoolean("zeroHolders") &&
                              !candidate.extraBoolean("pureSellPressure") &&
                              !candidate.extraBoolean("liquidityDraining") &&
                              !candidate.extraBoolean("unsellableSignal")
        if (rawRugcheckScore in 0..2) {
            return FatalRiskResult(true, "EXTREME_RUG_CRITICAL_score=$rawRugcheckScore")
        }
        if (!wideOpen && !(isPaperLearningRC && rugFlagsCleanRC) &&
            rawRugcheckScore in 0..5) {
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
        // V5.9.417 — split the threshold: keep rugScore >= 95 blocked even
        // in free-range (those are 'all 4 extras flagged' candidates that
        // bled the bot to -48 % on Day 1). Only bypass the 90..94 band.
        // V5.9.662 — operator: 'meme trader only. moonshot bluechip the
        // quality layer and the v3 layer' aren't firing. Logs showed
        // every brand-new pump.fun launch hit EXTREME_RUG_RISK_100
        // because rugcheck returns rcScore=1 (pending) which RugModel
        // interprets as max risk via rawRiskScore=100 default before
        // any verification round-trip. This blocks ShitCoin's siblings
        // (Moonshot/Quality/BlueChip/V3 routes) from collecting paper
        // training data the same way ShitCoin already does via its
        // TradeAuth PAPER_LEARNING bypass. In paper mode AND only when
        // the secondary rug-flag bundle (zeroHolders/pureSellPressure/
        // liquidityDraining/unsellableSignal) is NOT set, allow the
        // entry through fatal so V3 scoring still has the final say.
        // Live mode keeps the strict 95+ block unchanged.
        val isPaperLearning = (ctx.mode == com.lifecyclebot.v3.core.V3BotMode.PAPER ||
                               ctx.mode == com.lifecyclebot.v3.core.V3BotMode.LEARNING)
        val rugFlagsClean = !candidate.extraBoolean("zeroHolders") &&
                            !candidate.extraBoolean("pureSellPressure") &&
                            !candidate.extraBoolean("liquidityDraining") &&
                            !candidate.extraBoolean("unsellableSignal")
        if (rugScore >= 95 && !(isPaperLearning && rugFlagsClean)) {
            return FatalRiskResult(true, "EXTREME_RUG_RISK_$rugScore")
        }
        if (!wideOpen && !isPaperLearning && rugScore >= config.fatalRugThreshold) {
            return FatalRiskResult(true, "EXTREME_RUG_RISK_$rugScore")
        }
        
        // Not fatal - let scoring handle it
        return FatalRiskResult(false)
    }
}

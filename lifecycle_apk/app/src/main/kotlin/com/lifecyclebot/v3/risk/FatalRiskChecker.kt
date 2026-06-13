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
        // ═══════════════════════════════════════════════════════════════════
        // V5.9.1511 — ROOT FIX: SCALE INVERSION (operator: "fresh launches all
        // die at V3 EXTREME_RUG_RISK_97/99/100, standard lane silent").
        //
        // candidate.rawRiskScore is populated in V3Adapter as
        //   rawRiskScore = safety.rugcheckScore  (rugcheck convention: HIGHER
        //   = SAFER; 0 = rugged/honeypot, 100 = clean). The V3Adapter comment
        //   even states "-1 → 100 which is safe".
        // But this model previously did `var score = rawRiskScore ?: 0` and then
        //   the gate blocks at `rugScore >= 95/90` as a RISK score. So a SAFE
        //   token (safety=97) was read as RISK=97 and hard-blocked. The safer
        //   the token, the more certainly it died — inverting the whole intent
        //   and starving V3/STANDARD of every healthy fresh launch (JAM buy%=92
        //   liq=$356k included).
        //
        // Correct mapping: risk = 100 - safety, with the sentinel exception:
        // rugcheckScore=1 means RC_PENDING / needs more data, NOT safety=1.
        // V5.9.1566 — this was the zero-trading regression: pending score=1
        // became risk=99, then one fresh-launch transient flag pushed it to
        // EXTREME_RUG_RISK_100. Keep 0 as confirmed rug via the raw-score
        // fatal check below; map 1/null to neutral pending risk.
        val safety = candidate.rawRiskScore
        var score = when (safety) {
            null -> 50
            1 -> 50
            else -> (100 - safety).coerceIn(0, 100)
        }

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
        // V5.9.689 — score=1 is RC_PENDING sentinel (API not yet resolved),
        // NOT a confirmed bad score. Treat it the same as ShitCoin's
        // PAPER_LEARNING bypass: paper mode passes with RC_PENDING flag,
        // live mode still blocks (unknown RC on real money = too risky).
        // score=0 = confirmed rugged/honeypot → unconditional hard block.
        if (rawRugcheckScore == 0) {
            return FatalRiskResult(true, "EXTREME_RUG_CRITICAL_score=0_CONFIRMED_RUG")
        }
        // V5.9.1535 — ROOT FIX (live trader dead: EVERY fresh launch fatal'd).
        // score==1 is the RC_PENDING sentinel (rugcheck API not yet resolved) —
        // it is NOT a confirmed rug. Previous fixes (V5.9.689/1329) only carved
        // this out for PAPER, so in LIVE every fresh pump.fun launch (where RC is
        // ALWAYS pending at birth) died here as EXTREME_RUG_CRITICAL_score=1_RC_
        // PENDING_LIVE — BEFORE ExecutableOpenGate's V5.9.1504 pending-rug→FDG
        // fallback could run. The two gates contradicted: OpenGate opened the
        // door, FatalRiskChecker locked it upstream. We now MIRROR OpenGate: a
        // PENDING RC (score==1) in live is NON-FATAL when the token clears a hard
        // liquidity floor (real, exitable pool) AND the danger-flag bundle is
        // clean — it is routed onward to V3 scoring + FDG, which make the final
        // (size-capped) call. Confirmed rug (score==0) below is still an
        // unconditional live hard block. Known ruggers still caught by
        // TokenBlacklist at liveBuy (V5.9.1502); -15% SL unchanged.
        if (rawRugcheckScore == 1 && !isPaperLearningRC) {
            val pendingRcLiqFloorOk = candidate.liquidityUsd >= 8_000.0
            if (!(pendingRcLiqFloorOk && rugFlagsCleanRC)) {
                return FatalRiskResult(true, "EXTREME_RUG_CRITICAL_score=1_RC_PENDING_LIVE")
            }
            // else: pending RC + strong liquidity + clean flags → fall through to
            // scoring/FDG (logged downstream as RC_PENDING route).
        }
        // V5.9.1329 — ROOT FIX: score=1 is RC_PENDING, not a confirmed rug.
        // The carve-out at line 116 lets score=1 PASS in paper, but the
        // generic 0..5 band check below was re-trapping score=1 because the
        // (isPaperLearningRC && rugFlagsCleanRC) bypass requires ALL rug
        // flags clean. PumpPortal fresh launches commonly arrive before
        // holders-API resolves so zeroHolders=true → bypass false → score=1
        // gets blocked as "EXTREME_RUG_CRITICAL_score=1". Train-First
        // doctrine: pending RC must reach FDG (paper-micro). Restrict the
        // band check to 2..5 (genuine low scores) so score=1 always falls
        // through in paper mode.
        if (!wideOpen && !(isPaperLearningRC && rugFlagsCleanRC) &&
            rawRugcheckScore in 2..5) {
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
        // V5.9.1544 — COLLAPSE the contradictory rug-score fatals into ONE
        // liquidity-tiered policy (operator: 55 commits stacked gates on top of
        // working logic; PBTC at $351k liq was hard-fatal'd EXTREME_RUG_RISK_91).
        //
        // ROOT CAUSE: "confirmedHighRisk" counted liquidityDraining (= meta.breakdown,
        // a MOMENTARY momentum signal that fires on nearly every fresh launch in its
        // first minutes) as a CONFIRMED danger flag. So a deep, clearly-exitable pool
        // got hard-vetoed by a transient breakdown blip. That violates soft-shape >
        // veto: a token you can demonstrably SELL is not a rug — penalize it in
        // scoring, don't kill it upstream of FDG + the size-cap.
        //
        // New model — two flag classes:
        //   CONFIRMED danger  = unsellableSignal (safety blocked) OR resolved
        //                       zeroHolders. These are structural; they still hard-veto.
        //   SOFT/transient     = liquidityDraining (momentary breakdown) +
        //                       pureSellPressure (low-but-nonzero skew). These NEVER
        //                       hard-veto on their own — UnifiedScorer already
        //                       penalizes them (liquiditycycle/orderflow layers).
        // And liquidity tiers the whole decision:
        //   DEEP (>= $25k)  = demonstrably exitable -> rug SCORE alone never fatals;
        //                     only a CONFIRMED structural danger flag does.
        //   MID  ($8k-25k)  = require rugScore high AND a confirmed danger flag.
        //   THIN (< $8k)    = strict: rugScore >= threshold fatals (real rugs live here).
        val confirmedDanger = candidate.extraBoolean("unsellableSignal") ||
                              candidate.extraBoolean("zeroHolders")
        val deepLiquidity = candidate.liquidityUsd >= 25_000.0
        val thinLiquidity = candidate.liquidityUsd < 8_000.0
        val paperClean = isPaperLearning && rugFlagsClean

        val rugFatal = when {
            wideOpen || paperClean -> false
            // DEEP pool: only a confirmed structural danger flag can hard-veto.
            deepLiquidity -> confirmedDanger && rugScore >= 95
            // THIN pool: strict — score alone fatals (this is rug territory).
            thinLiquidity -> rugScore >= config.fatalRugThreshold
            // MID pool: score high AND a confirmed danger flag.
            else -> rugScore >= config.fatalRugThreshold && confirmedDanger
        }
        if (rugFatal) {
            return FatalRiskResult(true, "EXTREME_RUG_RISK_$rugScore")
        }
        
        // Not fatal - let scoring handle it
        return FatalRiskResult(false)
    }
}

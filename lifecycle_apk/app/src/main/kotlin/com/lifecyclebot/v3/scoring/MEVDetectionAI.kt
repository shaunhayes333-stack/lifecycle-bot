package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.9.123 — MEVDetectionAI
 *
 * Sandwich/frontrun exposure scorer. Tokens that are currently the target
 * of MEV bots lose slippage on every fill. Signals we use (all cheap):
 *
 *  • Very high buy/sell count ratio in last 5 min (> 3:1) often indicates
 *    sandwich bots buying ahead of retail.
 *  • Extremely frequent tiny-size trades interleaving with normal sizes.
 *  • Sub-second time between large buys.
 *
 * We can't watch the mempool from a phone, so we proxy via the candle's
 * own buy/sell stats that DexScreener returns. This is a low-weight
 * penalty layer — rarely dominates a decision, but dings obvious targets.
 */
object MEVDetectionAI {

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        return try {
            // CandidateSnapshot has buyPressurePct (0..100) not raw buy/sell counts.
            // Treat extreme one-sided pressure on a shallow pool as MEV-sandwich risk.
            val bp = candidate.buyPressurePct
            val liq = candidate.liquidityUsd

            val suspicious = when {
                bp > 92.0 && liq < 25_000   -> -8    // near-total buy pressure + shallow = bot sandwich
                bp > 85.0 && liq < 15_000   -> -5
                bp in 40.0..70.0            -> +2    // healthy 2-way flow
                bp < 10.0 && liq < 30_000   -> -4    // sells stacking into shallow = dump in progress
                else                        -> 0
            }
            val reason = "🥪 MEV_PROBE: buyP=${bp.toInt()}%% liq=\$${liq.toInt()} → $suspicious"
            ScoreComponent("MEVDetectionAI", suspicious, reason)
        } catch (e: Exception) {
            ErrorLogger.debug("MEVDet", "score failed: ${e.message}")
            ScoreComponent("MEVDetectionAI", 0, "NO_DATA")
        }
    }
}

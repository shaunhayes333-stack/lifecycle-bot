package com.lifecyclebot.v3.sizing

import com.lifecyclebot.v3.core.V3BotMode
import com.lifecyclebot.v3.core.DecisionBand
import com.lifecyclebot.v3.core.TradingConfigV3
import com.lifecyclebot.v3.scanner.CandidateSnapshot

/**
 * V3 Wallet Snapshot
 */
data class WalletSnapshot(
    val totalSol: Double,
    val tradeableSol: Double
)

/**
 * V3 Portfolio Risk State
 */
data class PortfolioRiskState(
    val recentDrawdownPct: Double = 0.0
)

/**
 * V3 Size Result
 */
data class SizeResult(
    val sizeSol: Double
)

/**
 * V3 Smart Sizer
 * Confidence-adjusted position sizing
 * 
 * V3 SELECTIVITY TUNING:
 * - Probe sizes reduced 0.4-0.6x for low-confidence EXECUTE_SMALL
 * - C-grade multiplier added for below-threshold setups
 * - AI degradation reduces size
 */
class SmartSizerV3(
    private val config: TradingConfigV3
) {
    /**
     * Compute position size based on:
     * - Decision band
     * - Confidence level
     * - Liquidity
     * - Drawdown state
     * - Bot mode (paper/learning reduces size)
     * - C-grade penalty (low score = smaller size)
     */
    fun compute(
        band: DecisionBand,
        wallet: WalletSnapshot,
        confidence: Int,
        candidate: CandidateSnapshot,
        risk: PortfolioRiskState,
        mode: V3BotMode
    ): SizeResult {
        val tradeable = wallet.tradeableSol
        
        // V5.0.3921 — LIVE-MODE SIZE PROMOTION. Operator dump V5.0.3922
        // showed live trades landing at ~0.0095 SOL (~$1.50 — too small to
        // self-sustain after Solana fees + 0.5% fee skim). Root cause:
        // EXECUTE_SMALL basePct was capped at 3.0% of tradeable, compounded
        // with confMult≤0.55 + probeMult=0.50 + liqMult≤0.40 to deliver
        // <1% of wallet. Promote LIVE-mode EXECUTE_SMALL to 5% basePct and
        // drop the 0.50 probe shrink (probe rationale was for LEARNING /
        // PAPER, not real-money). PAPER / LEARNING modes are unchanged so
        // backtests remain conservative.
        val isLive = mode == V3BotMode.LIVE
        // Base percentage by band
        val basePct = when (band) {
            DecisionBand.EXECUTE_SMALL -> if (isLive) maxOf(config.maxSmallSizePct.coerceAtMost(0.05), 0.05)
                                          else config.maxSmallSizePct.coerceAtMost(0.03)
            DecisionBand.EXECUTE_STANDARD -> if (isLive) 0.08 else 0.06
            DecisionBand.EXECUTE_AGGRESSIVE -> if (isLive) 0.12 else 0.09
            else -> 0.0
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: Confidence multiplier (tightened for low confidence)
        // 
        // Low confidence = much smaller size
        // - < 30: 0.40x (was 0.60)
        // - < 40: 0.55x (new tier)
        // - < 50: 0.75x (was 0.85)
        // ═══════════════════════════════════════════════════════════════════
        val confMult = when {
            confidence < 30 -> 0.40  // Very low confidence = tiny probe
            confidence < 40 -> 0.55  // Low confidence = reduced probe
            confidence < 50 -> 0.75  // Below average = smaller size
            confidence < 65 -> 1.00  // Normal
            else -> 1.10             // High confidence = slight boost
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // V3 SELECTIVITY: EXECUTE_SMALL probe multiplier
        // 
        // EXECUTE_SMALL is already a "probe" tier, so reduce further:
        // - Apply 0.5x multiplier for probe trades
        // - This makes probes truly tiny (learning, not risking)
        // ═══════════════════════════════════════════════════════════════════
        val probeMult = if (band == DecisionBand.EXECUTE_SMALL) {
            0.50  // Probe trades are half the normal EXECUTE_SMALL size
        } else {
            1.00
        }
        
        // Liquidity multiplier (tightened for low liquidity)
        val liqMult = when {
            candidate.liquidityUsd < 3_000 -> 0.40   // Very low liquidity = tiny
            candidate.liquidityUsd < 7_000 -> 0.60   // Low liquidity = reduced
            candidate.liquidityUsd < 15_000 -> 0.80  // Below average
            candidate.liquidityUsd < 40_000 -> 1.00  // Normal
            else -> 1.05                              // High liquidity = slight boost
        }
        
        // Drawdown multiplier
        val ddMult = when {
            risk.recentDrawdownPct >= 20.0 -> 0.50
            risk.recentDrawdownPct >= 10.0 -> 0.70
            else -> 1.00
        }
        
        // Learning mode multiplier
        val learningMult = if (mode == V3BotMode.PAPER || mode == V3BotMode.LEARNING) {
            config.paperLearningSizeMult
        } else {
            1.00
        }
        
        // V3 Confidence Config size multiplier (user-adjustable)
        val v3ConfigMult = try {
            com.lifecyclebot.engine.V3ConfidenceConfig.getSizeMultiplier()
        } catch (e: Exception) {
            1.00
        }
        
        // Final size calculation (includes probe multiplier)
        val rawSize = tradeable * basePct * confMult * probeMult * liqMult * ddMult * learningMult * v3ConfigMult
        val cappedSize = rawSize.coerceAtLeast(0.0).coerceAtMost(tradeable * config.maxAggressiveSizePct)

        // V5.0.6269 — HARD NO-DUST FLOOR (operator directive:
        // "no stupid dust size trades ffs!"). Op-report V5.0.6268 showed
        // CHILLINU sized at 0.0062 SOL sent to Jupiter which returned
        // ROUTE_FAILED_NO_OPEN_COMMITTED — pump.fun tokens simply have no
        // executable route below ~$5 (~0.03 SOL). Every one of these attempts
        // burns compute, watchlist attention, and Jupiter quota for zero
        // return. Refuse the trade entirely when the stacked size would be
        // dust. This lets the bot wait for higher-conviction / better-liquidity
        // candidates that CAN actually round-trip instead of hemorrhaging
        // routing attempts on tiny probes. PAPER path still allowed to sub-cent
        // sizes because the mock engine can always fill (learning surface).
        // V5.0.6269 → V5.0.6271 evolution.
        //
        // V5.0.6269 rationale: block sub-0.05 SOL live trades outright because
        // Jupiter returned ROUTE_FAILED_NO_OPEN_COMMITTED on the CHILLINU
        // 0.0062 SOL attempt.
        //
        // V5.0.6271 correction after op-report showed only 2 trades in 30 min:
        // the block was TOO aggressive — with a 0.6 SOL wallet, the stacked-
        // multiplier math produces sub-0.05 SOL for every EXECUTE_SMALL
        // conviction band (score<60 / conf<70%), i.e. ~80% of the funnel got
        // hard-blocked and the bot sat idle. The real issue was DUST sent to
        // Jupiter — not the fact that low-conviction candidates were sized
        // conservatively. So promote instead of block: if V3 already said
        // EXECUTE (this candidate passed lane + safety + FDG + confidence
        // gates upstream), snap the size UP to the 0.05 SOL floor so Jupiter
        // gets a routable trade. Sub-dust (essentially zero) still blocks —
        // that only happens on a zero-liq or zero-tradeable input. Confidence
        // and score selectivity remain the operator's responsibility upstream.
        val liveNoDustFloor6269 = 0.05  // MIN_POSITION_SOL — mirrors CashGenerationAI floor
        val effectiveSize = if (isLive && cappedSize > 0.0 && cappedSize < liveNoDustFloor6269) {
            if (tradeable < liveNoDustFloor6269) {
                // Not enough wallet headroom to send even a floor-sized trade — hard block.
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SMART_SIZER_V3_DUST_BLOCK_NO_HEADROOM_6271")
                    com.lifecyclebot.engine.ForensicLogger.lifecycle(
                        "SMART_SIZER_V3_DUST_BLOCK_NO_HEADROOM_6271",
                        "band=$band conf=$confidence tradeable=${"%.4f".format(tradeable)} floor=$liveNoDustFloor6269 note=wallet_below_floor_cannot_promote"
                    )
                } catch (_: Throwable) {}
                return SizeResult(sizeSol = 0.0)
            }
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SMART_SIZER_V3_DUST_PROMOTED_6271")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "SMART_SIZER_V3_DUST_PROMOTED_6271",
                    "band=$band conf=$confidence liq=${candidate.liquidityUsd.toInt()} raw=${"%.4f".format(cappedSize)} promotedTo=$liveNoDustFloor6269 note=v3_execute_gate_passed_promote_to_min_position_sol"
                )
            } catch (_: Throwable) {}
            liveNoDustFloor6269
        } else cappedSize

        return SizeResult(sizeSol = effectiveSize)
    }
}

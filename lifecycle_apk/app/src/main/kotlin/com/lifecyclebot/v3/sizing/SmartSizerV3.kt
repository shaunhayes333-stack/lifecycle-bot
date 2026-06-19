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
        val size = tradeable * basePct * confMult * probeMult * liqMult * ddMult * learningMult * v3ConfigMult
        
        return SizeResult(
            sizeSol = size.coerceAtLeast(0.0).coerceAtMost(tradeable * config.maxAggressiveSizePct)
        )
    }
}

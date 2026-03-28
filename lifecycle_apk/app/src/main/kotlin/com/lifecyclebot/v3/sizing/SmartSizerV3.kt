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
        
        // Base percentage by band
        val basePct = when (band) {
            DecisionBand.EXECUTE_SMALL -> config.maxSmallSizePct.coerceAtMost(0.03)
            DecisionBand.EXECUTE_STANDARD -> 0.06
            DecisionBand.EXECUTE_AGGRESSIVE -> 0.09
            else -> 0.0
        }
        
        // Confidence multiplier
        val confMult = when {
            confidence < 35 -> 0.60
            confidence < 50 -> 0.85
            confidence < 65 -> 1.00
            else -> 1.10
        }
        
        // Liquidity multiplier
        val liqMult = when {
            candidate.liquidityUsd < 5_000 -> 0.60
            candidate.liquidityUsd < 15_000 -> 0.80
            candidate.liquidityUsd < 40_000 -> 1.00
            else -> 1.05
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
        
        // Final size calculation
        val size = tradeable * basePct * confMult * liqMult * ddMult * learningMult * v3ConfigMult
        
        return SizeResult(
            sizeSol = size.coerceAtLeast(0.0).coerceAtMost(tradeable * config.maxAggressiveSizePct)
        )
    }
}

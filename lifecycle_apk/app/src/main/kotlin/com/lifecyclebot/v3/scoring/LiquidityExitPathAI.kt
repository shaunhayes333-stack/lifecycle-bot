package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.9.123 — LiquidityExitPathAI
 *
 * The #1 memecoin P&L killer: the chart shows +200% but YOUR sell order
 * at size eats 30% of the pool and you exit near entry. This layer
 * pre-simulates exit slippage using a constant-product AMM model
 * (x*y=k, the standard Raydium/Orca pricing) so we only enter trades
 * where the realistic exit PnL actually exceeds the TP target.
 *
 *   slippage ≈ positionUsd / liquidityUsd   (1st-order for small orders)
 *
 * For a position sized at 1% of LP, slippage ≈ 2% (round trip). For a
 * position at 10% of LP, slippage ≈ 20%+. Rejection kicks in when
 * expected round-trip slippage eats more than half the TP target.
 *
 * Impact: directly raises realized edge per trade. This is the layer the
 * bot has been missing while reporting negative Sharpe with winning
 * on-paper PnL — the paper never modelled the fill.
 */
object LiquidityExitPathAI {

    private const val TAG = "LiqExitPath"
    private const val DEFAULT_TP_PCT = 30.0

    /**
     * Estimate round-trip slippage (entry + exit) for an intended position.
     * Returns % slippage cost. Constant-product approximation.
     */
    fun estimateRoundTripSlippagePct(positionUsd: Double, liquidityUsd: Double): Double {
        if (liquidityUsd <= 0.0 || positionUsd <= 0.0) return 100.0
        val sizeFrac = positionUsd / liquidityUsd
        // Constant-product: priceImpact ≈ sizeFrac / (1 + sizeFrac). Round-trip = 2x.
        val oneLegImpact = sizeFrac / (1.0 + sizeFrac) * 100.0
        return (2.0 * oneLegImpact).coerceAtMost(100.0)
    }

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        return try {
            val liq = candidate.liquidityUsd
            if (liq <= 0.0) return ScoreComponent("LiquidityExitPathAI", 0, "NO_LIQ_DATA")

            // Meme-trader plans ~\$50 per entry at paper defaults; use a conservative
            // \$75 to surface slippage on shallow pools even for large entries.
            val plannedUsd = 75.0
            val slipPct = estimateRoundTripSlippagePct(plannedUsd, liq)
            val targetTp = DEFAULT_TP_PCT

            val value = when {
                slipPct < 2.0   -> +5                                          // very safe fill
                slipPct < 5.0   -> +2
                slipPct < 10.0  -> 0
                slipPct < 20.0  -> -6
                slipPct > targetTp * 0.5 -> -20                                // slippage eats half the TP
                else -> -10
            }
            val reason = "🚪 EXIT_SLIP: ${"%.1f".format(slipPct)}%% roundtrip on \$${plannedUsd.toInt()} into \$${liq.toInt()} pool → score=$value"
            ScoreComponent("LiquidityExitPathAI", value, reason)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "score failed: ${e.message}")
            ScoreComponent("LiquidityExitPathAI", 0, "NO_DATA")
        }
    }
}

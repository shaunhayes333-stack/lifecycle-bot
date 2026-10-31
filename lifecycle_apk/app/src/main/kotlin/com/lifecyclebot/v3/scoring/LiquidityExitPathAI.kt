package com.lifecyclebot.v3.scoring

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.v3.core.TradingContext
import com.lifecyclebot.v3.scanner.CandidateSnapshot

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

    // V5.0.4111 — learning brain. Features: round-trip slippage / TP fraction,
    // log-pool-size normalized. Learns whether the slippage estimate predicts
    // real round-trip cost.
    private val brain = com.lifecyclebot.engine.LayerBrain.register("LiquidityExitPathAI", nFeatures = 2)

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

    /** Pure (V5.0.8491): the USD size of the planned entry; [FALLBACK_PLANNED_USD_8491] when either input is unknown. */
    internal fun plannedUsd8491(ticketSol: Double, solUsd: Double): Double =
        if (ticketSol.isFinite() && ticketSol > 0.0 && solUsd.isFinite() && solUsd > 0.0) ticketSol * solUsd else FALLBACK_PLANNED_USD_8491
    internal const val FALLBACK_PLANNED_USD_8491 = 75.0

    fun score(candidate: CandidateSnapshot, @Suppress("UNUSED_PARAMETER") ctx: TradingContext): ScoreComponent {
        return try {
            val liq = candidate.liquidityUsd
            if (liq <= 0.0) return ScoreComponent("LiquidityExitPathAI", 0, "NO_LIQ_DATA")

            // V5.0.8491 — the round trip is priced at the ticket the bot actually trades (NetEdge8030.entryTicket8151 x SOL/USD,
            // ~\$3-4 on the current wallet), not a fixed \$75: 20x too big an assumed order read most small pools as
            // "slippage eats the TP" and scored real, fillable entries -6..-20. \$75 remains only when the ticket or SOL
            // price is unknown (conservative).
            val plannedUsd = plannedUsd8491(
                try { com.lifecyclebot.engine.truth.NetEdge8030.entryTicket8151() } catch (_: Throwable) { Double.NaN },
                try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { Double.NaN })
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
            val feats = doubleArrayOf(
                (slipPct / 40.0).coerceIn(0.0, 1.0),
                (kotlin.math.ln(liq.coerceAtLeast(1.0)) / 20.0).coerceIn(0.0, 1.0),
            )
            val biased = brain.applyBias(value.toDouble(), feats).toInt()
            try { brain.stamp(candidate.mint, feats) } catch (_: Throwable) {}
            ScoreComponent("LiquidityExitPathAI", biased, reason)
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "score failed: ${e.message}")
            ScoreComponent("LiquidityExitPathAI", 0, "NO_DATA")
        }
    }
}

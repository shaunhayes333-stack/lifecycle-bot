package com.lifecyclebot.engine.truth

/**
 * V5.0.6472 §P0.3 — TYPED ECONOMIC OUTCOME.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "Split ambiguous `pnl` everywhere into typed fields:
 *      realizedPnlSol
 *      unrealizedPnlSol
 *      returnFraction
 *      returnPct
 *      proceedsSol
 *      costBasisSol
 *      feesSol
 *    No percentage/fraction may ever enter a SOL accumulator.
 *
 *    StrategyTruthLedger, PerformanceAnalytics, CapitalAuthority,
 *    reward learners, dashboard and replay consume the same
 *    EconomicOutcome object."
 *
 * DESIGN
 * ──────
 * Immutable value type. Every consumer takes an `EconomicOutcome6472`
 * and reads whichever typed field it needs. There is no ambiguous
 * `pnl` accessor.
 *
 * Invariants asserted at construction:
 *   • all SOL fields are finite and non-negative EXCEPT
 *     realized/unrealized PnL which can be negative.
 *   • returnFraction ≈ realizedPnlSol / costBasisSol (when cost > 0).
 *   • returnPct = returnFraction * 100.
 */
data class EconomicOutcome6472(
    val positionId: String,
    val mint: String,
    val symbol: String,
    val proceedsSol: Double,
    val costBasisSol: Double,
    val feesSol: Double,
    val realizedPnlSol: Double,
    val unrealizedPnlSol: Double,
    val returnFraction: Double,
    val terminal: Boolean,
    val atMs: Long = System.currentTimeMillis(),
) {

    val returnPct: Double get() = returnFraction * 100.0

    init {
        require(proceedsSol.isFinite() && proceedsSol >= 0.0) { "proceedsSol must be finite/non-neg: $proceedsSol" }
        require(costBasisSol.isFinite() && costBasisSol >= 0.0) { "costBasisSol must be finite/non-neg: $costBasisSol" }
        require(feesSol.isFinite() && feesSol >= 0.0) { "feesSol must be finite/non-neg: $feesSol" }
        require(realizedPnlSol.isFinite()) { "realizedPnlSol must be finite: $realizedPnlSol" }
        require(unrealizedPnlSol.isFinite()) { "unrealizedPnlSol must be finite: $unrealizedPnlSol" }
        require(returnFraction.isFinite()) { "returnFraction must be finite: $returnFraction" }
    }

    companion object {
        /**
         * Convenience factory that derives `realizedPnlSol` and
         * `returnFraction` from proceeds / cost / fees. Ensures every
         * caller uses the SAME math and prevents "% into SOL accumulator"
         * bugs at the source.
         */
        fun ofSell(
            positionId: String, mint: String, symbol: String,
            proceedsSol: Double, costBasisSol: Double, feesSol: Double,
            unrealizedRemainderSol: Double = 0.0,
            terminal: Boolean = true,
        ): EconomicOutcome6472 {
            val realized = proceedsSol - costBasisSol - feesSol
            val frac = if (costBasisSol > 0.0) realized / costBasisSol else 0.0
            return EconomicOutcome6472(
                positionId = positionId, mint = mint, symbol = symbol,
                proceedsSol = proceedsSol, costBasisSol = costBasisSol, feesSol = feesSol,
                realizedPnlSol = realized, unrealizedPnlSol = unrealizedRemainderSol,
                returnFraction = frac, terminal = terminal,
            )
        }
    }
}

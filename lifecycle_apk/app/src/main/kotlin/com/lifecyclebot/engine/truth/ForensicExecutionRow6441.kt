package com.lifecyclebot.engine.truth

/**
 * V5.0.6441 §5 — FORENSIC ACCOUNTING CONTRACT.
 *
 * OPERATOR MANDATE §5:
 *   Every execution row must carry unambiguous immutable fields:
 *     entryCostSol, soldCostBasisSol, proceedsSol, feesSol,
 *     realizedPnlSol, realizedPnlPct, qtyBeforeRaw, qtyExecutedRaw,
 *     qtyAfterRaw, tokenDecimals, executionPrice, markPrice, markSource,
 *     markAgeMs, positionId, executionId, idempotencyKey.
 *
 *   Invariant:
 *     realizedPnlSol = proceedsSol - soldCostBasisSol - feesSol
 *
 *   Never overload "sol" or "pnl" with mixed meanings.
 *   Journal is an event ledger, NOT mutable position state.
 */
data class ForensicExecutionRow6441(
    val executionId: String,
    val idempotencyKey: String,
    val positionId: String,
    val side: Side,
    val whenMs: Long,
    val entryCostSol: Double,
    val soldCostBasisSol: Double,
    val proceedsSol: Double,
    val feesSol: Double,
    val realizedPnlSol: Double,
    val realizedPnlPct: Double,
    val qtyBeforeRaw: String,       // BigInteger as string for immutability + JSON safety
    val qtyExecutedRaw: String,
    val qtyAfterRaw: String,
    val tokenDecimals: Int,
    val executionPrice: Double,
    val markPrice: Double,
    val markSource: String,
    val markAgeMs: Long,
    val paperMode: Boolean,
    val lane: String,
) {
    enum class Side { BUY, SELL_PARTIAL, SELL_FULL }

    /** Verify the canonical PnL invariant. Returns non-empty string when
     *  the row is corrupt. */
    fun verifyInvariant(tolerance: Double = 1e-9): String {
        if (side == Side.BUY) return ""
        val computed = proceedsSol - soldCostBasisSol - feesSol
        return if (kotlin.math.abs(computed - realizedPnlSol) > tolerance) {
            "REALIZED_PNL_MISMATCH computed=${"%.9f".format(computed)} row=${"%.9f".format(realizedPnlSol)}"
        } else ""
    }
}

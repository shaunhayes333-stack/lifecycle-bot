package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6412 — POSITION COST-BASIS REPAIR AUTHORITY.
 *
 * OPERATOR REPORT (Feb 2026, screenshot):
 * ────────────────────────────────────────
 * Open Positions panel showed ANTHROPIC "-100.0% Size 0.0000◎"
 * while the actual on-chain position held 4667 tokens at $0.000148,
 * a +277% winner. Chud showed similar "Size 0.0000◎" corruption.
 * The `pos.costSol` field had gone to zero while `qtyToken` and
 * `entryPrice` remained intact, blowing up PnL to -100% because
 * `(currentValue - 0) / 0` collapses.
 *
 * DOWNSTREAM DAMAGE
 * ─────────────────
 * A -100% mark is treated by the exit coordinator as a CATASTROPHIC /
 * RUG signal and can trigger emergency sell of a token that is
 * actually up +277%. The learning loop also flags the bucket as
 * MASSIVE_LOSS and biases future entries away from a real winning
 * setup. This authority MUST run before any exit / learning / UI
 * consumer reads the cost basis.
 *
 * DESIGN
 * ──────
 * repairedCostSol(qtyToken, entryPrice, priorCostSol, solPriceUsd)
 *   returns priorCostSol when it is finite and > 0
 *   else, when qtyToken × entryPrice yields a positive USD value AND
 *        solPriceUsd > 0, reconstructs costSol = usd / solPriceUsd
 *   else 0.0 (caller should treat as "basis wait")
 *
 * Emits POSITION_COST_BASIS_REPAIRED_6412 with delta so operators
 * can measure how frequently the ledger is losing costSol data.
 * NEVER mutates the position record itself — this is a read-side
 * authority. A follow-up commit will migrate the write path to
 * re-persist the repaired value in the SQLite portfolio store.
 */
object PositionCostBasisRepair6412 {

    /**
     * Return a trustworthy costSol for [mint], repairing zero/NaN
     * values when possible. Report-only.
     */
    fun repairedCostSol(
        mint: String,
        symbol: String,
        qtyToken: Double,
        entryPriceUsd: Double,
        priorCostSol: Double,
        solPriceUsd: Double,
    ): Double {
        // Trust the prior value when it looks real.
        if (priorCostSol.isFinite() && priorCostSol > 0.0000001) return priorCostSol
        // Need positive token qty AND positive entry price AND positive SOL price.
        if (qtyToken <= 0.0 || !qtyToken.isFinite()) return 0.0
        if (entryPriceUsd <= 0.0 || !entryPriceUsd.isFinite()) return 0.0
        if (solPriceUsd <= 0.0 || !solPriceUsd.isFinite()) return 0.0
        val usdBasis = qtyToken * entryPriceUsd
        val solBasis = usdBasis / solPriceUsd
        if (!solBasis.isFinite() || solBasis <= 0.0) return 0.0
        try {
            ForensicLogger.lifecycle(
                "POSITION_COST_BASIS_REPAIRED_6412",
                "mint=${mint.take(10)} sym=$symbol qty=$qtyToken entryPriceUsd=$entryPriceUsd " +
                    "priorCostSol=$priorCostSol solPriceUsd=$solPriceUsd repairedCostSol=$solBasis",
            )
            PipelineHealthCollector.labelInc("POSITION_COST_BASIS_REPAIRED_6412")
        } catch (_: Throwable) {}
        return solBasis
    }

    /**
     * Compute a trustworthy PnL% for a position. Returns null when
     * the basis and mark cannot be reconciled (caller should show
     * "basis wait" rather than a phantom -100%).
     */
    fun repairedPnlPct(
        mint: String,
        symbol: String,
        qtyToken: Double,
        entryPriceUsd: Double,
        lastPriceUsd: Double,
        priorCostSol: Double,
        solPriceUsd: Double,
    ): Double? {
        val cost = repairedCostSol(mint, symbol, qtyToken, entryPriceUsd, priorCostSol, solPriceUsd)
        if (cost <= 0.0) return null
        if (lastPriceUsd <= 0.0 || !lastPriceUsd.isFinite()) return null
        val currentUsd = qtyToken * lastPriceUsd
        val currentSol = if (solPriceUsd > 0.0) currentUsd / solPriceUsd else 0.0
        if (currentSol <= 0.0 || !currentSol.isFinite()) return null
        return ((currentSol - cost) / cost) * 100.0
    }

    fun statusLine(): String = try {
        val n = PipelineHealthCollector.labelCountSnapshot("POSITION_COST_BASIS_REPAIRED_6412")
        "repairs=$n"
    } catch (_: Throwable) { "unavailable" }
}

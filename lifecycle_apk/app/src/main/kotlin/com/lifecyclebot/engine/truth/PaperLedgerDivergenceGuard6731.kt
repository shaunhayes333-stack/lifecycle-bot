package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6731 — §PAPER_LEDGER_DIVERGENCE_HARD_STOP.
 *
 * Operator report following 6730: "accounting drift across the trading
 * decks. balances no longer align." This is the deferred Fault #3 from
 * the 6727 diagnostic that has now spread across all three decks
 * (meme / crypto / perps). Cash divergence between the authoritative
 * paper ledger and the journal replay has been observed reaching
 * 11.71 SOL with open-cost divergence 9.64 SOL and 43-position gap.
 *
 * The reconciliation surgery (heal-A) has been reverted twice because
 * paper journal replay uses multiple internal decimal representations,
 * so any attempted rewrite breaks the pricing invariant check. Rather
 * than attempt state healing (proven high-risk), this authority
 * publishes a HARD-STOP verdict that BLOCKS new admissions when the
 * divergence exceeds threshold. Exits are not blocked — the coordinator
 * continues to drain inventory — so the ledger can converge naturally
 * as sells complete without new opens compounding the drift.
 *
 * Reads through CanonicalPaperReplay6464 which already computes the
 * parity (cashDelta / realizedDelta / openCostDelta / qtyMismatches /
 * orphanLotCount / divergenceTag6724). This authority collapses that
 * into a single boolean the admission surface consults.
 *
 * Never mutates state. Purely publishes the verdict.
 */
object PaperLedgerDivergenceGuard6731 {

    /** Absolute cash-delta threshold above which admission blocks (SOL). */
    private const val CASH_DELTA_HARD_STOP_SOL = 3.0
    /** Absolute open-cost delta threshold. */
    private const val OPEN_COST_DELTA_HARD_STOP_SOL = 5.0
    /** Position-count discrepancy threshold. */
    private const val POSITION_COUNT_GAP_HARD_STOP = 10
    /** V5.0.6732 §PARITY_STALENESS_TOLERANCE — parity snapshot must be
     *  younger than this to authoritatively hard-stop admission. The
     *  maintenance worker refreshes every ~10s in production; anything
     *  older than 15s is likely referencing a ledger state that has
     *  already re-converged and would produce false-positive stops. */
    private const val PARITY_MAX_AGE_MS = 15_000L

    data class Verdict(
        val allow: Boolean,
        val reason: String,
        val cashDelta: Double,
        val openCostDelta: Double,
        val realizedDelta: Double,
        val positionCountGap: Int,
        val divergenceTag: String,
    )

    fun evaluate(): Verdict {
        val parity = try { CanonicalPaperReplay6464.lastParity() } catch (_: Throwable) { null }
        if (parity == null) {
            return Verdict(true, "OK_NO_PARITY", 0.0, 0.0, 0.0, 0, "NO_PARITY")
        }
        // V5.0.6732 §PARITY_STALENESS_GUARD — if the parity snapshot is
        // older than PARITY_MAX_AGE_MS, do NOT hard-stop admission. The
        // 6731 dump showed 282 divergence events but the canonical
        // ledger simultaneously reported conservation OK and the next
        // replay reported zero delta — a stale snapshot was choking new
        // admissions long after the ledger reconverged. Fail-open on
        // stale reads; the maintenance worker will refresh shortly and
        // real drift will re-engage the guard once evidence is current.
        val parityAge = try { CanonicalPaperReplay6464.lastParityAgeMs() } catch (_: Throwable) { 0L }
        if (parityAge > PARITY_MAX_AGE_MS) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_STALE_PARITY_FAIL_OPEN_6732") } catch (_: Throwable) {}
            return Verdict(true, "OK_STALE_PARITY_6732", parity.cashDelta, parity.openCostDelta, parity.realizedDelta, parity.orphanLotCount, "STALE_${parityAge}ms")
        }
        val cashΔ = kotlin.math.abs(parity.cashDelta)
        val openΔ = kotlin.math.abs(parity.openCostDelta)
        val realΔ = kotlin.math.abs(parity.realizedDelta)
        val posGap = parity.orphanLotCount
        val tag = "orphans=${parity.orphanLotCount}_qtyMM=${parity.qtyMismatchCount}"
        if (cashΔ >= CASH_DELTA_HARD_STOP_SOL) {
            try {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_CASH_6731")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_CASH_6731_${bucketFor(cashΔ)}")
            } catch (_: Throwable) {}
            return Verdict(false, "PAPER_LEDGER_DIVERGENCE_CASH_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag)
        }
        if (openΔ >= OPEN_COST_DELTA_HARD_STOP_SOL) {
            try {
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_OPEN_COST_6731")
                PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_OPEN_COST_6731_${bucketFor(openΔ)}")
            } catch (_: Throwable) {}
            return Verdict(false, "PAPER_LEDGER_DIVERGENCE_OPEN_COST_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag)
        }
        if (posGap >= POSITION_COUNT_GAP_HARD_STOP) {
            try { PipelineHealthCollector.labelInc("PAPER_LEDGER_DIVERGENCE_HARD_STOP_POS_GAP_6731") } catch (_: Throwable) {}
            return Verdict(false, "PAPER_LEDGER_DIVERGENCE_POS_GAP_6731",
                parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag)
        }
        return Verdict(true, "OK", parity.cashDelta, parity.openCostDelta, parity.realizedDelta, posGap, tag)
    }

    private fun bucketFor(delta: Double): String = when {
        delta < 5.0 -> "LT_5SOL"
        delta < 10.0 -> "LT_10SOL"
        delta < 20.0 -> "LT_20SOL"
        delta < 50.0 -> "LT_50SOL"
        else -> "GT_50SOL"
    }

    fun diagnosticLine(): String {
        val v = evaluate()
        return "PAPER_LEDGER_GUARD_6731 allow=${v.allow} reason=${v.reason} cashΔ=${"%.4f".format(v.cashDelta)} openCostΔ=${"%.4f".format(v.openCostDelta)} realizedΔ=${"%.4f".format(v.realizedDelta)} posGap=${v.positionCountGap} tag=${v.divergenceTag}"
    }
}

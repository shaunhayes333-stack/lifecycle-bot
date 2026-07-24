package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import kotlin.math.abs

/**
 * V5.0.6346 — CANONICAL LEARNING CONTRACT (operator P0-4 spec).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "A row only becomes canonical if it passes exact-decimal, parity,
 *    and cost-basis checks. No broadcast-only attribution."
 *
 *   "Rows that fail canonical eligibility stay in the journal for
 *    forensic replay but are NOT counted in canonical performance,
 *    tactic switcher, personality tune, or governor stats."
 *
 * PURPOSE
 *   Single authoritative eligibility check that every canonical
 *   learning aggregator must consult before admitting a row. Sits
 *   downstream of [TradeRowSanityCheck] (which handles duplicate
 *   detection, mode-mismatch, and paper-row corruption) and adds the
 *   3 canonical-only invariants the operator listed:
 *
 *   • EXACT_DECIMALS  — token qty × 10^decimals must round-trip
 *                       within one raw unit; otherwise decimal-skew is
 *                       poisoning the cost basis.
 *   • COST_BASIS_PARITY — (entryCostSol / entryQtyToken) must match
 *                       [Trade.entryPriceSnapshot] within 2%. This is
 *                       [CanonicalPnLAuthority6343.assertPriceCostQtyParity]
 *                       applied at the learning gate.
 *   • PROOF_SOURCE    — LIVE_BROADCAST-only rows are NEVER canonical.
 *                       Confirmed on-chain proof (LIVE_FINALIZED) or
 *                       paper proof (PAPER) are the only accepted
 *                       attribution sources.
 *
 * RETURN VALUE
 *   [Verdict.CANONICAL] — safe to enter canonical aggregations.
 *   [Verdict.QUARANTINED] — visible for forensic replay but MUST be
 *   filtered out of tactic switcher / personality tune / governor stats
 *   / concentrator sample / expectancy classifier.
 */
object CanonicalLearningContract6346 {

    /** Absolute price relative tolerance re-used from 6343 (parity band). */
    private const val PRICE_INVARIANT_RELATIVE_TOLERANCE: Double = 0.02

    enum class Verdict { CANONICAL, QUARANTINED }

    data class Assessment(
        val verdict: Verdict,
        val reasons: List<String>,
    ) {
        val isCanonical: Boolean get() = verdict == Verdict.CANONICAL
    }

    /**
     * Contract check for a finalized trade row.
     *
     * @param proofSource  "LIVE_FINALIZED" | "LIVE_BROADCAST" | "PAPER".
     *                     "" is treated as unknown and rejected.
     * @param tokenDecimals on-chain decimals (from wallet-verified fill)
     */
    fun assess(
        trade: Trade,
        proofSource: String,
        tokenDecimals: Int,
    ): Assessment {
        val reasons = mutableListOf<String>()

        // Guard: proof source. LIVE_BROADCAST is never canonical.
        val proofUpper = proofSource.uppercase()
        when (proofUpper) {
            "LIVE_FINALIZED", "PAPER" -> { /* accepted proofs */ }
            "LIVE_BROADCAST" -> reasons += "PROOF_LIVE_BROADCAST_NOT_CANONICAL_6346"
            else -> reasons += "PROOF_SOURCE_UNKNOWN_6346:$proofSource"
        }

        // Guard: exact decimals. Reconstruct raw units and require the
        // round-trip equality within one raw unit. Skew of >1 raw unit
        // means the qty was heuristic-derived pre-wallet-verify — that
        // row must not enter canonical performance.
        val effectiveQty = when {
            trade.side.equals("BUY", ignoreCase = true) -> trade.entryQtyToken
            trade.soldQtyToken > 0.0 -> trade.soldQtyToken
            else -> trade.entryQtyToken
        }
        if (tokenDecimals in 0..12 && effectiveQty > 0.0) {
            val raw = java.math.BigDecimal(effectiveQty)
                .movePointRight(tokenDecimals)
                .toBigInteger()
            val roundTrip = java.math.BigDecimal(raw)
                .movePointLeft(tokenDecimals)
                .toDouble()
            val delta = abs(effectiveQty - roundTrip)
            val onePerRawUnit = Math.pow(10.0, -tokenDecimals.toDouble())
            if (delta > onePerRawUnit) {
                reasons += "EXACT_DECIMALS_MISMATCH_6346:" +
                    "qty=${"%.9f".format(effectiveQty)} " +
                    "roundTrip=${"%.9f".format(roundTrip)} " +
                    "decimals=$tokenDecimals"
            }
        }

        // Guard: cost-basis parity via the 6343 authority.
        if (trade.entryCostSol > 0.0 && trade.entryQtyToken > 0.0 &&
            trade.entryPriceSnapshot > 0.0) {
            val diag = CanonicalPnLAuthority6343.assertPriceCostQtyParity(
                entryCostSol = trade.entryCostSol,
                entryQty = trade.entryQtyToken,
                entryPriceSolPerToken = trade.entryPriceSnapshot,
            )
            if (diag != null) {
                reasons += "COST_BASIS_PARITY_FAIL_6346:$diag"
            }
        } else if (trade.side.equals("SELL", ignoreCase = true) ||
                   trade.side.equals("PARTIAL_SELL", ignoreCase = true)) {
            // Missing basis on a SELL is a canonical-eligibility fault;
            // TradeRowSanityCheck already quarantines it for learning
            // but we double-check here so no path admits it as canonical.
            if (trade.entryCostSol <= 0.0) reasons += "MISSING_ENTRY_COST_BASIS_6346"
            if (trade.entryPriceSnapshot <= 0.0) reasons += "MISSING_ENTRY_PRICE_SNAPSHOT_6346"
        }

        val verdict = if (reasons.isEmpty()) Verdict.CANONICAL else Verdict.QUARANTINED
        try {
            when (verdict) {
                Verdict.CANONICAL -> PipelineHealthCollector.labelInc("CANONICAL_LEARNING_ADMITTED_6346")
                Verdict.QUARANTINED -> {
                    PipelineHealthCollector.labelInc("CANONICAL_LEARNING_QUARANTINED_6346")
                    ForensicLogger.lifecycle(
                        "CANONICAL_LEARNING_QUARANTINED_6346",
                        "mint=${trade.mint.take(10)} side=${trade.side} " +
                            "proof=$proofSource decimals=$tokenDecimals " +
                            "reasons=${reasons.joinToString("|")}",
                    )
                }
            }
        } catch (_: Throwable) {}
        return Assessment(verdict, reasons)
    }
}

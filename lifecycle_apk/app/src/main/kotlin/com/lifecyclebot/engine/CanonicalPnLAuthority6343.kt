package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.6343 — CANONICAL PnL AUTHORITY (single source of realized-SOL truth).
 *
 * OPERATOR DIRECTIVE (CUPSEY PARTIAL-LOT CORRECTION, verbatim clauses):
 *
 *   (1) Never calculate realized SOL PnL using
 *       (exitPriceUsd - entryPriceUsd) * tokenQty.
 *   (3) Realized SOL PnL must be:
 *       proceedsReceivedSol - allocatedEntryCostSol - sellFeeSol.
 *   (4) Partial allocated cost must be:
 *       originalEntryCostSol * soldQty / originalEntryQty.
 *   (5) A partial sell must never inherit the full position cost basis.
 *   (6) Position may become terminal only when
 *       sum(all lot sold quantities) + remainingQty == original
 *       within token-decimal tolerance.
 *   (7) LIVE_BROADCAST must not calculate or replace canonical PnL —
 *       it may confirm the transaction only.
 *   (8) Notifications and journal must consume the same canonical
 *       finalized-sell result. They must not use separate calculators.
 *   (9) Invariant:
 *       abs(entryCostSol/entryQty - entryPriceSolPerToken) <= tolerance.
 *  (10) Cupsey: 0.0264 / 490.091 ≈ 5.387e-5 SOL/token. A stored value
 *       of 4.088e-3 (76× off) MUST be rejected.
 *
 * This module is the ONLY place realized SOL PnL is legally computed.
 * Journal writers, notification builders, learning eligibility gates,
 * canonical performance aggregators — all must call [computeRealizedSol]
 * and consume its FinalizedSell verdict. Any code path that computes
 * `(exitUsd - entryUsd) * qty` or reads LIVE_BROADCAST fills as
 * proceeds is a bug and must be routed here instead.
 *
 * Failure of any invariant returns [Verdict.QUARANTINED] — the row does
 * NOT enter canonical performance, learning, tactic switcher, personality
 * tune, governor stats, or the concentrator sample. It stays visible for
 * forensic replay but never influences future decisions.
 */
object CanonicalPnLAuthority6343 {

    /** Absolute SOL tolerance for cost/price/qty parity checks. Below
     *  this the mismatch is treated as float rounding and accepted. */
    private const val SOL_PARITY_TOLERANCE: Double = 1e-6

    /** Relative tolerance for the entryCost/entryQty vs entryPriceSol
     *  invariant. Anything above this multiplier is a data-integrity
     *  fault. The Cupsey case (76× off) trips this a mile wide. */
    private const val PRICE_INVARIANT_RELATIVE_TOLERANCE: Double = 0.02   // 2%

    /** Token-quantity tolerance when checking terminal-close closure
     *  (sum of sold qty + remaining qty == original). Scales down as
     *  decimals grow: 6 decimals → 1e-6 tokens. */
    private fun terminalQtyTolerance(decimals: Int): Double =
        if (decimals in 0..12) Math.pow(10.0, -decimals.toDouble())
        else 1e-6

    enum class Verdict {
        /** All invariants held; the row is safe to write, notify, and
         *  train on. Consume [FinalizedSell.realizedSol] for PnL. */
        CANONICAL,
        /** One or more invariants failed. The row is visible for
         *  forensic replay but MUST NOT enter canonical performance,
         *  learning, or any authoritative aggregator. */
        QUARANTINED,
    }

    data class FinalizedSell(
        val verdict: Verdict,
        val realizedSol: Double,
        val allocatedEntryCostSol: Double,
        val proceedsReceivedSol: Double,
        val sellFeeSol: Double,
        val soldQty: Double,
        val originalEntryQty: Double,
        val originalEntryCostSol: Double,
        val remainingQty: Double,
        val terminal: Boolean,
        val quarantineReasons: List<String>,
    ) {
        val isCanonical: Boolean get() = verdict == Verdict.CANONICAL
    }

    /**
     * Sole authoritative PnL calculator. Feed it the raw immutable
     * facts of a finalized sell and consume the returned [FinalizedSell].
     *
     * @param originalEntryCostSol  immutable buy-fill cost from the lot ledger
     * @param originalEntryQty      immutable buy-fill quantity from the lot ledger
     * @param entryPriceSolPerToken immutable per-token price stored at buy time
     * @param soldQty               qty consumed by THIS sell (partial or full)
     * @param proceedsReceivedSol   SOL received back from the sell (finalized, NOT broadcast)
     * @param sellFeeSol            execution / priority fee paid on THIS sell
     * @param cumulativeSoldQty     total qty sold across ALL sells on the lot
     *                              (including this one)
     * @param tokenDecimals         on-chain token decimals (for terminal tolerance)
     * @param proofSource           "LIVE_FINALIZED", "LIVE_BROADCAST", "PAPER" —
     *                              only LIVE_FINALIZED / PAPER count as canonical
     */
    fun computeRealizedSol(
        originalEntryCostSol: Double,
        originalEntryQty: Double,
        entryPriceSolPerToken: Double,
        soldQty: Double,
        proceedsReceivedSol: Double,
        sellFeeSol: Double,
        cumulativeSoldQty: Double,
        tokenDecimals: Int,
        proofSource: String,
    ): FinalizedSell {
        val quarantineReasons = mutableListOf<String>()

        // ── Clause (7) LIVE_BROADCAST does NOT calculate canonical PnL.
        if (proofSource.equals("LIVE_BROADCAST", ignoreCase = true)) {
            quarantineReasons += "PROOF_LIVE_BROADCAST_NOT_CANONICAL_6343"
        }

        // ── Clause (5) partial sell must not consume the full basis.
        if (originalEntryQty <= 0.0) {
            quarantineReasons += "ORIGINAL_ENTRY_QTY_ZERO_OR_NEGATIVE_6343"
        }
        if (soldQty <= 0.0) {
            quarantineReasons += "SOLD_QTY_ZERO_OR_NEGATIVE_6343"
        }
        if (originalEntryCostSol <= 0.0) {
            quarantineReasons += "ORIGINAL_ENTRY_COST_ZERO_OR_NEGATIVE_6343"
        }

        // ── Clause (9) & (10) price / cost / qty invariant.
        //     abs(entryCostSol/entryQty - entryPriceSolPerToken)
        //         <= relative tolerance × entryPriceSolPerToken.
        //     Cupsey: 0.0264/490.091 ≈ 5.387e-5; stored 4.088e-3 → 76× off.
        if (originalEntryQty > 0.0 && entryPriceSolPerToken > 0.0) {
            val derivedPricePerToken = originalEntryCostSol / originalEntryQty
            val delta = abs(derivedPricePerToken - entryPriceSolPerToken)
            val relTolerance = entryPriceSolPerToken * PRICE_INVARIANT_RELATIVE_TOLERANCE
            if (delta > relTolerance && delta > SOL_PARITY_TOLERANCE) {
                quarantineReasons += "PRICE_COST_QTY_INVARIANT_FAILURE_6343:" +
                    "derived=${"%.4e".format(derivedPricePerToken)} " +
                    "stored=${"%.4e".format(entryPriceSolPerToken)} " +
                    "delta=${"%.4e".format(delta)} tol=${"%.4e".format(relTolerance)}"
            }
        }

        // ── Clause (4) partial allocated cost.
        //     partialCost = originalCost × soldQty / originalQty.
        val allocatedEntryCostSol =
            if (originalEntryQty > 0.0) originalEntryCostSol * soldQty / originalEntryQty
            else 0.0

        // ── Clause (5) never inherit full basis on partial sell.
        if (allocatedEntryCostSol > originalEntryCostSol + SOL_PARITY_TOLERANCE) {
            quarantineReasons += "PARTIAL_INHERITED_FULL_BASIS_6343:" +
                "allocated=${"%.6f".format(allocatedEntryCostSol)} " +
                "original=${"%.6f".format(originalEntryCostSol)}"
        }

        // ── Clause (3) realized SOL PnL formula.
        val realizedSol = proceedsReceivedSol - allocatedEntryCostSol - sellFeeSol

        // ── Clause (6) terminal-close closure invariant.
        val remainingQty = (originalEntryQty - cumulativeSoldQty).coerceAtLeast(0.0)
        val terminalTol = terminalQtyTolerance(tokenDecimals)
        val terminal = remainingQty <= terminalTol && originalEntryQty > 0.0
        if (cumulativeSoldQty > originalEntryQty + terminalTol) {
            quarantineReasons += "CUMULATIVE_SOLD_EXCEEDS_ORIGINAL_6343:" +
                "sum=${"%.6f".format(cumulativeSoldQty)} " +
                "orig=${"%.6f".format(originalEntryQty)}"
        }

        val verdict = if (quarantineReasons.isEmpty()) Verdict.CANONICAL else Verdict.QUARANTINED

        try {
            if (verdict == Verdict.CANONICAL) {
                PipelineHealthCollector.labelInc("CANONICAL_PNL_AUTHORITY_OK_6343")
            } else {
                PipelineHealthCollector.labelInc("CANONICAL_PNL_QUARANTINED_6343")
                ForensicLogger.lifecycle(
                    "CANONICAL_PNL_QUARANTINED_6343",
                    "reasons=${quarantineReasons.joinToString("|")} " +
                        "origQty=${"%.4f".format(originalEntryQty)} " +
                        "origCost=${"%.6f".format(originalEntryCostSol)} " +
                        "soldQty=${"%.4f".format(soldQty)} " +
                        "cumSold=${"%.4f".format(cumulativeSoldQty)} " +
                        "proceeds=${"%.6f".format(proceedsReceivedSol)} " +
                        "fee=${"%.6f".format(sellFeeSol)} " +
                        "allocated=${"%.6f".format(allocatedEntryCostSol)} " +
                        "realized=${"%.6f".format(realizedSol)} " +
                        "remaining=${"%.4f".format(remainingQty)} " +
                        "terminal=$terminal proof=$proofSource",
                )
            }
        } catch (_: Throwable) {}

        return FinalizedSell(
            verdict = verdict,
            realizedSol = if (verdict == Verdict.CANONICAL) realizedSol else 0.0,
            allocatedEntryCostSol = allocatedEntryCostSol,
            proceedsReceivedSol = proceedsReceivedSol,
            sellFeeSol = sellFeeSol,
            soldQty = soldQty,
            originalEntryQty = originalEntryQty,
            originalEntryCostSol = originalEntryCostSol,
            remainingQty = remainingQty,
            terminal = terminal && verdict == Verdict.CANONICAL,
            quarantineReasons = quarantineReasons,
        )
    }

    /**
     * Standalone invariant checker for the price/cost/qty triangle
     * (clauses 9 and 10). Callable from journal-write pre-flight and
     * from unit tests. Returns null on pass, a diagnostic string on fail.
     */
    fun assertPriceCostQtyParity(
        entryCostSol: Double,
        entryQty: Double,
        entryPriceSolPerToken: Double,
    ): String? {
        if (entryQty <= 0.0 || entryPriceSolPerToken <= 0.0) return null
        val derivedPricePerToken = entryCostSol / entryQty
        val delta = abs(derivedPricePerToken - entryPriceSolPerToken)
        val relTolerance = entryPriceSolPerToken * PRICE_INVARIANT_RELATIVE_TOLERANCE
        return if (delta > relTolerance && delta > SOL_PARITY_TOLERANCE) {
            "PARITY_FAIL derived=${"%.4e".format(derivedPricePerToken)} " +
                "stored=${"%.4e".format(entryPriceSolPerToken)} " +
                "delta=${"%.4e".format(delta)} tol=${"%.4e".format(relTolerance)}"
        } else null
    }
}

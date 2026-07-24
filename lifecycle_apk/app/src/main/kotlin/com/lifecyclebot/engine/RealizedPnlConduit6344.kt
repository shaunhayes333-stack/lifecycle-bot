package com.lifecyclebot.engine

import kotlin.math.abs

/**
 * V5.0.6344 — REALIZED PnL CONDUIT (single funnel into [CanonicalPnLAuthority6343]).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Notifications and journal must consume the same canonical
 *    finalized-sell result. They must not use separate calculators."
 *
 *   "Every writer of realized SOL PnL — Executor, TradeHistoryStore,
 *    V3JournalRecorder, PositionCloseLedger, notification builder,
 *    learning ingest — must route through the authority. No inline
 *    (exitPriceUsd - entryPriceUsd) * qty math is legal."
 *
 * PURPOSE
 *   Provides the single call every writer uses. Under the hood the
 *   conduit
 *     1. Resolves the immutable lot from [FillLotLedger6344] (or falls
 *        back to a caller-supplied basis when the ledger has no matching
 *        row — legacy positions opened before 6344 landed);
 *     2. Invokes [CanonicalPnLAuthority6343.computeRealizedSol] with the
 *        exact cost basis and cumulative sold qty from the ledger;
 *     3. Compares the canonical realized SOL against the classic inline
 *        formula (`proceeds - fee - basis`) and emits a divergence health
 *        counter if the two disagree by more than a strict tolerance;
 *     4. Appends the sell partial to the ledger so the next partial
 *        starts from the correct cumulative-sold baseline.
 *
 * ENFORCEMENT MODE (V5.0.6344 = SHADOW)
 *   In this initial push the conduit is authoritative for its own return
 *   value BUT does not block the sell path if the classic inline pnl
 *   disagrees. Every disagreement is logged via
 *   `CANONICAL_PNL_DIVERGENCE_6344` and `CANONICAL_PNL_QUARANTINED_6344`
 *   for forensic replay. A follow-up push flips this to HARD enforcement
 *   once the golden-tape corpus has been re-baselined against canonical
 *   values.
 */
object RealizedPnlConduit6344 {

    /** Absolute SOL delta above which classic-vs-canonical divergence is loud. */
    private const val DIVERGENCE_SOL_TOLERANCE: Double = 1e-4   // 0.0001 SOL

    data class Result(
        val realizedSol: Double,
        val allocatedEntryCostSol: Double,
        val proceedsSol: Double,
        val feeSol: Double,
        val soldQty: Double,
        val remainingQty: Double,
        val terminal: Boolean,
        val canonical: Boolean,
        val quarantineReasons: List<String>,
        val diverged: Boolean,
        val classicRealizedSol: Double,
    )

    /**
     * Sole authoritative finalize call for realized SOL PnL.
     *
     * @param walletAddress caller's Solana wallet base58 pubkey
     * @param mintAddress   token mint being sold
     * @param buyTxSig      confirmed buy signature that opened the lot; may be
     *                      blank if unknown (legacy position). If blank, the
     *                      conduit falls back to [FillLotLedger6344.latestOpenLot]
     *                      or to the caller-supplied fallback basis.
     * @param sellTxSig     confirmed sell signature
     * @param soldQty       qty sold in this partial (UI units, decimals aware)
     * @param proceedsSol   SOL received back on finalize (NOT broadcast)
     * @param feeSol        execution/priority fee paid on THIS sell
     * @param proofSource   "LIVE_FINALIZED", "LIVE_BROADCAST", "PAPER"
     * @param fallbackEntryCostSol   used only when no ledger lot is found
     * @param fallbackEntryQty       used only when no ledger lot is found
     * @param fallbackEntryPriceSol  used only when no ledger lot is found
     * @param fallbackTokenDecimals  used only when no ledger lot is found
     */
    fun finalize(
        walletAddress: String,
        mintAddress: String,
        buyTxSig: String,
        sellTxSig: String,
        soldQty: TokenQuantity,
        proceedsSol: SolAmount,
        feeSol: SolAmount,
        proofSource: String,
        fallbackEntryCostSol: SolAmount = SolAmount.ZERO,
        fallbackEntryQty: TokenQuantity = TokenQuantity.ZERO,
        fallbackEntryPriceSol: PriceSolPerToken = PriceSolPerToken.ZERO,
        fallbackTokenDecimals: Int = 9,
    ): Result {
        // 1. Resolve the immutable lot.
        val lot: FillLotLedger6344.Lot? =
            if (buyTxSig.isNotBlank())
                FillLotLedger6344.get(walletAddress, mintAddress, buyTxSig)
                    ?: FillLotLedger6344.latestOpenLot(walletAddress, mintAddress)
            else
                FillLotLedger6344.latestOpenLot(walletAddress, mintAddress)

        val originalEntryCostSol = lot?.entryCostSol ?: fallbackEntryCostSol.unwrap()
        val originalEntryQty = lot?.entryQty ?: fallbackEntryQty.unwrap()
        val entryPriceSolPerToken = lot?.entryPriceSolPerToken ?: fallbackEntryPriceSol.unwrap()
        val tokenDecimals = lot?.decimals ?: fallbackTokenDecimals
        val prevCumulativeSold = lot?.cumulativeSoldQty ?: 0.0
        val cumulativeSoldQty = prevCumulativeSold + soldQty.unwrap()

        if (lot == null) {
            try {
                PipelineHealthCollector.labelInc("REALIZED_PNL_CONDUIT_LOT_MISSING_6344")
                ForensicLogger.lifecycle(
                    "REALIZED_PNL_CONDUIT_LOT_MISSING_6344",
                    "wallet=${walletAddress.take(10)} mint=${mintAddress.take(10)} " +
                        "buySig=${buyTxSig.take(12)} sellSig=${sellTxSig.take(12)} " +
                        "using_fallback_basis=${originalEntryCostSol > 0.0}",
                )
            } catch (_: Throwable) {}
        }

        // 2. Delegate to the authority.
        val verdict = CanonicalPnLAuthority6343.computeRealizedSol(
            originalEntryCostSol = originalEntryCostSol,
            originalEntryQty = originalEntryQty,
            entryPriceSolPerToken = entryPriceSolPerToken,
            soldQty = soldQty.unwrap(),
            proceedsReceivedSol = proceedsSol.unwrap(),
            sellFeeSol = feeSol.unwrap(),
            cumulativeSoldQty = cumulativeSoldQty,
            tokenDecimals = tokenDecimals,
            proofSource = proofSource,
        )

        // 3. Divergence check vs classic inline formula (proceeds - fee - basis).
        //    Classic path used the FULL basis on partials — the bug 6343 exists
        //    to eradicate. This divergence counter proves the surface still had
        //    the bug and is now being corrected.
        val classicRealizedSol =
            proceedsSol.unwrap() - feeSol.unwrap() - originalEntryCostSol
        val diverged = verdict.isCanonical &&
            abs(verdict.realizedSol - classicRealizedSol) > DIVERGENCE_SOL_TOLERANCE
        if (diverged) {
            try {
                PipelineHealthCollector.labelInc("CANONICAL_PNL_DIVERGENCE_6344")
                ForensicLogger.lifecycle(
                    "CANONICAL_PNL_DIVERGENCE_6344",
                    "mint=${mintAddress.take(10)} classic=${"%.6f".format(classicRealizedSol)} " +
                        "canonical=${"%.6f".format(verdict.realizedSol)} " +
                        "allocated=${"%.6f".format(verdict.allocatedEntryCostSol)} " +
                        "origCost=${"%.6f".format(originalEntryCostSol)} " +
                        "soldQty=${"%.4f".format(soldQty.unwrap())} " +
                        "origQty=${"%.4f".format(originalEntryQty)} " +
                        "cumSold=${"%.4f".format(cumulativeSoldQty)}",
                )
            } catch (_: Throwable) {}
        }

        // 4. Append the sell to the ledger (only when proof is finalized
        //    and we actually found a lot to attribute against).
        if (lot != null && sellTxSig.isNotBlank() &&
            proofSource.equals("LIVE_FINALIZED", ignoreCase = true)) {
            try {
                FillLotLedger6344.appendSell(
                    walletAddress = walletAddress,
                    mintAddress = mintAddress,
                    buyTxSig = lot.buyTxSig,
                    sellTxSig = sellTxSig,
                    soldQty = soldQty,
                    proceedsSol = proceedsSol,
                    feeSol = feeSol,
                    sellTsMs = System.currentTimeMillis(),
                    proofSource = proofSource,
                )
            } catch (_: Throwable) {}
        }

        return Result(
            realizedSol = verdict.realizedSol,
            allocatedEntryCostSol = verdict.allocatedEntryCostSol,
            proceedsSol = proceedsSol.unwrap(),
            feeSol = feeSol.unwrap(),
            soldQty = soldQty.unwrap(),
            remainingQty = verdict.remainingQty,
            terminal = verdict.terminal,
            canonical = verdict.isCanonical,
            quarantineReasons = verdict.quarantineReasons,
            diverged = diverged,
            classicRealizedSol = classicRealizedSol,
        )
    }
}

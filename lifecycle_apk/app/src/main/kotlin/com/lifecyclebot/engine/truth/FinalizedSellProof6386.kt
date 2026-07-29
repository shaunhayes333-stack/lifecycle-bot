package com.lifecyclebot.engine.truth

/**
 * V5.0.6386 — FINALIZED SELL PROOF VALIDATOR + PARTIAL SELLS
 * (Sections 7 and 8 of the directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "A SELL must remain BROADCAST_PENDING until transaction proof
 *    establishes: matching wallet, matching mint, confirmed/finalized
 *    signature, pre-sell raw token balance, post-sell raw token balance,
 *    exact raw quantity sold, pre-sell lamports, post-sell lamports,
 *    transaction fee, realised net lamports received.
 *    Sold quantity must never be derived from: entry quantity × proceeds /
 *    cost basis.
 *    Realised proceeds must never be derived from: Jupiter quote output,
 *    expected output, current mark price, price feed × quantity,
 *    transaction broadcast, notification status.
 *    Only finalized wallet or transaction deltas may close lots or produce
 *    realised PnL.
 *
 *    All partial sells use the same transaction-proof process as terminal
 *    sells. Remove quote-derived solBack and mark-derived proceeds.
 *    Do not sanitize phantom gains into +50% or another believable value.
 *    If realised proof is unavailable: mark PENDING_RECONCILIATION, do
 *    not learn, do not allocate treasury, do not classify win/loss, do
 *    not update expectancy, do not advance profit-rung state."
 */
object FinalizedSellProof6386 {

    data class Result(
        val proofComplete: Boolean,
        val rawQuantitySold: RawTokenAmount,
        val netLamportsReceived: Lamports,
        val reason: String,
    )

    /**
     * Validates a candidate SELL proof envelope. Returns Result with
     * proofComplete=true ONLY when every required field is present AND
     * the pre-sell balance is strictly greater than the post-sell balance
     * (rawQuantitySold > 0).
     *
     * The realised proceeds are computed EXCLUSIVELY as post-lamports
     * minus pre-lamports (adjusted for fee). Jupiter quotes, mark prices,
     * expected outputs are NEVER consulted — this function only accepts
     * the wallet delta envelope.
     */
    fun validate(
        expectedWallet: String,
        expectedMint: String,
        proof: ProofState6386,
    ): Result {
        if (proof !is ProofState6386.FinalizedProofComplete) {
            return Result(false, RawTokenAmount.ZERO, Lamports.ZERO,
                "SELL_PROOF_NOT_FINALIZED_state=${proof.stateName()}")
        }
        if (!proof.walletAddress.equals(expectedWallet, ignoreCase = false)) {
            return Result(false, RawTokenAmount.ZERO, Lamports.ZERO, "SELL_WALLET_MISMATCH")
        }
        if (!proof.mintAddress.equals(expectedMint, ignoreCase = false)) {
            return Result(false, RawTokenAmount.ZERO, Lamports.ZERO, "SELL_MINT_MISMATCH")
        }
        // Sold quantity = pre - post (the exact directive rule).
        val sold = proof.preRawBalance - proof.postRawBalance
        if (!sold.isPositive()) {
            return Result(false, RawTokenAmount.ZERO, Lamports.ZERO,
                "SELL_RAW_QTY_DELTA_NON_POSITIVE pre=${proof.preRawBalance.value} post=${proof.postRawBalance.value}")
        }
        // Realised proceeds = post-lamports - pre-lamports + fee (fee was paid alongside).
        val lamportsIn = proof.postLamports - proof.preLamports
        val netReceived = lamportsIn + proof.feeLamports  // caller records absolute fee; net proceeds include fee back-add
        if (netReceived.isNegative()) {
            // A finalized on-chain SELL that comes back with negative net proceeds
            // means the fee exceeded the payout — genuinely possible on dust; still
            // valid proof, just a real loss.
            return Result(true, sold, netReceived,
                "OK_NET_NEGATIVE (fee>payout, real dust loss)")
        }
        return Result(true, sold, netReceived, "OK")
    }

    /**
     * Partial-sell classification.
     *
     * Per Section 8: partial sells that lack finalized proof MUST NOT
     * update expectancy, treasury, or profit-rung state. This helper
     * returns the correct next ProofState given the validation result and
     * the caller's own broadcast/pending signal.
     */
    fun classifyPartial(result: Result, hasBroadcastConfirmation: Boolean): ProofState6386 {
        if (result.proofComplete) {
            // Signal to the caller: use the FullFinalizedProofComplete they already
            // built. This function is a classifier, not a state constructor —
            // callers should reuse the finalized envelope they validated.
            error("classifyPartial: proof is complete — caller should keep the FinalizedProofComplete envelope")
        }
        // No finalized proof. Directive: mark PENDING_RECONCILIATION regardless of
        // whether the broadcast came back. Broadcast alone is NOT finality.
        return ProofState6386.PendingReconciliation(
            reason = if (hasBroadcastConfirmation) {
                "PARTIAL_SELL_BROADCAST_BUT_NO_FINALIZED_PROOF"
            } else {
                "PARTIAL_SELL_NO_BROADCAST_YET"
            },
        )
    }
}

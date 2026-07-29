package com.lifecyclebot.engine.truth

/**
 * V5.0.6386 — FINALIZED BUY PROOF VALIDATOR (Section 5 of the directive).
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "A BUY transaction must remain PENDING_FILL_PROOF until transaction
 *    metadata or pre/post wallet snapshots establish: matching owner,
 *    matching mint, confirmed/finalized signature, pre-buy raw token
 *    balance, post-buy raw token balance, raw quantity delta greater than
 *    zero, mint decimals, pre-buy lamports, post-buy lamports, fee
 *    lamports, net lamports spent.
 *    Never use the post-buy total ATA balance as the quantity for the new
 *    lot. If transaction metadata is unavailable, use a pre-captured
 *    balance plus post-balance and calculate delta only.
 *    Do not open stops, take-profit logic, learning, canonical performance
 *    or lot accounting until BUY proof is complete."
 */
object FinalizedBuyProof6386 {

    data class Result(
        val proofComplete: Boolean,
        val quantityDelta: RawTokenAmount,
        val netLamportsSpent: Lamports,
        val reason: String,
    )

    /**
     * Validates a candidate BUY proof envelope. Returns Result with
     * proofComplete=true ONLY when every required field is present AND
     * the raw quantity delta is strictly positive.
     *
     * IMPORTANT: quantityDelta = post - pre. The directive explicitly
     * forbids using the post-buy total ATA balance as the new lot size —
     * doing so would fold pre-existing holdings into the new lot's cost
     * basis. This function enforces the delta-only rule.
     */
    fun validate(
        expectedWallet: String,
        expectedMint: String,
        proof: ProofState6386,
    ): Result {
        if (proof !is ProofState6386.FinalizedProofComplete) {
            return Result(false, RawTokenAmount.ZERO, Lamports.ZERO,
                "PROOF_NOT_FINALIZED_state=${proof.stateName()}")
        }
        if (!proof.walletAddress.equals(expectedWallet, ignoreCase = false)) {
            return Result(false, RawTokenAmount.ZERO, Lamports.ZERO,
                "WALLET_MISMATCH expected=${expectedWallet.take(10)} got=${proof.walletAddress.take(10)}")
        }
        if (!proof.mintAddress.equals(expectedMint, ignoreCase = false)) {
            return Result(false, RawTokenAmount.ZERO, Lamports.ZERO,
                "MINT_MISMATCH expected=${expectedMint.take(10)} got=${proof.mintAddress.take(10)}")
        }
        val delta = proof.postRawBalance - proof.preRawBalance
        if (!delta.isPositive()) {
            return Result(false, RawTokenAmount.ZERO, Lamports.ZERO,
                "RAW_QTY_DELTA_NON_POSITIVE pre=${proof.preRawBalance.value} post=${proof.postRawBalance.value}")
        }
        val lamportsDelta = proof.preLamports - proof.postLamports  // spent = pre - post (before fee sub)
        // Net lamports spent = lamports delta - fee lamports on the wallet side.
        // (For SOL-native fee accounts, fee is included in the delta; the caller
        // supplies feeLamports so we can normalise net.)
        val netSpent = lamportsDelta - proof.feeLamports
        if (netSpent.isNegative()) {
            return Result(false, delta, Lamports.ZERO,
                "NET_LAMPORTS_SPENT_NEGATIVE lamportsDelta=${lamportsDelta.value} fee=${proof.feeLamports.value}")
        }
        return Result(true, delta, netSpent, "OK")
    }
}

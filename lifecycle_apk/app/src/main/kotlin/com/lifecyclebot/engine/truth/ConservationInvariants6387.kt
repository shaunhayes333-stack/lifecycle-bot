package com.lifecyclebot.engine.truth

/**
 * V5.0.6387 — CONSERVATION INVARIANTS (Directive A, P0).
 *
 * entryRawQty == totalSoldRawQty + remainingWalletRawQty (SPL)
 * originalBasisLamports == realisedAllocatedBasisLamports + remainingBasisLamports
 */
object ConservationInvariants6387 {

    data class QuantityCheck(val ok: Boolean, val delta: java.math.BigInteger, val reason: String)
    data class BasisCheck(val ok: Boolean, val delta: Long, val reason: String)

    fun checkQuantity(
        entryRaw: RawTokenAmount,
        totalSoldRaw: RawTokenAmount,
        remainingWalletRaw: RawTokenAmount,
        transferFeeWithheldRaw: RawTokenAmount = RawTokenAmount.ZERO,
    ): QuantityCheck {
        val sum = totalSoldRaw + remainingWalletRaw + transferFeeWithheldRaw
        val delta = entryRaw.value - sum.value
        val ok = delta.signum() == 0
        return QuantityCheck(
            ok = ok, delta = delta,
            reason = if (ok) "OK" else "RAW_QUANTITY_CONSERVATION_FAIL entry=${entryRaw.value} sold=${totalSoldRaw.value} remaining=${remainingWalletRaw.value} fee=${transferFeeWithheldRaw.value} delta=${delta}",
        )
    }

    fun checkBasis(
        originalBasisLamports: Lamports,
        realisedAllocatedBasisLamports: Lamports,
        remainingBasisLamports: Lamports,
    ): BasisCheck {
        val sum = realisedAllocatedBasisLamports + remainingBasisLamports
        val delta = originalBasisLamports.value.subtract(sum.value).toLong()
        val ok = delta == 0L
        return BasisCheck(
            ok = ok, delta = delta,
            reason = if (ok) "OK" else "BASIS_CONSERVATION_FAIL orig=${originalBasisLamports.value} realised=${realisedAllocatedBasisLamports.value} remaining=${remainingBasisLamports.value} delta=$delta",
        )
    }

    /** Directive: "quarantine the position; block learning; block discretionary exits; set live entry authority to HOLD." */
    fun onFailure(reason: String) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("RAW_QUANTITY_CONSERVATION_FAIL_6387")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BASIS_CONSERVATION_FAIL_6387")
        } catch (_: Throwable) {}
        CanonicalLedgerParityHold6387.onInvariantFailure(reason)
    }
}

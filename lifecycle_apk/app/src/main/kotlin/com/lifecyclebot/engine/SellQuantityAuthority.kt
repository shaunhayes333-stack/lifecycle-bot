package com.lifecyclebot.engine

/**
 * V5.0.6324 — SELL QUANTITY CANONICAL AUTHORITY (operator hotfix §4).
 *
 * Every sell request must derive quantity from the canonical remaining
 * quantity clamped to wallet-confirmed available quantity. This helper
 * centralises the math and emits [SELL_QTY_CANONICAL_AUTHORITY_6324]
 * so the audit trail records the decision inputs.
 */
object SellQuantityAuthority {

    data class Decision(
        val effectiveQuantity: Double,
        val clampApplied: Boolean,
        val walletUsed: Boolean,
        val canonicalRemaining: Double,
        val walletAvailable: Double,
        val requestedFraction: Double,
        val walletAgeMs: Long,
        val reason: String,
    )

    /**
     * Compute the effective sell quantity per the operator spec:
     *
     *   effectiveSellQuantity =
     *     min(requestedFraction × canonicalRemainingQuantity,
     *         walletConfirmedAvailableQuantity)
     *
     * For full exits (requestedFraction ~= 1.0) the wallet-confirmed
     * quantity wins outright, provided it is fresh.
     *
     * Never derives quantity from price, original SOL allocation,
     * market cap, advisor estimate, Dexscreener, or a stale journal row.
     */
    fun compute(
        mint: String,
        positionId: String,
        requestedFraction: Double,
        canonicalRemaining: Double,
        walletAvailable: Double,
        walletAgeMs: Long,
        exitReason: String,
        executionId: String,
    ): Decision {
        val frac = requestedFraction.coerceIn(0.0, 1.0)
        val walletFresh = walletAvailable > 0.0 && walletAgeMs in 0..30_000L
        val fromCanonical = frac * canonicalRemaining
        val isFullExit = frac >= 0.999
        val (effective, walletUsed, clamped, reason) = when {
            isFullExit && walletFresh ->
                Quadruple(walletAvailable, true, walletAvailable < canonicalRemaining, "FULL_EXIT_WALLET_AUTHORITATIVE")
            walletFresh && walletAvailable < fromCanonical ->
                Quadruple(walletAvailable, true, true, "WALLET_CLAMP_APPLIED")
            else ->
                Quadruple(fromCanonical.coerceAtLeast(0.0), false, false, if (walletFresh) "CANONICAL_WITHIN_WALLET" else "WALLET_STALE_USE_CANONICAL")
        }
        try {
            ForensicLogger.lifecycle(
                "SELL_QTY_CANONICAL_AUTHORITY_6324",
                "mint=${mint.take(10)} positionId=${positionId.take(24)} requestedFraction=${"%.4f".format(frac)} canonicalRemaining=$canonicalRemaining walletAvailable=$walletAvailable effectiveQuantity=$effective walletUsed=$walletUsed clampApplied=$clamped walletAgeMs=$walletAgeMs exitReason=${exitReason.take(48)} executionId=${executionId.take(24)} reason=$reason",
            )
            PipelineHealthCollector.labelInc("SELL_QTY_CANONICAL_AUTHORITY_6324")
            if (clamped) PipelineHealthCollector.labelInc("SELL_QTY_WALLET_CLAMPED_6324")
        } catch (_: Throwable) {}
        return Decision(
            effectiveQuantity = effective,
            clampApplied = clamped,
            walletUsed = walletUsed,
            canonicalRemaining = canonicalRemaining,
            walletAvailable = walletAvailable,
            requestedFraction = frac,
            walletAgeMs = walletAgeMs,
            reason = reason,
        )
    }

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}

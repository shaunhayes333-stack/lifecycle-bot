package com.lifecyclebot.engine.truth

import java.math.BigInteger

/**
 * V5.0.6401 §7/§8 — CANONICAL SELL INTENT QUANTITY AUTHORITY.
 *
 * ROOT CAUSE ADDRESSED
 * ─────────────────────
 * The V5.0.6400 pipeline health snapshot showed sell rows where the
 * intended UI quantity was 1409 tokens but the sell path attempted to
 * broadcast 1.409e5 (100× inflated) raw tokens. That is the classic
 * decimals-double-application bug: a UI-scale quantity (already
 * decimal-adjusted) is treated as if it were raw base units, causing
 * one extra multiplication by 10^decimals somewhere on the sell path.
 *
 * OPERATOR DIRECTIVE
 * ───────────────────
 * "No floating-point token quantities; use strict BigInteger/lamport
 *  math. Sell intent quantity replacement must be raw-integer only,
 *  guarded by a hard invariant that rejects any request > canonical
 *  wallet raw balance."
 *
 * WHY A SEPARATE AUTHORITY
 * ─────────────────────────
 * The existing `QuantityIntegrityGuard6395` quarantines rows AFTER
 * broadcast when it sees a skew between estimate and confirmed
 * quantities. That is a POST-broadcast forensic step — funds may
 * already have been risked at the wrong scale. This authority runs
 * BEFORE broadcast so a 100× inflated request never leaves the app.
 *
 * PURE VALIDATION SURFACE
 * ────────────────────────
 * All decisions are pure functions over inputs. Callers on the sell
 * path convert UI qty → raw via [convertUiToRaw] and then call
 * [validateSellIntent] before handing the raw amount to the
 * transaction builder. Any violation returns a [Verdict.Reject]
 * that the caller must map to a canonical quarantine outcome.
 */
object SellIntentQuantityAuthority6401 {

    /**
     * Maximum acceptable overshoot vs canonical wallet raw balance.
     * We tolerate 0.5% for rounding artefacts on the final partial
     * exit; anything larger is treated as a decimal-scale error and
     * REJECTED.
     */
    const val OVERSHOOT_TOLERANCE_PCT: Double = 0.5

    /**
     * The 100× signature the operator called out — a request that
     * exceeds wallet raw balance by ≥ 50× is almost certainly a
     * decimals-double-application. Emitted separately so post-mortem
     * counters can distinguish "over-sold by a small skew" from
     * "over-sold by ~10^decimals".
     */
    const val DECIMAL_SKEW_MULTIPLIER_THRESHOLD: Double = 50.0

    sealed class Verdict {
        data class Accept(
            val rawQty: BigInteger,
            val decimals: Int,
            val note: String = "",
        ) : Verdict()
        data class Reject(
            val reason: String,
            val rawRequested: BigInteger,
            val rawAvailable: BigInteger,
            val overshootPct: Double,
        ) : Verdict()
    }

    /**
     * Convert a UI (decimal-adjusted) token quantity to a raw
     * BigInteger amount using the mint's KNOWN decimals. Refuses to
     * operate on Unknown decimals — the operator directive is
     * explicit that we never coerce unknown decimals to zero.
     */
    fun convertUiToRaw(uiQty: Double, decimals: MintDecimals): BigInteger {
        require(uiQty.isFinite()) { "uiQty must be finite (got $uiQty)" }
        require(uiQty >= 0.0) { "uiQty must be non-negative (got $uiQty)" }
        val known = when (decimals) {
            is MintDecimals.Known -> decimals.count
            MintDecimals.Unknown -> throw IllegalStateException(
                "SellIntentQuantityAuthority6401.convertUiToRaw refuses MintDecimals.Unknown — decimals must be resolved on the sell path",
            )
        }
        // Route through BigDecimal.movePointRight so we never introduce
        // Double-multiplication drift on token counts.
        val bd = java.math.BigDecimal(uiQty.toString())
            .movePointRight(known)
            .setScale(0, java.math.RoundingMode.DOWN)
        return bd.toBigInteger()
    }

    /**
     * Validate a sell intent AT THE BOUNDARY, before any transaction
     * is built. Enforces `rawQtyRequested <= walletRawBalance *
     * (1 + OVERSHOOT_TOLERANCE_PCT/100)`. Overshoots > 50× are
     * flagged as DECIMAL_SKEW so the operator can find the
     * scale-error site fast.
     */
    fun validateSellIntent(
        mint: String,
        rawQtyRequested: BigInteger,
        walletRawBalance: BigInteger,
        decimals: MintDecimals,
    ): Verdict {
        if (walletRawBalance.signum() <= 0) {
            return Verdict.Reject(
                reason = "WALLET_RAW_BALANCE_NON_POSITIVE",
                rawRequested = rawQtyRequested,
                rawAvailable = walletRawBalance,
                overshootPct = Double.POSITIVE_INFINITY,
            )
        }
        if (rawQtyRequested.signum() <= 0) {
            return Verdict.Reject(
                reason = "SELL_QTY_NON_POSITIVE",
                rawRequested = rawQtyRequested,
                rawAvailable = walletRawBalance,
                overshootPct = 0.0,
            )
        }
        val overshootPct = if (rawQtyRequested <= walletRawBalance) 0.0 else {
            val delta = rawQtyRequested.subtract(walletRawBalance).toDouble()
            (delta / walletRawBalance.toDouble()) * 100.0
        }
        if (overshootPct <= OVERSHOOT_TOLERANCE_PCT) {
            val decimalsCount = (decimals as? MintDecimals.Known)?.count ?: -1
            return Verdict.Accept(
                rawQty = rawQtyRequested.min(walletRawBalance),
                decimals = decimalsCount,
                note = if (overshootPct > 0.0) "clamped_within_tolerance" else "exact",
            )
        }
        val multiplier = rawQtyRequested.toDouble() / walletRawBalance.toDouble()
        val reason = if (multiplier >= DECIMAL_SKEW_MULTIPLIER_THRESHOLD)
            "QTY_DECIMAL_SKEW_6401_LIKELY_10X_DECIMALS"
        else
            "SELL_QTY_EXCEEDS_WALLET_RAW_BALANCE"
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(reason)
            com.lifecyclebot.engine.ForensicLogger.lifecycle(
                reason,
                "mint=${mint.take(10)} rawReq=$rawQtyRequested rawAvail=$walletRawBalance overshootPct=${"%.2f".format(overshootPct)} mult=${"%.2f".format(multiplier)}",
            )
        } catch (_: Throwable) {}
        return Verdict.Reject(
            reason = reason,
            rawRequested = rawQtyRequested,
            rawAvailable = walletRawBalance,
            overshootPct = overshootPct,
        )
    }

    /**
     * Convenience overload — accepts the UI-scale quantity and does
     * the UI→raw conversion internally. Preferred at any callsite
     * that only holds the UI value.
     */
    fun validateSellIntentFromUi(
        mint: String,
        uiQtyRequested: Double,
        walletRawBalance: BigInteger,
        decimals: MintDecimals,
    ): Verdict {
        val raw = try { convertUiToRaw(uiQtyRequested, decimals) }
        catch (t: IllegalStateException) {
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    "SELL_INTENT_REJECTED_UNKNOWN_DECIMALS_6401")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "SELL_INTENT_REJECTED_UNKNOWN_DECIMALS_6401",
                    "mint=${mint.take(10)} uiQty=$uiQtyRequested reason=${t.message?.take(120)}",
                )
            } catch (_: Throwable) {}
            return Verdict.Reject(
                reason = "MINT_DECIMALS_UNKNOWN",
                rawRequested = BigInteger.ZERO,
                rawAvailable = walletRawBalance,
                overshootPct = Double.POSITIVE_INFINITY,
            )
        }
        return validateSellIntent(mint, raw, walletRawBalance, decimals)
    }
}

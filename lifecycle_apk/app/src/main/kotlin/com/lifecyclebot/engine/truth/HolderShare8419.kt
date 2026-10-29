package com.lifecyclebot.engine.truth

import org.json.JSONArray

/**
 * V5.0.8419 §A_TRUNCATED_HOLDER_LIST_IS_NOT_A_DENOMINATOR.
 *
 * Two holder-concentration readers never used the verified supply:
 *  - TelegramScraper.checkWalletConcentration divided the top ten of getTokenLargestAccounts by the TOTAL OF THOSE 20
 *    ROWS (pool and curve accounts included) and answered 50.0 — a fabricated "neutral" — whenever the RPC failed. That
 *    figure fed SentimentResult.walletConcentration, which the Cortex WALLET_CONCENTRATION voter reads.
 *  - DataPipeline summed a `percentage` / `pct` field over the first ten Solscan rows. Solscan's rows state amounts, not
 *    percentages, so a 20-row list with no such field read 0 % — a clean figure built from nothing — into
 *    AlphaSignals.topHolderPct / whaleRatio.
 *
 * One holder figure exists: OnChainHolderConcentration7379 (owners classified, divided by getTokenSupply, UNKNOWN when
 * the evidence is incomplete). The sentiment feed now reads that figure through [sentimentConcentration8419], and the
 * pipeline's row sum is UNKNOWN (-1.0) unless the rows actually state a percentage ([top10PctFromRows8419]).
 */
object HolderShare8419 {

    /** The checker's UNKNOWN sentinel, shared with TokenSafetyChecker.topHolderPct / AlphaSignals.topHolderPct. */
    const val UNKNOWN_8419 = -1.0

    /**
     * Pure: the figure SentimentResult.walletConcentration carries for a verified top-ten share [verifiedTop10Pct]
     * (OnChainHolderConcentration7379.top10Pct). Unknown is 0.0: the Cortex voter's `pos()` treats a non-positive value
     * as "no reading", and the summary prints nothing below 70. Never 50, never any other stand-in number.
     */
    fun sentimentConcentration8419(verifiedTop10Pct: Double?): Double =
        verifiedTop10Pct?.takeIf { it.isFinite() && it >= 0.0 }?.coerceIn(0.0, 100.0) ?: 0.0

    /**
     * Pure: the summed top-ten percentage a holder-row array states, or [UNKNOWN_8419] when no row among the first ten
     * carries a `percentage` / `pct` figure (an amount-only list is not a 0 % concentration).
     */
    fun top10PctFromRows8419(rows: JSONArray?): Double {
        if (rows == null || rows.length() == 0) return UNKNOWN_8419
        var total = 0.0
        var stated = 0
        val limit = minOf(rows.length(), 10)
        for (i in 0 until limit) {
            val h = rows.optJSONObject(i) ?: continue
            val v = when {
                h.has("percentage") -> h.optDouble("percentage", Double.NaN)
                h.has("pct") -> h.optDouble("pct", Double.NaN)
                else -> Double.NaN
            }
            if (v.isFinite() && v >= 0.0) { total += v; stated++ }
        }
        return if (stated == 0) UNKNOWN_8419 else total.coerceIn(0.0, 100.0)
    }
}

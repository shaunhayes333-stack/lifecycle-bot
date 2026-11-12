package com.lifecyclebot.network

import org.json.JSONObject

/**
 * V5.0.8730 §JUPITER_IMPACT_IS_READ_IN_ONE_UNIT — owner's second diagnostic: "Jupiter documents priceImpactPct as a decimal
 * fraction: 0.01 represents 1% ... a potential 100x understatement of market impact."
 *
 * Verified against Jupiter's own documentation:
 *  - Metis `/swap/v1/quote` `priceImpactPct`: "Price impact as a decimal string (0 to 1, not a percentage). Multiply by 100
 *    to get the percentage." (developers.jup.ag, how-to-build-a-custom-swap-with-metis)
 *  - `/order` (Ultra / Swap v2) `priceImpact`: "Price impact in percentage points (e.g. -0.1 = -0.1%)"; its `priceImpactPct`
 *    is "Deprecated: decimal price impact ratio. Use priceImpact divided by 100 instead." (api-reference/swap/order)
 * JupiterApi read `priceImpactPct` raw on all three paths and labelled it "%", and the order paths fell back to the signed
 * percentage-point `priceImpact` when the fraction was absent: one field, two units and a sign. Every consumer (SecurityGuard
 * 3 % cap, the sell-side 25 % abort, ExecutableQuoteGate, SizingQuoteRecheck8153, EntryEconomics8329, RoundTrip8418's cost)
 * reads percent. A 5 % impact arrived as 0.05.
 *
 * [pctPoints8730] is the one reader: the order API's `priceImpact` (percentage points) when present, else the fraction
 * `priceImpactPct` x 100; the magnitude (impact is a cost whichever way the sign is written); [absent] when neither is given.
 */
object JupiterImpact8730 {
    /** Pure: the quote's one-way price impact in percentage points, or [absent] when the response states none. */
    fun pctPoints8730(json: JSONObject?, absent: Double = 0.0): Double {
        if (json == null) return absent
        num(json.opt("priceImpact"))?.let { return kotlin.math.abs(it) }
        num(json.opt("priceImpactPct"))?.let { return kotlin.math.abs(it) * 100.0 }
        return absent
    }

    private fun num(v: Any?): Double? = when (v) {
        null, JSONObject.NULL -> null
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }?.takeIf { it.isFinite() }
}

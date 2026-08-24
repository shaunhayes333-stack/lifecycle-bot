package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.abs

/** V5.0.6509 — pure raw/UI conversion bound to the mint's canonical decimals. */
object PaperTokenQuantityAuthority6509 {
    data class DimensionalCheck(val ok: Boolean, val expectedQtyToken: Double, val decodedQtyToken: Double, val ratio: Double, val reason: String)

    fun resolveDecimals(mint: String, chainOrMetadataDecimals: Int?): Int? {
        MintDecimalsAuthority6392.get(mint)?.let { return it }
        val d = chainOrMetadataDecimals?.takeIf { it in 0..18 } ?: return null
        return MintDecimalsAuthority6392.resolveAndCache(mint, d)
    }

    fun encode(qtyToken: Double, decimals: Int): BigInteger {
        require(qtyToken.isFinite() && qtyToken > 0.0) { "INVALID_TOKEN_QTY" }
        require(decimals in 0..18) { "INVALID_TOKEN_DECIMALS:$decimals" }
        return BigDecimal.valueOf(qtyToken).movePointRight(decimals)
            .setScale(0, RoundingMode.HALF_UP).toBigIntegerExact()
    }

    fun decode(raw: BigInteger, decimals: Int): Double {
        require(raw.signum() >= 0) { "NEGATIVE_RAW_QTY" }
        require(decimals in 0..18) { "INVALID_TOKEN_DECIMALS:$decimals" }
        return raw.toBigDecimal().movePointLeft(decimals).toDouble()
    }

    /** Independent dimensional invariant; expected qty is not derived from raw qty. */
    fun independentCheck(costSol: Double, solUsd: Double, tokenPriceUsd: Double, raw: BigInteger, decimals: Int, tolerance: Double = 0.02): DimensionalCheck {
        if (!costSol.isFinite() || costSol <= 0.0 || !solUsd.isFinite() || solUsd <= 0.0 ||
            !tokenPriceUsd.isFinite() || tokenPriceUsd <= 0.0 || raw.signum() <= 0 || decimals !in 0..18) {
            return DimensionalCheck(false, 0.0, 0.0, Double.POSITIVE_INFINITY, "INVALID_DIMENSIONAL_INPUT")
        }
        val expected = (costSol * solUsd) / tokenPriceUsd
        val decoded = decode(raw, decimals)
        val ratio = abs(decoded - expected) / expected.coerceAtLeast(1e-18)
        return DimensionalCheck(ratio <= tolerance, expected, decoded, ratio,
            if (ratio <= tolerance) "OK" else "RAW_QTY_DIMENSIONAL_MISMATCH_6509")
    }

    fun resolveJournalSoldQty(suppliedSoldQtyToken: Double, suppliedEntryQtyToken: Double, terminal: Boolean, explicitLegacyInferenceQty: Double = 0.0): Double = when {
        suppliedSoldQtyToken.isFinite() && suppliedSoldQtyToken > 0.0 -> suppliedSoldQtyToken
        terminal && suppliedEntryQtyToken.isFinite() && suppliedEntryQtyToken > 0.0 -> suppliedEntryQtyToken
        suppliedSoldQtyToken <= 0.0 && suppliedEntryQtyToken <= 0.0 && explicitLegacyInferenceQty.isFinite() && explicitLegacyInferenceQty > 0.0 -> explicitLegacyInferenceQty
        else -> 0.0
    }

    fun journalSoldRaw(soldQtyToken: Double, decimals: Int): BigInteger = encode(soldQtyToken, decimals)
}

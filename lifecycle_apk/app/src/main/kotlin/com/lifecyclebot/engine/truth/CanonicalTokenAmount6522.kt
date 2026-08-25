package com.lifecyclebot.engine.truth

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** V5.0.6522 — token quantity is raw integer + decimals. UI is presentation only. */
data class CanonicalTokenAmount(
    val raw: BigInteger,
    val decimals: Int,
) {
    init {
        require(raw.signum() >= 0) { "NEGATIVE_CANONICAL_RAW" }
        require(decimals in 0..18) { "INVALID_CANONICAL_DECIMALS:$decimals" }
    }

    fun ui(): BigDecimal = raw.toBigDecimal().movePointLeft(decimals)
    fun uiDoubleForDisplay(): Double = ui().toDouble()
    @Deprecated("Presentation compatibility only; canonical/accounting code must use raw")
    val first: Double get() = uiDoubleForDisplay()
    @Deprecated("Use decimals explicitly")
    val second: Int get() = decimals

    fun rawRoundTrip(): BigInteger = ui().movePointRight(decimals)
        .setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact()

    fun requireValidForSell(remaining: CanonicalTokenAmount) {
        require(raw.signum() > 0) { "SELL_RAW_NOT_POSITIVE" }
        require(decimals == remaining.decimals) { "QTY_DECIMAL_SKEW" }
        require(raw <= remaining.raw) { "SELL_RAW_EXCEEDS_REMAINING" }
        require(rawRoundTrip() == raw) { "QTY_RAW_UI_ROUND_TRIP_FAILED" }
    }

    companion object {
        fun fromRpcAmount(amount: String, decimals: Int): CanonicalTokenAmount {
            require(amount.isNotBlank()) { "RPC_RAW_AMOUNT_BLANK" }
            val raw = BigDecimal(amount.trim()).setScale(0, RoundingMode.UNNECESSARY).toBigIntegerExact()
            return CanonicalTokenAmount(raw, decimals)
        }
    }
}

@JvmInline value class PriceUsd(val value: BigDecimal)
@JvmInline value class PriceSol(val value: BigDecimal)
@JvmInline value class SolUsd(val value: BigDecimal)

object CanonicalPriceDomains6522 {
    fun priceSol(priceUsd: PriceUsd, solUsd: SolUsd): PriceSol {
        require(solUsd.value.signum() > 0) { "SOL_USD_NOT_POSITIVE" }
        return PriceSol(priceUsd.value.divide(solUsd.value, 24, RoundingMode.HALF_UP))
    }

    fun valueSol(amount: CanonicalTokenAmount, priceSol: PriceSol): BigDecimal =
        amount.ui().multiply(priceSol.value)
}

data class CanonicalSellValidation6522(val allowed: Boolean, val reason: String, val position: CanonicalPositionAuthority6441.Position? = null)

object CanonicalSellQuantityGuard6522 {
    fun validate(
        mode: String,
        positionId: String,
        generation: Long,
        mint: String,
        sellRaw: BigInteger,
        sellDecimals: Int,
        callerRemainingRaw: BigInteger,
        terminal: Boolean,
    ): CanonicalSellValidation6522 {
        val p = CanonicalPositionAuthority6441.getPosition(positionId)
            ?: return blocked("UNKNOWN_POSITION", positionId, mint)
        if (!p.mode.equals(mode, true) || p.mint != mint || p.openedAtMs != generation) return blocked("POSITION_GENERATION_MISMATCH", positionId, mint)
        if (p.lifecycle !in setOf(CanonicalPositionAuthority6441.Lifecycle.OPEN, CanonicalPositionAuthority6441.Lifecycle.PARTIALLY_CLOSED)) return blocked("LIFECYCLE_${p.lifecycle}", positionId, mint)
        if (sellRaw.signum() <= 0) return blocked("SELL_RAW_NOT_POSITIVE", positionId, mint)
        if (sellDecimals != p.quantityScale) return blocked("QTY_DECIMAL_SKEW", positionId, mint)
        if (callerRemainingRaw != p.remainingQtyRaw) return blocked("STALE_REMAINING_RAW", positionId, mint)
        if (sellRaw > p.remainingQtyRaw) return blocked("SELL_RAW_EXCEEDS_REMAINING", positionId, mint)
        if (terminal && sellRaw != p.remainingQtyRaw) return blocked("FULL_CLOSE_NOT_EXACT_REMAINDER", positionId, mint)
        val amount = try { CanonicalTokenAmount(sellRaw, sellDecimals) } catch (_: Throwable) { return blocked("QTY_DECIMAL_SKEW", positionId, mint) }
        if (amount.rawRoundTrip() != sellRaw) return blocked("QTY_RAW_UI_ROUND_TRIP_FAILED", positionId, mint)
        return CanonicalSellValidation6522(true, "OK", p)
    }

    private fun blocked(reason: String, positionId: String, mint: String): CanonicalSellValidation6522 {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CANONICAL_SELL_BLOCKED_$reason" )
            com.lifecyclebot.engine.ForensicLogger.lifecycle("CANONICAL_SELL_REPAIR_REQUIRED_6522", "reason=$reason positionId=$positionId mint=${mint.take(10)} action=retain_open_no_accounting")
        } catch (_: Throwable) {}
        return CanonicalSellValidation6522(false, reason)
    }
}

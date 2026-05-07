package com.lifecyclebot.network

import java.math.BigInteger

/**
 * V5.9.495z39 — operator spec item 2.
 *
 * "Stop mixing percentage and amount on PumpPortal calls. The current code
 *  passes percentage and raw amount in different paths, making it impossible
 *  to audit what was actually sold."
 *
 * PumpPortal's local-trading API accepts EITHER:
 *   • amount as a percentage string (`"50%"`)  — relative to current wallet balance
 *   • amount as a raw token quantity         — absolute, decimals applied
 *
 * This sealed class encodes the boundary as **disjoint** types so the call
 * site MUST pick exactly one mode. There is no "mix" path. The serialiser
 * (`toApiAmountField`) emits exactly one string in the format PumpPortal
 * expects.
 *
 * Adapters MUST construct one of these and pass it to the PumpPortal call;
 * they may NOT pass a raw `Double` or a free-form `String`.
 */
sealed class PumpPortalSellRequest {

    abstract val mint: String
    abstract val slippagePercent: Int        // 0..100, integer percent (PumpPortal expects integer).
    abstract val priorityFeeSol: Double

    /** Percentage of current wallet token balance, e.g. 25 → "25%". */
    data class Percent(
        override val mint: String,
        val percentage: Int,                 // 1..100
        override val slippagePercent: Int,
        override val priorityFeeSol: Double = 0.0001,
    ) : PumpPortalSellRequest() {
        init {
            require(mint.isNotBlank())                  { "mint blank" }
            require(percentage in 1..100)               { "percentage out of range: $percentage" }
            require(slippagePercent in 0..100)          { "slippagePercent out of range: $slippagePercent" }
            require(priorityFeeSol >= 0.0)              { "priorityFeeSol negative" }
        }
    }

    /** Absolute raw token amount (atomic units, decimals applied off-chain). */
    data class RawAmount(
        override val mint: String,
        val amountRaw: BigInteger,
        val decimals: Int,
        override val slippagePercent: Int,
        override val priorityFeeSol: Double = 0.0001,
    ) : PumpPortalSellRequest() {
        init {
            require(mint.isNotBlank())                  { "mint blank" }
            require(amountRaw.signum() > 0)             { "amountRaw must be > 0" }
            require(decimals in 0..18)                  { "decimals out of range: $decimals" }
            require(slippagePercent in 0..100)          { "slippagePercent out of range: $slippagePercent" }
            require(priorityFeeSol >= 0.0)              { "priorityFeeSol negative" }
        }

        /** UI-decimal token amount derived from amountRaw — informational only. */
        fun amountUi(): Double = amountRaw.toBigDecimal().movePointLeft(decimals).toDouble()
    }

    /** Render the `amount` field for the PumpPortal trade-local payload. */
    fun toApiAmountField(): String = when (this) {
        is Percent   -> "$percentage%"
        is RawAmount -> amountUi().toString()
    }
}

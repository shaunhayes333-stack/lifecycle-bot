package com.lifecyclebot.perps.crypto

/**
 * V5.9.495z30 — CEX adapter stub.
 *
 * Same honesty rule as the bridge stub: until real exchange API keys
 * + permissions are wired, `isConfigured()` is false, the resolver
 * marks native-only assets as CEX_REQUIRED, and the trader logs a
 * precise diagnostic instead of fake-failing.
 */
object CryptoCexAdapter {
    fun isConfigured(): Boolean = false

    fun placeOrder(
        targetSymbol: String,
        sizeSol: Double,
    ): CexOrderResult = CexOrderResult.NotConfigured
}

sealed class CexOrderResult {
    object NotConfigured : CexOrderResult()
    data class Placed(val orderId: String, val provider: String, val priceUsd: Double) : CexOrderResult()
    data class Rejected(val code: String, val reason: String) : CexOrderResult()
}

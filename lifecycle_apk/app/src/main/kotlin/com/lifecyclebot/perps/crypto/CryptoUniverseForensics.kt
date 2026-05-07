package com.lifecyclebot.perps.crypto

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.LiveTradeLogStore

/**
 * V5.9.495z30 — Crypto Universe forensics.
 *
 * Single funnel for emitting precise diagnostic events. Distinguishes
 * route-discovery outcomes (which MUST NOT count as a buy failure)
 * from real execution failures (which keep the legacy BUY_FAILED
 * phase).
 */
object CryptoUniverseForensics {

    private const val TAG = "CryptoUniverse"

    /** Route-discovery outcome — informational only. NOT a buy failure. */
    fun logRouteOutcome(
        symbol: String,
        mintOrPlaceholder: String,
        diagCode: String,
        humanMessage: String,
        sizeSol: Double,
    ) {
        val key = "CU_${symbol}_${System.currentTimeMillis()}"
        ErrorLogger.info(TAG, "[$diagCode] $symbol — $humanMessage (size=${"%.4f".format(sizeSol)} SOL)")
        try {
            LiveTradeLogStore.log(
                tradeKey = key,
                mint = mintOrPlaceholder,
                symbol = symbol,
                side = "BUY",
                phase = LiveTradeLogStore.Phase.WARNING,
                message = "$diagCode | $humanMessage",
                solAmount = sizeSol,
                traderTag = "CRYPTO_UNIVERSE",
            )
        } catch (_: Throwable) { /* forensics is best-effort */ }
    }

    /** Real execution failure (tx built/order placed → failed). Counts
     *  as a true buy failure. */
    fun logExecutionFailure(
        symbol: String,
        mint: String,
        diagCode: String,
        humanMessage: String,
        sizeSol: Double,
    ) {
        val key = "CU_${symbol}_EXEC_${System.currentTimeMillis()}"
        ErrorLogger.warn(TAG, "[$diagCode] $symbol — $humanMessage (size=${"%.4f".format(sizeSol)} SOL)")
        try {
            LiveTradeLogStore.log(
                tradeKey = key,
                mint = mint,
                symbol = symbol,
                side = "BUY",
                phase = LiveTradeLogStore.Phase.BUY_FAILED,
                message = "$diagCode | $humanMessage",
                solAmount = sizeSol,
                traderTag = "CRYPTO_UNIVERSE",
            )
        } catch (_: Throwable) { /* forensics is best-effort */ }
    }
}

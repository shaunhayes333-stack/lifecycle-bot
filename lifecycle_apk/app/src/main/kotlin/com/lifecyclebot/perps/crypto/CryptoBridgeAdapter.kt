package com.lifecyclebot.perps.crypto

/**
 * V5.9.495z30 — Cross-chain bridge adapter stub.
 *
 * Operator brief explicitly says:
 *   "If the app currently only has a Solana wallet and no CEX/bridge
 *    executor, then full-universe crypto live trading cannot execute
 *    native BTC/XMR/TON directly yet."
 *
 * So this stub deliberately reports `isConfigured() = false` until a
 * real bridge adapter (deBridge, Wormhole, Mayan, Allbridge, …) is
 * wired in. The resolver will then classify these assets as
 * BRIDGE_REQUIRED and the trader logs a precise diagnostic — it does
 * NOT fake-execute or fake-fail.
 */
object CryptoBridgeAdapter {
    fun isConfigured(): Boolean = false

    /** Stub. Real implementation will return executable bridge route
     *  details (provider, dest chain, fee, ETA, min amount). */
    fun quoteBridge(
        targetSymbol: String,
        sizeSol: Double,
    ): BridgeQuoteResult = BridgeQuoteResult.NotConfigured
}

sealed class BridgeQuoteResult {
    object NotConfigured : BridgeQuoteResult()
    data class Quoted(
        val provider: String,
        val destChain: String,
        val feeSol: Double,
        val etaSec: Int,
        val minSol: Double,
    ) : BridgeQuoteResult()
    data class Rejected(val code: String, val reason: String) : BridgeQuoteResult()
}

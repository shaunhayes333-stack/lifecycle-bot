package com.lifecyclebot.perps.crypto

/**
 * V5.9.495z30 — CryptoUniverseTrader: route classification.
 *
 * Each crypto asset signal is resolved to exactly one of these routes.
 * The trader must NOT use the generic
 *   "no tx (mint missing, bridge fail, or insufficient SOL)"
 * failure anymore. Replace with a precise diagnostic so the operator
 * can see whether the issue is "no executor wired" vs "tx actually
 * failed".
 *
 * Spec items A–J (operator brief):
 *   A. Solana meme tokens go to MemeTrader.
 *   B. SPL/wrapped crypto with valid Jupiter route executes here.
 *   C. BTC w/o wrapped & no CEX  -> CEX_REQUIRED (NOT BUY_FAILED).
 *   D. XMR w/o route             -> CEX_REQUIRED / NO_ROUTE_AVAILABLE.
 *   E. PAXG w/ price but no exec -> WRAPPED_ROUTE_MISSING.
 *   F. AXS / RENDER / TNSR       -> precise route classification.
 *   G. Route-discovery failures do NOT consume live failure count.
 *   H. Markets/Yahoo/Commodities scans don't block this lane.
 *   I. SOL→USDC→target works when an executable route exists.
 *   J. Wallet lifecycle keys by mint/order-id, not ticker.
 */
enum class CryptoExecutionRoute {
    SOLANA_SPL_DIRECT,           // SOL/USDC -> Solana SPL representation
    JUPITER_ROUTABLE,            // Jupiter can route the token directly
    PUMPFUN_MEME,                // belongs to MemeTrader, NOT CryptoUniverseTrader
    RAYDIUM_ROUTABLE,
    METEORA_ROUTABLE,
    BRIDGED_WRAPPED_ASSET,       // wrapped BTC, wrapped ETH, tokenized gold, etc.
    CEX_REQUIRED,                // native BTC, native XMR, native TON, etc.
    BRIDGE_REQUIRED,             // cross-chain bridge route required
    PERP_ONLY,
    PAPER_ONLY,
    NO_ROUTE_AVAILABLE,
    ROUTE_DISABLED,
    INSUFFICIENT_SOL,
}

/** Precise diagnostic codes. These replace BUY_FAILED for route-discovery
 *  outcomes. Only true broadcast/order-execution failures should keep the
 *  legacy BUY_FAILED phase. */
object CryptoUniverseDiagCodes {
    const val ROUTE_SOLANA_SPL          = "CRYPTO_ROUTE_SOLANA_SPL"
    const val ROUTE_JUPITER             = "CRYPTO_ROUTE_JUPITER"
    const val ROUTE_BRIDGE_REQUIRED     = "CRYPTO_ROUTE_BRIDGE_REQUIRED"
    const val ROUTE_CEX_REQUIRED        = "CRYPTO_ROUTE_CEX_REQUIRED"
    const val ROUTE_NO_WRAPPED_ASSET    = "CRYPTO_ROUTE_NO_WRAPPED_ASSET"
    const val ROUTE_NO_EXECUTOR         = "CRYPTO_ROUTE_NO_EXECUTOR"
    const val ROUTE_INSUFFICIENT_SOL    = "CRYPTO_ROUTE_INSUFFICIENT_SOL"
    const val TX_BUILD_FAILED           = "CRYPTO_TX_BUILD_FAILED"
    const val BRIDGE_QUOTE_FAILED       = "CRYPTO_BRIDGE_QUOTE_FAILED"
    const val CEX_ORDER_FAILED          = "CRYPTO_CEX_ORDER_FAILED"
    const val ROUTE_DISCOVERY_FAILED    = "CRYPTO_ROUTE_DISCOVERY_FAILED"
    const val ROUTE_MEME_REJECT         = "CRYPTO_ROUTE_MEME_REJECT"
    const val ROUTE_PAPER_ONLY          = "CRYPTO_ROUTE_PAPER_ONLY"
}

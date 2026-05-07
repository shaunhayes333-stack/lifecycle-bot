package com.lifecyclebot.perps.crypto

import com.lifecyclebot.engine.ErrorLogger

/**
 * V5.9.495z30 — Maps crypto symbols to their wrapped Solana SPL
 * representation when one exists. Uses the static
 * `CryptoAssetRegistry.possibleSolanaMints` first, then falls back to
 * the runtime `DynamicAltTokenRegistry`.
 *
 * If no wrapped representation exists, returns null — callers must
 * NOT fabricate a fake mint or send the asset down the meme executor.
 */
object CryptoWrappedAssetMapper {

    private const val TAG = "CryptoWrappedAssetMapper"

    fun resolveWrappedMint(symbol: String): String? {
        val sym = symbol.uppercase()
        // 1) Curated registry first (high-trust mappings only).
        val profile = CryptoAssetRegistry.get(sym)
        profile?.possibleSolanaMints?.firstOrNull()?.let { return it }

        // 2) DynamicAltTokenRegistry (runtime-discovered mints). Only
        //    accept entries that look like real mint addresses (32–44
        //    base58 chars, NOT placeholder "cg:*" / "static:*").
        try {
            val dyn = com.lifecyclebot.perps.DynamicAltTokenRegistry
                .getTokenBySymbol(sym)
                ?.mint
                ?.takeIf {
                    it.isNotBlank() &&
                    !it.startsWith("cg:") &&
                    !it.startsWith("static:") &&
                    it.length in 32..44
                }
            if (dyn != null) return dyn
        } catch (e: Throwable) {
            ErrorLogger.debug(TAG, "DynamicAltTokenRegistry lookup failed for $sym: ${e.message}")
        }
        return null
    }

    /** Returns true iff the symbol is known to have NO Solana
     *  representation at all (BTC native, XMR native, etc.). */
    fun isNativeOnly(symbol: String): Boolean =
        CryptoAssetRegistry.isKnownNativeOnly(symbol)
}

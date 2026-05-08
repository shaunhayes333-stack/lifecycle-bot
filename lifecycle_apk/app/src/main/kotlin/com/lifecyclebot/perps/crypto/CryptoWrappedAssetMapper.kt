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
            val tok = com.lifecyclebot.perps.DynamicAltTokenRegistry.getTokenBySymbol(sym)
            val dyn = tok?.mint?.takeIf {
                it.isNotBlank() &&
                !it.startsWith("cg:") &&
                !it.startsWith("static:") &&
                com.lifecyclebot.engine.execution.MintIntegrityGate.isLikelyMint(it)
            }?.takeIf {
                // V5.9.607 — most KNOWN_SOLANA_MINTS seed as static_enum and
                // must be allowed (FIDA/KMNO/TRUMP/TNSR/LDO/WBTC). But a few
                // legacy symbol aliases (AAVE/SHIB in the forensic export) have
                // no trustworthy Solana representation and must stay paper-only
                // unless Jupiter/Dex later discovers a real dynamic mint.
                val src = tok.source.lowercase()
                val trustedDynamic = src.contains("jupiter") || src.contains("dex") ||
                    src.contains("birdeye") || src.contains("restored_dyn")
                val staticAliasDenied = tok.isStatic && sym in setOf("AAVE", "SHIB") && !trustedDynamic
                !staticAliasDenied
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

package com.lifecyclebot.engine.truth

/**
 * V5.0.6525 §ASSET_CLASS_AXIS — one axis every canonical lifecycle,
 * telemetry, and mark-refresh path can dispatch on.
 *
 * Operator source-level audit (Feb 2026):
 *   > "Repair canonical cross-asset transactions. CanonicalPaperTransaction
 *   >  .open() needs assetClass, actual entryPrice, canonical quantity
 *   >  semantics and price-source metadata. Do not synthesize 1e9 @ 9
 *   >  decimals for stocks/FX."
 *   > "Route exit marks by asset class. tryFallbackPriceData(\"GBPJPY\")
 *   >  must become impossible."
 *
 * Written to Position + Trade so the exit mark router and telemetry never
 * have to reverse-infer the class from a mint or symbol string.
 */
enum class AssetClass {
    SOLANA_TOKEN,
    STOCK,
    FOREX,
    COMMODITY,
    METAL,
    CRYPTO_ALT,  // Non-SOL cryptos routed through CryptoAltTrader (BTC/ETH/etc)
    PERPS,
    UNKNOWN;

    val tag: String get() = name
    /** True when this class is priced by a Solana on-chain oracle (Birdeye / DexScreener / pump.fun). */
    val isSolanaOnChain: Boolean get() = this == SOLANA_TOKEN
    /** True when this class needs a per-asset off-chain price provider (equities / FX / commodities / metals). */
    val isOffChainMarket: Boolean get() = this == STOCK || this == FOREX || this == COMMODITY || this == METAL

    companion object {
        fun fromLane(lane: String?): AssetClass = when (lane?.uppercase()?.trim()) {
            "STOCK", "STOCKS", "MARKETS_STOCKS" -> STOCK
            "FOREX", "FX" -> FOREX
            "COMMODITY", "COMMODITIES" -> COMMODITY
            "METAL", "METALS" -> METAL
            "CRYPTO_ALT", "CRYPTOALT", "ALTCRYPTO" -> CRYPTO_ALT
            "PERPS", "PERP" -> PERPS
            // V5.0.6592 §ASSET_CLASS_IMMUTABILITY — operator directive:
            // "There must be NO fallback/default of unknown/null/non-Solana
            //  -> SOLANA_TOKEN." Silent coercion of unknown lanes into
            // SOLANA_TOKEN is exactly how STOCK_* / FOREX_* / CRYPTO_ALT
            // positions ended up routed through the Solana on-chain mark
            // path (Birdeye / DexScreener / pump.fun). The correct default
            // for a lane we cannot classify is UNKNOWN — the mark router
            // already treats UNKNOWN as non-productive and refuses to
            // dispatch, so wiring gaps surface as ASSET_CLASS_UNKNOWN_ON_*
            // telemetry instead of contaminating the Solana pipeline.
            null, "" -> UNKNOWN
            // Recognised Solana meme/shitcoin/bluechip lanes stay SOLANA_TOKEN.
            "SHITCOIN", "MEME", "MOONSHOT", "EXPRESS", "BLUECHIP", "MANIP",
            "MANIPULATED", "PROJECT_SNIPER", "QUALITY", "TREASURY", "STANDARD",
            "V3_CORE", "CASHGEN", "DIP_HUNTER", "COPY_TRADE", "COMMUNITY",
            "CYCLIC", "LAB", "RECOVERED_CARRY_6492" -> SOLANA_TOKEN
            else -> UNKNOWN
        }

        /**
         * V5.0.6592 §ASSET_CLASS_POSITIONID_CONTRACT — infer class from a
         * canonical positionId prefix. Used ONLY as a repair/invariant
         * signal, never as the primary source of truth. When a stored
         * position's `assetClass` disagrees with `fromPositionIdPrefix`,
         * the invariant `ASSET_CLASS_POSITIONID_MISMATCH_6592` fires and
         * the mark router routes by the inferred class so a stock
         * positionId cannot silently become a Birdeye lookup.
         */
        fun fromPositionIdPrefix(positionId: String?): AssetClass {
            val p = positionId?.trim()?.uppercase() ?: return UNKNOWN
            return when {
                p.startsWith("STOCK_") || p.startsWith("STOCK:") -> STOCK
                p.startsWith("FOREX_") || p.startsWith("FX_") || p.startsWith("FOREX:") -> FOREX
                p.startsWith("METAL_") || p.startsWith("METAL:") -> METAL
                p.startsWith("COMMODITY_") || p.startsWith("COMMODITY:") -> COMMODITY
                p.startsWith("ALT_") || p.startsWith("CRYPTOALT_") || p.startsWith("CRYPTO_ALT_") -> CRYPTO_ALT
                p.startsWith("PERPS_") || p.startsWith("PERP_") || p.startsWith("PERPS:") -> PERPS
                else -> UNKNOWN
            }
        }
    }
}

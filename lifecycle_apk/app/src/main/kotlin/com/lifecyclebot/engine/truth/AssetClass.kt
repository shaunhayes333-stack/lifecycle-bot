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
            null, "" -> UNKNOWN
            else -> SOLANA_TOKEN  // Every other lane (SHITCOIN/MOONSHOT/EXPRESS/MEME/etc) is a Solana token
        }
    }
}

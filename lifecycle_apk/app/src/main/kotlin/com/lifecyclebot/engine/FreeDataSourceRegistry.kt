package com.lifecyclebot.engine

/**
 * V5.0.4311 — registry of free/no-key or already-integrated market-data
 * surfaces worth using for background enrichment.  This is not a hot-path
 * fetcher and never performs network I/O itself.
 */
object FreeDataSourceRegistry {
    data class Surface(
        val id: String,
        val kind: ResearchScout.SourceKind,
        val noKeyOrExisting: Boolean,
        val hotPathAllowed: Boolean,
        val useFor: String,
        val note: String,
    )

    val surfaces: List<Surface> = listOf(
        Surface("dexscreener_free", ResearchScout.SourceKind.DEXSCREENER_FREE, true, false, "pairs, boosts, social links, liquidity/volume cross-check", "free/no signup public API; already has DexscreenerApi/DexScreenerSocialSource"),
        Surface("geckoterminal_free", ResearchScout.SourceKind.GECKOTERMINAL_FREE, true, false, "new pools, OHLCV, pair/liquidity cross-check", "GeckoTerminal public DEX API; documented free tier around 10 calls/min"),
        Surface("rugcheck_free", ResearchScout.SourceKind.RUGCHECK_FREE, true, false, "LP/risk-name verification and hard-rug overlay confirmation", "background verification only; hard safety still at TokenSafetyChecker"),
        Surface("jupiter_quote_free", ResearchScout.SourceKind.JUPITER_QUOTE_FREE, true, false, "route availability, price impact, most reliable AMM quote report", "Jupiter quote path /swap/v1/quote / Swap V2 docs; use background/cache, not scanner hot path"),
        Surface("pumpportal_ws_free", ResearchScout.SourceKind.PUMPPORTAL_WS_FREE, true, false, "new token, migration, trade stream, PumpSwap graduation", "PumpPortal WS real-time stream already fits source-balanced scanner intake"),
        Surface("coingecko_onchain_free", ResearchScout.SourceKind.COINGECKO_ONCHAIN_FREE, true, false, "established token/context and GeckoTerminal onchain bridge", "CoinGecko/GeckoTerminal public onchain data context"),
        Surface("social_free", ResearchScout.SourceKind.SOCIAL_FREE, true, false, "X/Telegram/social velocity cache", "use cached scrapers only; no synchronous scrape in FDG/executor"),
        // V5.0.6914 — three keyless price surfaces added to the meme sell-side
        // fallback chain (PriceResolverFallback). Operator 5.0.6911 had
        // dexscreener at sr=0% (62 5xx), dexpaprika at 402 and birdeye at 401
        // simultaneously, leaving GeckoTerminal at 66% carrying the fleet.
        // hotPathAllowed=true for these three: unlike the research surfaces
        // above they ARE consulted on the exit price path, which is the point —
        // a stop-loss that cannot read a price fails silently.
        Surface("jupiter_price_free", ResearchScout.SourceKind.JUPITER_PRICE_FREE, true, true, "direct USD price, batch-capable, no route or decimals needed", "lite-api.jup.ag/price/v3; jupiter was the healthiest keyless host in the fleet at sr=100%"),
        Surface("raydium_v3_free", ResearchScout.SourceKind.RAYDIUM_V3_FREE, true, true, "long-tail price for graduated memes DexScreener has not indexed", "api-v3.raydium.io/mint/price; native #1 Solana DEX, already used by PriceAggregator §6065"),
        Surface("pumpfun_frontend_free", ResearchScout.SourceKind.PUMPFUN_FRONTEND_FREE, true, true, "pre-graduation bonding-curve price where no DEX pair exists yet", "frontend-api-v3.pump.fun/coins/<mint>; USD derived as usd_market_cap/total_supply because its own price field is in SOL"),
    )

    fun defaultSources(): Set<ResearchScout.SourceKind> = surfaces.filter { it.noKeyOrExisting }.map { it.kind }.toSet()

    fun status(): String = "FREE_DATA_SOURCE_REGISTRY_4311 surfaces=${surfaces.size} defaultSources=${defaultSources().joinToString(",") { it.name }} background_only=true no_hot_path_fetch=true"
}

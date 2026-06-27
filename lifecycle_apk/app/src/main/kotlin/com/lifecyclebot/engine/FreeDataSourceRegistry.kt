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
    )

    fun defaultSources(): Set<ResearchScout.SourceKind> = surfaces.filter { it.noKeyOrExisting }.map { it.kind }.toSet()

    fun status(): String = "FREE_DATA_SOURCE_REGISTRY_4311 surfaces=${surfaces.size} defaultSources=${defaultSources().joinToString(",") { it.name }} background_only=true no_hot_path_fetch=true"
}

package com.lifecyclebot.engine

/**
 * V5.0.6140 — VenueUniverse.
 *
 * AATE is not a Pump/Jupiter bot. This classifier gives Meme Trader and Crypto
 * Universe a shared venue/exchange/source vocabulary so scanners, route truth,
 * social trend intake and chain-specific execution can align without hardcoding
 * every decision to pump.fun or Jupiter.
 */
object VenueUniverse {
    enum class ChainFamily { SOLANA, BNB, ETHEREUM, BASE, CEX, CHINA_REGIONAL, MULTICHAIN, UNKNOWN }
    enum class VenueFamily { LAUNCHPAD, AMM_DEX, AGGREGATOR, CEX, SOCIAL_TREND, RPC, DATA_INDEXER, UNKNOWN }
    enum class RouteFamily { PUMP_NATIVE, SOL_AGGREGATOR, SOL_AMM_DIRECT, CHAIN_SPECIFIC_DEX, CEX_SIGNAL_ONLY, TREND_SIGNAL_ONLY, DATA_ONLY, UNKNOWN }

    data class Venue(
        val chain: ChainFamily,
        val venue: VenueFamily,
        val route: RouteFamily,
        val canonical: String,
        val aliases: List<String>,
    )

    private val known = listOf(
        Venue(ChainFamily.SOLANA, VenueFamily.LAUNCHPAD, RouteFamily.PUMP_NATIVE, "PUMP_FUN", listOf("PUMP", "PUMPFUN", "PUMP_FUN", "PUMPPORTAL", "PUMPSWAP")),
        Venue(ChainFamily.SOLANA, VenueFamily.AGGREGATOR, RouteFamily.SOL_AGGREGATOR, "JUPITER", listOf("JUPITER", "JUP", "ULTRA", "METIS")),
        Venue(ChainFamily.SOLANA, VenueFamily.AMM_DEX, RouteFamily.SOL_AMM_DIRECT, "RAYDIUM", listOf("RAYDIUM", "RAYDIUM_NEW_POOL", "OPENBOOK")),
        Venue(ChainFamily.SOLANA, VenueFamily.AMM_DEX, RouteFamily.SOL_AMM_DIRECT, "ORCA", listOf("ORCA", "WHIRLPOOL")),
        Venue(ChainFamily.SOLANA, VenueFamily.AMM_DEX, RouteFamily.SOL_AMM_DIRECT, "METEORA", listOf("METEORA", "DLMM")),
        Venue(ChainFamily.SOLANA, VenueFamily.LAUNCHPAD, RouteFamily.CHAIN_SPECIFIC_DEX, "SOL_LAUNCHPAD", listOf("LAUNCHPAD", "MOONSHOT", "BELIEVE", "BONK", "RUGCHECK", "DEXLAB")),
        Venue(ChainFamily.BNB, VenueFamily.AMM_DEX, RouteFamily.CHAIN_SPECIFIC_DEX, "PANCAKESWAP", listOf("PANCAKE", "PANCAKESWAP", "BNB", "BSC")),
        Venue(ChainFamily.BASE, VenueFamily.AMM_DEX, RouteFamily.CHAIN_SPECIFIC_DEX, "BASE_DEX", listOf("BASE", "AERODROME", "UNISWAP_BASE")),
        Venue(ChainFamily.ETHEREUM, VenueFamily.AMM_DEX, RouteFamily.CHAIN_SPECIFIC_DEX, "ETH_DEX", listOf("UNISWAP", "SUSHI", "ETHEREUM", "ETH")),
        Venue(ChainFamily.CEX, VenueFamily.CEX, RouteFamily.CEX_SIGNAL_ONLY, "COINSPOT", listOf("COINSPOT")),
        Venue(ChainFamily.CEX, VenueFamily.CEX, RouteFamily.CEX_SIGNAL_ONLY, "BINANCE", listOf("BINANCE")),
        Venue(ChainFamily.CEX, VenueFamily.CEX, RouteFamily.CEX_SIGNAL_ONLY, "OKX", listOf("OKX", "OKEX")),
        Venue(ChainFamily.CEX, VenueFamily.CEX, RouteFamily.CEX_SIGNAL_ONLY, "BYBIT", listOf("BYBIT")),
        Venue(ChainFamily.CEX, VenueFamily.CEX, RouteFamily.CEX_SIGNAL_ONLY, "MEXC", listOf("MEXC")),
        Venue(ChainFamily.CEX, VenueFamily.CEX, RouteFamily.CEX_SIGNAL_ONLY, "GATE", listOf("GATE", "GATEIO", "GATE_IO")),
        Venue(ChainFamily.CHINA_REGIONAL, VenueFamily.CEX, RouteFamily.CEX_SIGNAL_ONLY, "CHINESE_EXCHANGE", listOf("HUOBI", "HTX", "BITGET", "LBANK", "CHINA", "CHINESE")),
        Venue(ChainFamily.CHINA_REGIONAL, VenueFamily.SOCIAL_TREND, RouteFamily.TREND_SIGNAL_ONLY, "CHINESE_SOCIAL", listOf("WEIBO", "WECHAT", "DOUYIN", "BILIBILI", "XIAOHONGSHU")),
        Venue(ChainFamily.MULTICHAIN, VenueFamily.DATA_INDEXER, RouteFamily.DATA_ONLY, "DEXSCREENER", listOf("DEXSCREENER", "DEX_SCREENER")),
        Venue(ChainFamily.MULTICHAIN, VenueFamily.DATA_INDEXER, RouteFamily.DATA_ONLY, "BIRDEYE", listOf("BIRDEYE")),
        Venue(ChainFamily.MULTICHAIN, VenueFamily.DATA_INDEXER, RouteFamily.DATA_ONLY, "GECKOTERMINAL", listOf("GECKOTERMINAL", "GECKO_TERMINAL")),
        Venue(ChainFamily.MULTICHAIN, VenueFamily.DATA_INDEXER, RouteFamily.DATA_ONLY, "COINGECKO", listOf("COINGECKO", "COIN_GECKO")),
        Venue(ChainFamily.MULTICHAIN, VenueFamily.RPC, RouteFamily.DATA_ONLY, "RPC", listOf("RPC", "HELIUS", "QUICKNODE", "ALCHEMY", "TRITON", "ANKR")),
    )

    fun classify(raw: String?): Venue {
        val src = raw.orEmpty().uppercase()
        if (src.isBlank()) return unknown("UNKNOWN")
        return known.firstOrNull { venue -> venue.aliases.any { src.contains(it) } } ?: unknown(src.take(32))
    }

    fun isSolanaExecutable(v: Venue): Boolean = v.chain == ChainFamily.SOLANA && (v.route == RouteFamily.PUMP_NATIVE || v.route == RouteFamily.SOL_AGGREGATOR || v.route == RouteFamily.SOL_AMM_DIRECT || v.route == RouteFamily.CHAIN_SPECIFIC_DEX)

    private fun unknown(name: String) = Venue(ChainFamily.UNKNOWN, VenueFamily.UNKNOWN, RouteFamily.UNKNOWN, name, emptyList())
}

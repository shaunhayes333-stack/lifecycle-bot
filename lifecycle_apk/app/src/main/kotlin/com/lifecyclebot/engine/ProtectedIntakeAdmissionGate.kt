package com.lifecyclebot.engine

/**
 * V5.9.1548b — source-neutral pre-watchlist intake pressure gate.
 *
 * HARD RULE: this bot is NOT a pump.fun-only bot. DexScreener, CoinGecko,
 * CoinMarketCap-backed dynamic discovery, Raydium/Meteora/Orca, Birdeye and
 * generic scanner feeds must remain first-class inflow.
 *
 * This is NOT protected-pool pruning. It only diverts brand-new, cold,
 * zero-volume dust from ANY external discovery source into probation before it
 * enters the hot watchlist/supervisor path, and only when the supervisor is
 * already under real pressure. User adds, restore/probation paths, higher-liq,
 * non-zero-volume, and multi-source candidates keep flowing. Probation can still
 * promote later when another source, price action, or liquidity confirms the mint.
 */
object ProtectedIntakeAdmissionGate {
    data class Decision(val probationOnly: Boolean, val reason: String)

    private fun isExternalDiscovery(tags: String): Boolean {
        return listOf(
            // pump.fun / PumpPortal
            "PUMP_PORTAL", "PUMPPORTAL", "PUMP.FUN", "PUMPFUN", "PUMP_FUN",
            // DexScreener families
            "DEXSCREENER", "DEX_SCREENER", "DEX_TREND", "DEX_TRENDING", "DEX_GAIN", "DEX_GAINERS", "DEX_BOOST", "DEX_BOOSTED",
            // CoinGecko / GeckoTerminal / CoinMarketCap-backed dynamic universe
            "COINGECKO", "COIN_GECKO", "GECKO", "GECKOTERMINAL", "GECKO_TERMINAL", "COINMARKETCAP", "COIN_MARKET_CAP", "CMC",
            // DEX/venue discovery
            "RAYDIUM", "METEORA", "ORCA", "JUPITER", "NEW_POOL", "LIQUIDITY",
            // market/scanner aggregators
            "BIRDEYE", "SCANNER_DIRECT", "SCANNER_HEAL", "DATA_ORCHESTRATOR", "TRENDING", "BOOSTED", "GAINERS",
        ).any { tags.contains(it) }
    }

    fun decide(
        source: String,
        allSources: Set<String>,
        liquidityUsd: Double,
        marketCapUsd: Double,
        volumeH1: Double,
        supervisorTimeouts10m: Int,
        supervisorActive: Int,
        supervisorLiveCap: Int,
    ): Decision {
        val tags = (source + "|" + allSources.joinToString("|")).uppercase()
        val isExternal = isExternalDiscovery(tags)
        val isUser = source.equals("USER", true) || source.contains("USER_ADDED", true)
        val isRestore = source.equals("MEME_REGISTRY_RESTORE", true) || source.equals("PROBATION", true)
        if (!isExternal || isUser || isRestore) return Decision(false, "not_external_discovery_or_exempt")

        // Multi-source candidates are exactly what we want more of — do not demote
        // them just because pressure is high. Let FDG/lanes decide.
        val multiSource = allSources.size >= 2 && !allSources.any { it.equals("SCANNER_DIRECT", true) || it.equals("SCANNER_HEAL", true) }
        if (multiSource && (liquidityUsd >= 1_500.0 || volumeH1 > 0.0)) return Decision(false, "multi_source_quality_flow")

        val coldDust = volumeH1 <= 0.0 && liquidityUsd < 1_500.0 && marketCapUsd < 250_000.0
        if (!coldDust) return Decision(false, "not_cold_dust")

        val active = supervisorActive.coerceAtLeast(0)
        val liveCap = supervisorLiveCap.coerceAtLeast(1)
        val pressure = supervisorTimeouts10m > 150 || active >= liveCap
        if (!pressure) return Decision(false, "no_supervisor_pressure")

        return Decision(
            probationOnly = true,
            reason = "cold_external_pressure liq=${liquidityUsd.toInt()} mcap=${marketCapUsd.toInt()} vol1h=${volumeH1.toInt()} active=$active liveCap=$liveCap timeouts10m=$supervisorTimeouts10m tags=${tags.take(96)}",
        )
    }
}

package com.lifecyclebot.engine

/**
 * V5.0.4309 — source-family router for CashGenerationAI/Treasury.
 *
 * Treasury was evaluating every token as if source did not matter.  For a
 * cashflow lane, discovery family is signal: PumpPortal newborns behave
 * differently from Raydium/Meteora/Orca graduated pools, DexScreener boosted
 * names, and Birdeye/CG/Jupiter established lists.  This helper is pure,
 * cache-free, network-free, and soft-only.
 */
object TreasurySourceRouter {
    data class SourceBias(
        val family: String,
        val scoreDelta: Int,
        val confidenceDelta: Int,
        val sizeMultiplier: Double,
        val reason: String,
    )

    fun bias(
        discoverySource: String,
        priceSource: String,
        priceDex: String,
        liquidityUsd: Double,
        ageMinutes: Double,
    ): SourceBias {
        val hay = listOf(discoverySource, priceSource, priceDex).joinToString("|").uppercase()
        val graduatedDex = hay.contains("RAYDIUM") || hay.contains("METEORA") || hay.contains("ORCA") || hay.contains("PUMPSWAP")
        val pumpNewborn = hay.contains("PUMP_FUN") || hay.contains("PUMPPORTAL") || hay.contains("PUMP_PORTAL") || hay.contains("BONDING")
        val dexscreener = hay.contains("DEXSCREENER") || hay.contains("DEX_SCREENER")
        val boosted = hay.contains("BOOST") || hay.contains("TREND") || hay.contains("GAINER")
        val established = hay.contains("BIRDEYE") || hay.contains("COINGECKO") || hay.contains("JUPITER") || hay.contains("STRICT_TOKEN")
        return when {
            graduatedDex && liquidityUsd >= 25_000.0 -> SourceBias("GRADUATED_DEX_DEPTH", 6, 5, 1.10, "graduated_dex_depth")
            graduatedDex -> SourceBias("GRADUATED_DEX_THIN", 2, 2, 1.00, "graduated_dex_but_thin")
            dexscreener && boosted && liquidityUsd >= 20_000.0 -> SourceBias("DEXSCREENER_BOOSTED_DEPTH", 5, 4, 1.08, "boosted_depth_social_attention")
            dexscreener -> SourceBias("DEXSCREENER_LISTED", 2, 2, 1.00, "listed_market_visibility")
            established && liquidityUsd >= 20_000.0 -> SourceBias("ESTABLISHED_LIST_DEPTH", 4, 4, 1.06, "established_source_depth")
            pumpNewborn && ageMinutes <= 8.0 -> SourceBias("PUMP_NEWBORN", -5, -4, 0.82, "newborn_cashflow_risk_soft_shape")
            pumpNewborn -> SourceBias("PUMP_MID_AGE", -2, -2, 0.92, "pump_source_cashflow_caution")
            hay.isBlank() || hay == "||" -> SourceBias("UNKNOWN", -2, -2, 0.95, "unknown_source_cashflow_caution")
            else -> SourceBias("MIXED_OTHER", 0, 0, 1.00, "neutral_source_family")
        }
    }

    fun status(): String = "TREASURY_SOURCE_ROUTER_4309 soft_only=true no_hard_reject=true no_network=true families=GRADUATED_DEX_DEPTH,DEXSCREENER_BOOSTED_DEPTH,ESTABLISHED_LIST_DEPTH,PUMP_NEWBORN,UNKNOWN"
}

package com.lifecyclebot.engine

/** V5.0.6492 — economic metadata boundary before stop/learning state. */
object MarketDataIntegrity6492 {
    private val majorTrillionCapSymbols = setOf("BTC", "ETH")

    fun trustedMarketCapUsd(raw: Double, symbol: String, source: String, liquidityUsd: Double): Double? {
        if (!raw.isFinite() || raw <= 0.0) return null
        val sym = symbol.trim().uppercase().removePrefix("$")
        val implausibleAbsolute = raw > 100_000_000_000.0 && sym !in majorTrillionCapSymbols
        val implausibleVsLiquidity = liquidityUsd > 0.0 && raw > 20_000_000_000.0 && raw / liquidityUsd > 1_000_000.0
        if (implausibleAbsolute || implausibleVsLiquidity) {
            try {
                PipelineHealthCollector.labelInc("MARKET_CAP_REJECTED_IMPLAUSIBLE_6492")
                ForensicLogger.lifecycle(
                    "MARKET_CAP_REJECTED_IMPLAUSIBLE_6492",
                    "symbol=${symbol.take(16)} raw=$raw liq=$liquidityUsd source=${source.take(32)} action=preserve_last_good_never_feed_stop_or_learning",
                )
            } catch (_: Throwable) {}
            return null
        }
        return raw
    }
}

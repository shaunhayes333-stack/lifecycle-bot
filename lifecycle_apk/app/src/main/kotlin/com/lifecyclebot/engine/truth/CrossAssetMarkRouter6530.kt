package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketDataFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * V5.0.6530 §CROSS_ASSET_MARK_ROUTING — resolve marks for non-Solana
 * canonical positions through the correct per-asset provider.
 *
 * Operator source-level audit (Feb 2026):
 *   > "Route exit marks by asset class.
 *   >   SOLANA_TOKEN → Solana token price router
 *   >   FOREX        → Forex market-data provider
 *   >   STOCK        → stock market-data provider
 *   >   COMMODITY    → commodity provider
 *   >   METAL        → metal provider
 *   > tryFallbackPriceData(\"GBPJPY\") must become impossible."
 *
 * PerpsMarketDataFetcher already prices stocks, FX, metals, commodities
 * via Pyth Oracle → PriceAggregator → Yahoo fallback. It has been the
 * canonical off-chain quote source for the perps card for months. We
 * simply route the canonical exit-mark refresh through it whenever the
 * position's assetClass is off-chain.
 *
 * The historical bug was that the canonical exit-mark refresher was
 * only wired to tryFallbackPriceData (Birdeye / DexScreener / pump.fun)
 * — Solana-only. Non-SOL canonicals never got a mark, missingMark stuck
 * at N, and CANONICAL_EXIT_MARK_REFRESH_QUEUED_6513 blew up.
 */
object CrossAssetMarkRouter6530 {

    /**
     * Try to fetch and stamp an off-chain mark on the given TokenState.
     * Returns true when the mark was updated.
     *
     * Must be called from a coroutine — this hits the network via
     * PerpsMarketDataFetcher.getMarketData.
     */
    suspend fun refreshMark(assetClass: AssetClass, symbol: String, ts: TokenState): Boolean {
        if (assetClass == AssetClass.SOLANA_TOKEN || assetClass == AssetClass.UNKNOWN) return false
        val market = resolveMarket(symbol) ?: run {
            emit("UNROUTABLE_SYMBOL", assetClass, symbol, "no PerpsMarket entry matched — provider wiring gap")
            return false
        }
        return try {
            val data = withContext(Dispatchers.IO) { PerpsMarketDataFetcher.getMarketData(market) }
            if (data.price.isFinite() && data.price > 0.0) {
                ts.lastPrice = data.price
                ts.lastPriceSource = "CrossAssetMarkRouter6530/${assetClass.tag}/${market.name}"
                ts.lastPriceMs = System.currentTimeMillis()
                emit("OK", assetClass, symbol, "price=${"%.6f".format(data.price)} market=${market.name}")
                true
            } else {
                emit("ZERO_PRICE", assetClass, symbol, "provider returned price=${data.price} market=${market.name}")
                false
            }
        } catch (e: Throwable) {
            emit("PROVIDER_ERROR", assetClass, symbol, "err=${e.javaClass.simpleName}:${e.message?.take(60)}")
            false
        }
    }

    /**
     * Map a canonical symbol string to a PerpsMarket enum. Uses PerpsMarket
     * symbol equality first (covers EURUSD/GBPJPY/AAPL/TSLA/XAU/BRENT/etc.);
     * for unknown symbols, returns null and emits an UNROUTABLE_SYMBOL
     * telemetry line so the operator sees which wiring gaps still exist.
     */
    fun resolveMarket(symbol: String): PerpsMarket? {
        val norm = symbol.uppercase().trim()
        if (norm.isBlank()) return null
        return PerpsMarket.values().firstOrNull { it.symbol == norm }
    }

    private fun emit(status: String, assetClass: AssetClass, symbol: String, detail: String) {
        try {
            PipelineHealthCollector.labelInc("CROSS_ASSET_MARK_ROUTE_6530|STATUS=$status|CLASS=${assetClass.tag}")
        } catch (_: Throwable) {}
        try {
            ForensicLogger.lifecycle(
                "CROSS_ASSET_MARK_ROUTE_6530",
                "status=$status class=${assetClass.tag} symbol=$symbol $detail",
            )
        } catch (_: Throwable) {}
    }
}

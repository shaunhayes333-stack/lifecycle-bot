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
            // V5.0.7004 §ASKING_THE_WRONG_KIND_OF_PROVIDER.
            //
            // resolveMarket maps a SYMBOL to a PerpsMarket enum, so AERO, CRO,
            // VVV, KTA — ordinary DEX tokens on base/eth/polygon/avax — can
            // never resolve. There is no perps contract for them and there
            // never will be. The operator's 5.0.7003 snapshot shows what that
            // costs: 25 UNROUTABLE_SYMBOL lines, 24 CRYPTO_ALT positions with
            // mark=0.0 and markAgeMs=Long.MAX_VALUE (never marked, not once),
            // and CRYPTO_LEV as the session's single largest loser at
            // -2.7349 SOL. A position that cannot be priced cannot be exited.
            //
            // The identity needed to price them was on the position all along:
            // ts.mint is `base|0xc06…`, `eth|0x4624…`. DefiLlama's coins API
            // keys on exactly that, and KeylessPriceSources6996 was already
            // calling it — for `solana:MINT` only.
            val keylessPx7004 = try {
                com.lifecyclebot.network.KeylessPriceSources6996.crossChainPrice(ts.mint)
            } catch (_: Throwable) { 0.0 }
            if (keylessPx7004.isFinite() && keylessPx7004 > 0.0) {
                ts.lastPrice = keylessPx7004
                ts.lastPriceSource = "DEFILLAMA_CROSSCHAIN_7004"
                ts.lastPriceUpdate = System.currentTimeMillis()
                try {
                    QuoteFreshnessGuard6452.note(
                        mint = ts.mint,
                        priceUsd = keylessPx7004,
                        source = QuoteFreshnessGuard6452.Provenance.REST_LIVE,
                    )
                } catch (_: Throwable) {}

                emit("OK_KEYLESS_CROSSCHAIN_7004", assetClass, symbol, "price=$keylessPx7004 id=${ts.mint.take(24)}")
                return true
            }
            emit("UNROUTABLE_SYMBOL", assetClass, symbol, "no PerpsMarket entry and no keyless cross-chain mark for id=${ts.mint.take(24)}")
            return false
        }
        return try {
            val data = withContext(Dispatchers.IO) { PerpsMarketDataFetcher.getMarketData(market) }
            if (data.price.isFinite() && data.price > 0.0) {
                ts.lastPrice = data.price
                ts.lastPriceSource = "CrossAssetMarkRouter6530/${assetClass.tag}/${market.name}"
                ts.lastPriceUpdate = System.currentTimeMillis()
                // V5.0.7010 §THE_OTHER_MARK_PATH_STILL_DID_NOT_STAMP.
                //
                // V5.0.6999 taught the SOLANA open-position loop to stamp
                // QuoteFreshnessGuard when it commits a mark, because the exit
                // feed tests provenance, not ts.lastPrice. It did not teach
                // THIS path — the cross-asset router — and so the fix covered
                // one of the two places the app writes a mark.
                //
                // Operator 5.0.7006, with the bot holding 92 positions:
                //
                //   CROSS_ASSET_MARK_ROUTE_6530 status=OK symbol=ZEN price=6.986
                //   CANONICAL_EXIT_FEED_6512 ... missingMark=89 of 92
                //   ... markAgeMs=30935 provenanceFresh=false
                //   Exit scheduler eval=170763 SL=0 CATA=0 TP=0 TRAIL=0
                //   Quote freshness: missing=76663
                //
                // Read those together: the marks were arriving and were 30s
                // old — comfortably inside the 60s window — and the exit feed
                // still counted 89 of 92 positions unmarked, because nothing
                // had ever written them into the guard it actually consults.
                // 170,763 exit evaluations produced two sells.
                //
                // Same defect, same shape, one path later. Stamping here is the
                // whole fix: the mark is already fetched, already validated and
                // already committed to TokenState on the line above.
                try {
                    QuoteFreshnessGuard6452.note(
                        mint = ts.mint,
                        priceUsd = data.price,
                        source = QuoteFreshnessGuard6452.Provenance.REST_LIVE,
                    )
                } catch (_: Throwable) {}

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

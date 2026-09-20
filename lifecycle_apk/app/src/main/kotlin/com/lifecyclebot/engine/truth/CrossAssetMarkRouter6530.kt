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
     * V5.0.7178 — oldest DynamicAltTokenRegistry price this router will
     * publish as a live mark. Matches the registry's own PRICE_TTL_MS and the
     * 60s bar the exit feed applies, so a stale registry row is refused here
     * rather than laundered into the canonical surface as fresh.
     */
    private const val REGISTRY_MARK_MAX_AGE_MS_7178 = 60_000L

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
            // V5.0.7178 §THE_PRICE_WAS_ALREADY_IN_THE_BUILDING.
            //
            // Operator: "why cant they be priced? are they not real world
            // trading opportunities?" They are, and the bot already has their
            // prices. It was asking the two sources least likely to have them.
            //
            // This branch tried exactly two things: a PerpsMarket symbol
            // lookup, then DefiLlama's coins API. Both fail for the identities
            // CryptoAltTrader actually holds:
            //
            //  * LLAMA_CHAIN_ALIASES_7004 keys twelve chains. The discovery
            //    layer traded on arc, robinhood and tron as well, so
            //    crossChainPrice returns 0.0 for those without issuing a
            //    request at all (20 identities in the 5.0.7176 run).
            //  * For chains it DOES key, DefiLlama's coins API carries
            //    established tokens, not freshly-discovered pool tokens — so
            //    solana|DTe5B1Qc… (BABYPHIL) fails on a perfectly valid alias.
            //
            // Meanwhile DynamicAltTokenRegistry is holding a live price for
            // precisely these rows. It is keyed by canonicalIdentity6544,
            // which is the same `chain|token` string ts.mint carries, and it
            // is refreshed by CoinGecko and by ParallelMarkFanout7088 — the
            // six-feed fan-out that returned JUPITER=3526, RAYDIUM=1512,
            // DEFILLAMA=1364, DEXSCREENER=301 quotes in that same run. This
            // router contained no reference to it.
            //
            // That is why the report could say `Economic units unpriced=24`
            // and `UNROUTABLE_SYMBOL` while CryptoAltTrader was closing the
            // same positions on real movement (HARD_TP: price=0.000686
            // crossed TP=0.000499, +131.59%). The market was never missing;
            // one of two parallel mark paths simply did not consult the
            // authority that had the number.
            //
            // Reading the registry adds no network call and no new provider.
            // The registry's own PRICE_TTL_MS is 60s, the same freshness bar
            // the exit feed applies, so anything older is refused here rather
            // than published as live.
            val regTok7178 = try {
                com.lifecyclebot.perps.DynamicAltTokenRegistry
                    .getTokenByCanonicalIdentity6544(ts.mint)
            } catch (_: Throwable) { null }
            val regPx7178 = regTok7178?.price ?: 0.0
            val regAgeMs7178 = if (regTok7178 == null) Long.MAX_VALUE else
                (System.currentTimeMillis() - regTok7178.lastUpdatedMs).coerceAtLeast(0L)
            if (regPx7178.isFinite() && regPx7178 > 0.0 && regAgeMs7178 <= REGISTRY_MARK_MAX_AGE_MS_7178) {
                ts.lastPrice = regPx7178
                ts.lastPriceSource = "ALT_REGISTRY_7178"
                ts.lastPriceUpdate = System.currentTimeMillis()
                // Stamp, for the same reason V5.0.7010 had to stamp the branch
                // above and V5.0.7175 the third path: the exit feed tests
                // provenance, not ts.lastPrice. A mark committed without a
                // stamp is a mark the exit engine cannot see.
                try {
                    QuoteFreshnessGuard6452.note(
                        mint = ts.mint,
                        priceUsd = regPx7178,
                        source = QuoteFreshnessGuard6452.Provenance.REST_LIVE,
                    )
                    PipelineHealthCollector.labelInc("CROSS_ASSET_MARK_FROM_ALT_REGISTRY_7178")
                } catch (_: Throwable) {}
                emit(
                    "OK_ALT_REGISTRY_7178", assetClass, symbol,
                    "price=$regPx7178 ageMs=$regAgeMs7178 id=${ts.mint.take(24)}",
                )
                return true
            }
            if (regTok7178 != null) {
                // The registry knows this identity but its price is absent or
                // stale. That is a refresh-cadence fault, not an unroutable
                // asset, and the two need opposite fixes.
                try {
                    PipelineHealthCollector.labelInc("CROSS_ASSET_ALT_REGISTRY_MARK_STALE_7178")
                } catch (_: Throwable) {}
            }
            emit("UNROUTABLE_SYMBOL", assetClass, symbol, "no PerpsMarket entry, no keyless cross-chain mark and no fresh alt-registry price for id=${ts.mint.take(24)} regKnown=${regTok7178 != null} regAgeMs=$regAgeMs7178")
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

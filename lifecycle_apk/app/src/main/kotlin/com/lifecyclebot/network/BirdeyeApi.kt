package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Birdeye API client — free tier, no key required for basic endpoints.
 *
 * Provides:
 *   1. OHLCV candle history — proper candles, not fake ones from polling
 *   2. Token security report — mint authority, freeze, top holders
 *   3. Token overview — price, volume, liquidity, holder count
 *
 * Free endpoints (no API key):
 *   /defi/price                   — current price
 *   /defi/ohlcv                   — OHLCV candles (requires free key for higher limits)
 *   /defi/token_security           — security data
 *   /defi/token_overview           — comprehensive token data
 *
 * Free API key at: https://birdeye.so/developer (takes 1 minute)
 * With key: 100 req/min. Without: 10 req/min.
 *
 * We seed the candle history with real Birdeye OHLCV data when a token
 * is first added to the watchlist. This means the strategy has 100 real
 * candles immediately instead of waiting 14 minutes to accumulate them.
 */
class BirdeyeApi(private val apiKey: String = "") {

    private val http = SharedHttpClient.builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // V5.9.1030 — readTimeout shrunk 12s → 4s. Same rationale as
        // DexscreenerApi: a single wedged Birdeye socket can't be
        // allowed to tie up a supervisor chunk worker past the 4.5s
        // budget. Cached candle history makes a dropped fetch
        // recoverable on the next cycle.
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val BASE = "https://public-api.birdeye.so"

    // ── OHLCV candle history ──────────────────────────────────────────

    /**
     * Fetch the last [count] candles for a token.
     * timeframe: "1m" | "3m" | "5m" | "15m" | "30m" | "1H"
     *
     * We default to 1-minute candles to seed the strategy with
     * ~100 candles of real price history immediately.
     */
    fun getCandles(
        mint: String,
        timeframe: String = "1m",
        count: Int = 100,
    ): List<Candle> {
        val now      = System.currentTimeMillis() / 1000
        val interval = timeframeToSeconds(timeframe)
        val from     = now - (count * interval)

        val url = "$BASE/defi/ohlcv?address=$mint" +
                  "&type=$timeframe&time_from=$from&time_to=$now"

        val body = get(url) ?: return emptyList()
        return try {
            val json  = JSONObject(body)
            val items = json.optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val item = items.optJSONObject(i) ?: return@mapNotNull null
                Candle(
                    ts         = item.optLong("unixTime", 0L) * 1000L,
                    priceUsd   = item.optDouble("c", 0.0),   // close
                    marketCap  = 0.0,                         // filled by overview
                    volumeH1   = item.optDouble("v", 0.0),
                    volume24h  = 0.0,
                    buysH1     = 0,
                    sellsH1    = 0,
                    // Real OHLC — the key improvement over polling
                    highUsd    = item.optDouble("h", 0.0),
                    lowUsd     = item.optDouble("l", 0.0),
                    openUsd    = item.optDouble("o", 0.0),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── Token security ────────────────────────────────────────────────

    data class SecurityReport(
        val mintAuthorityDisabled: Boolean?,
        val freezeAuthorityDisabled: Boolean?,
        val lpLockPct: Double,
        val top10HolderPct: Double,
        val creatorBalance: Double,         // how much token the creator still holds
        val isOpenSource: Boolean?,
    )

    fun getTokenSecurity(mint: String): SecurityReport? {
        val body = get("$BASE/defi/token_security?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            SecurityReport(
                mintAuthorityDisabled   = data.optInt("mintable", -1)
                    .let { if (it < 0) null else it == 0 },
                freezeAuthorityDisabled = data.optInt("freezeable", -1)
                    .let { if (it < 0) null else it == 0 },
                lpLockPct               = data.optDouble("lpPercentage", -1.0),
                top10HolderPct          = data.optDouble("top10HolderPercent", -1.0) * 100,
                creatorBalance          = data.optDouble("creatorBalance", 0.0),
                isOpenSource            = data.optInt("isOpenSource", -1)
                    .let { if (it < 0) null else it == 1 },
            )
        } catch (_: Exception) { null }
    }

    // ── Token overview ────────────────────────────────────────────────

    data class TokenOverview(
        val symbol: String,
        val name: String,
        val priceUsd: Double,
        val marketCap: Double,
        val liquidity: Double,
        val volume24h: Double,
        val priceChange24h: Double,
        val holderCount: Int,
        val createdAt: Long,          // epoch ms
    )

    fun getTokenOverview(mint: String): TokenOverview? {
        val body = get("$BASE/defi/token_overview?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            TokenOverview(
                symbol        = data.optString("symbol", ""),
                name          = data.optString("name",   ""),
                priceUsd      = data.optDouble("price",  0.0),
                marketCap     = data.optDouble("mc",     0.0),
                liquidity     = data.optDouble("liquidity", 0.0),
                volume24h     = data.optDouble("v24hUSD",   0.0),
                priceChange24h = data.optDouble("priceChange24hPercent", 0.0),
                holderCount   = data.optInt("holder", 0),
                createdAt     = data.optLong("createdAt", 0L) * 1000L,
            )
        } catch (_: Exception) { null }
    }

    /**
     * V5.9.924 — lightweight single-mint price fetch for open-position
     * tick loop fallback. /defi/price is the cheapest Birdeye endpoint
     * (no tier limit per memory rule #143 testing). When DexScreener
     * drops a rugged mint from its batch endpoint, this is the cheapest
     * way to confirm a real -90% price before catastrophe gates fire.
     *
     * Returns the price in USD, or null on any error / missing data.
     */
    fun getTokenPrice(mint: String): Double? {
        val body = get("$BASE/defi/price?address=$mint") ?: return null
        return parsePriceBody(body)
    }

    /**
     * V5.9.1123 — emergency-only open-position fallback. The caller must pass
     * BirdeyeBudgetGate.canAffordOpenPositionEmergency() before calling this;
     * this method records the one allowed call and skips the generic gate, which
     * is intentionally closed during conservation mode.
     */
    fun getTokenPriceEmergency(mint: String): Double? {
        com.lifecyclebot.engine.BirdeyeBudgetGate.recordCalls(1)
        val body = getRaw("$BASE/defi/price?address=$mint") ?: return null
        return parsePriceBody(body)
    }

    private fun parsePriceBody(body: String): Double? {
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val p = data.optDouble("value", 0.0)
            if (p > 0.0) p else null
        } catch (_: Exception) { null }
    }

    // ── V5.9.937 — STARTER-TIER RICH DATA ENDPOINTS ────────────────────
    //
    // These endpoints unlocked when operator upgraded from no-tier/Standard
    // to Starter ($99/mo, 5M CUs/mo, 15 rps). Live-probed 2026-05-19 04:05:
    //   /defi/token_security        → 200 OK
    //   /defi/token_overview        → 200 OK
    //   /defi/v3/token/trade-data/single → 200 OK (Starter unlocks)
    //   /defi/v3/token/market-data  → 200 OK
    //   /defi/v3/price/stats/single → 200 OK
    //   /defi/token_creation_info   → 200 OK
    //   /holder/v1/distribution     → 200 OK
    //   /defi/v2/tokens/top_traders → 200 OK
    //
    // All endpoints fail-open: any error returns null so the caller
    // continues on its default path. Caching belongs upstream
    // (BirdeyeSecurityProvider, etc.).

    // ── Trade-data — buy/sell/volume aggregates across 5m/30m/1h/2h/4h/8h/24h ─

    data class TradeData(
        val price: Double,
        val priceChange30mPct: Double,
        val priceChange1hPct: Double,
        val priceChange4hPct: Double,
        val priceChange24hPct: Double,
        val volume30m: Double,
        val volume1h: Double,
        val volume24h: Double,
        // Buy/sell counts — crucial for flow-imbalance / smart-money signals
        val buys30m: Int,
        val sells30m: Int,
        val buys1h: Int,
        val sells1h: Int,
        val buys24h: Int,
        val sells24h: Int,
        // Unique traders
        val uniqueWallets24h: Int,
        // Volume buy/sell split (in USD)
        val volBuy24h: Double,
        val volSell24h: Double,
    )

    fun getTradeData(mint: String): TradeData? {
        val body = get("$BASE/defi/v3/token/trade-data/single?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            TradeData(
                price             = data.optDouble("price", 0.0),
                priceChange30mPct = data.optDouble("price_change_30m_percent", 0.0),
                priceChange1hPct  = data.optDouble("price_change_1h_percent", 0.0),
                priceChange4hPct  = data.optDouble("price_change_4h_percent", 0.0),
                priceChange24hPct = data.optDouble("price_change_24h_percent", 0.0),
                volume30m         = data.optDouble("volume_30m_usd", 0.0),
                volume1h          = data.optDouble("volume_1h_usd", 0.0),
                volume24h         = data.optDouble("volume_24h_usd", 0.0),
                buys30m           = data.optInt("buy_30m", 0),
                sells30m          = data.optInt("sell_30m", 0),
                buys1h            = data.optInt("buy_1h", 0),
                sells1h           = data.optInt("sell_1h", 0),
                buys24h           = data.optInt("buy_24h", 0),
                sells24h          = data.optInt("sell_24h", 0),
                uniqueWallets24h  = data.optInt("unique_wallet_24h", 0),
                volBuy24h         = data.optDouble("volume_buy_24h_usd", 0.0),
                volSell24h        = data.optDouble("volume_sell_24h_usd", 0.0),
            )
        } catch (_: Exception) { null }
    }

    // ── Market-data — liquidity / price / fdv / circulating supply ────────

    data class MarketData(
        val price: Double,
        val liquidity: Double,
        val marketCap: Double,
        val fdv: Double,
        val circulatingSupply: Double,
        val totalSupply: Double,
    )

    fun getMarketData(mint: String): MarketData? {
        val body = get("$BASE/defi/v3/token/market-data?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            MarketData(
                price             = data.optDouble("price", 0.0),
                liquidity         = data.optDouble("liquidity", 0.0),
                marketCap         = data.optDouble("market_cap", 0.0),
                fdv               = data.optDouble("fdv", 0.0),
                circulatingSupply = data.optDouble("circulating_supply", 0.0),
                totalSupply       = data.optDouble("total_supply", 0.0),
            )
        } catch (_: Exception) { null }
    }

    // ── Price stats — volatility / high-low / SMA on multiple windows ─────

    data class PriceStats(
        val priceMin30m: Double, val priceMax30m: Double, val stdDev30m: Double,
        val priceMin1h: Double,  val priceMax1h: Double,  val stdDev1h: Double,
        val priceMin24h: Double, val priceMax24h: Double, val stdDev24h: Double,
    )

    fun getPriceStats(mint: String): PriceStats? {
        // V5.9.938: list_timeframe REQUIRED per sandbox probe.
        val body = get("$BASE/defi/v3/price/stats/single?address=$mint&list_timeframe=30m,1h,24h") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val w30 = data.optJSONObject("30m") ?: JSONObject()
            val w1h = data.optJSONObject("1h")  ?: JSONObject()
            val w24 = data.optJSONObject("24h") ?: JSONObject()
            PriceStats(
                priceMin30m = w30.optDouble("price_min", 0.0),
                priceMax30m = w30.optDouble("price_max", 0.0),
                stdDev30m   = w30.optDouble("price_std_dev", 0.0),
                priceMin1h  = w1h.optDouble("price_min", 0.0),
                priceMax1h  = w1h.optDouble("price_max", 0.0),
                stdDev1h    = w1h.optDouble("price_std_dev", 0.0),
                priceMin24h = w24.optDouble("price_min", 0.0),
                priceMax24h = w24.optDouble("price_max", 0.0),
                stdDev24h   = w24.optDouble("price_std_dev", 0.0),
            )
        } catch (_: Exception) { null }
    }

    // ── Creation info — age, creator wallet, initial liquidity ────────────

    data class CreationInfo(
        val creatorAddress: String,
        val createdAtMs: Long,
        val creationTx: String,
        val createdSlot: Long,
    )

    fun getCreationInfo(mint: String): CreationInfo? {
        val body = get("$BASE/defi/token_creation_info?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            CreationInfo(
                creatorAddress = data.optString("owner", ""),
                createdAtMs    = data.optLong("blockUnixTime", 0L) * 1000L,
                creationTx     = data.optString("txHash", ""),
                createdSlot    = data.optLong("slot", 0L),
            )
        } catch (_: Exception) { null }
    }

    // ── Holder distribution — top-10 / top-50 / top-100 concentration ────

    data class HolderDistribution(
        val top10Pct: Double,
        val top50Pct: Double,
        val top100Pct: Double,
        val totalHolders: Int,
    )

    fun getHolderDistribution(mint: String): HolderDistribution? {
        val body = get("$BASE/holder/v1/distribution?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            HolderDistribution(
                top10Pct     = data.optDouble("top10_holder_percent", 0.0) * 100.0,
                top50Pct     = data.optDouble("top50_holder_percent", 0.0) * 100.0,
                top100Pct    = data.optDouble("top100_holder_percent", 0.0) * 100.0,
                totalHolders = data.optInt("total_holders", 0),
            )
        } catch (_: Exception) { null }
    }

    // ── Top traders — who's winning this coin (max 10) ────────────────────

    data class TopTrader(
        val wallet: String,
        val volumeBuy: Double,
        val volumeSell: Double,
        val trades: Int,
        val tradesBuy: Int,
        val tradesSell: Int,
    )

    fun getTopTraders(mint: String, limit: Int = 10): List<TopTrader> {
        val body = get("$BASE/defi/v2/tokens/top_traders?address=$mint&limit=$limit&offset=0") ?: return emptyList()
        return try {
            val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                TopTrader(
                    wallet      = it.optString("owner", ""),
                    volumeBuy   = it.optDouble("volumeBuy", 0.0),
                    volumeSell  = it.optDouble("volumeSell", 0.0),
                    trades      = it.optInt("trade", 0),
                    tradesBuy   = it.optInt("tradeBuy", 0),
                    tradesSell  = it.optInt("tradeSell", 0),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── V5.9.938 — RICH-DATA ENDPOINTS (charts, pair, mint/burn, meta, search) ─

    data class PairOverview(
        val pairAddress: String,
        val baseSymbol: String,
        val quoteSymbol: String,
        val liquidityUsd: Double,
        val volume24hUsd: Double,
        val volume24hChangePct: Double,
        val trade24h: Int,
        val priceChange24hPct: Double,
        val createdAtMs: Long,
        val ammSource: String,
    )

    fun getPairOverview(pairAddress: String): PairOverview? {
        val body = get("$BASE/defi/v3/pair/overview/single?address=$pairAddress") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val base = data.optJSONObject("base") ?: JSONObject()
            val quote = data.optJSONObject("quote") ?: JSONObject()
            PairOverview(
                pairAddress       = data.optString("address", pairAddress),
                baseSymbol        = base.optString("symbol", ""),
                quoteSymbol       = quote.optString("symbol", ""),
                liquidityUsd      = data.optDouble("liquidity", 0.0),
                volume24hUsd      = data.optDouble("volume_24h_usd", 0.0),
                volume24hChangePct= data.optDouble("volume_24h_change_percent", 0.0),
                trade24h          = data.optInt("trade_24h", 0),
                priceChange24hPct = data.optDouble("price_change_24h_percent", 0.0),
                createdAtMs       = data.optLong("created_at", 0L) * 1000L,
                ammSource         = data.optString("source", ""),
            )
        } catch (_: Exception) { null }
    }

    data class MintBurnEvent(
        val timestampMs: Long,
        val type: String,
        val amountTokens: Double,
        val owner: String,
        val txHash: String,
    )

    fun getMintBurnTxs(mint: String, limit: Int = 20): List<MintBurnEvent> {
        val body = get("$BASE/defi/v3/token/mint-burn-txs?address=$mint&sort_type=desc&limit=$limit") ?: return emptyList()
        return try {
            val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                MintBurnEvent(
                    timestampMs  = it.optLong("block_unix_time", 0L) * 1000L,
                    type         = it.optString("type", ""),
                    amountTokens = it.optDouble("ui_amount", 0.0),
                    owner        = it.optString("owner", ""),
                    txHash       = it.optString("tx_hash", ""),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    data class TokenMetaData(
        val name: String,
        val symbol: String,
        val decimals: Int,
        val logoUri: String,
        val description: String,
        val twitter: String,
        val telegram: String,
        val website: String,
        val discord: String,
        val coingeckoId: String,
    )

    fun getTokenMetaData(mint: String): TokenMetaData? {
        val body = get("$BASE/defi/v3/token/meta-data/single?address=$mint") ?: return null
        return try {
            val data = JSONObject(body).optJSONObject("data") ?: return null
            val ext = data.optJSONObject("extensions") ?: JSONObject()
            TokenMetaData(
                name        = data.optString("name", ""),
                symbol      = data.optString("symbol", ""),
                decimals    = data.optInt("decimals", 0),
                logoUri     = data.optString("logo_uri", ""),
                description = ext.optString("description", ""),
                twitter     = ext.optString("twitter", ""),
                telegram    = ext.optString("telegram", ""),
                website     = ext.optString("website", ""),
                discord     = ext.optString("discord", ""),
                coingeckoId = ext.optString("coingecko_id", ""),
            )
        } catch (_: Exception) { null }
    }

    data class AllTimeStats(
        val totalBuys: Long,
        val totalSells: Long,
        val totalTrades: Long,
        val volumeBuyUsd: Double,
        val volumeSellUsd: Double,
        val totalVolumeUsd: Double,
    )

    fun getAllTimeStats(mint: String): AllTimeStats? {
        val body = get("$BASE/defi/v3/all-time/trades/single?address=$mint") ?: return null
        return try {
            val arr = JSONObject(body).optJSONArray("data") ?: return null
            if (arr.length() == 0) return null
            val it = arr.optJSONObject(0) ?: return null
            AllTimeStats(
                totalBuys      = it.optLong("buy", 0L),
                totalSells     = it.optLong("sell", 0L),
                totalTrades    = it.optLong("total_trade", 0L),
                volumeBuyUsd   = it.optDouble("volume_buy_usd", 0.0),
                volumeSellUsd  = it.optDouble("volume_sell_usd", 0.0),
                totalVolumeUsd = it.optDouble("total_volume_usd", 0.0),
            )
        } catch (_: Exception) { null }
    }

    data class SearchResult(
        val mint: String,
        val name: String,
        val symbol: String,
        val fdv: Double,
        val volume24hUsd: Double,
        val liquidity: Double,
        val priceChange24hPct: Double,
        val marketCapUsd: Double = 0.0,
        val priceUsd: Double = 0.0,
        val trade24hCount: Int = 0,
        val uniqueWallet24h: Int = 0,
    )

    fun searchTokens(keyword: String, limit: Int = 10): List<SearchResult> {
        val body = get(
            "$BASE/defi/v3/search?keyword=$keyword&target=token" +
            "&sort_by=volume_24h_usd&sort_type=desc&offset=0&limit=$limit"
        ) ?: return emptyList()
        return try {
            val groups = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
            val out = mutableListOf<SearchResult>()
            for (g in 0 until groups.length()) {
                val results = groups.optJSONObject(g)?.optJSONArray("result") ?: continue
                for (i in 0 until results.length()) {
                    val it = results.optJSONObject(i) ?: continue
                    out.add(SearchResult(
                        mint              = it.optString("address", ""),
                        name              = it.optString("name", ""),
                        symbol            = it.optString("symbol", ""),
                        fdv               = it.optDouble("fdv", 0.0),
                        volume24hUsd      = it.optDouble("volume_24h_usd", 0.0),
                        liquidity         = it.optDouble("liquidity", 0.0),
                        priceChange24hPct = it.optDouble("price_change_24h_percent", 0.0),
                        marketCapUsd      = it.optDouble("market_cap", 0.0),
                        priceUsd          = it.optDouble("price", 0.0),
                        trade24hCount     = it.optInt("trade_24h", 0),
                        uniqueWallet24h   = it.optInt("unique_wallet_24h", 0),
                    ))
                }
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    data class TraderRanking(
        val wallet: String,
        val pnlUsd: Double,
        val volumeUsd: Double,
        val tradeCount: Int,
    )

    fun getTraderGainersLosers(type: String = "today", descending: Boolean = true, limit: Int = 10): List<TraderRanking> {
        val sort = if (descending) "desc" else "asc"
        val body = get(
            "$BASE/trader/gainers-losers?type=$type&sort_by=PnL&sort_type=$sort&offset=0&limit=$limit"
        ) ?: return emptyList()
        return try {
            val items = JSONObject(body).optJSONObject("data")?.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                val it = items.optJSONObject(i) ?: return@mapNotNull null
                TraderRanking(
                    wallet     = it.optString("address", ""),
                    pnlUsd     = it.optDouble("pnl", 0.0),
                    volumeUsd  = it.optDouble("volume", 0.0),
                    tradeCount = it.optInt("trade_count", 0),
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // ── HTTP helper ───────────────────────────────────────────────────

    private fun get(url: String): String? {
        // V5.9.952 — global budget gate. Every normal BirdeyeApi call routes here.
        if (!com.lifecyclebot.engine.BirdeyeBudgetGate.canAfford(1)) {
            com.lifecyclebot.engine.BirdeyeBudgetGate.logThrottleIfDue()
            return null
        }
        com.lifecyclebot.engine.BirdeyeBudgetGate.recordCalls(1)
        return getRaw(url)
    }

    private fun getRaw(url: String): String? {
        // V5.9.866 — KeyValidator entry gate. Birdeye is key-auth-class API.
        // (Converted from expression body — early returns aren't legal there.)
        if (apiKey.isBlank()) return null
        if (!com.lifecyclebot.engine.KeyValidator.isLive("birdeye")) return null
        // V5.9.1048 — reactive Birdeye backoff. Operator V5.9.1047 dump
        // showed birdeye sr=59% 4xx=344 — Birdeye was being hammered
        // through 429s because BirdeyeApi.get() never consulted ApiBackoff.
        // Now: skip the call entirely while locked out and route the
        // response code through ApiBackoff.markFailure / markSuccess so
        // consecutive 429s engage exponential backoff (5s → 5min cap).
        if (com.lifecyclebot.engine.ApiBackoff.isLockedOut("birdeye")) return null

        return try {
            val effectiveUrl = try { com.lifecyclebot.engine.AutoEndpointMigrator.rewrite(url) } catch (_: Throwable) { url }
            val builder = Request.Builder().url(effectiveUrl)
                .header("accept", "application/json")
                .header("x-chain", "solana")
            builder.header("X-API-KEY", apiKey)

            val beStart = System.currentTimeMillis()
            val resp = try {
                http.newCall(builder.build()).execute()
            } catch (e: Exception) {
                try { com.lifecyclebot.engine.ApiHealthMonitor.recordNetworkError("birdeye", e.message) } catch (_: Throwable) {}
                throw e
            }
            try { com.lifecyclebot.engine.ApiHealthMonitor.record("birdeye", resp.code, System.currentTimeMillis() - beStart) } catch (_: Throwable) {}
            // V5.9.1048 — feed response codes to ApiBackoff so consecutive
            // 429/4xx escalate the lockout.
            try {
                if (resp.code in 400..599) com.lifecyclebot.engine.ApiBackoff.markFailure("birdeye", resp.code)
                else if (resp.isSuccessful) com.lifecyclebot.engine.ApiBackoff.markSuccess("birdeye")
            } catch (_: Throwable) {}
            when {
                resp.code in listOf(401, 403) -> {
                    try { com.lifecyclebot.engine.KeyValidator.recordResult("birdeye", success = false, httpStatus = resp.code) } catch (_: Throwable) {}
                    null
                }
                resp.isSuccessful -> {
                    try { com.lifecyclebot.engine.KeyValidator.recordResult("birdeye", success = true, httpStatus = resp.code) } catch (_: Throwable) {}
                    resp.body?.string()
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun timeframeToSeconds(tf: String): Long = when (tf) {
        "1m"  -> 60L
        "3m"  -> 180L
        "5m"  -> 300L
        "15m" -> 900L
        "30m" -> 1800L
        "1H"  -> 3600L
        else  -> 60L
    }
}

package com.lifecyclebot.network

import com.lifecyclebot.data.Candle
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.perps.PerpsMarket
import com.lifecyclebot.perps.PerpsMarketDataFetcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * V5.9.57: Chart history fetcher with multi-source fallback.
 *
 * Previously, the MiniPriceChartView in CryptoAltActivity relied on Birdeye's
 * free OHLCV endpoint which is aggressively rate-limited without an API key
 * (10 req/min), so almost every chart rendered as "Waiting for price data…".
 *
 * This fetcher tries sources in priority order:
 *   1. Birdeye OHLCV        — best for Solana memecoins (mint address)
 *   2. CoinGecko OHLC       — for "cg:<id>" pseudo-mints
 *   3. Yahoo Finance chart  — for known PerpsMarket symbols (stocks, BTC, ETH, SOL)
 *   4. Synth (prev→now)     — last resort, from current price + 24h change
 *
 * Returns a list of Candle-like points with at least .priceUsd set.
 * Callers should prefer Birdeye when mint is a real Solana address.
 */
object ChartHistoryFetcher {

    private const val TAG = "ChartHist"

    private val http = SharedHttpClient.builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch up to [count] price points for a given token.
     *
     * @param mint       Solana mint address, or "cg:<id>", or "static:<sym>"
     * @param symbol     Ticker (e.g. "BONK", "SOL", "AAPL") — used for Yahoo
     * @param timeframe  "1m" | "5m" | "15m" | "1H" (display hint)
     * @param currentPrice Last known price (used for synth fallback)
     * @param change24hPct 24h change % (used for synth fallback)
     */
    fun fetch(
        mint: String,
        symbol: String,
        timeframe: String,
        currentPrice: Double,
        change24hPct: Double,
        count: Int = 60,
    ): List<Double> {
        // 1. Birdeye for real Solana mints (not cg:/static:)
        if (mint.length > 20 && !mint.startsWith("cg:") && !mint.startsWith("static:")) {
            try {
                val candles = BirdeyeApi().getCandles(mint, timeframe, count)
                if (candles.size >= 2) return candles.map { it.priceUsd }.filter { it > 0 }
            } catch (_: Exception) {}
        }

        // 2. CoinGecko OHLC for cg:<id>
        if (mint.startsWith("cg:")) {
            val id = mint.removePrefix("cg:")
            fetchCoinGecko(id, timeframe)?.let { if (it.size >= 2) return it }
        }

        // 3. Yahoo chart for known ticker (stocks, BTC, ETH, SOL, etc.)
        val perps = runCatching {
            PerpsMarket.values().firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
        }.getOrNull()
        if (perps != null) {
            fetchYahooChart(perps, timeframe)?.let { if (it.size >= 2) return it }
        }

        // 4. Synthesized 2-point line from current price + 24h change.
        val livePrice = if (currentPrice > 0) currentPrice else {
            // Last resort: pull a cached live price from the market fetcher.
            runCatching { PerpsMarketDataFetcher.getCachedPrice(perps ?: return@runCatching null)?.price ?: 0.0 }
                .getOrNull() ?: 0.0
        }
        if (livePrice <= 0) return emptyList()
        val prev = if (change24hPct != 0.0) livePrice / (1.0 + change24hPct / 100.0) else livePrice * 0.999
        return listOf(prev, livePrice)
    }

    /**
     * CoinGecko free OHLC endpoint.
     *   /api/v3/coins/{id}/ohlc?vs_currency=usd&days=1
     * Returns [[ts_ms, open, high, low, close], ...]
     */
    private fun fetchCoinGecko(id: String, timeframe: String): List<Double>? {
        val days = when (timeframe) {
            "1m", "5m" -> 1
            "15m"      -> 1
            "1H"       -> 7
            else       -> 1
        }
        val url = "https://api.coingecko.com/api/v3/coins/$id/ohlc?vs_currency=usd&days=$days"
        return try {
            val body = httpGet(url) ?: return null
            val arr = JSONArray(body)
            val out = ArrayList<Double>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                val close = row.optDouble(4, 0.0)
                if (close > 0) out.add(close)
            }
            out.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "CG OHLC failed for $id: ${e.message}")
            null
        }
    }

    /**
     * Yahoo Finance v8 chart endpoint — free, no key.
     *   /v8/finance/chart/{SYMBOL}?range=1d&interval=5m
     */
    private fun fetchYahooChart(market: PerpsMarket, timeframe: String): List<Double>? {
        val (range, interval) = when (timeframe) {
            "1m"  -> "1d"  to "1m"
            "5m"  -> "5d"  to "5m"
            "15m" -> "5d"  to "15m"
            "1H"  -> "1mo" to "60m"
            else  -> "5d"  to "15m"
        }
        val ySym = yahooSymbol(market)
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ySym?range=$range&interval=$interval"
        return try {
            val body = httpGet(url) ?: return null
            val chart = JSONObject(body).optJSONObject("chart") ?: return null
            val result = chart.optJSONArray("result")?.optJSONObject(0) ?: return null
            val closes = result.optJSONObject("indicators")
                ?.optJSONArray("quote")?.optJSONObject(0)
                ?.optJSONArray("close") ?: return null
            val out = ArrayList<Double>(closes.length())
            for (i in 0 until closes.length()) {
                val v = closes.opt(i)
                if (v is Number) {
                    val d = v.toDouble()
                    if (d > 0 && !d.isNaN()) out.add(d)
                }
            }
            out.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            ErrorLogger.debug(TAG, "Yahoo chart failed for ${market.symbol}: ${e.message}")
            null
        }
    }

    /**
     * Map a PerpsMarket to its Yahoo Finance ticker.
     * Crypto symbols need the "-USD" suffix on Yahoo.
     */
    private fun yahooSymbol(market: PerpsMarket): String {
        val sym = market.symbol.uppercase()
        // Rough classification: if the enum name suggests crypto, append -USD.
        val isCrypto = sym in setOf(
            "SOL", "BTC", "ETH", "BNB", "XRP", "DOGE", "ADA", "AVAX", "DOT",
            "MATIC", "LINK", "ATOM", "LTC", "BCH", "NEAR", "UNI", "SHIB",
            "PEPE", "WIF", "BONK", "TRUMP", "FLOKI", "APT", "SUI", "ARB",
            "OP", "INJ", "TON"
        )
        return if (isCrypto) "$sym-USD" else sym
    }

    private fun httpGet(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("accept", "application/json")
            .header("user-agent", "Mozilla/5.0 (Android) AATE/5.9")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) { null }
}

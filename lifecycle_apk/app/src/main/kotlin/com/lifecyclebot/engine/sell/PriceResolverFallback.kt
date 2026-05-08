package com.lifecyclebot.engine.sell

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HostWalletTokenTracker
import com.lifecyclebot.network.DexscreenerApi
import com.lifecyclebot.network.JupiterApi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.9.495z48 — operator P0 (Message 472):
 * "If the bot can't price a wallet token, stop-losses and trailing stops
 *  fail silently."
 *
 * Single canonical price-resolver fallback chain:
 *   1. DexScreener (latest pair priceUsd)         — fastest, broadest
 *   2. GeckoTerminal (token-info v2)              — covers tokens DexScreener misses
 *   3. Jupiter (1-token-→-SOL quote × SOL/USD)    — works whenever a route exists
 *   4. HostWalletTokenTracker cached lastPrice    — last good observation
 *   5. HostWalletTokenTracker.entryPriceUsd       — better than 0 for SL math
 *   6. null — caller must treat as UNKNOWN, NEVER 0.
 *
 * Each successful fetch is also written back into a small in-process cache
 * so a brief multi-source outage doesn't spike SL/TP false positives.
 */
object PriceResolverFallback {

    private const val TAG = "PriceResolverFallback"

    /** Last successful price + ts, keyed by mint. Survives until process death. */
    private data class Cached(val priceUsd: Double, val source: String, val tsMs: Long)
    private val cache = ConcurrentHashMap<String, Cached>()

    enum class Source { DEXSCREENER, GECKOTERMINAL, JUPITER, CACHED, ENTRY, UNKNOWN }

    data class Resolved(val priceUsd: Double, val source: Source)

    /**
     * @param mint            token mint (Solana)
     * @param solUsdHint      latest SOL/USD price (used by Jupiter step). Pass
     *                        `WalletManager.lastKnownSolPrice` from caller.
     * @return null if every fallback failed. The caller MUST treat null as
     *         UNKNOWN and skip exit gates, not as 0.
     */
    fun resolve(mint: String, solUsdHint: Double): Resolved? {
        if (mint.isBlank()) return null

        // 1. DexScreener
        try {
            val price = DexscreenerApi().getBestPair(mint)?.candle?.priceUsd ?: 0.0
            if (price > 0.0) {
                cache[mint] = Cached(price, "DEXSCREENER", System.currentTimeMillis())
                return Resolved(price, Source.DEXSCREENER)
            }
        } catch (_: Throwable) { /* fall through */ }

        // 2. GeckoTerminal
        try {
            val price = fetchGeckoTerminalPrice(mint)
            if (price > 0.0) {
                cache[mint] = Cached(price, "GECKOTERMINAL", System.currentTimeMillis())
                return Resolved(price, Source.GECKOTERMINAL)
            }
        } catch (_: Throwable) { /* fall through */ }

        // 3. Jupiter quote-derived: quote 1 unit of token → SOL, multiply by SOL/USD.
        try {
            if (solUsdHint > 0.0) {
                val price = fetchJupiterDerivedPrice(mint, solUsdHint)
                if (price > 0.0) {
                    cache[mint] = Cached(price, "JUPITER", System.currentTimeMillis())
                    return Resolved(price, Source.JUPITER)
                }
            }
        } catch (_: Throwable) { /* fall through */ }

        // 4. In-process cache (last good)
        cache[mint]?.let { c ->
            if (c.priceUsd > 0.0) return Resolved(c.priceUsd, Source.CACHED)
        }

        // 5. HostWalletTokenTracker entry price (last resort, prevents SL silent-fail)
        try {
            val tracked = HostWalletTokenTracker.getEntry(mint)
            val cached = tracked?.currentPriceUsd?.takeIf { it > 0.0 }
                ?: tracked?.entryPriceUsd?.takeIf { it > 0.0 }
            if (cached != null) {
                return Resolved(cached, Source.ENTRY)
            }
        } catch (_: Throwable) { /* fall through */ }

        ErrorLogger.warn(TAG, "all price sources failed for ${mint.take(8)}… — UNKNOWN")
        return null
    }

    /**
     * GeckoTerminal token endpoint. Free, no API key, ~150ms.
     * https://api.geckoterminal.com/api/v2/networks/solana/tokens/<mint>
     */
    private fun fetchGeckoTerminalPrice(mint: String): Double {
        val url = URL("https://api.geckoterminal.com/api/v2/networks/solana/tokens/$mint")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 4_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) return 0.0
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val data = root.optJSONObject("data") ?: return 0.0
            val attrs = data.optJSONObject("attributes") ?: return 0.0
            // GeckoTerminal returns price_usd as a string.
            val priceStr = attrs.optString("price_usd", "")
            return priceStr.toDoubleOrNull() ?: 0.0
        } finally { conn.disconnect() }
    }

    /**
     * Jupiter quote-derived price. Assumes 6 decimals as a starting probe is
     * fine because we only need a ratio (out / in). Uses HostWalletTokenTracker
     * to read decimals when known; otherwise probes with 6.
     */
    private fun fetchJupiterDerivedPrice(mint: String, solUsdHint: Double): Double {
        val tracked = try { HostWalletTokenTracker.getEntry(mint) } catch (_: Throwable) { null }
        val decimals = tracked?.decimals?.takeIf { it > 0 } ?: 6
        // Quote 1 whole token (10^decimals) → SOL.
        val oneToken = java.math.BigInteger.TEN.pow(decimals).toLong()
        val quote = try {
            JupiterApi().getQuote(mint, JupiterApi.SOL_MINT, oneToken, 100)
        } catch (_: Throwable) { return 0.0 }
        // outAmount is in lamports (SOL has 9 decimals). Convert to SOL, then USD.
        val solOut = quote.outAmount / 1_000_000_000.0
        if (solOut <= 0.0) return 0.0
        return solOut * solUsdHint
    }

    /** Test/diagnostic accessor: snapshot of in-memory cache. */
    fun cacheSnapshot(): Map<String, Pair<Double, String>> =
        cache.mapValues { (_, c) -> c.priceUsd to c.source }
}

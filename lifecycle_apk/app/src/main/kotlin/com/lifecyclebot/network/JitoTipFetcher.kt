package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.483 — Dynamic Jito tip fetcher.
 *
 * Operator earlier-session log showed repeated:
 *   ⚠️ Jito failed: transaction #0 could not be decoded, falling back to normal RPC
 *   ⚠️ Jito failed: Network congested. Endpoint is globally rate limited.
 *
 * Static `c.jitoTipLamports` (default 10_000) is too low during congestion;
 * Jito's relay rejects the bundle and we fall back to plain RPC, which is
 * slow → tx lands late → on-chain price has moved → 0x1788/0x1789 slippage
 * fail.
 *
 * Solution per Jito docs: fetch live tip floor from
 *   https://bundles.jito.wtf/api/v1/bundles/tip_floor
 * and use the 75th percentile (`landed_tips_75th_percentile`) as the actual
 * tip amount. Auto-rises during congestion, drops back during quiet periods,
 * always landing in the top 25% of bundles.
 *
 * We cache the value for 30 seconds — Jito's API is rate-limited and tip
 * floors don't move that fast. On API failure we fall back to a static
 * default (caller-supplied), so this is purely an optimization with zero
 * downside risk.
 */
object JitoTipFetcher {
    private const val TAG = "JitoTipFetcher"
    private const val URL_STR = "https://bundles.jito.wtf/api/v1/bundles/tip_floor"
    private const val CACHE_TTL_MS = 30_000L  // 30s

    @Volatile private var cachedTipLamports: Long = -1L
    @Volatile private var cacheTimestampMs: Long = 0L
    private val fetchInFlight = AtomicLong(0L)

    /**
     * Returns the current 75th-percentile Jito bundle tip in lamports.
     * Caches for 30s. On API failure or before first successful fetch,
     * returns `staticFallback` so callers keep working.
     */
    fun getDynamicTip(staticFallback: Long): Long {
        val now = System.currentTimeMillis()
        val cached = cachedTipLamports
        val cacheAge = now - cacheTimestampMs

        // Cache hit — return immediately.
        if (cached > 0 && cacheAge < CACHE_TTL_MS) {
            return cached
        }

        // Cache stale or empty — try to refresh, but only one in-flight at a time.
        if (fetchInFlight.compareAndSet(0L, now)) {
            try {
                val fresh = fetchFromApi()
                if (fresh > 0) {
                    cachedTipLamports = fresh
                    cacheTimestampMs = now
                    if (cached <= 0 || kotlin.math.abs(fresh - cached) > 5000) {
                        // Log only when value meaningfully changes (≥0.000005 SOL delta)
                        // to keep the log feed quiet during stable periods.
                        ErrorLogger.info(TAG, "🎯 Jito tip refreshed: ${fresh}lamp (~${fresh / 1_000_000_000.0} SOL) [75th pct, cached 30s]")
                    }
                    return fresh
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "⚠️ Jito tip fetch failed: ${e.message?.take(80)} — using static fallback ${staticFallback}lamp")
            } finally {
                fetchInFlight.set(0L)
            }
        }

        // Fetch in-flight or just failed — return what we have, falling back to static.
        return if (cached > 0) cached else staticFallback
    }

    private fun fetchFromApi(): Long {
        val conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 3500
            readTimeout = 3500
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "LifecycleBot/5.9 JitoTipFetcher")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() == 0) {
                throw RuntimeException("empty array response")
            }
            val first = arr.getJSONObject(0)
            // Jito returns floats in SOL — convert to lamports.
            val tipSol = first.optDouble("landed_tips_75th_percentile", 0.00001)
            val lamports = (tipSol * 1_000_000_000.0).toLong()
            // Sanity bound: 1k - 1M lamports (0.000001 - 0.001 SOL).
            return lamports.coerceIn(1_000L, 1_000_000L)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }
}

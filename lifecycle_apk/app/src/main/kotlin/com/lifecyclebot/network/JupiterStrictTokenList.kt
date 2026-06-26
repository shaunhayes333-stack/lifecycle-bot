package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HealthAwareHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * JupiterStrictTokenList — V5.0.4108 (SOL-wide scanner expansion)
 *                          V5.0.4174 — TOKENS API V2 MIGRATION.
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Operator P0: 'the scanner!! its meant to be sol wide!!! more candidates
 * more wins!!!'
 *
 * Hits Jupiter's verified token list — every Solana token that has a
 * Jupiter route + verified metadata. Free, no API key, no rate limit.
 * Returns thousands of established tokens; scanner picks the freshest /
 * trending subset to enqueue per cycle.
 *
 * V5.0.4174 — `tokens.jup.ag` (Tokens V1) was deprecated 30 Sep 2025 and is
 * now NXDOMAIN (field log: `Unable to resolve host "tokens.jup.ag"`). New
 * free-tier endpoint: `https://lite-api.jup.ag/tokens/v2/tag?query=verified`.
 * Schema changed:
 *   • address → id
 *   • daily_volume → stats24h.buyVolume + stats24h.sellVolume
 *   • returns liquidity, mcap, fdv, holderCount, organicScore for free
 *
 *   GET https://lite-api.jup.ag/tokens/v2/tag?query=verified
 *
 * Response: array of { id, symbol, name, decimals, liquidity, mcap, fdv,
 *                      stats24h: { buyVolume, sellVolume, ... }, ... }
 * Cached 60 minutes — universe of verified Solana tokens doesn't churn
 * faster than that.
 */
class JupiterStrictTokenList {

    private val http: OkHttpClient = SharedHttpClient.builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class JupVerifiedToken(
        val mint: String,
        val symbol: String,
        val name: String,
        val decimals: Int,
        val dailyVolumeUsd: Double,
    )

    @Volatile private var cache: List<JupVerifiedToken> = emptyList()
    @Volatile private var fetchedAt: Long = 0L
    private val CACHE_TTL_MS = 60L * 60 * 1000

    /** Returns the verified Solana token universe sorted by daily volume. */
    fun getVerified(): List<JupVerifiedToken> {
        val now = System.currentTimeMillis()
        if (now - fetchedAt < CACHE_TTL_MS && cache.isNotEmpty()) return cache
        return refresh()
    }

    fun primeOnStart() {
        try { refresh() } catch (_: Throwable) { }
    }

    fun refresh(): List<JupVerifiedToken> {
        // V5.0.4174 — migrated from dead `https://tokens.jup.ag/tokens?tags=verified`
        // to Tokens API V2 free tier at `lite-api.jup.ag/tokens/v2/tag?query=verified`.
        // The old host is NXDOMAIN; field log: `Unable to resolve host
        // "tokens.jup.ag"`. Schema changed (address→id, daily_volume→
        // stats24h.buyVolume+sellVolume); parser below handles V2 fields and
        // falls back to V1 keys if the operator's network ever serves an old
        // cached shape.
        val body = get("https://lite-api.jup.ag/tokens/v2/tag?query=verified") ?: return cache
        return try {
            val arr = JSONArray(body)
            val list = mutableListOf<JupVerifiedToken>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                // V2 calls the mint `id`; V1 called it `address`.
                val mint = obj.optString("id", "").ifBlank { obj.optString("address", "") }
                if (mint.isBlank() || mint.length !in 32..64) continue
                val sym = obj.optString("symbol", "").uppercase()
                if (sym in setOf("SOL", "WSOL", "USDC", "USDT", "USDH", "JITOSOL", "MSOL", "BSOL")) continue
                // V2 splits volume into buy + sell under stats24h. V1 had
                // a flat `daily_volume` field. Use whichever is present.
                val vol = run {
                    val s24 = obj.optJSONObject("stats24h")
                    if (s24 != null) {
                        s24.optDouble("buyVolume", 0.0) + s24.optDouble("sellVolume", 0.0)
                    } else {
                        obj.optDouble("daily_volume", 0.0)
                    }
                }
                if (vol < 5_000.0) continue   // floor — established but liquid
                list.add(
                    JupVerifiedToken(
                        mint = mint,
                        symbol = sym,
                        name = obj.optString("name", ""),
                        decimals = obj.optInt("decimals", 9),
                        dailyVolumeUsd = vol,
                    )
                )
            }
            // Sort by daily volume desc — most active established tokens first
            val sorted = list.sortedByDescending { it.dailyVolumeUsd }
            cache = sorted
            fetchedAt = System.currentTimeMillis()
            ErrorLogger.info(
                "JupiterStrict",
                "✅ verified Solana token list cached: ${sorted.size} tokens (vol>=\$5K) via lite-api.jup.ag/tokens/v2"
            )
            sorted
        } catch (e: Exception) {
            ErrorLogger.debug("JupiterStrict", "parse error: ${e.message}")
            cache
        }
    }

    private fun get(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "AATE/5.0 (Solana trader)")
            .build()
        val resp = HealthAwareHttp.execute(http, req, host = "jupiter")
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}

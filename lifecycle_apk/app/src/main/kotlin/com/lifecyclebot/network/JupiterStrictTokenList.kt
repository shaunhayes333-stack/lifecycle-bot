package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HealthAwareHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * JupiterStrictTokenList — V5.0.4108 (SOL-wide scanner expansion)
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
 *   GET https://tokens.jup.ag/tokens?tags=verified
 *
 * Response: array of {address, name, symbol, decimals, daily_volume, ...}
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
        val body = get("https://tokens.jup.ag/tokens?tags=verified") ?: return cache
        return try {
            val arr = JSONArray(body)
            val list = mutableListOf<JupVerifiedToken>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val mint = obj.optString("address", "")
                if (mint.isBlank() || mint.length !in 32..64) continue
                val sym = obj.optString("symbol", "").uppercase()
                if (sym in setOf("SOL", "WSOL", "USDC", "USDT", "USDH", "JITOSOL", "MSOL", "BSOL")) continue
                val vol = obj.optDouble("daily_volume", 0.0)
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
                "✅ verified Solana token list cached: ${sorted.size} tokens (vol>=\$5K)"
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

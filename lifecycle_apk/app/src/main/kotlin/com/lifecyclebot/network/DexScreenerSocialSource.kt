package com.lifecyclebot.network

import com.lifecyclebot.data.MentionEvent
import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.HealthAwareHttp
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.9.1340 — REAL replacement for the dead Twitter/Nitter scraper.
 *
 * Nitter is officially discontinued (all public instances are down or 403),
 * and X's official API is paywalled at ~$42k/yr — symbol-text Twitter scraping
 * is simply not available for free any more. Instead of scraping generic tweets,
 * we use DexScreener's FREE, NO-KEY social/momentum endpoints, which give a
 * STRONGER signal for the exact tokens we trade:
 *
 *   • token-boosts/top      — tokens with paid boosts = active promotion / hype
 *   • token-profiles/latest — which new tokens have real socials (twitter/tg/site)
 *   • community-takeovers    — CTO = organised community momentum (very bullish)
 *
 * DexScreener is already our most reliable API (~98% health), rate-limited at
 * 60 req/min on these endpoints. We poll the three lists ONCE per refresh window
 * (not per-token) and serve per-mint lookups from an in-memory cache, so a full
 * watchlist scan costs at most 3 HTTP calls per window.
 *
 * Emits MentionEvent(source="dexsocial", ...) so SentimentEngine folds it into
 * the same aggregate as the (still-live) Telegram sources.
 */
object DexScreenerSocialSource {

    private const val TAG = "DexSocial"
    private const val BASE = "https://api.dexscreener.com"
    private const val REFRESH_MS = 90_000L

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    data class Social(
        val boostTotal: Double,
        val socialCount: Int,
        val communityTakeover: Boolean,
        val ts: Long,
    )

    private val cache = ConcurrentHashMap<String, Social>()
    @Volatile private var lastRefreshMs = 0L

    fun searchBySymbol(symbol: String, mint: String): List<MentionEvent> {
        refreshIfDue()
        val key = mint.lowercase()
        val s = cache[key] ?: return emptyList()
        val now = System.currentTimeMillis()
        val events = mutableListOf<MentionEvent>()

        if (s.boostTotal > 0) {
            val sent = when {
                s.boostTotal >= 500 -> 0.85
                s.boostTotal >= 100 -> 0.65
                else                -> 0.45
            }
            events.add(MentionEvent("dexsocial", now, sent,
                "DexScreener boost ${s.boostTotal.toInt()} on $symbol"))
        }
        if (s.communityTakeover) {
            events.add(MentionEvent("dexsocial", now, 0.80,
                "Community takeover active for $symbol"))
        }
        if (s.socialCount >= 2) {
            events.add(MentionEvent("dexsocial", now, 0.30,
                "$symbol has ${s.socialCount} social links"))
        }
        return events
    }

    private fun refreshIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshMs < REFRESH_MS) return
        synchronized(this) {
            if (System.currentTimeMillis() - lastRefreshMs < REFRESH_MS) return
            lastRefreshMs = System.currentTimeMillis()
        }
        runCatching { pollBoosts() }.onFailure { ErrorLogger.warn(TAG, "boosts poll failed: ${it.message}") }
        runCatching { pollProfiles() }.onFailure { ErrorLogger.warn(TAG, "profiles poll failed: ${it.message}") }
        runCatching { pollTakeovers() }.onFailure { ErrorLogger.warn(TAG, "takeovers poll failed: ${it.message}") }
    }

    private fun pollBoosts() {
        val arr = getArray("$BASE/token-boosts/top/v1") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("chainId") != "solana") continue
            val mint = o.optString("tokenAddress").lowercase()
            if (mint.isBlank()) continue
            val total = o.optDouble("totalAmount", o.optDouble("amount", 0.0))
            val links = o.optJSONArray("links")?.length() ?: 0
            merge(mint) { it.copy(boostTotal = total, socialCount = maxOf(it.socialCount, links)) }
        }
    }

    private fun pollProfiles() {
        val arr = getArray("$BASE/token-profiles/latest/v1") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("chainId") != "solana") continue
            val mint = o.optString("tokenAddress").lowercase()
            if (mint.isBlank()) continue
            val links = o.optJSONArray("links")?.length() ?: 0
            merge(mint) { it.copy(socialCount = maxOf(it.socialCount, links)) }
        }
    }

    private fun pollTakeovers() {
        val arr = getArray("$BASE/community-takeovers/latest/v1") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("chainId") != "solana") continue
            val mint = o.optString("tokenAddress").lowercase()
            if (mint.isBlank()) continue
            merge(mint) { it.copy(communityTakeover = true) }
        }
    }

    private inline fun merge(mint: String, f: (Social) -> Social) {
        val now = System.currentTimeMillis()
        val cur = cache[mint] ?: Social(0.0, 0, false, now)
        cache[mint] = f(cur).copy(ts = now)
        if (cache.size > 4000) {
            val cutoff = now - 30 * 60_000L
            cache.entries.removeIf { it.value.ts < cutoff }
        }
    }

    private fun getArray(url: String): JSONArray? {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json").build()
        val resp = HealthAwareHttp.execute(http, req, host = "dexscreener")
        return resp.use { r ->
            if (!r.isSuccessful) return null
            val body = r.body?.string().orEmpty()
            if (body.isBlank()) return null
            val trimmed = body.trimStart()
            if (trimmed.startsWith("[")) {
                JSONArray(body)
            } else if (trimmed.startsWith("{")) {
                JSONObject(body).optJSONArray("data") ?: JSONArray()
            } else {
                null
            }
        }
    }
}

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

    private val http: OkHttpClient = SharedHttpClient.builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Social(
        val boostTotal: Double,
        val socialCount: Int,
        val communityTakeover: Boolean,
        val ts: Long,
        /** V5.0.8809 — "Dex Paid": 1 = an approved paid order (profile, ad, takeover), 0 = asked and none, -1 = never asked. */
        val dexPaid: Int = -1,
        /** V5.0.8809 — when the earliest approved order was paid (ms; 0 when unknown). */
        val paidAtMs: Long = 0L,
    )

    private val cache = ConcurrentHashMap<String, Social>()

    /** V5.0.7895 — read-only peek for the Cortex (no refresh, no network). */
    fun peek7895(mint: String): Social? = cache[mint] ?: cache[mint.lowercase()]
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

    // ── V5.0.8809 §WHAT_THE_OWNER_READS_IN_THE_HEADER ──
    // Owner: he looks for "dex boosted coins ... with shout outs etc and chatter"; 14 of his 16 GMGN screenshots show Dex
    // Paid. The 5.0.8802 dataset carried boost / socials on 147 of 60,792 rows: this source only knew the three global
    // lists (top boosts, latest profiles, takeovers), refreshed when a symbol search happened to run. Two real sources:
    //  - every DexScreener pair the bot is already served (DexscreenerApi.parsePair: ~38,000 mints a session through the
    //    pair batch lane) carries its info.socials / info.websites and boosts.active — observed here, so a coin with none
    //    reads 0, not unknown ([observePair8809]);
    //  - "Dex Paid" is DexScreener's orders endpoint, one keyless call per coin (60 a minute allowed): asked for each coin
    //    a decision is being made on, newest first, paced, cached ([wantOrders8809]).
    private const val ORDER_GAP_MS_8809 = 1_500L
    private const val ORDER_QUEUE_MAX_8809 = 200
    private const val PAID_TTL_MS_8809 = 6L * 60 * 60_000L
    private const val UNPAID_TTL_MS_8809 = 5L * 60_000L
    private const val FAILED_TTL_MS_8809 = 2L * 60_000L
    private val orderQueue8809 = java.util.ArrayDeque<String>()
    private val orderAskedAt8809 = ConcurrentHashMap<String, Long>()
    private val orderWorker8809 = java.util.concurrent.atomic.AtomicBoolean(false)
    private val pairsObserved8809 = java.util.concurrent.atomic.AtomicLong()
    private val ordersAsked8809 = java.util.concurrent.atomic.AtomicLong()
    private val ordersAnswered8809 = java.util.concurrent.atomic.AtomicLong()
    private val ordersPaid8809 = java.util.concurrent.atomic.AtomicLong()
    private val ordersFailed8809 = java.util.concurrent.atomic.AtomicLong()
    private val ordersDropped8809 = java.util.concurrent.atomic.AtomicLong()

    /** DexscreenerApi.parsePair: one served Solana pair of [mint] with [links] social / website links and [boostsActive] active boosts. */
    fun observePair8809(mint: String, links: Int, boostsActive: Double) {
        if (mint.isBlank()) return
        pairsObserved8809.incrementAndGet()
        merge(mint.lowercase()) {
            it.copy(boostTotal = if (boostsActive.isFinite() && boostsActive > it.boostTotal) boostsActive else it.boostTotal,
                socialCount = maxOf(it.socialCount, links))
        }
    }

    /**
     * Pure: DexScreener orders answer (an array, or {orders:[...]}) -> (1 when any order is approved else 0, the earliest
     * approved payment time in ms or 0); null when the body is not an orders answer.
     */
    fun parseOrders8809(body: String?): Pair<Int, Long>? {
        val t = body?.trimStart() ?: return null
        val arr: JSONArray = try {
            when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> JSONObject(t).optJSONArray("orders")
                else -> null
            }
        } catch (_: Throwable) { null } ?: return null
        var paid = 0; var at = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optString("status").equals("approved", ignoreCase = true)) continue
            paid = 1
            val ts = o.optLong("paymentTimestamp", 0L)
            if (ts > 0L && (at == 0L || ts < at)) at = ts
        }
        return paid to at
    }

    /** Pure: is an orders answer for a coin asked at [askedAtMs] still current at [nowMs]? ([dexPaid] as in [Social]). */
    fun ordersFresh8809(dexPaid: Int, askedAtMs: Long, nowMs: Long): Boolean {
        if (askedAtMs <= 0L) return false
        val ttl = when (dexPaid) { 1 -> PAID_TTL_MS_8809; 0 -> UNPAID_TTL_MS_8809; else -> FAILED_TTL_MS_8809 }
        return nowMs - askedAtMs in 0L until ttl
    }

    /** A decision is being made on [mint]: its Dex Paid status is wanted (cheap; the newest ask is served first). */
    fun wantOrders8809(mint: String, nowMs: Long = System.currentTimeMillis()) {
        if (mint.length !in 32..44) return
        val key = mint.lowercase()
        if (ordersFresh8809(cache[key]?.dexPaid ?: -1, orderAskedAt8809[key] ?: 0L, nowMs)) return
        orderAskedAt8809[key] = nowMs
        if (orderAskedAt8809.size > 6_000) orderAskedAt8809.entries.removeIf { nowMs - it.value > PAID_TTL_MS_8809 }
        synchronized(orderQueue8809) {
            orderQueue8809.addFirst(mint)
            while (orderQueue8809.size > ORDER_QUEUE_MAX_8809) { orderQueue8809.removeLast(); ordersDropped8809.incrementAndGet() }
        }
        // the worker needs the running app (its network stack); a unit test has none
        if ((try { com.lifecyclebot.AATEApp.appContextOrNull() } catch (_: Throwable) { null }) == null) return
        if (!orderWorker8809.compareAndSet(false, true)) return
        try {
            Thread({ try { drainOrders8809() } catch (_: Throwable) {} finally { orderWorker8809.set(false) } }, "dex-orders-8809")
                .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        } catch (_: Throwable) { orderWorker8809.set(false) }
    }

    private fun drainOrders8809() {
        while (true) {
            val mint = synchronized(orderQueue8809) { orderQueue8809.pollFirst() } ?: return
            ordersAsked8809.incrementAndGet()
            val got = try { parseOrders8809(getBody8809("$BASE/orders/v1/solana/$mint")) } catch (_: Throwable) { null }
            if (got == null) ordersFailed8809.incrementAndGet()
            else {
                ordersAnswered8809.incrementAndGet()
                if (got.first == 1) ordersPaid8809.incrementAndGet()
                merge(mint.lowercase()) { it.copy(dexPaid = got.first, paidAtMs = got.second) }
            }
            Thread.sleep(ORDER_GAP_MS_8809)
        }
    }

    private fun getBody8809(url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        return HealthAwareHttp.execute(http, req, host = "dexscreener").use { r -> if (r.isSuccessful) r.body?.string() else null }
    }

    fun statusLine8809(): String = "dexSocial8809[cached=${cache.size} pairsObserved=${pairsObserved8809.get()} orders{asked=${ordersAsked8809.get()} " +
        "answered=${ordersAnswered8809.get()} paid=${ordersPaid8809.get()} failed=${ordersFailed8809.get()} queued=${synchronized(orderQueue8809) { orderQueue8809.size }} " +
        "dropped=${ordersDropped8809.get()}} rule=socials and boosts from every served pair; Dex Paid from the orders endpoint, one paced call per coin under decision]"

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

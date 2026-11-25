package com.lifecyclebot.network

import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.9016 §EVERYTHING_ELSE_DEXSCREENER_GIVES_FOR_FREE — owner, 25 Nov 20:13 / 20:17: "what other data can we pull from
 * dexscreener as an additive" ... "go on all of them".
 *
 * The bot already read DexScreener's pairs (price, pools, liquidity, volume, counts, age, cap), boosts, paid orders, new
 * profiles and community takeovers. docs.dexscreener.com/api/reference (read 25 Nov) lists five things it did not:
 *
 *   /metas/trending/v1            the narratives trending now: cap, liquidity, volume, token count and the change of the
 *                                 narrative's cap over 5 m / 1 h / 6 h / 24 h                               (60 calls a minute)
 *   /metas/meta/v1/{slug}         one narrative with every pair in it                                       (60 a minute)
 *   /ads/latest/v1                paid ads: type, duration (h), impressions, date                           (60 a minute)
 *   /token-profiles/recent-updates/v1   profiles just edited (a team still working on its coin)             (60 a minute)
 *   the pair object's "labels"    the pool's type tag (CPMM / CLMM / DLMM / v2 / v3 ...)                    (already in every pair answer)
 *   wss://api.dexscreener.com     the same list feeds pushed (profiles, updates, takeovers, ads, boosts); the page states no
 *                                 authentication, rate or connection limit for it
 *
 * Why the narrative ones matter: the decision dataset has carried narrHeat / narrExhaust since 8798 and 685 of 108,069 rows
 * have a value, because their source keys a narrative by SYMBOL from the bot's own trades. DexScreener groups by mint.
 *
 * What this does: one paced refresher (its own daemon thread, the shared "dexscreener" pacer, its own health label, and
 * HostCircuitInterceptor already cools each of these endpoints down on its own, apart from the pair lookups) keeps
 *  - the trending narratives every 5 minutes, and the pairs of the [TOP_METAS] with the fastest-rising cap over the hour
 *    and the [COLD_METAS] with the fastest-falling, so a Solana mint maps to the hottest narrative it sits in;
 *  - the latest ads and the recently updated profiles every 2 minutes, or as the websocket pushes them: two sockets only
 *    (ads, recent updates), a reconnect at most once a minute, and the poll simply carries on while a socket is quiet;
 *  - the label of each coin's data pool, noted from pair answers the bot already receives (no extra call).
 * LearnerColumns8798 appends seven columns from it (metaHeat1h, metaHeat6h, metaTokens, dexAd, dexAdImpr,
 * profileUpdAgeMin, poolLabel): the dataset, the tree and the coverage report follow, as that file is built to do. Nothing
 * here refuses, sizes or sells anything; an unobserved value is NaN, never 0. (DexScreenerWebSocket, the older
 * io.dexscreener.com price socket, is a different, undocumented feed and stays as it is: off since 7381.)
 */
object DexExtras9016 {
    private const val BASE = "https://api.dexscreener.com"
    private const val WS = "wss://api.dexscreener.com"
    private const val METAS_EVERY_MS = 5L * 60_000L
    private const val LISTS_EVERY_MS = 2L * 60_000L
    private const val META_FRESH_MS = 30L * 60_000L
    private const val LIST_FRESH_MS = 10L * 60_000L
    private const val UPD_KEEP_MS = 24L * 3_600_000L
    private const val WS_RETRY_MS = 60_000L
    private const val WS_RETRY_MAX_MS = 30L * 60_000L
    private const val WS_QUIET_MS = 2L * 60_000L
    internal const val TOP_METAS = 8
    internal const val COLD_METAS = 4
    /** Its own health label: a refusal here is never written against the pair lookups that price coins ("dexscreener"). */
    internal const val HEALTH_HOST = "dexscreener_extras"
    private const val MAX_KEYS = 6_000

    data class Meta(val slug: String, val name: String, val capChgH1: Double, val capChgH6: Double, val tokens: Int, val atMs: Long)
    data class Ad(val type: String, val durationHours: Double, val impressions: Double, val dateMs: Long, val seenMs: Long)

    private val http: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }
    /** The push feeds' own client: a 30 s ping so a silent dead socket fails and is re-opened; no read timeout. */
    private val wsHttp: OkHttpClient by lazy {
        SharedHttpClient.builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    }
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "dex-extras-9016").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } } }
    private val busy = AtomicBoolean(false)

    private val metas = ConcurrentHashMap<String, Meta>()
    private val mintMeta = ConcurrentHashMap<String, String>()
    private val ads = ConcurrentHashMap<String, Ad>()
    private val profileUpd = ConcurrentHashMap<String, Long>()
    private val labels = ConcurrentHashMap<String, String>()
    // "tried" paces the poll (a failed read is asked again at the same pace, never at once); "at" is the last ANSWER
    @Volatile private var metasTriedMs = 0L
    @Volatile private var adsTriedMs = 0L
    @Volatile private var updTriedMs = 0L
    @Volatile private var metasAtMs = 0L
    @Volatile private var adsAtMs = 0L
    @Volatile private var updAtMs = 0L
    @Volatile private var adsPushAtMs = 0L
    @Volatile private var updPushAtMs = 0L
    @Volatile private var wsTriedAtMs = 0L
    @Volatile private var wsFails = 0
    @Volatile private var adsSocket: WebSocket? = null
    @Volatile private var updSocket: WebSocket? = null
    private val calls = AtomicLong(); private val failed = AtomicLong(); private val pushes = AtomicLong()

    // ── pure parsers ──

    private fun num(o: JSONObject?, k: String): Double = if (o == null || o.isNull(k)) Double.NaN else o.optDouble(k, Double.NaN)
    private fun rows(body: String?): JSONArray? {
        val t = body?.trim().orEmpty()
        return try {
            when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> JSONObject(t).let { it.optJSONArray("data") ?: it.optJSONArray("metas") }
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    /** Pure: the trending-narratives answer as metas (a narrative without a slug is skipped). */
    fun parseMetas9016(body: String?, nowMs: Long): List<Meta> {
        val a = rows(body) ?: return emptyList()
        val out = ArrayList<Meta>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val slug = o.optString("slug").trim()
            if (slug.isBlank()) continue
            val chg = o.optJSONObject("marketCapChange")
            out += Meta(slug, o.optString("name").take(40), num(chg, "h1"), num(chg, "h6"), o.optInt("tokenCount", 0), nowMs)
        }
        return out
    }

    /** Pure: the Solana base mints among one narrative's pairs. */
    fun parseMetaMints9016(body: String?): List<String> = try {
        val pairs = JSONObject(body.orEmpty()).optJSONArray("pairs")
        if (pairs == null) emptyList() else (0 until pairs.length()).mapNotNull { i ->
            val p = pairs.optJSONObject(i) ?: return@mapNotNull null
            if (!p.optString("chainId").equals("solana", true)) return@mapNotNull null
            p.optJSONObject("baseToken")?.optString("address")?.trim()?.takeIf { it.length >= 30 }
        }.distinct()
    } catch (_: Throwable) { emptyList() }

    private fun isoMs(s: String): Long = try { java.time.Instant.parse(s).toEpochMilli() } catch (_: Throwable) { 0L }

    /** Pure: the ads answer (an array, or {data:[...]} as the websocket sends it) as mint -> ad, Solana only. */
    fun parseAds9016(body: String?, nowMs: Long): Map<String, Ad> {
        val a = rows(body) ?: return emptyMap()
        val out = HashMap<String, Ad>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (!o.optString("chainId").equals("solana", true)) continue
            val mint = o.optString("tokenAddress").trim()
            if (mint.length < 30) continue
            out[mint] = Ad(o.optString("type").take(24), num(o, "durationHours"), num(o, "impressions"), isoMs(o.optString("date")), nowMs)
        }
        return out
    }

    /** Pure: the Solana mints of a profiles answer (array or {data:[...]}). */
    fun parseProfiles9016(body: String?): List<String> {
        val a = rows(body) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (!o.optString("chainId").equals("solana", true)) null else o.optString("tokenAddress").trim().takeIf { it.length >= 30 }
        }.distinct()
    }

    /** Pure: is an ad seen at [ad].seenMs still running at [nowMs]? Its own duration from its date when both are known, else a day from when it was seen. */
    fun adLive9016(ad: Ad, nowMs: Long): Boolean =
        if (ad.dateMs > 0L && ad.durationHours.isFinite() && ad.durationHours > 0.0) nowMs - ad.dateMs in 0L..(ad.durationHours * 3_600_000.0).toLong()
        else nowMs - ad.seenMs in 0L..UPD_KEEP_MS

    /** Pure: a pool's type tag as a number for the learner (NaN when the pair carried none). */
    fun labelCode9016(label: String?): Double = when (label?.trim()?.uppercase().orEmpty()) {
        "" -> Double.NaN
        "CPMM" -> 1.0; "CLMM" -> 2.0; "DLMM" -> 3.0; "DYN", "DYN2" -> 4.0; "V2" -> 5.0; "V3" -> 6.0; "WP" -> 7.0; "V4" -> 8.0
        else -> 9.0
    }

    /** Pure: the [n] narratives with the fastest-rising cap over the hour (an unread change sorts last). */
    fun hottest9016(all: Collection<Meta>, n: Int = TOP_METAS): List<Meta> =
        all.sortedByDescending { if (it.capChgH1.isFinite()) it.capChgH1 else Double.NEGATIVE_INFINITY }.take(n.coerceAtLeast(0))

    /**
     * Pure: the narratives whose coins are fetched, coldest first: the [cold] with the fastest-falling cap over the hour, then
     * the [n] fastest-rising from the least to the most (so a coin in two keeps the hotter one when written in this order).
     * A coin in a narrative that is being sold is as much a fact as one in a narrative being bought.
     */
    fun watched9016(all: Collection<Meta>, n: Int = TOP_METAS, cold: Int = COLD_METAS): List<Meta> {
        val hot = hottest9016(all, n)
        val coldest = all.filter { it.capChgH1.isFinite() && it !in hot }.sortedBy { it.capChgH1 }.take(cold.coerceAtLeast(0))
        return coldest + hot.reversed()
    }

    // ── reads (no network) ──

    fun metaOf9016(mint: String, nowMs: Long): Meta? = mintMeta[mint]?.let { metas[it] }?.takeIf { nowMs - it.atMs in 0L..META_FRESH_MS }
    fun adsFresh9016(nowMs: Long): Boolean = maxOf(adsAtMs, adsPushAtMs).let { it > 0L && nowMs - it in 0L..LIST_FRESH_MS }
    fun adOf9016(mint: String, nowMs: Long): Ad? = ads[mint]?.takeIf { adLive9016(it, nowMs) }
    fun profileUpdAgeMin9016(mint: String, nowMs: Long): Double = profileUpd[mint]?.let { if (nowMs >= it) (nowMs - it) / 60_000.0 else Double.NaN } ?: Double.NaN
    fun labelOf9016(mint: String): Double = labelCode9016(labels[mint])

    /** DexscreenerApi's pair batch: the labels of [mint]'s chosen data pool (the first one names its type). */
    fun noteLabels9016(mint: String, arr: JSONArray?) {
        if (mint.isBlank() || arr == null || arr.length() == 0) return
        val l = arr.optString(0).trim()
        if (l.isBlank()) return
        if (labels.size > MAX_KEYS) labels.clear()
        labels[mint] = l.take(12)
    }

    // ── refresh ──

    private fun body(path: String): String? {
        if (!com.lifecyclebot.engine.RateLimiter.waitForSlot("dexscreener", 2_000L)) return null
        calls.incrementAndGet()
        val got: String? = try {
            val req = Request.Builder().url(BASE + path).header("Accept", "application/json").build()
            HealthAwareHttp.execute(http, req, host = HEALTH_HOST).use { r -> if (r.isSuccessful) r.body?.string() else null }
        } catch (_: Throwable) { null }
        if (got == null) failed.incrementAndGet()
        return got
    }

    private fun applyAds(m: Map<String, Ad>) { if (ads.size > MAX_KEYS) ads.clear(); ads.putAll(m) }
    private fun applyUpd(mints: List<String>, nowMs: Long) {
        if (profileUpd.size > MAX_KEYS) profileUpd.entries.removeIf { nowMs - it.value > UPD_KEEP_MS }
        if (profileUpd.size > MAX_KEYS) profileUpd.clear()
        for (m in mints) profileUpd.putIfAbsent(m, nowMs)   // first seen in the feed is when it was updated
    }

    /** LearnerColumns8798.extract: refresh whatever is due, off the caller's thread; one pass at a time. */
    fun tick9016(nowMs: Long = System.currentTimeMillis()) {
        val due = nowMs - metasTriedMs >= METAS_EVERY_MS || nowMs - adsTriedMs >= LISTS_EVERY_MS || nowMs - updTriedMs >= LISTS_EVERY_MS
        if (!due) return
        // the refresher needs the running app (its network stack); a unit test has none
        if ((try { com.lifecyclebot.AATEApp.appContextOrNull() } catch (_: Throwable) { null }) == null) return
        if (!busy.compareAndSet(false, true)) return
        try { io.execute { try { pass(System.currentTimeMillis()) } catch (_: Throwable) {} finally { busy.set(false) } } } catch (_: Throwable) { busy.set(false) }
    }

    private fun pass(nowMs: Long) {
        if (nowMs - metasTriedMs >= METAS_EVERY_MS) {
            metasTriedMs = nowMs
            val list = parseMetas9016(body("/metas/trending/v1"), nowMs)
            if (list.isNotEmpty()) {
                metasAtMs = nowMs
                metas.clear(); list.forEach { metas[it.slug] = it }
                val fresh = HashMap<String, String>()
                for (m in watched9016(list)) for (mint in parseMetaMints9016(body("/metas/meta/v1/" + java.net.URLEncoder.encode(m.slug, "UTF-8")))) fresh[mint] = m.slug
                if (fresh.isNotEmpty()) { mintMeta.clear(); mintMeta.putAll(fresh) }
                try { PipelineHealthCollector.labelInc("DEX_EXTRAS_METAS_REFRESHED_9016") } catch (_: Throwable) {}
            }
        }
        if (nowMs - adsTriedMs >= LISTS_EVERY_MS) {
            adsTriedMs = nowMs
            if (nowMs - adsPushAtMs > WS_QUIET_MS) body("/ads/latest/v1")?.let { applyAds(parseAds9016(it, nowMs)); adsAtMs = nowMs }
        }
        if (nowMs - updTriedMs >= LISTS_EVERY_MS) {
            updTriedMs = nowMs
            if (nowMs - updPushAtMs > WS_QUIET_MS) body("/token-profiles/recent-updates/v1")?.let { applyUpd(parseProfiles9016(it), nowMs); updAtMs = nowMs }
        }
        sockets(nowMs)
    }

    /** Pure: the wait before the next socket open after [fails] opens in a row that never delivered a message: 1, 2, 4 ... 30 minutes. */
    fun wsRetryMs9016(fails: Int): Long = (WS_RETRY_MS shl fails.coerceIn(0, 10)).coerceAtMost(WS_RETRY_MAX_MS)

    /**
     * The two push feeds: opened once, and after a close or failure re-opened no sooner than [wsRetryMs9016] (a minute, doubling
     * to 30 while no message has ever arrived, so a feed that refuses the handshake is not knocked on every minute). The
     * poll never depends on them.
     */
    private fun sockets(nowMs: Long) {
        if (adsSocket != null && updSocket != null) return
        if (nowMs - wsTriedAtMs < wsRetryMs9016(wsFails)) return
        wsTriedAtMs = nowMs
        wsFails = (wsFails + 1).coerceAtMost(10)   // cleared by the first message either socket delivers
        if (adsSocket == null) connect("/ads/latest/v1", { t -> val now = System.currentTimeMillis(); applyAds(parseAds9016(t, now)); adsPushAtMs = now }) { adsSocket = it }
        if (updSocket == null) connect("/token-profiles/recent-updates/v1", { t -> val now = System.currentTimeMillis(); applyUpd(parseProfiles9016(t), now); updPushAtMs = now }) { updSocket = it }
    }

    /** Opens one feed and hands the socket to [hold]; a close or failure hands it null, also when that lands before the socket was held. */
    private fun connect(path: String, onText: (String) -> Unit, hold: (WebSocket?) -> Unit) {
        val gone = AtomicBoolean(false)
        val lost = { gone.set(true); hold(null) }
        val ws = try {
            wsHttp.newWebSocket(Request.Builder().url(WS + path).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { try { pushes.incrementAndGet(); wsFails = 0; onText(text) } catch (_: Throwable) {} }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { try { webSocket.close(1000, null) } catch (_: Throwable) {}; lost() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost() }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lost() }
            })
        } catch (_: Throwable) { null }
        hold(ws)
        if (gone.get()) hold(null)
    }

    fun statusLine9016(): String {
        val now = System.currentTimeMillis()
        fun ago(t: Long) = if (t > 0L) "${(now - t) / 1000}s" else "never"
        val hot = hottest9016(metas.values, 3).joinToString(",") { "${it.slug.take(16)}:${if (it.capChgH1.isFinite()) "%+.0f".format(it.capChgH1) else "?"}%" }.ifBlank { "-" }
        return "dexExtras9016[metas=${metas.size} mintsMapped=${mintMeta.size} hottest1h{$hot} ads=${ads.size} profileUpdates=${profileUpd.size} " +
            "poolLabels=${labels.size} calls=${calls.get()} failed=${failed.get()} pushes=${pushes.get()} sockets{ads=${adsSocket != null},updates=${updSocket != null}} " +
            "socketOpensUnanswered=$wsFails answered{metas=${ago(metasAtMs)},ads=${ago(maxOf(adsAtMs, adsPushAtMs))},updates=${ago(maxOf(updAtMs, updPushAtMs))}}]"
    }
}

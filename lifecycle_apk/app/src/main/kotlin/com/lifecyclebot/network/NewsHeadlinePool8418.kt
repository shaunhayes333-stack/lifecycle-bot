package com.lifecyclebot.network

import com.lifecyclebot.engine.HealthAwareHttp
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * V5.0.8418 — market headline mood from free, keyless publisher RSS feeds, pooled so their budgets add up.
 *
 * Owner: "theres a heap of free keyless ... ai data apis we could be using ... daisy chain to build a bigger power
 * source". Three crypto newsrooms publish their headlines as public RSS (no key, no account): CoinDesk, Cointelegraph,
 * Decrypt. Each is its own [CapacityBucket8418]; a refresh reads every feed that has budget, a feed that fails or is
 * paused is skipped without touching the others, and the headlines are merged and de-duplicated.
 *
 * ADVISORY ONLY. The reading is a market mood for display and for soft context; it never blocks, never sizes, and
 * never reaches a hard safety gate or FinalDecisionGate. Too few scored headlines (or none fetched, or stale) is
 * UNKNOWN — never a default number. Only headline titles are read; nothing is republished.
 */
object NewsHeadlinePool8418 {

    data class Feed8418(val name: String, val url: String)

    enum class Mood8418 { BULLISH, BEARISH, MIXED, UNKNOWN }

    data class Reading8418(
        val mood: Mood8418,
        val bullish: Int,
        val bearish: Int,
        val headlines: Int,
        val solanaHeadlines: Int,
        val sources: List<String>,
        val atMs: Long,
    )

    private val FEEDS_8418: List<Feed8418> = listOf(
        Feed8418("coindesk", "https://www.coindesk.com/arc/outboundfeeds/rss/"),
        Feed8418("cointelegraph", "https://cointelegraph.com/rss"),
        Feed8418("decrypt", "https://decrypt.co/feed"),
    )

    private const val REFRESH_MS_8418 = 10L * 60_000L
    const val STALE_MS_8418 = 60L * 60_000L
    private const val MIN_SCORED_8418 = 4

    private val UNKNOWN_8418 = Reading8418(Mood8418.UNKNOWN, 0, 0, 0, 0, emptyList(), 0L)

    /** Word-start matched (so "ban" never fires on "bank", "rug" never on "drug"); stems end where noted. */
    private val BULL_8418 = Regex(
        "\\b(approv(e|es|ed|al)|surg(e|es|ed|ing)|soar(s|ed|ing)?|rall(y|ies|ied)|record high|all-time high|inflows?|" +
            "adopt(s|ed|ion)?|upgrades?|partnership|bullish|gains|jumps?|climbs?|rebound(s|ed)?)\\b",
    )
    private val BEAR_8418 = Regex(
        "\\b(hack(s|ed|er|ers)?|exploit(s|ed)?|drained|lawsuit|sues|charged|bans?|banned|crash(es|ed)?|plung(e|es|ed)|" +
            "tumbl(e|es|ed)|slump(s|ed)?|outflows?|liquidat\\w*|bankrupt\\w*|delist\\w*|outage|halt(s|ed)?|fraud|bearish|" +
            "sell-?off|rug pull|rugged)\\b",
    )

    private fun freshBuckets8418(): Map<String, CapacityBucket8418> =
        FEEDS_8418.associate { it.name to CapacityBucket8418("news_${it.name}", 2, 1) }

    @Volatile private var buckets: Map<String, CapacityBucket8418> = freshBuckets8418()

    @Volatile private var latest: Reading8418 = UNKNOWN_8418
    @Volatile private var lastAttemptMs: Long = 0L

    private val http by lazy {
        SharedHttpClient.builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    /** Pure: the item titles of an RSS 2.0 / Atom document (CDATA and entities unwrapped), channel title excluded. */
    fun parseTitles8418(xml: String): List<String> {
        val out = ArrayList<String>()
        val item = Regex("<(item|entry)\\b[\\s\\S]*?</(item|entry)>", RegexOption.IGNORE_CASE)
        val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
        for (m in item.findAll(xml)) {
            val raw = title.find(m.value)?.groupValues?.getOrNull(1) ?: continue
            val t = raw.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&#39;", "'").replace("&apos;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    /** Pure: the mood of a set of headlines. Fewer than four scored headlines is UNKNOWN. */
    fun score8418(titles: List<String>, sources: List<String>, nowMs: Long): Reading8418 {
        var bull = 0
        var bear = 0
        var sol = 0
        for (t in titles) {
            val low = t.lowercase()
            val b = BULL_8418.containsMatchIn(low)
            val r = BEAR_8418.containsMatchIn(low)
            if (b && !r) bull++
            if (r && !b) bear++
            if (low.contains("solana") || Regex("\\bsol\\b").containsMatchIn(low)) sol++
        }
        val scored = bull + bear
        val mood = when {
            titles.isEmpty() || scored < MIN_SCORED_8418 -> Mood8418.UNKNOWN
            (bull - bear).toDouble() / scored > 0.25 -> Mood8418.BULLISH
            (bear - bull).toDouble() / scored > 0.25 -> Mood8418.BEARISH
            else -> Mood8418.MIXED
        }
        return Reading8418(mood, bull, bear, titles.size, sol, sources, nowMs)
    }

    /**
     * Reads every feed that has budget (in health order), merges the titles, scores them. A failed feed releases
     * only its own bucket. [fetch] is injectable for tests; the default goes through HealthAwareHttp (own row each).
     */
    fun refresh8418(nowMs: Long, fetch: (Feed8418) -> String? = { f -> get8418(f) }): Reading8418 {
        lastAttemptMs = nowMs
        val ordered = FEEDS_8418.sortedByDescending { buckets[it.name]?.weight(nowMs) ?: 0.0 }
        val seen = LinkedHashSet<String>()
        val used = ArrayList<String>()
        for (f in ordered) {
            val b = buckets[f.name] ?: continue
            if (!b.tryAcquire(nowMs)) continue
            val t0 = System.currentTimeMillis()
            val body = try { fetch(f) } catch (_: Throwable) { null }
            val titles = if (body.isNullOrBlank()) emptyList() else parseTitles8418(body)
            val outcome = if (titles.isEmpty()) CapacityBucket8418.Outcome8418.EMPTY else CapacityBucket8418.Outcome8418.OK
            b.release(nowMs, outcome, System.currentTimeMillis() - t0)
            if (titles.isEmpty()) continue
            used.add(f.name)
            for (t in titles) seen.add(t)
        }
        val reading = score8418(seen.toList(), used, nowMs)
        if (reading.headlines > 0) latest = reading
        try { PipelineHealthCollector.labelInc("NEWS_POOL_8418_${reading.mood.name}") } catch (_: Throwable) {}
        return reading
    }

    /** Refreshes when due (call off the main thread), then answers [latest8418]. */
    fun refreshIfDue8418(nowMs: Long = System.currentTimeMillis()): Reading8418 {
        if (nowMs - lastAttemptMs >= REFRESH_MS_8418) {
            try { refresh8418(nowMs) } catch (_: Throwable) {}
        }
        return latest8418(nowMs)
    }

    /** The last reading, or UNKNOWN when none was fetched or it is older than an hour. */
    fun latest8418(nowMs: Long = System.currentTimeMillis()): Reading8418 {
        val r = latest
        return if (r.atMs <= 0L || nowMs - r.atMs > STALE_MS_8418) UNKNOWN_8418 else r
    }

    fun statusLine8418(nowMs: Long = System.currentTimeMillis()): String {
        val r = latest8418(nowMs)
        val cells = FEEDS_8418.joinToString(" · ") { buckets[it.name]?.cell(nowMs) ?: it.name }
        return "newsPool8418: mood=${r.mood.name} bull=${r.bullish} bear=${r.bearish} headlines=${r.headlines} " +
            "solana=${r.solanaHeadlines} from=${r.sources.joinToString(",").ifEmpty { "none" }} · $cells"
    }

    private fun get8418(f: Feed8418): String? = try {
        val req = Request.Builder().url(f.url)
            .header("User-Agent", "lifecycle-bot-android/6.0")
            .header("Accept", "application/rss+xml, application/xml;q=0.9, */*;q=0.5")
            .get().build()
        HealthAwareHttp.execute(http, req, host = "news_${f.name}").use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) { null }

    fun resetForTests8418() {
        latest = UNKNOWN_8418
        lastAttemptMs = 0L
        buckets = freshBuckets8418()
    }
}

package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * V5.0.8128 — the holder count's producer.
 *
 * Audit: Candle.holderCount had no producer, so ts.peakHolderCount, holderGrowthRate and every
 * lane / scorer reading history.last().holderCount saw 0 on every token. Jupiter Tokens v2 carries
 * `holderCount` per token: the market sweep already parses it into Row.holders, and a mint the sweep
 * did not list is asked through `/tokens/v2/search` — once per mint per 10 min, at most one request
 * every 3 s overall (the same host serves the sweep's lists). No answer stays null, never a guess.
 */
object JupiterHolders8128 {
    private const val SEARCH_URL_8128 = "https://lite-api.jup.ag/tokens/v2/search?query="
    const val REFRESH_MS_8128 = 10L * 60_000L
    private const val MIN_SPACING_MS_8128 = 3_000L

    private class Entry8128(val holders: Int?, val atMs: Long)
    private val cache8128 = ConcurrentHashMap<String, Entry8128>()
    private val askedAt8128 = ConcurrentHashMap<String, Long>()
    @Volatile private var lastRequestAtMs8128 = 0L

    private val http = SharedHttpClient.builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val worker8128: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            1, 1, 30L, TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(16),
            { r -> Thread(r, "jup-holders-8128").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        )
    }

    /** Pure: [mint]'s holderCount in a `/tokens/v2/search` body; null when absent or not positive. */
    fun parseHolders8128(body: String?, mint: String): Int? {
        if (body.isNullOrBlank() || mint.isBlank()) return null
        val arr = try { JSONArray(body.trim()) } catch (_: Throwable) { return null }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id", "") != mint) continue
            return o.optInt("holderCount", 0).takeIf { it > 0 }
        }
        return null
    }

    /** Pure: a mint may be asked again only [REFRESH_MS_8128] after its last ask, and the host only every 3 s. */
    fun askDue8128(lastAskMs: Long?, lastAnyRequestMs: Long, nowMs: Long): Boolean =
        (lastAskMs == null || nowMs - lastAskMs >= REFRESH_MS_8128) && nowMs - lastAnyRequestMs >= MIN_SPACING_MS_8128

    /**
     * [mint]'s holder count: the latest market sweep row first, else this cache. Never blocks; a mint
     * neither answers is asked in the background (paced as above) and served on a later call.
     */
    fun holders8128(mint: String, nowMs: Long = System.currentTimeMillis()): Int? {
        if (mint.length < 32) return null
        val swept = try { com.lifecyclebot.engine.market.MarketSweep7297.holdersFor8128(mint, nowMs) } catch (_: Throwable) { null }
        if (swept != null) return swept
        val e = cache8128[mint]
        if (e == null || nowMs - e.atMs >= REFRESH_MS_8128) requestAsync8128(mint, nowMs)
        if (e == null || nowMs - e.atMs >= 3 * REFRESH_MS_8128) return null
        return e.holders
    }

    private fun requestAsync8128(mint: String, nowMs: Long) {
        if (!askDue8128(askedAt8128[mint], lastRequestAtMs8128, nowMs)) return
        askedAt8128[mint] = nowMs
        lastRequestAtMs8128 = nowMs
        try {
            worker8128.execute {
                val h = parseHolders8128(get8128(SEARCH_URL_8128 + mint), mint)
                cache8128[mint] = Entry8128(h, System.currentTimeMillis())
                try { PipelineHealthCollector.labelInc(if (h == null) "JUP_HOLDERS_MISS_8128" else "JUP_HOLDERS_HIT_8128") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
            askedAt8128.remove(mint)   // shed: nothing reached the wire
        }
        if (askedAt8128.size > 5_000) {
            askedAt8128.entries.removeIf { nowMs - it.value > REFRESH_MS_8128 }
            cache8128.entries.removeIf { nowMs - it.value.atMs > 3 * REFRESH_MS_8128 }
        }
    }

    private fun get8128(url: String): String? = try {
        val req = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AATE")
            .build()
        http.newCall(req).execute().use { resp ->
            if (HostCircuitInterceptor.isSyntheticBlock(resp) || !resp.isSuccessful) {
                try { PipelineHealthCollector.labelInc("JUP_HOLDERS_8128_HTTP_${resp.code}") } catch (_: Throwable) {}
                null
            } else resp.body?.string()
        }
    } catch (e: Exception) {
        ErrorLogger.debug("JupiterHolders8128", "search failed: ${e.message}")
        null
    }
}

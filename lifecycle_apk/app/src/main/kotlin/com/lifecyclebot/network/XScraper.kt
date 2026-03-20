package com.lifecyclebot.network

import com.lifecyclebot.data.MentionEvent
import com.lifecyclebot.engine.SentimentAnalyzer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Free X (Twitter) mention scraper — no API key required.
 *
 * Uses two free public endpoints:
 *
 * 1. Twitter's own syndication API (used by Twitter embed widgets):
 *    https://syndication.twitter.com/srv/timeline-profile/screen-name/{handle}
 *    Returns public tweets in JSON. No auth. Rate limited but usable for polling.
 *
 * 2. Nitter public instances (open-source Twitter frontend):
 *    https://nitter.privacydev.net/search?q={query}&f=tweets
 *    Returns HTML we parse for tweet text. No auth.
 *
 * Limitations:
 *   - Rate limited (~1 req/min per endpoint is safe)
 *   - Only public accounts/tweets
 *   - No DMs, private accounts
 *   - Nitter instances may go down — we try multiple
 *
 * For higher volume: upgrade to X Basic API ($100/mo) and replace
 * searchBySymbol() with the v2 recent search endpoint.
 */
class XScraper {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Multiple Nitter instances — try each until one works
    private val nitterHosts = listOf(
        "https://nitter.privacydev.net",
        "https://nitter.poast.org",
        "https://nitter.1d4.us",
    )

    // Known high-signal Solana/crypto X accounts to monitor
    private val solanaWatchAccounts = listOf(
        "pumpdotfun",
        "solana",
        "DegenCryptoNews",
        "SolanaFloor",
        "solanalegend",
    )

    /**
     * Search recent tweets mentioning this token symbol or address.
     * Returns MentionEvents with sentiment scored.
     */
    fun searchBySymbol(symbol: String, mintAddress: String): List<MentionEvent> {
        val events = mutableListOf<MentionEvent>()

        // Search both symbol and first 8 chars of mint address
        val queries = listOf(
            symbol.take(10),
            mintAddress.take(8),
        ).filter { it.length >= 3 }

        for (query in queries) {
            events.addAll(searchNitter(query))
            Thread.sleep(200) // small gap between requests
        }

        return events.distinctBy { it.text }.take(50)
    }

    /**
     * Scrape Nitter search results for a query.
     * Parses the HTML response to extract tweet text.
     */
    private fun searchNitter(query: String): List<MentionEvent> {
        for (host in nitterHosts) {
            try {
                val url  = "$host/search?q=${encode(query)}&f=tweets"
                val html = get(url) ?: continue
                return parseNitterHtml(html)
            } catch (_: Exception) {
                continue  // try next instance
            }
        }
        return emptyList()
    }

    /**
     * Parse Nitter HTML to extract tweet texts and timestamps.
     * Nitter uses consistent CSS classes we can regex for.
     */
    private fun parseNitterHtml(html: String): List<MentionEvent> {
        val events  = mutableListOf<MentionEvent>()
        val now     = System.currentTimeMillis()

        // Extract tweet content between <div class="tweet-content ..."> tags
        val tweetRegex = Regex(
            """<div class="tweet-content[^"]*"[^>]*>(.*?)</div>""",
            RegexOption.DOT_MATCHES_ALL
        )
        // Extract timestamps from <span class="tweet-date"> ... title="..." >
        val timeRegex = Regex("""title="([^"]+)"""")

        val tweetMatches = tweetRegex.findAll(html).toList()

        for (match in tweetMatches.take(30)) {
            // Strip HTML tags from tweet content
            val rawText = match.groupValues[1]
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (rawText.length < 10) continue

            val scored = SentimentAnalyzer.scoreText(rawText)

            // Approximate timestamp — Nitter doesn't always expose it cleanly
            // Use current time minus an estimated offset based on position
            val approxTs = now - (events.size * 2 * 60_000L)  // ~2 min apart

            events.add(MentionEvent(
                source    = "x",
                ts        = approxTs,
                sentiment = scored.score,
                text      = rawText.take(280),
            ))

            // If hard block found, return immediately with just this event
            if (scored.hardBlock) return events
        }

        return events
    }

    /**
     * Optional: monitor specific Solana accounts for token mentions.
     * Uses Twitter syndication API (no key needed).
     */
    fun checkSolanaAccounts(symbol: String): List<MentionEvent> {
        val events = mutableListOf<MentionEvent>()
        for (account in solanaWatchAccounts.take(3)) {
            try {
                val url  = "https://syndication.twitter.com/srv/timeline-profile/screen-name/$account"
                val body = get(url) ?: continue
                events.addAll(parseSyndicationTimeline(body, symbol))
                Thread.sleep(300)
            } catch (_: Exception) {
                continue
            }
        }
        return events
    }

    private fun parseSyndicationTimeline(body: String, filterSymbol: String): List<MentionEvent> {
        val events = mutableListOf<MentionEvent>()
        val now    = System.currentTimeMillis()
        try {
            // Syndication response embeds JSON in a script tag
            val jsonMatch = Regex("""window\.__INITIAL_STATE__\s*=\s*(\{.+?\});""",
                setOf(RegexOption.DOT_MATCHES_ALL)).find(body)
            val jsonStr = jsonMatch?.groupValues?.get(1) ?: return emptyList()
            val json    = JSONObject(jsonStr)

            // Navigate to tweets array — structure varies but usually under .timeline.entries
            val timeline = json.optJSONObject("timeline") ?: return emptyList()
            val entries  = timeline.optJSONArray("entries") ?: return emptyList()

            for (i in 0 until minOf(entries.length(), 20)) {
                val entry   = entries.optJSONObject(i) ?: continue
                val content = entry.optJSONObject("content")
                    ?.optJSONObject("itemContent")
                    ?.optJSONObject("tweet_results")
                    ?.optJSONObject("result")
                    ?.optJSONObject("legacy") ?: continue

                val text = content.optString("full_text", "")
                if (text.isBlank()) continue
                if (!text.lowercase().contains(filterSymbol.lowercase())) continue

                val scored = SentimentAnalyzer.scoreText(text)
                events.add(MentionEvent("x", now - (i * 3 * 60_000L), scored.score, text.take(280)))
            }
        } catch (_: Exception) {}
        return events
    }

    private fun get(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; bot/1.0)")
            .header("Accept",     "text/html,application/json")
            .build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }

    private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

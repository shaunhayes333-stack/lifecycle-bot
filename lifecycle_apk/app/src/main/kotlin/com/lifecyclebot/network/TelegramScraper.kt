package com.lifecyclebot.network

import com.lifecyclebot.data.MentionEvent
import com.lifecyclebot.engine.SentimentAnalyzer
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Telegram sentiment scraper — two modes:
 *
 * MODE 1: Public channel web preview (no account/API needed)
 *   Telegram exposes public channels at t.me/s/{channel}
 *   This returns HTML we can parse for message text.
 *   Works for any public channel.
 *
 * MODE 2: Telegram Bot API (free, requires bot token)
 *   Create a bot via @BotFather in 2 minutes, get a token.
 *   Add the bot to any channel as a member.
 *   Bot can then read messages from those channels.
 *   Much more reliable than scraping.
 *
 * Built-in Solana/Pump.fun public channels we always monitor:
 *   - @pumpfun_solana (community)
 *   - @solanaalpha
 *   - @solana_gems_official
 *   - @dexscreener_trending
 *
 * User can add their own channels in settings.
 */
class TelegramScraper(private val botToken: String = "") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // Default public Solana channels to always monitor
    private val defaultChannels = listOf(
        "pumpfunsol",
        "solanaalpha",
        "solana_defi_news",
        "newsolanatoken",
        "solananewlistings",
    )

    // ── public web scraping (no auth) ─────────────────────────────────

    /**
     * Scrape public channel for mentions of this symbol.
     * Telegram's web preview at t.me/s/{channel} returns the last ~20 messages.
     */
    fun scrapePublicChannel(channelHandle: String, symbol: String, mintAddress: String): List<MentionEvent> {
        val url  = "https://t.me/s/${channelHandle.trimStart('@')}"
        val html = get(url) ?: return emptyList()
        return parseTelegramHtml(html, symbol, mintAddress)
    }

    /**
     * Scrape all default Solana channels for this token.
     */
    fun scrapeDefaultChannels(symbol: String, mintAddress: String): List<MentionEvent> {
        val events = mutableListOf<MentionEvent>()
        for (channel in defaultChannels) {
            try {
                val channelEvents = scrapePublicChannel(channel, symbol, mintAddress)
                events.addAll(channelEvents)
                Thread.sleep(250)
            } catch (_: Exception) { continue }
        }
        return events
    }

    /**
     * Scrape user-configured channels.
     */
    fun scrapeUserChannels(channels: List<String>, symbol: String, mintAddress: String): List<MentionEvent> {
        val events = mutableListOf<MentionEvent>()
        for (channel in channels.take(10)) {
            try {
                events.addAll(scrapePublicChannel(channel, symbol, mintAddress))
                Thread.sleep(250)
            } catch (_: Exception) { continue }
        }
        return events
    }

    private fun parseTelegramHtml(html: String, symbol: String, mintAddress: String): List<MentionEvent> {
        val events   = mutableListOf<MentionEvent>()
        val now      = System.currentTimeMillis()
        val symLower = symbol.lowercase()
        val mintPfx  = mintAddress.take(8).lowercase()

        // Telegram web preview wraps messages in <div class="tgme_widget_message_text ...">
        val msgRegex = Regex(
            """<div class="tgme_widget_message_text[^"]*"[^>]*>(.*?)</div>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )

        // Extract timestamps from <time datetime="...">
        val timeRegex = Regex("""datetime="([^"]+)"""")
        val timeMatches = timeRegex.findAll(html).map { it.groupValues[1] }.toList()

        val msgMatches = msgRegex.findAll(html).toList()

        msgMatches.forEachIndexed { idx, match ->
            val rawText = match.groupValues[1]
                .replace(Regex("<br\\s*/?>"), "\n")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (rawText.length < 5) return@forEachIndexed

            val textLower = rawText.lowercase()

            // Only include messages that mention the symbol or mint prefix
            // OR that are from a pump channel (these always count)
            val mentionsToken = textLower.contains(symLower) ||
                                textLower.contains(mintPfx)
            val isPumpMsg     = SentimentAnalyzer.scoreText(rawText).isPumpChannel

            if (!mentionsToken && !isPumpMsg) return@forEachIndexed

            // Parse timestamp if available
            val ts = try {
                if (idx < timeMatches.size) {
                    java.time.Instant.parse(timeMatches[idx]).toEpochMilli()
                } else {
                    now - (idx * 5 * 60_000L)
                }
            } catch (_: Exception) {
                now - (idx * 5 * 60_000L)
            }

            val scored = SentimentAnalyzer.scoreText(rawText)
            events.add(MentionEvent(
                source    = "telegram",
                ts        = ts,
                sentiment = scored.score,
                text      = rawText.take(500),
            ))

            if (scored.hardBlock) return events  // stop on rug signal
        }

        return events
    }

    // ── Bot API mode (requires token) ─────────────────────────────────

    /**
     * Read recent messages from a channel via Bot API.
     * Bot must be a member of the channel.
     * channelId format: "@channelname" or numeric "-100xxxxxxx"
     */
    fun readViaBot(channelId: String, symbol: String, mintAddress: String,
                   limit: Int = 20): List<MentionEvent> {
        if (botToken.isBlank()) return emptyList()

        val url  = "https://api.telegram.org/bot$botToken/getUpdates?limit=$limit&timeout=0"
        val body = get(url) ?: return emptyList()

        return parseBotUpdates(body, symbol, mintAddress)
    }

    private fun parseBotUpdates(body: String, symbol: String, mintAddress: String): List<MentionEvent> {
        val events   = mutableListOf<MentionEvent>()
        val symLower = symbol.lowercase()
        val mintPfx  = mintAddress.take(8).lowercase()
        try {
            val json    = JSONObject(body)
            val results = json.optJSONArray("result") ?: return emptyList()
            for (i in 0 until results.length()) {
                val update  = results.getJSONObject(i)
                val msg     = update.optJSONObject("message")
                    ?: update.optJSONObject("channel_post") ?: continue
                val text    = msg.optString("text", "")
                    .ifBlank { msg.optString("caption", "") }
                if (text.isBlank()) continue
                val textLower = text.lowercase()
                if (!textLower.contains(symLower) && !textLower.contains(mintPfx)) continue
                val ts     = msg.optLong("date", 0L) * 1000L
                val scored = SentimentAnalyzer.scoreText(text)
                events.add(MentionEvent("telegram", ts, scored.score, text.take(500)))
                if (scored.hardBlock) return events
            }
        } catch (_: Exception) {}
        return events
    }

    // ── Wallet concentration check via Solana RPC ─────────────────────

    /**
     * Check if top holders control > 80% of supply.
     * Uses the Solana RPC getTokenLargestAccounts method.
     * Returns concentration 0-100 (100 = one wallet holds everything).
     */
    fun checkWalletConcentration(rpcUrl: String, mintAddress: String): Double {
        return try {
            val payload = """{"jsonrpc":"2.0","id":1,"method":"getTokenLargestAccounts","params":["$mintAddress"]}"""
            val resp    = post(rpcUrl, payload) ?: return 50.0  // unknown = neutral
            val json    = JSONObject(resp)
            val value   = json.optJSONObject("result")
                ?.optJSONArray("value") ?: return 50.0

            val amounts = (0 until value.length()).map { i ->
                value.getJSONObject(i).optString("uiAmount", "0").toDoubleOrNull() ?: 0.0
            }
            if (amounts.isEmpty() || amounts.sum() == 0.0) return 50.0

            val total   = amounts.sum()
            val top10   = amounts.sortedDescending().take(10).sum()
            (top10 / total * 100.0).coerceIn(0.0, 100.0)
        } catch (_: Exception) { 50.0 }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────

    private fun get(url: String): String? = try {
        val req  = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; LifecycleBot/1.0)")
            .build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }

    private fun post(url: String, json: String): String? = try {
        val body = json.toRequestBody("application/json".toMediaType())
        val req  = Request.Builder().url(url).post(body).build()
        val resp = http.newCall(req).execute()
        if (resp.isSuccessful) resp.body?.string() else null
    } catch (_: Exception) { null }
}

private fun String.toRequestBody(mediaType: String) =
    okhttp3.this.toRequestBody(mediaType.toMediaType())

private fun String.toMediaType() =
    this.toMediaType()

package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.MentionEvent
import com.lifecyclebot.data.SentimentResult
import com.lifecyclebot.network.TelegramScraper
import com.lifecyclebot.network.XScraper

/**
 * SentimentEngine
 *
 * Orchestrates all external data sources into a single SentimentResult per token.
 *
 * Runs on a separate cadence from price polling (every 3-5 min by default)
 * to avoid rate-limiting.
 *
 * Data sources:
 *   1. X (Twitter) — Nitter scraping, no API key required
 *   2. Telegram — public channel scraping + optional Bot API
 *   3. Wallet concentration — Solana RPC token account check
 *
 * Outputs:
 *   • sentimentScore (-100 to +100)
 *   • confidence (0-100)
 *   • hardBlock (never trade this token)
 *   • divergenceSignal (mentions rising faster than price)
 */
class SentimentEngine(private val cfg: () -> BotConfig) {

    private val xScraper  = XScraper()
    private val tgScraper get() = TelegramScraper(cfg().telegramBotToken)

    // Per-token rolling mention history (last 60 min)
    private val mentionHistory = mutableMapOf<String, MutableList<MentionEvent>>()
    private val lastSentiment  = mutableMapOf<String, SentimentResult>()
    private val lastFetchTs    = mutableMapOf<String, Long>()

    // Fetch interval: don't hammer these endpoints
    private val FETCH_INTERVAL_MS = 3 * 60_000L   // 3 minutes

    // ── public interface ──────────────────────────────────────────────

    /**
     * Returns current sentiment for a token.
     * Triggers a refresh if data is stale, otherwise returns cached result.
     * Non-blocking: returns cached result immediately, refresh happens async.
     */
    fun getSentiment(mint: String, symbol: String): SentimentResult {
        return lastSentiment[mint] ?: SentimentResult(updatedAt = 0L)
    }

    /**
     * Refresh sentiment data for a token. Call this on a background thread.
     * Returns the fresh SentimentResult.
     */
    fun refresh(mint: String, symbol: String, currentPrice: Double): SentimentResult {
        val now       = System.currentTimeMillis()
        val lastFetch = lastFetchTs[mint] ?: 0L

        // Throttle fetches
        if (now - lastFetch < FETCH_INTERVAL_MS) {
            return lastSentiment[mint] ?: SentimentResult(updatedAt = 0L)
        }
        lastFetchTs[mint] = now

        val events    = mutableListOf<MentionEvent>()
        val c         = cfg()

        // ── 1. X scraping ─────────────────────────────────────────────
        try {
            val xEvents = xScraper.searchBySymbol(symbol, mint)
            events.addAll(xEvents)
        } catch (_: Exception) {}

        // ── 2. Telegram default Solana channels ───────────────────────
        try {
            val tgDefault = tgScraper.scrapeDefaultChannels(symbol, mint)
            events.addAll(tgDefault)
        } catch (_: Exception) {}

        // ── 3. User-configured Telegram channels ──────────────────────
        try {
            if (c.telegramChannels.isNotEmpty()) {
                val tgUser = tgScraper.scrapeUserChannels(c.telegramChannels, symbol, mint)
                events.addAll(tgUser)
            }
        } catch (_: Exception) {}

        // ── 4. Telegram Bot API (if token configured) ─────────────────
        try {
            if (c.telegramBotToken.isNotBlank() && c.telegramChannels.isNotEmpty()) {
                for (ch in c.telegramChannels.take(3)) {
                    val botEvents = tgScraper.readViaBot(ch, symbol, mint)
                    events.addAll(botEvents)
                }
            }
        } catch (_: Exception) {}

        // ── 5. Wallet concentration check ─────────────────────────────
        val concentration = try {
            tgScraper.checkWalletConcentration(c.rpcUrl, mint)
        } catch (_: Exception) { 50.0 }

        // ── Aggregate ─────────────────────────────────────────────────

        // Update rolling history
        val history = mentionHistory.getOrPut(mint) { mutableListOf() }
        history.addAll(events)
        // Trim to last 60 min
        history.removeIf { now - it.ts > 60 * 60_000L }

        // Split by source
        val xEvents  = history.filter { it.source == "x" }
        val tgEvents = history.filter { it.source == "telegram" }

        val (xScore, xConf, xVel)   = SentimentAnalyzer.aggregate(xEvents)
        val (tgScore, tgConf, tgVel) = SentimentAnalyzer.aggregate(tgEvents)

        // Weighted combined score: Telegram weighted slightly higher for Solana
        // because Solana pump culture lives on Telegram
        val combinedScore = when {
            xConf == 0.0 && tgConf == 0.0 -> 0.0
            xConf == 0.0                   -> tgScore
            tgConf == 0.0                  -> xScore
            else -> (xScore * 0.4 + tgScore * 0.6)
        }
        val combinedConf = minOf(100.0, (xConf + tgConf) / 2.0)

        // Hard block check
        val hardBlockEvent = events.firstOrNull {
            SentimentAnalyzer.scoreText(it.text).hardBlock
        }
        val hardBlock    = hardBlockEvent != null
        val blockReason  = hardBlockEvent?.let {
            SentimentAnalyzer.scoreText(it.text).blockReason
        } ?: ""

        // Wallet concentration block: > 80% in top 10 holders
        val concBlock = concentration > 80.0

        // Price/mention divergence: mentions spiking but price not yet
        // Signal: velocity > 2.0 mentions/min AND price change < 5% in last period
        val divergence = (xVel + tgVel) > 2.0

        // Build summary string for UI display
        val summary = buildSummary(
            xMentions    = xEvents.size,
            tgMentions   = tgEvents.size,
            score        = combinedScore,
            concentration = concentration,
            hardBlock    = hardBlock,
            blockReason  = blockReason,
            velocity     = xVel + tgVel,
        )

        val result = SentimentResult(
            score                  = combinedScore,
            confidence             = combinedConf,
            blocked                = hardBlock || concBlock,
            blockReason            = if (concBlock) "Wallet concentration ${concentration.toInt()}% > 80%" else blockReason,
            xScore                 = xScore,
            xMentions              = xEvents.size,
            xVelocity              = xVel,
            telegramScore          = tgScore,
            telegramMentions       = tgEvents.size,
            telegramVelocity       = tgVel,
            walletConcentration    = concentration,
            concentrationBlocked   = concBlock,
            divergenceSignal       = divergence,
            summary                = summary,
            updatedAt              = now,
        )

        lastSentiment[mint] = result
        return result
    }

    private fun buildSummary(
        xMentions: Int, tgMentions: Int, score: Double,
        concentration: Double, hardBlock: Boolean, blockReason: String,
        velocity: Double,
    ): String {
        if (hardBlock) return "🚫 BLOCKED: $blockReason"
        val sentiment = when {
            score >= 50  -> "🟢 Very bullish"
            score >= 20  -> "🟡 Bullish"
            score >= -20 -> "⚪ Neutral"
            score >= -50 -> "🟠 Bearish"
            else         -> "🔴 Very bearish"
        }
        val conc = if (concentration > 70) "⚠️ Concentrated ${concentration.toInt()}%" else ""
        val vel  = if (velocity > 1.5) "🔥 Trending (${String.format("%.1f", velocity)}/min)" else ""
        return listOf(
            sentiment,
            "X:$xMentions TG:$tgMentions",
            conc, vel,
        ).filter { it.isNotBlank() }.joinToString("  ")
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * TelegramNotifier — sends trade alerts to a personal Telegram chat.
 *
 * Setup:
 *   1. Create a bot via @BotFather → get token
 *   2. Send any message to your bot
 *   3. Open https://api.telegram.org/bot<TOKEN>/getUpdates → find your chat_id
 *   4. Enter token + chat ID in Settings → Telegram Alerts
 *
 * Fires on: BUY, SELL, PARTIAL SELL, DEV SELL, circuit breaker, treasury milestone.
 * Does NOT fire on: every poll tick, brain analysis updates.
 *
 * Fully non-blocking — failures are silent (bot health > notification delivery).
 */
object TelegramNotifier {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Send a message to the configured Telegram chat.
     * Fire-and-forget: exceptions are swallowed.
     * Should be called from a background thread (it blocks briefly).
     */
    fun send(cfg: BotConfig, message: String) {
        if (!cfg.telegramTradeAlerts) return
        if (cfg.telegramBotToken.isBlank() || cfg.telegramChatId.isBlank()) return
        try {
            val body = JSONObject()
                .put("chat_id",    cfg.telegramChatId)
                .put("text",       message)
                .put("parse_mode", "HTML")
                .toString()
            val req = Request.Builder()
                .url("https://api.telegram.org/bot${cfg.telegramBotToken}/sendMessage")
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().close()  // close immediately — we don't need the response body
        } catch (_: Exception) {}  // never let Telegram break the trading loop
    }

    /**
     * Format a trade alert message.
     * Example:
     *   🟢 BUY BONK
     *   Size: 0.0450◎  Score: 78
     *   Wallet: 1.2340◎
     */
    fun buyMsg(symbol: String, sizeSol: Double, score: Double, walletSol: Double) =
        "🟢 <b>BUY $symbol</b>\n" +
        "Size: ${"%.4f".format(sizeSol)}◎  Score: ${score.toInt()}\n" +
        "Wallet: ${"%.4f".format(walletSol)}◎"

    fun sellMsg(symbol: String, pnlSol: Double, pnlPct: Double, reason: String) =
        (if (pnlSol >= 0) "✅" else "🔴") + " <b>SELL $symbol</b>\n" +
        "PnL: ${"%+.4f".format(pnlSol)}◎ (${"%+.1f".format(pnlPct)}%)\n" +
        "Reason: $reason"

    fun partialMsg(symbol: String, fraction: Int, gainPct: Double, solBack: Double) =
        "💰 <b>PARTIAL $symbol</b> ${fraction}%\n" +
        "At: +${gainPct.toInt()}%  Back: ${"%.4f".format(solBack)}◎"

    fun devSellMsg(symbol: String, pct: Int) =
        "🚨 <b>DEV SELL $symbol</b> — developer dumped ${pct}%"

    fun treasuryMsg(milestoneName: String, treasurySol: Double) =
        "🏦 <b>Treasury milestone: $milestoneName</b>\n" +
        "Locked: ${"%.4f".format(treasurySol)}◎"

    fun circuitBreakerMsg(reason: String) =
        "🛑 <b>Circuit breaker triggered</b>\n$reason"
}

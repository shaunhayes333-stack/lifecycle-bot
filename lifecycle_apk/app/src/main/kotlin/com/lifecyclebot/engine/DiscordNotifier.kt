package com.lifecyclebot.engine

import com.lifecyclebot.network.SharedHttpClient

import com.lifecyclebot.data.BotConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * DiscordNotifier — sends trade alerts to a Discord channel via webhook.
 *
 * V4.0: Added for real-time trade notifications to Discord.
 *
 * Setup:
 *   1. Go to your Discord server → Channel Settings → Integrations → Webhooks
 *   2. Create a webhook, copy the URL
 *   3. Enter webhook URL in Settings → Discord Alerts
 *
 * Fires on: BUY, SELL, big wins, circuit breaker, treasury milestone.
 * Does NOT fire on: every poll tick, brain analysis updates.
 *
 * Fully non-blocking — failures are silent (bot health > notification delivery).
 */
object DiscordNotifier {

    private val http = SharedHttpClient.builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /**
     * Send a message to the configured Discord webhook.
     * Fire-and-forget: exceptions are swallowed.
     * Should be called from a background thread (it blocks briefly).
     */
    fun send(cfg: BotConfig, message: String, color: Int = 0x9945FF) {
        if (!cfg.discordTradeAlerts) return
        if (cfg.discordWebhookUrl.isBlank()) return
        try {
            // Discord webhook expects embeds for rich formatting
            val embed = JSONObject()
                .put("description", message)
                .put("color", color)
            
            val body = JSONObject()
                .put("username", "AATE Bot")
                .put("embeds", JSONArray().put(embed))
                .toString()
            
            val req = Request.Builder()
                .url(cfg.discordWebhookUrl)
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().close()
        } catch (_: Exception) {}  // never let Discord break the trading loop
    }

    /**
     * Send a rich embed with title and fields.
     */
    fun sendEmbed(cfg: BotConfig, title: String, fields: List<Pair<String, String>>, color: Int = 0x9945FF) {
        if (!cfg.discordTradeAlerts) return
        if (cfg.discordWebhookUrl.isBlank()) return
        try {
            val fieldsArray = JSONArray()
            fields.forEach { (name, value) ->
                fieldsArray.put(JSONObject()
                    .put("name", name)
                    .put("value", value)
                    .put("inline", true))
            }
            
            val embed = JSONObject()
                .put("title", title)
                .put("fields", fieldsArray)
                .put("color", color)
                .put("footer", JSONObject().put("text", "AATE V4.0 | Solana Trading Bot"))
            
            val body = JSONObject()
                .put("username", "AATE Bot")
                .put("embeds", JSONArray().put(embed))
                .toString()
            
            val req = Request.Builder()
                .url(cfg.discordWebhookUrl)
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    // Color constants
    const val GREEN = 0x00FF00   // Wins
    const val RED = 0xFF0000     // Losses
    const val PURPLE = 0x9945FF  // Solana purple - neutral
    const val GOLD = 0xFFD700    // Big wins
    const val ORANGE = 0xFFA500  // Warnings

    /**
     * Format a BUY alert.
     */
    fun buyMsg(symbol: String, sizeSol: Double, score: Double, mode: String) =
        "**🟢 BUY $symbol**\n" +
        "Size: `${"%.4f".format(sizeSol)}◎`  Score: `${score.toInt()}`\n" +
        "Mode: $mode"

    fun sellMsg(symbol: String, pnlSol: Double, pnlPct: Double, reason: String) =
        "**${if (pnlSol >= 0) "✅" else "🔴"} SELL $symbol**\n" +
        "PnL: `${"%+.4f".format(pnlSol)}◎` (`${"%+.1f".format(pnlPct)}%`)\n" +
        "Reason: $reason"

    fun bigWinMsg(symbol: String, pnlPct: Double, pnlSol: Double) =
        "**🚀 BIG WIN $symbol**\n" +
        "Profit: `+${pnlPct.toInt()}%` (`+${"%.4f".format(pnlSol)}◎`)\n" +
        "🔥 Broadcast to network!"

    fun treasuryMsg(milestoneName: String, treasurySol: Double) =
        "**🏦 Treasury Milestone: $milestoneName**\n" +
        "Locked: `${"%.4f".format(treasurySol)}◎`"

    fun circuitBreakerMsg(reason: String) =
        "**🛑 Circuit Breaker Triggered**\n$reason"
}

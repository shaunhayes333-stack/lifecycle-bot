package com.lifecyclebot.engine

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * TelegramBot — Remote monitoring & control
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Features:
 *   📊 Trade alerts (entry, exit, P&L)
 *   💰 Daily/weekly performance summaries
 *   🛑 Remote commands (pause, resume, status, kill)
 *   ⚠️ Risk alerts (drawdown, circuit breaker)
 *
 * Setup:
 *   1. Message @BotFather on Telegram → /newbot → get token
 *   2. Message your bot, then visit:
 *      https://api.telegram.org/bot<TOKEN>/getUpdates
 *      to find your chat_id
 *   3. Set botToken and chatId in BotConfig
 *
 * Commands (send to your bot):
 *   /status  — Current positions, P&L, regime
 *   /pause   — Pause auto-trading
 *   /resume  — Resume auto-trading
 *   /kill    — Emergency stop all trading
 *   /pnl     — Today's P&L summary
 *   /positions — List open positions
 *   /treasury — Treasury status
 */
object TelegramBot {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var botToken: String = ""
    private var chatId: String = ""
    private var enabled: Boolean = false
    private var lastUpdateId: Long = 0
    private var pollingJob: Job? = null

    // Callbacks for commands
    var onPauseCommand: () -> Unit = {}
    var onResumeCommand: () -> Unit = {}
    var onKillCommand: () -> Unit = {}
    var onStatusRequest: () -> String = { "Status not configured" }
    var onPnlRequest: () -> String = { "P&L not configured" }
    var onPositionsRequest: () -> String = { "Positions not configured" }
    var onTreasuryRequest: () -> String = { "Treasury not configured" }

    /**
     * Initialize the Telegram bot.
     */
    fun init(token: String, chat: String) {
        if (token.isBlank() || chat.isBlank()) {
            enabled = false
            return
        }
        botToken = token
        chatId = chat
        enabled = true
    }

    /**
     * Start listening for commands.
     */
    fun startPolling(intervalMs: Long = 3000L) {
        if (!enabled) return
        
        pollingJob?.cancel()
        pollingJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                try {
                    pollUpdates()
                } catch (e: Exception) {
                    // Silently continue
                }
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ══════════════════════════════════════════════════════════════════
    // ALERT METHODS — Call these from your trading logic
    // ══════════════════════════════════════════════════════════════════

    /**
     * Send trade entry alert.
     */
    fun alertEntry(
        symbol: String,
        mint: String,
        solAmount: Double,
        priceUsd: Double,
        entryScore: Int,
        phase: String,
    ) {
        val msg = buildString {
            appendLine("🟢 *ENTRY: $symbol*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("💰 Size: `${solAmount.fmt(3)} SOL`")
            appendLine("💵 Price: `$${priceUsd.fmt(8)}`")
            appendLine("📊 Score: `$entryScore`")
            appendLine("📈 Phase: `$phase`")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("[Chart](https://dexscreener.com/solana/$mint)")
        }
        send(msg, parseMode = "Markdown")
    }

    /**
     * Send trade exit alert.
     */
    fun alertExit(
        symbol: String,
        mint: String,
        pnlSol: Double,
        pnlPct: Double,
        holdMins: Double,
        exitReason: String,
    ) {
        val emoji = if (pnlSol >= 0) "🟢" else "🔴"
        val pnlEmoji = if (pnlSol >= 0) "📈" else "📉"
        
        val msg = buildString {
            appendLine("$emoji *EXIT: $symbol*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("$pnlEmoji P&L: `${if (pnlSol >= 0) "+" else ""}${pnlSol.fmt(4)} SOL (${pnlPct.fmt(1)}%)`")
            appendLine("⏱ Hold: `${holdMins.fmt(1)} min`")
            appendLine("📝 Reason: `$exitReason`")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("[Chart](https://dexscreener.com/solana/$mint)")
        }
        send(msg, parseMode = "Markdown")
    }

    /**
     * Send partial sell alert.
     */
    fun alertPartialSell(
        symbol: String,
        sellPct: Int,
        pnlPct: Double,
        remaining: Double,
    ) {
        val msg = buildString {
            appendLine("🟡 *PARTIAL SELL: $symbol*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("📤 Sold: `$sellPct%`")
            appendLine("📈 Gain: `+${pnlPct.fmt(1)}%`")
            appendLine("💼 Remaining: `${remaining.fmt(4)} SOL`")
        }
        send(msg, parseMode = "Markdown")
    }

    /**
     * Send risk alert (drawdown, circuit breaker, etc.)
     */
    fun alertRisk(title: String, details: String) {
        val msg = buildString {
            appendLine("⚠️ *RISK ALERT*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("🚨 $title")
            appendLine(details)
        }
        send(msg, parseMode = "Markdown")
    }

    /**
     * Send shadow learning insight.
     */
    fun alertLearningInsight(
        insight: ShadowLearningEngine.LearningInsight,
    ) {
        val msg = buildString {
            appendLine("🧠 *LEARNING INSIGHT*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("💡 ${insight.message}")
            appendLine("")
            appendLine("📌 *Suggested Action:*")
            appendLine("${insight.suggestedAction}")
            appendLine("")
            appendLine("📊 Confidence: `${(insight.confidence * 100).toInt()}%`")
            appendLine("📈 Improvement: `+${insight.improvement.toInt()}%` vs live")
        }
        send(msg, parseMode = "Markdown")
    }

    /**
     * Send daily summary.
     */
    fun alertDailySummary(
        trades: Int,
        wins: Int,
        totalPnlSol: Double,
        totalPnlUsd: Double,
        bestTrade: String,
        worstTrade: String,
    ) {
        val winRate = if (trades > 0) (wins.toDouble() / trades * 100) else 0.0
        val emoji = if (totalPnlSol >= 0) "🟢" else "🔴"
        
        val msg = buildString {
            appendLine("📊 *DAILY SUMMARY*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("📈 Trades: `$trades` (Win rate: `${winRate.fmt(1)}%`)")
            appendLine("$emoji P&L: `${if (totalPnlSol >= 0) "+" else ""}${totalPnlSol.fmt(4)} SOL`")
            appendLine("💵 USD: `${if (totalPnlUsd >= 0) "+" else ""}$${totalPnlUsd.fmt(2)}`")
            appendLine("🏆 Best: `$bestTrade`")
            appendLine("💀 Worst: `$worstTrade`")
        }
        send(msg, parseMode = "Markdown")
    }

    /**
     * Send treasury milestone alert.
     */
    fun alertTreasuryMilestone(milestone: String, treasurySol: Double, treasuryUsd: Double) {
        val msg = buildString {
            appendLine("🏦 *TREASURY MILESTONE*")
            appendLine("━━━━━━━━━━━━━━━━")
            appendLine("🎯 Reached: `$milestone`")
            appendLine("💰 Treasury: `${treasurySol.fmt(2)} SOL`")
            appendLine("💵 Value: `$${treasuryUsd.fmt(2)}`")
        }
        send(msg, parseMode = "Markdown")
    }

    /**
     * Send generic message.
     */
    fun send(text: String, parseMode: String = "Markdown") {
        if (!enabled) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = "https://api.telegram.org/bot$botToken/sendMessage"
                val json = JSONObject().apply {
                    put("chat_id", chatId)
                    put("text", text)
                    put("parse_mode", parseMode)
                    put("disable_web_page_preview", false)
                }
                
                val body = json.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                http.newCall(request).execute().close()
            } catch (e: Exception) {
                // Silent fail
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // COMMAND POLLING
    // ══════════════════════════════════════════════════════════════════

    private fun pollUpdates() {
        val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}&timeout=1"
        val request = Request.Builder().url(url).build()
        val response = http.newCall(request).execute()
        
        if (!response.isSuccessful) {
            response.close()
            return
        }
        
        val body = response.body?.string() ?: return
        val json = JSONObject(body)
        val results = json.optJSONArray("result") ?: return
        
        for (i in 0 until results.length()) {
            val update = results.getJSONObject(i)
            lastUpdateId = update.getLong("update_id")
            
            val message = update.optJSONObject("message") ?: continue
            val text = message.optString("text", "")
            val fromChatId = message.optJSONObject("chat")?.optString("id", "") ?: ""
            
            // Only respond to our configured chat
            if (fromChatId != chatId) continue
            
            handleCommand(text)
        }
    }

    private fun handleCommand(text: String) {
        when (text.lowercase().trim()) {
            "/status" -> send(onStatusRequest())
            "/pause" -> {
                onPauseCommand()
                send("⏸ *Trading PAUSED*\nSend /resume to continue.", "Markdown")
            }
            "/resume" -> {
                onResumeCommand()
                send("▶️ *Trading RESUMED*", "Markdown")
            }
            "/kill" -> {
                onKillCommand()
                send("🛑 *EMERGENCY STOP ACTIVATED*\nAll trading halted.", "Markdown")
            }
            "/pnl" -> send(onPnlRequest())
            "/positions" -> send(onPositionsRequest())
            "/treasury" -> send(onTreasuryRequest())
            "/shadow" -> send(ShadowLearningEngine.getStatusSummary(), "Markdown")
            "/insights" -> {
                val insights = ShadowLearningEngine.getInsights(5)
                if (insights.isEmpty()) {
                    send("No learning insights yet. Keep trading!", "Markdown")
                } else {
                    val msg = buildString {
                        appendLine("🧠 *RECENT INSIGHTS*")
                        appendLine("━━━━━━━━━━━━━━━━")
                        insights.forEach { i ->
                            appendLine("💡 ${i.message}")
                            appendLine("   → ${i.suggestedAction}")
                            appendLine("")
                        }
                    }
                    send(msg, "Markdown")
                }
            }
            "/help" -> send(buildString {
                appendLine("*Available Commands:*")
                appendLine("/status — Current bot status")
                appendLine("/pnl — Today's P&L")
                appendLine("/positions — Open positions")
                appendLine("/treasury — Treasury status")
                appendLine("/shadow — Shadow learning status")
                appendLine("/insights — Recent AI insights")
                appendLine("/pause — Pause trading")
                appendLine("/resume — Resume trading")
                appendLine("/kill — Emergency stop")
            }, "Markdown")
        }
    }

    private fun Double.fmt(d: Int) = "%.${d}f".format(this)
}

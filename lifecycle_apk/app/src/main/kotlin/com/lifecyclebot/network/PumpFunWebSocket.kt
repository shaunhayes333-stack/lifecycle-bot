package com.lifecyclebot.network

import com.lifecyclebot.data.MentionEvent
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Pump.fun WebSocket client.
 *
 * Connects to Pump.fun's public WebSocket API and streams:
 *   - newToken events: brand new token created on pump.fun
 *   - trade events:    buys/sells on any token
 *   - graduation:      token graduates from bonding curve to Raydium
 *
 * Endpoint: wss://pumpportal.fun/api/data
 * No auth required — public stream.
 *
 * On new token detection: calls onNewToken() immediately so the bot
 * can run safety checks and add to watchlist before anyone else sees it.
 */
class PumpFunWebSocket(
    private val onNewToken: (mint: String, symbol: String, name: String, devWallet: String) -> Unit,
    private val onTrade: (mint: String, isBuy: Boolean, solAmount: Double, walletAddress: String) -> Unit,
    private val onGraduation: (mint: String) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)   // no read timeout — persistent connection
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var running = false
    private var reconnectDelayMs = 2_000L

    // Tokens we are actively watching trades for
    private val subscribedMints = mutableSetOf<String>()

    fun connect() {
        if (running) return
        running = true
        doConnect()
    }

    fun disconnect() {
        running = false
        ws?.close(1000, "Bot stopped")
        ws = null
    }

    /** Subscribe to trade events for a specific token */
    fun subscribeToken(mint: String) {
        subscribedMints.add(mint)
        ws?.send(JSONObject().apply {
            put("method", "subscribeTokenTrade")
            put("keys", org.json.JSONArray().put(mint))
        }.toString())
    }

    /** Unsubscribe from a token's trade events */
    fun unsubscribeToken(mint: String) {
        subscribedMints.remove(mint)
        ws?.send(JSONObject().apply {
            put("method", "unsubscribeTokenTrade")
            put("keys", org.json.JSONArray().put(mint))
        }.toString())
    }

    private fun doConnect() {
        val req = Request.Builder()
            .url("wss://pumpportal.fun/api/data")
            .build()

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                onLog("Pump.fun WebSocket connected")
                reconnectDelayMs = 2_000L

                // Subscribe to all new token creations
                webSocket.send(JSONObject().apply {
                    put("method", "subscribeNewToken")
                }.toString())

                // Re-subscribe to any tokens we were watching
                if (subscribedMints.isNotEmpty()) {
                    webSocket.send(JSONObject().apply {
                        put("method", "subscribeTokenTrade")
                        put("keys", org.json.JSONArray().apply {
                            subscribedMints.forEach { put(it) }
                        })
                    }.toString())
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog("Pump.fun WS error: ${t.message} — reconnecting in ${reconnectDelayMs/1000}s")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) {
                    onLog("Pump.fun WS closed ($code) — reconnecting")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun parseMessage(text: String) {
        try {
            val msg    = JSONObject(text)
            val txType = msg.optString("txType", "")

            when (txType) {
                "create" -> {
                    // New token launched on pump.fun
                    val mint      = msg.optString("mint", "")
                    val symbol    = msg.optString("symbol", "")
                    val name      = msg.optString("name", "")
                    val devWallet = msg.optString("traderPublicKey", "")
                    if (mint.isNotBlank()) {
                        onLog("🆕 New token: $symbol ($name) mint=${mint.take(12)}…")
                        onNewToken(mint, symbol, name, devWallet)
                    }
                }

                "buy", "sell" -> {
                    val mint   = msg.optString("mint", "")
                    val isBuy  = txType == "buy"
                    val sol    = msg.optDouble("solAmount", 0.0)
                    val wallet = msg.optString("traderPublicKey", "")
                    if (mint.isNotBlank()) {
                        onTrade(mint, isBuy, sol, wallet)
                    }
                }

                else -> {
                    // Check for graduation event
                    if (msg.has("pool") && msg.optString("pool") == "pump") {
                        val mint = msg.optString("mint", "")
                        if (mint.isNotBlank()) {
                            onLog("🎓 Token graduated: ${mint.take(12)}…")
                            onGraduation(mint)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        if (!running) return
        Thread.sleep(reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
        doConnect()
    }
}

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
    private val client = SharedHttpClient.builder()
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
                    val marketCapSol = msg.optDouble("marketCapSol", 0.0)
                    if (mint.isBlank()) return
                    // V5.0.4168 — PumpPortal WS adaptive throttle. Drops
                    // dust-mcap creates before they trigger the downstream
                    // provider/rugcheck cascade (the actual bandwidth cost).
                    if (!com.lifecyclebot.engine.PumpPortalThrottle.allowCreate(marketCapSol)) return
                    onLog("🆕 New token: $symbol ($name) mint=${mint.take(12)}…")
                    onNewToken(mint, symbol, name, devWallet)
                }

                "buy", "sell" -> {
                    val mint   = msg.optString("mint", "")
                    val isBuy  = txType == "buy"
                    val rawSol = msg.optDouble("solAmount", 0.0)
                    val sol    = normalizeSolAmount(rawSol)
                    val wallet = msg.optString("traderPublicKey", "")
                    if (mint.isBlank() || sol == null) return
                    // V5.0.4168 — trade firehose is the largest bandwidth
                    // driver. Drop trades on mints we don't track; the
                    // scoring pipeline only acts on watchlist members
                    // anyway, so processing other mints is pure waste.
                    if (!com.lifecyclebot.engine.PumpPortalThrottle.allowTrade(mint)) return
                    onTrade(mint, isBuy, sol, wallet)
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


    /**
     * V5.9.1113 — PumpPortal/PumpFun trade feeds are not consistent about
     * numeric basis. Some sessions delivered raw lamports/base units in the
     * `solAmount` slot, which downstream logged as 1,000,000+ SOL whale buys
     * and poisoned SmartMoneyAI divergence. Canonicalize to UI SOL at ingress.
     */
    private fun normalizeSolAmount(raw: Double): Double? {
        if (!raw.isFinite() || raw <= 0.0) return null
        val scaled = when {
            raw > 100_000.0 -> raw / 1_000_000_000.0   // lamports/base-unit leak
            raw > 10_000.0  -> raw / 1_000_000.0       // micro-SOL style leak
            else -> raw
        }
        return scaled.takeIf { it.isFinite() && it > 0.0 && it <= 1_000.0 }
    }

    private fun scheduleReconnect() {
        if (!running) return
        Thread.sleep(reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(60_000L)
        // V5.9.1310 — RE-CHECK running AFTER the backoff sleep. disconnect() can
        // flip running=false during the 2-60s sleep; without this re-check the WS
        // would reconnect AFTER the bot was stopped, re-streaming new tokens into
        // intake (operator regression: intake continued post-stop). Bail if stopped.
        if (!running) {
            onLog("Pump.fun WS reconnect aborted — bot stopped during backoff")
            return
        }
        doConnect()
    }
}

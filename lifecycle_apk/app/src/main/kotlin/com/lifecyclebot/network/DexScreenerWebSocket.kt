package com.lifecyclebot.network

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * DexScreener WebSocket client — real-time price/chart data for ANY Solana token.
 *
 * Unlike Pump.fun WS (new tokens only) or Helius (raw transactions), DexScreener
 * provides clean price/volume/OHLC data for ALL Solana tokens including:
 *   - Raydium pools
 *   - Orca Whirlpools  
 *   - Meteora
 *   - Any graduated token
 *
 * Endpoint: wss://io.dexscreener.com/dex/screener/pair/h24/1/solana
 * 
 * V5.6: Added to complement Pump.fun + Helius for complete real-time coverage.
 * No API key required — public WebSocket.
 */
class DexScreenerWebSocket(
    private val onPriceUpdate: (
        mint: String,
        priceUsd: Double,
        priceChange5m: Double,
        priceChange1h: Double,
        volume5m: Double,
        volume1h: Double,
        liquidity: Double,
        mcap: Double,
        buys5m: Int,
        sells5m: Int,
        txns5m: Int,
    ) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val client = SharedHttpClient.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var running = false
    private var reconnectDelayMs = 3_000L

    // Tokens we're actively tracking
    private val subscribedPairs = ConcurrentHashMap<String, String>()  // mint → pairAddress

    // Last known prices (for change detection)
    private val lastPrices = ConcurrentHashMap<String, Double>()

    val isConnected get() = ws != null && running

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

    /**
     * Subscribe to real-time price updates for a token.
     * Requires the pair address (from DexScreener API lookup).
     */
    fun subscribeToken(mint: String, pairAddress: String) {
        if (pairAddress.isBlank()) return
        subscribedPairs[mint] = pairAddress
        sendSubscribe(pairAddress)
    }

    fun unsubscribeToken(mint: String) {
        val pair = subscribedPairs.remove(mint) ?: return
        sendUnsubscribe(pair)
    }

    private fun doConnect() {
        // DexScreener public WebSocket endpoint
        val url = "wss://io.dexscreener.com/dex/screener/pairs/solana/h24/1"
        val req = Request.Builder()
            .url(url)
            .header("Origin", "https://dexscreener.com")
            .build()

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                onLog("DexScreener WebSocket connected")
                reconnectDelayMs = 3_000L

                // Re-subscribe to all watched tokens
                subscribedPairs.values.forEach { pair ->
                    sendSubscribe(pair)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog("DexScreener WS error: ${t.message?.take(50)} — reconnecting")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) {
                    onLog("DexScreener WS closed — reconnecting")
                    scheduleReconnect()
                }
            }
        })
    }

    private fun sendSubscribe(pairAddress: String) {
        ws?.send(JSONObject().apply {
            put("type", "subscribe")
            put("channel", "pair")
            put("pair", pairAddress)
        }.toString())
    }

    private fun sendUnsubscribe(pairAddress: String) {
        ws?.send(JSONObject().apply {
            put("type", "unsubscribe")
            put("channel", "pair")
            put("pair", pairAddress)
        }.toString())
    }

    private fun parseMessage(text: String) {
        try {
            val msg = JSONObject(text)
            
            // Handle different message types
            when (msg.optString("type", "")) {
                "pair", "pairs", "update" -> parsePairUpdate(msg)
                "ping" -> ws?.send("""{"type":"pong"}""")
            }
            
            // Also check if it's a direct pairs array
            if (msg.has("pairs")) {
                val pairs = msg.optJSONArray("pairs") ?: return
                for (i in 0 until pairs.length()) {
                    val pair = pairs.optJSONObject(i) ?: continue
                    parsePairData(pair)
                }
            }
        } catch (e: Exception) {
            // Silent fail — WS messages can be malformed
        }
    }

    private fun parsePairUpdate(msg: JSONObject) {
        // Single pair update
        val pair = msg.optJSONObject("pair") ?: msg
        parsePairData(pair)
    }

    private fun parsePairData(pair: JSONObject) {
        try {
            // Extract base token mint
            val baseToken = pair.optJSONObject("baseToken") ?: return
            val mint = baseToken.optString("address", "")
            if (mint.isBlank()) return

            // Only process if we're subscribed to this mint
            if (!subscribedPairs.containsKey(mint)) return

            // Price data
            val priceUsd = pair.optDouble("priceUsd", 0.0)
            
            // Price changes
            val priceChange = pair.optJSONObject("priceChange") ?: JSONObject()
            val priceChange5m = priceChange.optDouble("m5", 0.0)
            val priceChange1h = priceChange.optDouble("h1", 0.0)

            // Volume
            val volume = pair.optJSONObject("volume") ?: JSONObject()
            val volume5m = volume.optDouble("m5", 0.0)
            val volume1h = volume.optDouble("h1", 0.0)

            // Liquidity
            val liquidity = pair.optJSONObject("liquidity")?.optDouble("usd", 0.0) ?: 0.0

            // Market cap (FDV)
            val mcap = pair.optDouble("fdv", 0.0)

            // Transactions
            val txns = pair.optJSONObject("txns") ?: JSONObject()
            val txns5m = txns.optJSONObject("m5") ?: JSONObject()
            val buys5m = txns5m.optInt("buys", 0)
            val sells5m = txns5m.optInt("sells", 0)

            // Only emit if price actually changed
            val lastPrice = lastPrices[mint] ?: 0.0
            if (priceUsd != lastPrice && priceUsd > 0) {
                lastPrices[mint] = priceUsd
                onPriceUpdate(
                    mint,
                    priceUsd,
                    priceChange5m,
                    priceChange1h,
                    volume5m,
                    volume1h,
                    liquidity,
                    mcap,
                    buys5m,
                    sells5m,
                    buys5m + sells5m,
                )
            }
        } catch (_: Exception) {}
    }

    private fun scheduleReconnect() {
        if (!running) return
        Thread.sleep(reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 1.5).toLong().coerceAtMost(60_000L)
        if (running) doConnect()
    }

    /**
     * Get current subscribed token count for status display.
     */
    fun getSubscribedCount(): Int = subscribedPairs.size
}

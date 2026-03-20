package com.lifecyclebot.network

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Helius WebSocket client — real-time Solana transaction stream.
 *
 * Uses Helius enhanced websocket which gives:
 *   - Parsed transaction data (not raw bytes)
 *   - Token swap details (amounts, prices)
 *   - Wallet activity
 *
 * Free tier: 100k credits/day. Each transaction ~1 credit.
 * Get a free API key at: https://helius.dev (takes 2 minutes)
 *
 * Endpoint: wss://mainnet.helius-rpc.com/?api-key=YOUR_KEY
 *
 * Falls back gracefully to polling if no Helius key configured.
 */
class HeliusWebSocket(
    private val apiKey: String,
    private val onSwap: (
        mint: String,
        isBuy: Boolean,
        solAmount: Double,
        tokenAmount: Double,
        walletAddress: String,
        signature: String,
    ) -> Unit,
    private val onLargeWalletMove: (
        walletAddress: String,
        mint: String,
        solAmount: Double,
        isBuy: Boolean,
    ) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile private var running = false
    private val idCounter = AtomicInteger(1)
    private val subscriptions = mutableMapOf<String, Int>()  // mint → sub id
    // Wallet addresses we're tracking (dev wallets, large holders)
    private val watchedWallets = mutableSetOf<String>()
    private var reconnectDelay = 2_000L

    val isConnected get() = ws != null && running

    fun connect() {
        if (apiKey.isBlank()) {
            onLog("Helius: no API key — real-time stream disabled (add key in settings)")
            return
        }
        if (running) return
        running = true
        doConnect()
    }

    fun disconnect() {
        running = false
        ws?.close(1000, "Bot stopped")
        ws = null
    }

    /** Subscribe to all swaps for a specific token mint */
    fun subscribeToken(mint: String) {
        if (subscriptions.containsKey(mint)) return
        val id = idCounter.getAndIncrement()
        subscriptions[mint] = id
        sendSubscribe(id, listOf(mint))
    }

    fun unsubscribeToken(mint: String) {
        val id = subscriptions.remove(mint) ?: return
        ws?.send(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", idCounter.getAndIncrement())
            put("method", "logsUnsubscribe")
            put("params", JSONArray().put(id))
        }.toString())
    }

    /** Track a wallet address for large moves (dev wallet, top holders) */
    fun watchWallet(address: String) {
        watchedWallets.add(address)
        val id = idCounter.getAndIncrement()
        sendAccountSubscribe(id, address)
    }

    fun unwatchWallet(address: String) {
        watchedWallets.remove(address)
    }

    private fun doConnect() {
        val url = "wss://mainnet.helius-rpc.com/?api-key=$apiKey"
        val req = Request.Builder().url(url).build()

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                onLog("Helius WebSocket connected")
                reconnectDelay = 2_000L

                // Re-subscribe to all tokens
                subscriptions.forEach { (mint, _) ->
                    val id = idCounter.getAndIncrement()
                    subscriptions[mint] = id
                    sendSubscribe(id, listOf(mint))
                }

                // Re-watch wallets
                watchedWallets.forEach { addr ->
                    sendAccountSubscribe(idCounter.getAndIncrement(), addr)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onLog("Helius WS error: ${t.message?.take(60)} — reconnecting in ${reconnectDelay/1000}s")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) scheduleReconnect()
            }
        })
    }

    private fun sendSubscribe(id: Int, mints: List<String>) {
        // Subscribe to log notifications mentioning these program IDs
        // This catches all swaps on Raydium, Pump.fun, Orca, Jupiter
        val programIds = listOf(
            "675kPX9MHTjS2zt1qfr1NYHuzeLXfQM9H24wFSUt1Mp8",   // Raydium AMM
            "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBymwEuE5",  // Pump.fun
            "whirLbMiicVdio4qvUfM5KAg6Ct8VwpYzGff3uctyCc",    // Orca Whirlpool
            "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4",    // Jupiter v6
        )
        val mentions = mints + programIds
        ws?.send(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "logsSubscribe")
            put("params", JSONArray().apply {
                put(JSONObject().apply {
                    put("mentions", JSONArray(mentions))
                })
                put(JSONObject().apply {
                    put("commitment", "confirmed")
                })
            })
        }.toString())
    }

    private fun sendAccountSubscribe(id: Int, address: String) {
        ws?.send(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "accountSubscribe")
            put("params", JSONArray().apply {
                put(address)
                put(JSONObject().apply {
                    put("encoding", "jsonParsed")
                    put("commitment", "confirmed")
                })
            })
        }.toString())
    }

    private fun parseMessage(text: String) {
        try {
            val msg    = JSONObject(text)
            val method = msg.optString("method", "")
            val params = msg.optJSONObject("params") ?: return
            val result = params.optJSONObject("result") ?: return
            val value  = result.optJSONObject("value") ?: return

            when (method) {
                "logsNotification" -> parseLogsNotification(value)
                "accountNotification" -> parseAccountNotification(value, params)
            }
        } catch (_: Exception) {}
    }

    /**
     * Parse transaction log notifications.
     * We look for swap-related log messages to extract trade data.
     * Helius enhanced API enriches these with parsed token amounts.
     */
    private fun parseLogsNotification(value: JSONObject) {
        val logs = value.optJSONArray("logs") ?: return
        val sig  = value.optString("signature", "")
        if (sig.isBlank()) return

        // Detect swap direction from log messages
        val logList = (0 until logs.length()).map { logs.optString(it, "") }
        val isSwap  = logList.any {
            it.contains("Swap") || it.contains("swap") ||
            it.contains("Buy")  || it.contains("Sell")
        }
        if (!isSwap) return

        // Extract amounts from log lines
        // Pump.fun logs format: "Program log: Buy {token_amount} tokens for {sol_amount} SOL"
        val buyPattern  = Regex("""Buy\s+([\d.]+)\s+tokens?\s+for\s+([\d.]+)\s+SOL""", RegexOption.IGNORE_CASE)
        val sellPattern = Regex("""Sell\s+([\d.]+)\s+tokens?\s+for\s+([\d.]+)\s+SOL""", RegexOption.IGNORE_CASE)

        for (log in logList) {
            val buyMatch = buyPattern.find(log)
            if (buyMatch != null) {
                val tokenAmt = buyMatch.groupValues[1].toDoubleOrNull() ?: continue
                val solAmt   = buyMatch.groupValues[2].toDoubleOrNull() ?: continue
                // We don't know the mint from logs alone — use signature to look up
                // For now emit with empty mint; BotService will correlate
                onSwap("", true, solAmt, tokenAmt, "", sig)
                return
            }
            val sellMatch = sellPattern.find(log)
            if (sellMatch != null) {
                val tokenAmt = sellMatch.groupValues[1].toDoubleOrNull() ?: continue
                val solAmt   = sellMatch.groupValues[2].toDoubleOrNull() ?: continue
                onSwap("", false, solAmt, tokenAmt, "", sig)
                return
            }
        }
    }

    /**
     * Parse account change notifications — detect large wallet moves.
     * We track dev wallets and large holders for early warning.
     */
    private fun parseAccountNotification(value: JSONObject, params: JSONObject) {
        val lamports    = value.optLong("lamports", 0L)
        val solBalance  = lamports / 1_000_000_000.0
        val subscription = params.optInt("subscription", -1)

        // Find which wallet this is for
        val wallet = watchedWallets.firstOrNull() ?: return

        // Large move = balance changed by > 0.5 SOL
        // (we'd need to track previous balance for a proper diff — simplified here)
        if (solBalance > 0) {
            onLargeWalletMove(wallet, "", solBalance, false)
        }
    }

    private fun scheduleReconnect() {
        if (!running) return
        Thread.sleep(reconnectDelay)
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30_000L)
        if (running) doConnect()
    }
}

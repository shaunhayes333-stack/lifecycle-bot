package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.484 — Helius Enhanced WebSocket for whale-wallet activity streaming.
 *
 * Replaces polling-based InsiderWalletTracker with push notifications. As
 * soon as a tracked whale wallet emits a transaction, Helius's LaserStream
 * pushes the parsed tx over WebSocket — typically 200-500ms before
 * normal RPC confirmations propagate. This gives the bot a meaningful
 * head-start on copy-trading insider flows.
 *
 * Endpoint: wss://atlas-mainnet.helius-rpc.com/?api-key=<KEY>
 * Method: transactionSubscribe with accountInclude filter (up to 50k addrs)
 * Free on Helius Developer tier (already in env via cfg.heliusApiKey).
 *
 * Usage:
 *   HeliusEnhancedWS.start(apiKey, watchedWallets) { signature, accounts, fee, ... -> ... }
 *   HeliusEnhancedWS.updateWatchlist(newAccountList)  // hot-swap without disconnect
 *   HeliusEnhancedWS.stop()
 */
object HeliusEnhancedWS {
    private const val TAG = "HeliusEnhancedWS"
    private const val URL_TEMPLATE = "wss://atlas-mainnet.helius-rpc.com/?api-key=%s"

    private val running = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0L)
    private val nextRpcId = AtomicLong(420L)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var apiKey: String = ""
    @Volatile private var watchedAccounts: List<String> = emptyList()
    @Volatile private var subscriptionId: Long = -1L
    @Volatile private var onTxCb: ((sig: String, accounts: List<String>, raw: JSONObject) -> Unit)? = null

    fun start(
        heliusApiKey: String,
        watchAccounts: List<String>,
        onTransaction: (signature: String, accounts: List<String>, raw: JSONObject) -> Unit,
    ) {
        if (heliusApiKey.isBlank()) {
            ErrorLogger.warn(TAG, "no Helius API key — push subscription not started")
            return
        }
        if (!running.compareAndSet(false, true)) {
            ErrorLogger.warn(TAG, "already running — start() ignored")
            return
        }
        apiKey = heliusApiKey
        watchedAccounts = watchAccounts.distinct()
        onTxCb = onTransaction
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        connect()
    }

    fun updateWatchlist(newAccounts: List<String>) {
        watchedAccounts = newAccounts.distinct()
        // Re-subscribe with new filter (Helius doesn't support live filter mutation
        // on an existing subscription — easiest is unsubscribe + subscribe).
        ws?.let { sock ->
            try {
                if (subscriptionId >= 0) {
                    sock.send(JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", nextRpcId.getAndIncrement())
                        put("method", "transactionUnsubscribe")
                        put("params", JSONArray().put(subscriptionId))
                    }.toString())
                }
                sendSubscribe(sock)
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "watchlist update failed: ${e.message?.take(80)}")
            }
        }
    }

    fun stop() {
        running.set(false)
        try { ws?.close(1000, "client stop") } catch (_: Exception) {}
        ws = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        client = null
        ErrorLogger.info(TAG, "stopped")
    }

    private fun connect() {
        if (!running.get()) return
        val url = URL_TEMPLATE.format(apiKey)
        val req = Request.Builder().url(url).build()
        ErrorLogger.info(TAG, "🔌 connecting (attempt ${reconnectAttempt.get() + 1}, watching ${watchedAccounts.size} accounts)")
        ws = client?.newWebSocket(req, listener)
    }

    private fun sendSubscribe(socket: WebSocket) {
        if (watchedAccounts.isEmpty()) return
        val params = JSONArray().apply {
            put(JSONObject().put("accountInclude", JSONArray(watchedAccounts)))
            put(JSONObject().apply {
                put("commitment", "processed")
                put("encoding", "jsonParsed")
                put("transactionDetails", "full")
                put("maxSupportedTransactionVersion", 0)
            })
        }
        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", nextRpcId.getAndIncrement())
            put("method", "transactionSubscribe")
            put("params", params)
        }
        socket.send(req.toString())
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt.set(0)
            ErrorLogger.info(TAG, "✅ connected — subscribing to ${watchedAccounts.size} whale wallets")
            sendSubscribe(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                // Subscription confirmation
                if (msg.has("result") && msg.opt("result") is Number) {
                    subscriptionId = msg.getLong("result")
                    return
                }
                // Transaction notification
                if (msg.optString("method", "") != "transactionNotification") return
                val params = msg.optJSONObject("params") ?: return
                val result = params.optJSONObject("result") ?: return
                val sig = result.optString("signature", "")
                val tx = result.optJSONObject("transaction")
                val message = tx?.optJSONObject("message")
                val keys = mutableListOf<String>()
                val accountKeys = message?.optJSONArray("accountKeys")
                if (accountKeys != null) {
                    for (i in 0 until accountKeys.length()) {
                        val k = accountKeys.opt(i)
                        when (k) {
                            is JSONObject -> keys.add(k.optString("pubkey", ""))
                            is String -> keys.add(k)
                        }
                    }
                }
                if (sig.isNotBlank()) onTxCb?.invoke(sig, keys.filter { it.isNotBlank() }, result)
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "parse error: ${e.message?.take(80)}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ErrorLogger.warn(TAG, "❌ failure: ${t.message?.take(100)} — will reconnect")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (running.get()) scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!running.get()) return
        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = (1000L * (1 shl (attempt.coerceAtMost(5).toInt()))).coerceAtMost(30_000L)
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            connect()
        }.start()
    }
}

package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.484 — pump.fun WebSocket firehose via PumpPortal.
 *
 * Catches new pump.fun launches and migrations 5-30 seconds before
 * DexScreener / Birdeye see them — massive edge for the meme lane.
 *
 * Endpoint: wss://pumpportal.fun/api/data
 * Free subscriptions: subscribeNewToken + subscribeMigration
 * Paid subscriptions (skipped): subscribeTokenTrade, subscribeAccountTrade
 *
 * Usage:
 *   PumpFunWS.start(
 *     onNewToken = { mint, symbol, name, marketCapSol -> ... },
 *     onMigration = { mint -> ... },
 *   )
 *   PumpFunWS.stop()
 *
 * Auto-reconnects on disconnect with exponential backoff capped at 30s.
 * One persistent connection (per pump.fun rate limits — keep below 15
 * total connections per IP).
 */
object PumpFunWS {
    private const val TAG = "PumpFunWS"
    private const val URL = "wss://pumpportal.fun/api/data"

    private val running = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0L)
    @Volatile private var ws: WebSocket? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var onNewTokenCb: ((String, String, String, Double) -> Unit)? = null
    @Volatile private var onMigrationCb: ((String) -> Unit)? = null

    fun start(
        onNewToken: (mint: String, symbol: String, name: String, marketCapSol: Double) -> Unit,
        onMigration: (mint: String) -> Unit,
    ) {
        if (!running.compareAndSet(false, true)) {
            ErrorLogger.warn(TAG, "already running — start() ignored")
            return
        }
        onNewTokenCb = onNewToken
        onMigrationCb = onMigration
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        connect()
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
        val req = Request.Builder().url(URL).build()
        ErrorLogger.info(TAG, "🔌 connecting to $URL (attempt ${reconnectAttempt.get() + 1})")
        ws = client?.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt.set(0)
            ErrorLogger.info(TAG, "✅ connected — subscribing to subscribeNewToken + subscribeMigration (free)")
            webSocket.send(JSONObject().put("method", "subscribeNewToken").toString())
            webSocket.send(JSONObject().put("method", "subscribeMigration").toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val j = JSONObject(text)
                val txType = j.optString("txType", "")
                when {
                    txType == "create" || j.has("name") && j.has("symbol") && j.has("mint") -> {
                        val mint = j.optString("mint", "")
                        val symbol = j.optString("symbol", "?")
                        val name = j.optString("name", "?")
                        val marketCapSol = j.optDouble("marketCapSol", 0.0)
                        if (mint.isNotBlank()) onNewTokenCb?.invoke(mint, symbol, name, marketCapSol)
                    }
                    txType == "migrate" || j.optString("event", "") == "migration" -> {
                        val mint = j.optString("mint", j.optString("address", ""))
                        if (mint.isNotBlank()) onMigrationCb?.invoke(mint)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "parse error: ${e.message?.take(80)}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            ErrorLogger.info(TAG, "closing code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ErrorLogger.warn(TAG, "❌ failure: ${t.message?.take(100)} — will reconnect")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ErrorLogger.info(TAG, "closed code=$code")
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

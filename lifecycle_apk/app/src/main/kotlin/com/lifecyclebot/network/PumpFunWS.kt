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

    // V5.0.7278 §THE CURVE PRICES ITSELF ON EVERY TRADE.
    //
    // 5.0.7277: 31 of 32 open positions had no fresh mark. They were
    // bonding-curve launches the fast lane bought within seconds of the
    // create event; no aggregator lists them, the pump.fun frontend answered
    // 9% of calls, and the curve read from chain produced nothing. Yet the
    // same socket that announced each launch streams every buy and sell on
    // it, with the curve's virtual reserves in the payload — the spot price,
    // for free, sub-second. `subscribeTokenTrade` is a free data
    // subscription on this endpoint (the paid tier is the trading API, not
    // the stream); the header above was wrong about that.
    @Volatile private var onTradeCb: ((mint: String, priceSolPerToken: Double, marketCapSol: Double, isBuy: Boolean) -> Unit)? = null
    private val tradeSubscriptions7278 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val firstUntypedLogged7279 = AtomicBoolean(false)
    @Volatile private var lastUntypedFrame7280: String = ""
    private val untypedFrames7280 = AtomicLong(0L)

    /** V5.0.7280 — one line for the pipeline report: what the socket is doing. */
    fun status7280(): String =
        "running=${running.get()} socket=${if (ws != null) "open" else "none"} reconnects=${reconnectAttempt.get()} " +
            "tradeSubscribedMints=${tradeSubscriptions7278.size} untypedFrames=${untypedFrames7280.get()} " +
            "lastUntyped=${lastUntypedFrame7280.ifBlank { "-" }}"

    fun setOnTrade7278(cb: (mint: String, priceSolPerToken: Double, marketCapSol: Double, isBuy: Boolean) -> Unit) {
        onTradeCb = cb
    }

    /**
     * Keep the trade subscription equal to [mints]: subscribe the new ones,
     * unsubscribe the ones no longer held. Idempotent; safe every tick.
     */
    fun syncTradeSubscriptions7278(mints: Set<String>) {
        val wanted = mints.filter { it.isNotBlank() }.toSet()
        val add = wanted - tradeSubscriptions7278
        val drop = tradeSubscriptions7278 - wanted
        if (add.isEmpty() && drop.isEmpty()) return
        val sock = ws
        if (add.isNotEmpty()) {
            tradeSubscriptions7278.addAll(add)
            val sent7279 = sock?.send(JSONObject().put("method", "subscribeTokenTrade").put("keys", JSONArray(add.toList())).toString()) == true
            try {
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUBSCRIBED_7278")
                // V5.0.7279 — 7278 counted sync calls (17) and could not say how
                // many mints were on the stream, nor whether the frame reached a
                // live socket. Mints and frames are counted separately now.
                repeat(add.size) { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUBSCRIBED_MINTS_7279") }
                com.lifecyclebot.engine.PipelineHealthCollector.labelInc(
                    if (sent7279) "PUMP_TRADE_SUBSCRIBE_FRAME_SENT_7279" else "PUMP_TRADE_SUBSCRIBE_FRAME_QUEUED_NO_SOCKET_7279",
                )
            } catch (_: Throwable) {}
        }
        if (drop.isNotEmpty()) {
            tradeSubscriptions7278.removeAll(drop)
            sock?.send(JSONObject().put("method", "unsubscribeTokenTrade").put("keys", JSONArray(drop.toList())).toString())
        }
    }

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
            // V5.0.7278 — re-arm the held-mint trade stream after a reconnect.
            val held7278 = tradeSubscriptions7278.toList()
            if (held7278.isNotEmpty()) {
                webSocket.send(JSONObject().put("method", "subscribeTokenTrade").put("keys", JSONArray(held7278)).toString())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val j = JSONObject(text)
                val txType = j.optString("txType", "")
                // V5.0.7279 — every frame is counted by its own type, so a stream
                // that delivers no buy/sell frames is distinguishable from one
                // whose frames arrive under a name this parser does not read.
                // 5.0.7278: 17 subscriptions, 0 trade events, and nothing to say
                // which of the two it was.
                try {
                    val kind7279 = if (txType.isBlank()) {
                        if (j.has("message")) "message" else if (j.has("errors")) "error" else "untyped"
                    } else txType.lowercase().filter { it.isLetterOrDigit() || it == '_' }.take(16)
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_WS_FRAME_7279_$kind7279")
                    if (txType.isBlank() && firstUntypedLogged7279.compareAndSet(false, true)) {
                        ErrorLogger.info(TAG, "first untyped frame: ${text.take(220)}")
                    }
                    // V5.0.7280 — 5.0.7279: 85 `message` frames, 50 mints subscribed,
                    // zero trade frames, and the frame text never reached the
                    // snapshot. The last one is kept for the status line.
                    if (txType.isBlank()) {
                        lastUntypedFrame7280 = text.take(200).replace('\n', ' ')
                        untypedFrames7280.incrementAndGet()
                    }
                } catch (_: Throwable) {}
                when {
                    // V5.0.7278 — a trade on a held curve is a mark.
                    txType == "buy" || txType == "sell" -> {
                        val mint = j.optString("mint", "")
                        if (mint.isBlank()) return
                        try { PumpCurveKeys7269.remember(mint, j.optString("bondingCurveKey", "")) } catch (_: Throwable) {}
                        val vSol = j.optDouble("vSolInBondingCurve", 0.0)
                        val vTok = j.optDouble("vTokensInBondingCurve", 0.0)
                        val mcapSol = j.optDouble("marketCapSol", 0.0)
                        if (!vSol.isFinite() || !vTok.isFinite() || vSol <= 0.0 || vTok <= 0.0) return
                        val priceSol = vSol / vTok
                        if (!priceSol.isFinite() || priceSol <= 0.0) return
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_EVENT_7278") } catch (_: Throwable) {}
                        onTradeCb?.invoke(mint, priceSol, mcapSol, txType == "buy")
                    }
                    txType == "create" || j.has("name") && j.has("symbol") && j.has("mint") -> {
                        val mint = j.optString("mint", "")
                        val symbol = j.optString("symbol", "?")
                        val name = j.optString("name", "?")
                        val marketCapSol = j.optDouble("marketCapSol", 0.0)
                        if (mint.isBlank()) return
                        // V5.0.7269 — the curve address rides in this payload; keep
                        // it so the mark fan-out can read the curve from chain state
                        // when no aggregator lists the mint. Recorded before the
                        // throttle so a held mint is never left without its key.
                        try {
                            PumpCurveKeys7269.remember(mint, j.optString("bondingCurveKey", ""))
                        } catch (_: Throwable) {}
                        // V5.0.4168 — PumpPortal WS adaptive throttle. Drops
                        // dust-mcap creates BEFORE they trigger the cascade
                        // (provider proofs, rugcheck, Birdeye/DexScreener
                        // enrichment) that drives the bulk of mobile-data
                        // burn. Threshold rises when watchlist saturates.
                        // V5.0.7279 — the create frame carries the curve's virtual
                        // reserves after the dev buy: the spot price at t=0, from
                        // the same field pair every trade frame carries. Delivered
                        // through the trade callback after intake has created the
                        // token row, so the first mark exists before the first
                        // evaluation instead of after the first aggregator poll.
                        // V5.0.7280 — and remembered as the launch price before the
                        // throttle, so a later ticket's multiple over it is known.
                        val vSol0 = j.optDouble("vSolInBondingCurve", 0.0)
                        val vTok0 = j.optDouble("vTokensInBondingCurve", 0.0)
                        val priceSol0 = if (vSol0.isFinite() && vTok0.isFinite() && vSol0 > 0.0 && vTok0 > 0.0) vSol0 / vTok0 else 0.0
                        if (priceSol0.isFinite() && priceSol0 > 0.0) {
                            try { PumpCurveKeys7269.rememberCreate7280(mint, priceSol0, System.currentTimeMillis()) } catch (_: Throwable) {}
                        }
                        if (!com.lifecyclebot.engine.PumpPortalThrottle.allowCreate(marketCapSol)) return
                        onNewTokenCb?.invoke(mint, symbol, name, marketCapSol)
                        if (priceSol0.isFinite() && priceSol0 > 0.0) {
                            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_CREATE_MARK_EMITTED_7279") } catch (_: Throwable) {}
                            onTradeCb?.invoke(mint, priceSol0, marketCapSol, true)
                        }
                    }
                    txType == "migrate" || j.optString("event", "") == "migration" -> {
                        val mint = j.optString("mint", j.optString("address", ""))
                        if (mint.isBlank()) return
                        com.lifecyclebot.engine.PumpPortalThrottle.allowMigrate()
                        onMigrationCb?.invoke(mint)
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

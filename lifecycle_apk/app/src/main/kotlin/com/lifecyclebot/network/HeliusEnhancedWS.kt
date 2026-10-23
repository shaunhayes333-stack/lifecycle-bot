package com.lifecyclebot.network

import com.lifecyclebot.engine.ApiBackoff
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
    /** V5.0.7881 — every wallet asked for, in priority order; watchedAccounts is the affordable prefix. */
    @Volatile private var requestedAccounts: List<String> = emptyList()
    @Volatile private var lastWidthCheckMs7881 = 0L
    private const val WIDTH_CHECK_MS_7881 = 5L * 60_000L
    @Volatile private var subscriptionId: Long = -1L
    @Volatile private var onTxCb: ((sig: String, accounts: List<String>, raw: JSONObject) -> Unit)? = null

    // ── V5.0.8307 — the parsed trade tape for held / near-decision mints rides this socket (PumpSwapTape8307) ──
    @Volatile private var tapeAccounts8307: List<String> = emptyList()
    @Volatile private var tapeIncluded8307: Set<String> = emptySet()
    @Volatile private var tapeSubId8307: Long = -1L
    @Volatile private var tapeReqId8307: Long = -1L
    @Volatile private var whaleReqId8307: Long = -1L
    @Volatile private var tapeSubscribedAtMs8307 = 0L
    @Volatile private var capability8307 = "UNPROBED"
    @Volatile private var lastError8307 = "-"
    private val tapeResubs8307 = AtomicLong(0L)
    private const val TAPE_RESUB_MIN_MS_8307 = 60_000L

    /** Pure: the plan capability an Enhanced-WS JSON-RPC error states (UNSUPPORTED for a method / plan refusal, else ERROR). */
    internal fun capabilityFromError8307(code: Int, message: String): String {
        val m = message.lowercase()
        return if (code == -32601 || m.contains("not supported") || m.contains("not available") || m.contains("plan") ||
            m.contains("upgrade") || m.contains("method not found")) "UNSUPPORTED_PLAN" else "ERROR"
    }

    /** Pure: may the tape subscription be re-sent now ([last] = previous send; an empty previous set re-sends at once)? */
    internal fun tapeResubDue8307(prevEmpty: Boolean, lastMs: Long, nowMs: Long): Boolean =
        prevEmpty || nowMs - lastMs >= TAPE_RESUB_MIN_MS_8307

    fun capabilityLine8307(): String =
        "enhancedWs=$capability8307 whaleSub=${if (subscriptionId >= 0) "ACK" else "NONE"} whaleAccounts=${watchedAccounts.size} " +
            "tapeSub=${if (tapeSubId8307 >= 0) "ACK" else "NONE"} tapeMints=${tapeAccounts8307.size} tapeResubs=${tapeResubs8307.get()} lastError=$lastError8307 " +
            "curveSub8354=${curveSubState8354()}"

    // ── V5.0.8354 — ONE program-wide subscription on the pump.fun bonding-curve program (HeliusCurveTape8354) ──
    @Volatile private var curveWanted8354 = false
    @Volatile private var curveReqId8354 = -1L
    @Volatile private var curveSubId8354 = -1L
    @Volatile private var curveRefused8354 = "-"

    /** The plan accepts Enhanced subscriptions on an open socket, and the program subscription was not refused. */
    fun enhancedOk8354(): Boolean = running.get() && ws != null && capability8307 == "OK" && curveRefused8354 == "-"

    fun curveSubState8354(): String = when {
        curveSubId8354 >= 0L -> "ACK"
        curveReqId8354 >= 0L -> "PENDING"
        curveRefused8354 != "-" -> "REFUSED_$curveRefused8354"
        else -> "NONE"
    }

    /** HeliusCurveTape8354: the program subscription on (sent once, re-sent on every new socket) or off. */
    fun setCurveProgram8354(on: Boolean) {
        val was = curveWanted8354
        curveWanted8354 = on
        val sock = ws ?: return
        try {
            if (on && curveSubId8354 < 0L && curveReqId8354 < 0L) sendCurveSubscribe8354(sock)
            else if (!on && was) sendCurveUnsubscribe8354(sock)
        } catch (_: Throwable) {}
    }

    private fun sendCurveSubscribe8354(socket: WebSocket) {
        if (capability8307 == "UNSUPPORTED_PLAN" || curveRefused8354 != "-") return
        val id = nextRpcId.getAndIncrement()
        curveReqId8354 = id
        socket.send(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "transactionSubscribe")
            put("params", JSONArray().apply {
                put(JSONObject().put("accountInclude", JSONArray().put(HeliusCurveTape8354.PUMP_PROGRAM_8354)).put("vote", false).put("failed", false))
                put(JSONObject().apply {
                    put("commitment", "processed")
                    put("encoding", "jsonParsed")
                    put("transactionDetails", "full")
                    put("showRewards", false)
                    put("maxSupportedTransactionVersion", 0)
                })
            })
        }.toString())
    }

    private fun sendCurveUnsubscribe8354(socket: WebSocket) {
        val sub = curveSubId8354
        curveSubId8354 = -1L
        curveReqId8354 = -1L
        if (sub < 0L) return
        socket.send(JSONObject().put("jsonrpc", "2.0").put("id", nextRpcId.getAndIncrement())
            .put("method", "transactionUnsubscribe").put("params", JSONArray().put(sub)).toString())
    }

    /**
     * DataOrchestrator (every 10 s): the mints whose every swap should arrive parsed. Re-subscribes at most once a minute
     * (a set that only reorders never re-subscribes). No socket / not running: remembered for the next open.
     */
    fun updateTapeAccounts8307(accounts: List<String>, nowMs: Long = System.currentTimeMillis()) {
        val next = accounts.filter { it.isNotBlank() }.distinct().take(PumpSwapTape8307.TAPE_MAX_8307 + 8)
        if (next.toSet() == tapeAccounts8307.toSet() && (tapeSubId8307 >= 0 || next.isEmpty())) return
        if (!tapeResubDue8307(tapeAccounts8307.isEmpty(), tapeSubscribedAtMs8307, nowMs)) return
        tapeAccounts8307 = next
        val sock = ws ?: return
        try { sendTapeSubscribe8307(sock, nowMs) } catch (_: Throwable) {}
    }

    private fun sendTapeSubscribe8307(socket: WebSocket, nowMs: Long = System.currentTimeMillis()) {
        if (capability8307 == "UNSUPPORTED_PLAN") return
        if (tapeSubId8307 >= 0) {
            socket.send(JSONObject().put("jsonrpc", "2.0").put("id", nextRpcId.getAndIncrement())
                .put("method", "transactionUnsubscribe").put("params", JSONArray().put(tapeSubId8307)).toString())
            tapeSubId8307 = -1L
        }
        tapeIncluded8307 = tapeAccounts8307.toSet()
        tapeSubscribedAtMs8307 = nowMs
        if (tapeAccounts8307.isEmpty()) return
        val id = nextRpcId.getAndIncrement()
        tapeReqId8307 = id
        tapeResubs8307.incrementAndGet()
        socket.send(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "transactionSubscribe")
            put("params", JSONArray().apply {
                put(JSONObject().put("accountInclude", JSONArray(tapeAccounts8307)).put("vote", false).put("failed", false))
                put(JSONObject().apply {
                    put("commitment", "confirmed")
                    put("encoding", "jsonParsed")
                    put("transactionDetails", "full")
                    put("showRewards", false)
                    put("maxSupportedTransactionVersion", 0)
                })
            })
        }.toString())
    }

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
        requestedAccounts = watchAccounts.distinct()
        watchedAccounts = affordable7881(requestedAccounts)
        onTxCb = onTransaction
        client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        connect()
    }

    /** V5.0.8361 — the smart-money subscription (whales + alpha list) is acknowledged on an open socket. */
    fun whaleSubLive8361(): Boolean = running.get() && ws != null && subscriptionId >= 0L

    /** V5.0.7277 — so a later discovery can tell whether to start or to update. */
    fun isRunning(): Boolean = running.get()
    fun watchedAccounts7277(): List<String> = watchedAccounts

    /**
     * V5.0.7881 — the stream is billed by the bytes it delivers. Its width is the
     * affordable prefix of the requested list (HeliusCreditEconomy7881.streamWidth:
     * the COPY signal's measured value, narrowed when the stream's spend runs ahead
     * of its paced share).
     */
    private fun affordable7881(requested: List<String>): List<String> = try {
        // V5.0.7962 — OWNER and top-trader wallets first, so the affordable prefix always watches them.
        com.lifecyclebot.engine.ExpertWallets7962.prioritise7962(requested)
            .take(com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.streamWidth(requested.size))
    } catch (_: Throwable) { requested }

    fun updateWatchlist(newAccounts: List<String>) {
        requestedAccounts = newAccounts.distinct()
        val next = affordable7881(requestedAccounts)
        if (next == watchedAccounts && subscriptionId >= 0) return
        watchedAccounts = next
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
        val whaleId8307 = nextRpcId.getAndIncrement()
        whaleReqId8307 = whaleId8307
        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", whaleId8307)
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
            tapeSubId8307 = -1L   // V5.0.8307 — a new socket: the tape subscription is sent again
            try { sendTapeSubscribe8307(webSocket) } catch (_: Throwable) {}
            curveSubId8354 = -1L; curveReqId8354 = -1L   // V5.0.8354 — and the program subscription, when wanted
            if (curveWanted8354) try { sendCurveSubscribe8354(webSocket) } catch (_: Throwable) {}
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // V5.0.8307 — the tape subscription's bytes are metered on their own stream (never the whale stream's share).
            val subId8354 = HeliusSocketShare8191.subscriptionId8191(text)
            val tape8307 = tapeSubId8307 >= 0 && subId8354 == tapeSubId8307
            // V5.0.8354 — the program subscription's bytes are metered on their own stream (its governor's clock).
            val curve8354 = curveSubId8354 >= 0L && subId8354 == curveSubId8354
            if (curve8354) {
                try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.meterWs("HELIUS_CURVE_8354", text.length) } catch (_: Throwable) {}
                HeliusCurveTape8354.noteBytes8354(text.length)
            } else if (tape8307) {
                try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.meterWs("HELIUS_TAPE_8307", text.length) } catch (_: Throwable) {}
                PumpSwapTape8307.noteTapeBytes8307(text.length)
            } else try { com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.meterWs("SMART_MONEY_STREAM", text.length) } catch (_: Throwable) {}
            val now7881 = System.currentTimeMillis()
            if (now7881 - lastWidthCheckMs7881 >= WIDTH_CHECK_MS_7881) {
                lastWidthCheckMs7881 = now7881
                try { if (affordable7881(requestedAccounts) != watchedAccounts) updateWatchlist(requestedAccounts) } catch (_: Throwable) {}
            }
            try {
                // V5.0.8305 — oversize / out-of-heap frames are dropped (NetParseGuard8305), never fatal on the reader thread.
                val msg = NetParseGuard8305.parseObject8305("SMART_MONEY_STREAM", text, NetParseGuard8305.FRAME_CAP_TX_8305) ?: return
                // Subscription confirmation
                if (msg.has("result") && msg.opt("result") is Number) {
                    // V5.0.8307 — the tape subscription's ack is told apart by its request id.
                    // V5.0.8354 — and the program subscription's by its own.
                    val ackId8354 = msg.optLong("id", -1L)
                    if (curveReqId8354 >= 0L && ackId8354 == curveReqId8354) { curveSubId8354 = msg.getLong("result"); curveReqId8354 = -1L }
                    else if (tapeReqId8307 >= 0 && ackId8354 == tapeReqId8307) tapeSubId8307 = msg.getLong("result")
                    else if (ackId8354 != tapeReqId8307 && ackId8354 != curveReqId8354) subscriptionId = msg.getLong("result")
                    capability8307 = "OK"
                    return
                }
                // V5.0.8307 — a refused subscribe names the plan capability (reported, and the tape is not re-sent).
                if (msg.has("error") && msg.has("id")) {
                    val e8307 = msg.optJSONObject("error")
                    val code8307 = e8307?.optInt("code", 0) ?: 0
                    val m8307 = e8307?.optString("message", "").orEmpty()
                    lastError8307 = "$code8307:${m8307.take(48).replace(' ', '_')}"
                    // V5.0.8354 — a refused program subscription parks only itself (the whale and tape streams stand).
                    if (curveReqId8354 >= 0L && msg.optLong("id", -1L) == curveReqId8354) {
                        curveRefused8354 = "$code8307:${m8307.take(32).replace(' ', '_')}"
                        curveReqId8354 = -1L
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CURVE_TAPE_ENHANCED_SUB_REFUSED_8354") } catch (_: Throwable) {}
                        return
                    }
                    if (msg.optLong("id", -1L) == tapeReqId8307 || msg.optLong("id", -1L) == whaleReqId8307) capability8307 = capabilityFromError8307(code8307, m8307)
                    return
                }
                // Transaction notification
                if (msg.optString("method", "") != "transactionNotification") return
                val params = msg.optJSONObject("params") ?: return
                val result = params.optJSONObject("result") ?: return
                if (tapeSubId8307 >= 0 && params.optLong("subscription", -2L) == tapeSubId8307) {
                    PumpSwapTape8307.onEnhancedTx8307(result, tapeIncluded8307)   // V5.0.8307 — onto the trade tape, never the whale callback
                    return
                }
                if (curveSubId8354 >= 0L && params.optLong("subscription", -2L) == curveSubId8354) {
                    HeliusCurveTape8354.onEnhancedTx8354(result)   // V5.0.8354 — the program tape: filtered, then the curve tape
                    return
                }
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
            } catch (_: OutOfMemoryError) { NetParseGuard8305.dropOom8305("SMART_MONEY_STREAM")  // V5.0.8305 — never fatal on the reader thread
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "parse error: ${e.message?.take(80)}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            subscriptionId = -1L   // V5.0.8361 — a dead socket holds no subscription (whaleSubLive8361 reads false)
            val msg = t.message ?: ""
            // V5.9.953 — auth-fatal detection. 403 Forbidden means the API
            // key is wrong/restricted; reconnecting every 30s for hours
            // (operator dump: attempt 222 in 6700s) burns battery + log
            // noise for zero recovery chance. Detect 401/403 and back off
            // to 10 minutes — gives the operator time to rotate the key
            // without spamming.
            val code = response?.code ?: when {
                msg.contains("429") -> 429
                msg.contains("403") -> 403
                msg.contains("401") -> 401
                else -> 0
            }
            // V5.0.7279 — the websocket's handshake refusals used to arm the
            // shared "helius" REST backoff, so an Enhanced-WS 401/403 (a plan
            // tier, not the key) short-circuited every HealthAwareHttp call
            // labelled helius — 3,591 synthetic 503s on the pump-curve read on
            // 5.0.7278 while the helius REST row read 95% with zero 4xx/5xx.
            // The socket backs itself off (below); the REST key stays clean.
            try { if (code in 400..599) ApiBackoff.markFailure("helius_ws", code) } catch (_: Throwable) {}
            if (code == 401 || code == 403) capability8307 = "REFUSED_$code"   // V5.0.8307 — the plan / key refused the Enhanced socket
            val isRateLimited = code == 429 || msg.contains("429")
            val isAuthFatal = code == 403 || code == 401 || msg.contains("403") || msg.contains("401")
            if (isRateLimited) {
                // V5.9.1184 — Helius 429 is a hard upstream quota signal, not a
                // normal transient websocket close. Runtime 5.0.3145 showed Helius
                // success=0%, 4xx=288 and reconnect attempt 29/30 every ~30s. That
                // log churn steals IO/runtime budget while producing zero trade data.
                // Back off long enough for the quota bucket to cool; fail-open because
                // PumpPortal/Dex/Birdeye/scanner lanes continue independently.
                ErrorLogger.warn(TAG, "🛑 RATE-LIMIT failure: ${msg.take(100)} — backing off 15min (Helius 429)")
                scheduleReconnect(forceDelayMs = 900_000L)
            } else if (isAuthFatal) {
                ErrorLogger.warn(TAG, "🛑 AUTH-FATAL failure: ${msg.take(100)} — backing off 10min (rotate Helius key)")
                scheduleReconnect(forceDelayMs = 600_000L)
            } else {
                ErrorLogger.warn(TAG, "❌ failure: ${msg.take(100)} — will reconnect")
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            subscriptionId = -1L   // V5.0.8361
            if (running.get()) scheduleReconnect()
        }
    }

    private fun scheduleReconnect(forceDelayMs: Long = -1L) {
        if (!running.get()) return
        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = if (forceDelayMs > 0L) {
            forceDelayMs
        } else {
            (1000L * (1 shl (attempt.coerceAtMost(5).toInt()))).coerceAtMost(30_000L)
        }
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            connect()
        }.start()
    }
}

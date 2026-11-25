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
    private val reconnectPending8795 = AtomicBoolean(false)
    private val lifecycleEpoch8795 = AtomicLong(0L)
    private val nextRpcId = AtomicLong(420L)
    @Volatile private var ws: WebSocket? = null
    // 8795 — a non-null OkHttp WebSocket is not proof it is connected.
    // onFailure/onClosed formerly left the dead socket object and ACK ids
    // visible for the entire reconnect backoff (up to 15 min after a 429).
    @Volatile private var socketOpen8795 = false
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
    @Volatile private var tapeReqAtMs8795 = 0L
    @Volatile private var whaleReqAtMs8795 = 0L
    @Volatile private var tapeSubscribedAtMs8307 = 0L
    @Volatile private var tapeRetryAtMs8794 = 0L
    @Volatile private var capability8307 = "UNPROBED"
    @Volatile private var lastError8307 = "-"
    private val tapeResubs8307 = AtomicLong(0L)
    private const val TAPE_RESUB_MIN_MS_8307 = 60_000L

    /** Pure: the plan capability an Enhanced-WS JSON-RPC error states (UNSUPPORTED for a method / plan refusal, else ERROR). */
    internal fun capabilityFromError8307(code: Int, message: String): String {
        val m = message.lowercase()
        // 8794: a provider message such as "monthly plan credits exhausted"
        // contains "plan" but is NOT evidence that transactionSubscribe is
        // unsupported. Misclassifying it permanently disabled tape re-subscribe.
        if (retryableSubscriptionRefusal8794(code, message)) return "RETRYABLE_QUOTA"
        return if (code == -32601 || m.contains("not supported") || m.contains("not available") || m.contains("plan") ||
            m.contains("upgrade") || m.contains("method not found")) "UNSUPPORTED_PLAN" else "ERROR"
    }

    /** Pure: may the tape subscription be re-sent now ([last] = previous send; an empty previous set re-sends at once)? */
    internal fun tapeResubDue8307(prevEmpty: Boolean, lastMs: Long, nowMs: Long): Boolean =
        prevEmpty || nowMs - lastMs >= TAPE_RESUB_MIN_MS_8307

    fun capabilityLine8307(): String =
        "enhancedWs=$capability8307 socket=${if (connectedFeed8795(running.get(), socketOpen8795, ws != null)) "OPEN" else "DOWN"} whaleSub=${if (whaleSubLive8361()) "ACK" else if (whaleReqId8307 >= 0L && socketOpen8795) "PENDING" else "NONE"} whaleAccounts=${watchedAccounts.size} " +
            "tapeSub=${if (socketOpen8795 && tapeSubId8307 >= 0L) "ACK" else if (tapeReqId8307 >= 0L && socketOpen8795) "PENDING" else "NONE"} tapeMints=${tapeAccounts8307.size} tapeResubs=${tapeResubs8307.get()} lastError=$lastError8307 " +
            "curveSub8354=${curveSubState8354()} curveRetryS8794=${((curveRetryAtMs8794 - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)} " +
            "tapeRetryS8794=${((tapeRetryAtMs8794 - System.currentTimeMillis()).coerceAtLeast(0L) / 1000L)}"

    // ── V5.0.8354 — ONE program-wide subscription on the pump.fun bonding-curve program (HeliusCurveTape8354) ──
    @Volatile private var curveWanted8354 = false
    @Volatile private var curveReqId8354 = -1L
    @Volatile private var curveSubId8354 = -1L
    @Volatile private var curveRefused8354 = "-"
    @Volatile private var curveReqAtMs8794 = 0L
    // 8794: subscription-level quota refusals do not close the websocket.
    // Previously a 429 stuck in curveRefused8354 forever, even after the user
    // replenished credits: the curve transport never re-probed. Keep permanent
    // plan/invalid-request refusals parked, but retry transient provider faults.
    @Volatile private var curveRetryAtMs8794 = 0L
    private const val CURVE_RETRY_MS_8794 = 15L * 60_000L

    internal fun retryableSubscriptionRefusal8794(code: Int, message: String): Boolean {
        val m = message.lowercase()
        return code == 429 || code == -32005 || code in 500..599 ||
            m.contains("quota") || m.contains("credit") || m.contains("rate limit") ||
            m.contains("too many requests") || m.contains("temporarily unavailable")
    }

    internal fun retryDue8794(untilMs: Long, nowMs: Long): Boolean =
        untilMs > 0L && nowMs >= untilMs

    /** Lost subscribe ACK must not leave a program stream PENDING forever. */
    internal fun curveAckOverdue8794(requestId: Long, sentAtMs: Long, nowMs: Long): Boolean =
        requestId >= 0L && sentAtMs > 0L && nowMs - sentAtMs >= 90_000L

    private fun recoverTransientCurveRefusal8794(nowMs: Long = System.currentTimeMillis()) {
        if (curveAckOverdue8794(curveReqId8354, curveReqAtMs8794, nowMs)) {
            curveReqId8354 = -1L
            curveReqAtMs8794 = 0L
            curveRefused8354 = "ACK_TIMEOUT_8794"
            curveRetryAtMs8794 = nowMs + 60_000L
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_CURVE_ACK_TIMEOUT_8794") } catch (_: Throwable) {}
        }
        // A tape/whale 429 marks the entire enhanced capability retryable.
        // Clearing only the curve-specific refusal is insufficient: when no
        // tape account is queued there may never be another ACK to turn a
        // RETRYABLE_QUOTA capability back into a probeable one.
        if (capability8307 == "RETRYABLE_QUOTA" && retryDue8794(tapeRetryAtMs8794, nowMs)) {
            capability8307 = "UNPROBED"
            tapeRetryAtMs8794 = 0L
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_QUOTA_REPROBE_8794") } catch (_: Throwable) {}
        }
        if (retryDue8794(curveRetryAtMs8794, nowMs)) {
            curveRetryAtMs8794 = 0L
            curveRefused8354 = "-"
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_CURVE_QUOTA_REPROBE_8794") } catch (_: Throwable) {}
        }
    }

    internal fun enhancedCanProbe8794(capability: String): Boolean =
        capability == "OK" || capability == "UNPROBED"

    internal fun connectedFeed8795(running: Boolean, open: Boolean, socketPresent: Boolean): Boolean =
        running && open && socketPresent

    /**
     * A 429 at the WebSocket handshake can be a burst limit OR exhausted credits. A fixed 15-minute
     * wait hid the owner's credit refill for the entire cooldown. Respect Retry-After when present;
     * otherwise probe infrequently with a bounded exponential delay (1, 2, 4, 8, 15 minutes).
     * The reconnect lease below ensures only one outstanding probe for each socket generation.
     */
    internal fun retry429DelayMs8797(failures: Long, retryAfter: String?): Long {
        val serverMs = retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { s ->
            if (s > 900L) 900_000L else s * 1000L
        }
        if (serverMs != null) return serverMs.coerceIn(15_000L, 900_000L)
        val shift = (failures - 1L).coerceIn(0L, 4L).toInt()
        return (60_000L shl shift).coerceAtMost(900_000L)
    }

    /**
     * A connected but unprobed socket may try its FIRST program subscription.
     * Previously capability=UNPROBED required another whale/tape ACK before
     * selecting the curve transport; when neither feed had an ACK, nothing
     * ever sent the request that could establish capability in the first place.
     * Explicit plan and quota refusals remain excluded until recovered.
     */
    fun enhancedOk8354(): Boolean {
        recoverTransientCurveRefusal8794()
        return connectedFeed8795(running.get(), socketOpen8795, ws != null) && enhancedCanProbe8794(capability8307) &&
            curveRefused8354 == "-"
    }

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
        val sock = ws?.takeIf { socketOpen8795 } ?: return
        try {
            if (on && curveSubId8354 < 0L && curveReqId8354 < 0L) sendCurveSubscribe8354(sock)
            else if (!on && was) sendCurveUnsubscribe8354(sock)
        } catch (_: Throwable) {}
    }

    private fun sendCurveSubscribe8354(socket: WebSocket) {
        if (capability8307 == "UNSUPPORTED_PLAN" || curveRefused8354 != "-") return
        val id = nextRpcId.getAndIncrement()
        curveReqId8354 = id
        curveReqAtMs8794 = System.currentTimeMillis()
        val queued8794 = socket.send(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "transactionSubscribe")
            put("params", JSONArray().apply {
                put(JSONObject().put("accountInclude", JSONArray().put(HeliusCurveTape8354.PUMP_PROGRAM_8354)).put("vote", false).put("failed", false))
                put(JSONObject().apply {
                    put("commitment", "processed")
                    // V5.0.8829 — base64, not jsonParsed: the program tape reads only the signature, meta.err and meta.logMessages
                    // (HeliusCurveTape8354.onEnhancedTx8354), which stay JSON either way; the parsed message and parsed inner
                    // instructions were most of each frame. The 8820 run held the tape in its per-mint fallback (stepDowns=6)
                    // while the day's Helius credits stood at 21 % of the plan: the bytes, not the credits, switched it off.
                    put("encoding", "base64")
                    put("transactionDetails", "full")
                    put("showRewards", false)
                    put("maxSupportedTransactionVersion", 0)
                })
            })
        }.toString())
        // OkHttp returns false when a frame was never queued. An unacknowledged
        // request ID otherwise leaves the program subscription PENDING forever.
        if (!queued8794) {
            curveReqId8354 = -1L
            curveReqAtMs8794 = 0L
            curveRefused8354 = "SOCKET_SEND_NOT_QUEUED_8794"
            curveRetryAtMs8794 = System.currentTimeMillis() + 60_000L
        }
    }

    private fun sendCurveUnsubscribe8354(socket: WebSocket) {
        val sub = curveSubId8354
        curveSubId8354 = -1L
        curveReqId8354 = -1L
        curveReqAtMs8794 = 0L
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
        // This is invoked by the orchestrator every 10 seconds. A live socket
        // with a lost whale ACK cannot otherwise recover: whale filters may
        // remain unchanged indefinitely after the original request.
        recoverMissingSubscriptionAcks8795(nowMs)
        if (next.toSet() == tapeAccounts8307.toSet() && (tapeSubId8307 >= 0 || next.isEmpty())) return
        if (!tapeResubDue8307(tapeAccounts8307.isEmpty(), tapeSubscribedAtMs8307, nowMs)) return
        tapeAccounts8307 = next
        val sock = ws?.takeIf { socketOpen8795 } ?: return
        try { sendTapeSubscribe8307(sock, nowMs) } catch (_: Throwable) {}
    }

    private fun sendTapeSubscribe8307(socket: WebSocket, nowMs: Long = System.currentTimeMillis()) {
        if (capability8307 == "UNSUPPORTED_PLAN") return
        if (nowMs < tapeRetryAtMs8794) return // Quota refill retry, not a permanent plan refusal.
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
        tapeReqAtMs8795 = nowMs
        tapeResubs8307.incrementAndGet()
        val queued8795 = socket.send(JSONObject().apply {
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
        if (!queued8795) {
            tapeReqId8307 = -1L
            tapeReqAtMs8795 = 0L
            tapeRetryAtMs8794 = nowMs + 60_000L
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_TAPE_SEND_NOT_QUEUED_8795") } catch (_: Throwable) {}
        }
    }

    /** A missing JSON-RPC ACK is not a live subscription. Re-probe at most
     * once per 90 seconds, observing provider quota/plan backoff on the same
     * connected socket, and ignore any late ACK from an expired request. */
    private fun recoverMissingSubscriptionAcks8795(nowMs: Long) {
        val sock = ws?.takeIf { connectedFeed8795(running.get(), socketOpen8795, true) } ?: return
        if (capability8307 == "UNSUPPORTED_PLAN" || nowMs < tapeRetryAtMs8794) return
        if (curveAckOverdue8794(whaleReqId8307, whaleReqAtMs8795, nowMs)) {
            whaleReqId8307 = -1L
            whaleReqAtMs8795 = 0L
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WHALE_ACK_TIMEOUT_8795") } catch (_: Throwable) {}
        }
        if (curveAckOverdue8794(tapeReqId8307, tapeReqAtMs8795, nowMs)) {
            tapeReqId8307 = -1L
            tapeReqAtMs8795 = 0L
            tapeSubscribedAtMs8307 = 0L
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_TAPE_ACK_TIMEOUT_8795") } catch (_: Throwable) {}
        }
        if (watchedAccounts.isNotEmpty() && subscriptionId < 0L && whaleReqId8307 < 0L) {
            try { sendSubscribe(sock, nowMs) } catch (_: Throwable) {}
        }
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
        lifecycleEpoch8795.incrementAndGet()
        reconnectPending8795.set(false)
        // An explicit stop/start (including after a credit refill or key change)
        // must re-probe the new socket. Old subscription IDs and sticky refusals
        // belong to the old connection, never the new one.
        capability8307 = "UNPROBED"
        socketOpen8795 = false
        lastError8307 = "-"
        subscriptionId = -1L
        tapeSubId8307 = -1L
        tapeReqId8307 = -1L
        tapeReqAtMs8795 = 0L
        tapeRetryAtMs8794 = 0L
        whaleReqId8307 = -1L
        whaleReqAtMs8795 = 0L
        curveSubId8354 = -1L
        curveReqId8354 = -1L
        curveReqAtMs8794 = 0L
        curveRefused8354 = "-"
        curveRetryAtMs8794 = 0L
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
    fun whaleSubLive8361(): Boolean = connectedFeed8795(running.get(), socketOpen8795, ws != null) && subscriptionId >= 0L

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
        // V5.0.9020 — the owner's named traders are watched whole; the width the copy book's record sets applies to the rest
        // (5.0.9016: 3 of 26 wallets subscribed, the list would have been cut to a fifth).
        com.lifecyclebot.engine.truth.NamedTraders9020.streamList9020(
            com.lifecyclebot.engine.ExpertWallets7962.prioritise7962(requested)) { n -> com.lifecyclebot.engine.truth.HeliusCreditEconomy7881.streamWidth(n) }
    } catch (_: Throwable) { requested }

    fun updateWatchlist(newAccounts: List<String>) {
        requestedAccounts = newAccounts.distinct()
        val next = affordable7881(requestedAccounts)
        if (next == watchedAccounts && subscriptionId >= 0) return
        watchedAccounts = next
        // Re-subscribe with new filter (Helius doesn't support live filter mutation
        // on an existing subscription — easiest is unsubscribe + subscribe).
        ws?.takeIf { socketOpen8795 }?.let { sock ->
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
        lifecycleEpoch8795.incrementAndGet()
        reconnectPending8795.set(false)
        socketOpen8795 = false
        try { ws?.close(1000, "client stop") } catch (_: Exception) {}
        ws = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        client = null
        ErrorLogger.info(TAG, "stopped")
    }

    private fun connect() {
        if (!running.get()) return
        socketOpen8795 = false
        val url = URL_TEMPLATE.format(apiKey)
        val req = Request.Builder().url(url).build()
        ErrorLogger.info(TAG, "🔌 connecting (attempt ${reconnectAttempt.get() + 1}, watching ${watchedAccounts.size} accounts)")
        ws = client?.newWebSocket(req, listener)
    }

    private fun sendSubscribe(socket: WebSocket, nowMs: Long = System.currentTimeMillis()) {
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
        whaleReqAtMs8795 = nowMs
        val req = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", whaleId8307)
            put("method", "transactionSubscribe")
            put("params", params)
        }
        if (!socket.send(req.toString())) {
            whaleReqId8307 = -1L
            whaleReqAtMs8795 = 0L
            tapeRetryAtMs8794 = nowMs + 60_000L
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WHALE_SEND_NOT_QUEUED_8795") } catch (_: Throwable) {}
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!running.get()) { try { webSocket.close(1000, "stopped") } catch (_: Throwable) {}; return }
            if (ws !== webSocket) { try { webSocket.close(1000, "obsolete") } catch (_: Throwable) {}; return }
            socketOpen8795 = true
            reconnectAttempt.set(0)
            ErrorLogger.info(TAG, "✅ connected — subscribing to ${watchedAccounts.size} whale wallets")
            sendSubscribe(webSocket)
            tapeSubId8307 = -1L   // V5.0.8307 — a new socket: the tape subscription is sent again
            try { sendTapeSubscribe8307(webSocket) } catch (_: Throwable) {}
            curveSubId8354 = -1L; curveReqId8354 = -1L; curveReqAtMs8794 = 0L   // V5.0.8354 — and the program subscription, when wanted
            if (curveWanted8354) try { sendCurveSubscribe8354(webSocket) } catch (_: Throwable) {}
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!socketOpen8795 || ws !== webSocket) return
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
                    if (curveReqId8354 >= 0L && ackId8354 == curveReqId8354) {
                        curveSubId8354 = msg.getLong("result"); curveReqId8354 = -1L; curveReqAtMs8794 = 0L
                    } else if (tapeReqId8307 >= 0L && ackId8354 == tapeReqId8307) {
                        tapeSubId8307 = msg.getLong("result"); tapeReqId8307 = -1L; tapeReqAtMs8795 = 0L
                    } else if (whaleReqId8307 >= 0L && ackId8354 == whaleReqId8307) {
                        subscriptionId = msg.getLong("result"); whaleReqId8307 = -1L; whaleReqAtMs8795 = 0L
                    } else {
                        // A delayed ACK for an expired request is not proof
                        // that the whale stream subscribed. Previously EVERY
                        // unmatched numeric result became subscriptionId.
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_STALE_SUB_ACK_8794") } catch (_: Throwable) {}
                        return
                    }
                    capability8307 = "OK"
                    tapeRetryAtMs8794 = 0L
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
                        curveReqAtMs8794 = 0L
                        curveRetryAtMs8794 = if (retryableSubscriptionRefusal8794(code8307, m8307))
                            System.currentTimeMillis() + CURVE_RETRY_MS_8794 else 0L
                        if (curveRetryAtMs8794 > 0L) try {
                            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_CURVE_QUOTA_RETRY_SCHEDULED_8794")
                        } catch (_: Throwable) {}
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("CURVE_TAPE_ENHANCED_SUB_REFUSED_8354") } catch (_: Throwable) {}
                        return
                    }
                    val refusedId8794 = msg.optLong("id", -1L)
                    if ((tapeReqId8307 >= 0L && refusedId8794 == tapeReqId8307) ||
                        (whaleReqId8307 >= 0L && refusedId8794 == whaleReqId8307)) {
                        if (refusedId8794 == tapeReqId8307) { tapeReqId8307 = -1L; tapeReqAtMs8795 = 0L }
                        if (refusedId8794 == whaleReqId8307) { whaleReqId8307 = -1L; whaleReqAtMs8795 = 0L }
                        capability8307 = capabilityFromError8307(code8307, m8307)
                        if (retryableSubscriptionRefusal8794(code8307, m8307))
                            tapeRetryAtMs8794 = System.currentTimeMillis() + CURVE_RETRY_MS_8794
                    }
                    return
                }
                // Transaction notification
                if (msg.optString("method", "") != "transactionNotification") return
                val params = msg.optJSONObject("params") ?: return
                val result = params.optJSONObject("result") ?: return
                if (tapeSubId8307 >= 0 && params.optLong("subscription", -2L) == tapeSubId8307) {
                    PumpSwapTape8307.onEnhancedTx8307(result, tapeIncluded8307)   // V5.0.8307 — onto the trade tape, never the whale callback
                    ValidatorMarket8506.enhanced(result, params)
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
            if (ws !== webSocket) return // obsolete connection must not clear the replacement's health.
            socketOpen8795 = false
            subscriptionId = -1L   // V5.0.8361 — a dead socket holds no subscription (whaleSubLive8361 reads false)
            tapeSubId8307 = -1L; tapeReqId8307 = -1L; whaleReqId8307 = -1L
            tapeReqAtMs8795 = 0L; whaleReqAtMs8795 = 0L
            curveSubId8354 = -1L; curveReqId8354 = -1L; curveReqAtMs8794 = 0L
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
                val delay = retry429DelayMs8797(reconnectAttempt.get() + 1L, response?.header("Retry-After"))
                ErrorLogger.warn(TAG, "🛑 Helius 429: ${msg.take(100)} — retry in ${delay / 1000L}s (credit-refill aware)")
                scheduleReconnect(forceDelayMs = delay)
            } else if (isAuthFatal) {
                ErrorLogger.warn(TAG, "🛑 AUTH-FATAL failure: ${msg.take(100)} — backing off 10min (rotate Helius key)")
                scheduleReconnect(forceDelayMs = 600_000L)
            } else {
                ErrorLogger.warn(TAG, "❌ failure: ${msg.take(100)} — will reconnect")
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws !== webSocket) return
            socketOpen8795 = false
            subscriptionId = -1L   // V5.0.8361
            tapeSubId8307 = -1L; tapeReqId8307 = -1L; whaleReqId8307 = -1L
            tapeReqAtMs8795 = 0L; whaleReqAtMs8795 = 0L
            curveSubId8354 = -1L; curveReqId8354 = -1L; curveReqAtMs8794 = 0L
            if (running.get()) scheduleReconnect()
        }
    }

    private fun scheduleReconnect(forceDelayMs: Long = -1L) {
        if (!running.get() || !reconnectPending8795.compareAndSet(false, true)) return
        // onFailure followed by onClosed used to schedule two reconnects,
        // doubling program subscriptions and wasting scarce Helius credits.
        // An old delayed reconnect also must not re-open after stop/start.
        val epoch8795 = lifecycleEpoch8795.get()
        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = if (forceDelayMs > 0L) {
            forceDelayMs
        } else {
            (1000L * (1 shl (attempt.coerceAtMost(5).toInt()))).coerceAtMost(30_000L)
        }
        Thread {
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) {
                reconnectPending8795.set(false)
                return@Thread
            }
            if (!running.get() || lifecycleEpoch8795.get() != epoch8795) return@Thread
            reconnectPending8795.set(false)
            connect()
        }.start()
    }
}

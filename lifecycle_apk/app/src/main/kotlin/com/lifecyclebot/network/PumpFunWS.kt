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
 * Keyed subscriptions: subscribeTokenTrade, subscribeAccountTrade — the
 * socket said so on 5.0.7281: "only available when connecting with an API
 * key funded with at least 0.02 SOL." (V5.0.7278 read them as free; that
 * was wrong, and it cost three builds of zero trade frames.) With a key in
 * BotConfig.pumpPortalApiKey the socket connects as ?api-key= and the held
 * curves are marked on every trade; without one, launches only.
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
    // sub-second. V5.0.7284: 7278 called `subscribeTokenTrade` a free
    // subscription; the server's own reply on 5.0.7281 says it needs an API
    // key funded with 0.02 SOL. The header above is right now; 7278 was not.
    @Volatile private var onTradeCb: ((mint: String, priceSolPerToken: Double, marketCapSol: Double, isBuy: Boolean) -> Unit)? = null
    private val tradeSubscriptions7278 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val firstUntypedLogged7279 = AtomicBoolean(false)
    @Volatile private var lastUntypedFrame7280: String = ""
    private val untypedFrames7280 = AtomicLong(0L)
    // V5.0.7284 — the data key the socket connected with; blank = no trade stream.
    @Volatile private var apiKey7284: String = ""
    private val tradeSubscribeSkippedNoKey7284 = AtomicLong(0L)
    // V5.0.7485 — unsupported subscription demand is state, not an event every
    // supervisor tick. Track wanted mints while trade-stream capability is
    // unavailable so repeated syncs do not re-expand the same work/counters.
    private val unsupportedTradeDemand7485 = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val unsupportedTradeDemandCoalesced7485 = AtomicLong(0L)
    private val tradeAuthDenied7432 = AtomicBoolean(false)
    private val createStreamObserved7432 = AtomicBoolean(false)
    private val migrationStreamObserved7432 = AtomicBoolean(false)
    private val tokenTradeStreamObserved7432 = AtomicBoolean(false)
    private val rpcFallbackObserved7432 = AtomicBoolean(false)
    fun tokenTradeAuthDenied7432(): Boolean = tradeAuthDenied7432.get()
    fun observeRpcFallback7432() { if (tradeAuthDenied7432.get()) rpcFallbackObserved7432.set(true) }
    fun tokenTradeStreamActive7432(): Boolean = running.get() && ws != null &&
        !tradeAuthDenied7432.get() && tokenTradeStreamObserved7432.get()
    internal fun isTradeAuthRefusal7432(message: String): Boolean {
        val e = message.lowercase()
        return (e.contains("subscribetokentrade") || e.contains("subscribeaccounttrade")) &&
            (e.contains("api key") || e.contains("funded") || e.contains("only available"))
    }
    private const val LIFECYCLE_MAX_7420 = 96
    private const val LIFECYCLE_TTL_MS_7420 = 15L * 60_000L
    private val lifecycleMints7420 = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val subRequested7420 = AtomicLong(0L)
    private val subOk7420 = AtomicLong(0L)
    private val subFail7420 = AtomicLong(0L)
    private val eventBuy7420 = AtomicLong(0L)
    private val eventSell7420 = AtomicLong(0L)
    private val unsub7420 = AtomicLong(0L)

    private fun tradeStreamKeyed7284(): Boolean = apiKey7284.isNotBlank() && !tradeAuthDenied7432.get()
    fun lifecycleStreamCapable7420(): Boolean = tokenTradeStreamActive7432()
    fun lifecycleSubscribedMints7420(): Int = lifecycleMints7420.size
    fun lifecycleEvents7420(): Long = eventBuy7420.get() + eventSell7420.get()
    fun lifecycleSubscriptionFailures7420(): Long = subFail7420.get()

    // V5.0.7286 — 5.0.7284: keyed, 30 subscribe frames sent, 53 message
    // frames, one error frame, zero trade frames, and the only text kept was
    // the newest ("Unsubscribed."). The server's answer to the subscribe — the
    // acknowledgement or the refusal — had scrolled off. The last error frame
    // is kept on its own, and the last four DISTINCT untyped texts are kept,
    // so the report shows what the socket said to the subscription.
    @Volatile private var lastErrorFrame7286: String = ""
    private val recentUntyped7286 = java.util.ArrayDeque<String>()
    private const val RECENT_UNTYPED_KEEP_7286 = 4

    private fun rememberUntyped7286(text: String, isError: Boolean) {
        val t = text.take(160).replace('\n', ' ')
        if (isError) lastErrorFrame7286 = t
        synchronized(recentUntyped7286) {
            if (recentUntyped7286.contains(t)) return
            recentUntyped7286.addLast(t)
            while (recentUntyped7286.size > RECENT_UNTYPED_KEEP_7286) recentUntyped7286.removeFirst()
        }
    }

    /** V5.0.7280 — one line for the pipeline report: what the socket is doing. */
    fun status7280(): String {
        val recent = synchronized(recentUntyped7286) { recentUntyped7286.toList() }
        return "running=${running.get()} socket=${if (ws != null) "open" else "none"} reconnects=${reconnectAttempt.get()} " +
            "tradeStream=${if (tradeAuthDenied7432.get()) "AUTH_DENIED" else if (tokenTradeStreamActive7432()) "OBSERVED" else if (tradeStreamKeyed7284()) "KEYED_UNVERIFIED" else "NO_KEY_LAUNCHES_ONLY"} " +
            "PUMP_LIFECYCLE_STREAM_CAPABLE=${lifecycleStreamCapable7420()} " +
            "CREATE_STREAM_AVAILABLE=${running.get() && ws != null && createStreamObserved7432.get()} " +
            "MIGRATION_STREAM_AVAILABLE=${running.get() && ws != null && migrationStreamObserved7432.get()} " +
            "TOKEN_TRADE_STREAM_AVAILABLE=${tokenTradeStreamActive7432()} ACCOUNT_TRADE_STREAM_AVAILABLE=false " +
            "PUMP_TOKEN_TRADE_FALLBACK_ACTIVE=${tradeAuthDenied7432.get() && rpcFallbackObserved7432.get()} " +
            "tradeSubscribedMints=${tradeSubscriptions7278.size} lifecycleMints=${lifecycleMints7420.size} " +
            "unsupportedDemand7485=${unsupportedTradeDemand7485.size} coalescedDemand7485=${unsupportedTradeDemandCoalesced7485.get()} " +
            "subscriptionFailures=${subFail7420.get()} subscribeSkippedNoKey=${tradeSubscribeSkippedNoKey7284.get()} " +
            "untypedFrames=${untypedFrames7280.get()} " +
            "lastUntyped=${lastUntypedFrame7280.ifBlank { "-" }} " +
            "lastError7286=${lastErrorFrame7286.ifBlank { "-" }} " +
            "recentDistinct7286=${recent.joinToString(" | ").ifBlank { "-" }}"
    }

    fun setOnTrade7278(cb: (mint: String, priceSolPerToken: Double, marketCapSol: Double, isBuy: Boolean) -> Unit) {
        onTradeCb = cb
    }

    /**
     * V5.0.7743 — the rest of the trade frame. 7278 kept only the price; the
     * trader wallet, SOL size, side and the seller's remaining balance were
     * dropped, so WhaleDetector's launch tape, the real-time candle builder and
     * the dev-sell exit (all built for exactly this data) never received any.
     * [soldFractionOfHolding] is tokenAmount / (tokenAmount + newTokenBalance)
     * on a sell, else 0.
     */
    @Volatile private var onTradeDetailCb7743: ((mint: String, wallet: String, solAmount: Double, isBuy: Boolean, soldFractionOfHolding: Double) -> Unit)? = null

    fun setOnTradeDetail7743(cb: (mint: String, wallet: String, solAmount: Double, isBuy: Boolean, soldFractionOfHolding: Double) -> Unit) {
        onTradeDetailCb7743 = cb
    }

    private fun registerFreshLifecycle7420(mint: String) {
        if (mint.isBlank()) return
        val now = System.currentTimeMillis()
        val expired = lifecycleMints7420.entries.filter { now - it.value > LIFECYCLE_TTL_MS_7420 }.map { it.key }
        if (expired.isNotEmpty()) {
            lifecycleMints7420.keys.removeAll(expired.toSet())
            tradeSubscriptions7278.removeAll(expired.toSet())
            if (tradeStreamKeyed7284()) ws?.send(JSONObject().put("method", "unsubscribeTokenTrade").put("keys", JSONArray(expired)).toString())
            unsub7420.addAndGet(expired.size.toLong())
            try { repeat(expired.size) { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_UNSUBSCRIBED") } } catch (_: Throwable) {}
        }
        if (lifecycleMints7420.size >= LIFECYCLE_MAX_7420 && !lifecycleMints7420.containsKey(mint)) {
            val oldest = lifecycleMints7420.entries.minByOrNull { it.value }?.key
            if (oldest != null) {
                lifecycleMints7420.remove(oldest)
                tradeSubscriptions7278.remove(oldest)
                if (tradeStreamKeyed7284()) ws?.send(JSONObject().put("method", "unsubscribeTokenTrade").put("keys", JSONArray().put(oldest)).toString())
                unsub7420.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_UNSUBSCRIBED") } catch (_: Throwable) {}
            }
        }
        lifecycleMints7420[mint] = now
        subRequested7420.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUB_REQUESTED") } catch (_: Throwable) {}
        if (!tradeStreamKeyed7284()) {
            tradeSubscribeSkippedNoKey7284.incrementAndGet()
            return
        }
        if (tradeSubscriptions7278.add(mint)) {
            val sent = ws?.send(JSONObject().put("method", "subscribeTokenTrade").put("keys", JSONArray().put(mint)).toString()) == true
            if (sent) {
                subOk7420.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUB_OK") } catch (_: Throwable) {}
            } else {
                subFail7420.incrementAndGet()
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUB_FAIL") } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Keep the trade subscription equal to [mints]: subscribe the new ones,
     * unsubscribe the ones no longer held. Idempotent; safe every tick.
     */
    fun syncTradeSubscriptions7278(mints: Set<String>) {
        val wanted = mints.filter { it.isNotBlank() }.toSet()

        // V5.0.7485 — when token-trade capability is unavailable there is no
        // executable subscription set to diff against. Maintain the desired
        // unsupported set directly: count newly wanted mints once, remove
        // demand that is no longer held, and return before frame construction.
        if (!tradeStreamKeyed7284()) {
            var newlyWanted7485 = 0
            for (mint in wanted) {
                if (unsupportedTradeDemand7485.add(mint)) newlyWanted7485++
                else unsupportedTradeDemandCoalesced7485.incrementAndGet()
            }
            unsupportedTradeDemand7485.removeIf { it !in wanted }
            if (newlyWanted7485 > 0) {
                tradeSubscribeSkippedNoKey7284.addAndGet(newlyWanted7485.toLong())
                try {
                    repeat(newlyWanted7485) {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_SUBSCRIBE_SKIPPED_NO_KEY_7284")
                    }
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_UNSUPPORTED_DEMAND_NEW_7485")
                } catch (_: Throwable) {}
            }
            return
        }

        // Capability became available: the real subscription set takes over.
        unsupportedTradeDemand7485.clear()
        val add = wanted - tradeSubscriptions7278
        // V5.0.7743 — fresh-launch subscriptions (registerFreshLifecycle7420) are
        // not the held set and expire on their own 15-minute TTL. This diff used
        // to drop them on the next tick of the open-position loop (it passes held
        // curve mints only), which is why 5.0.7741 showed tradeSubscribedMints=3
        // against lifecycleMints=96 and every launch tape was empty.
        val drop = tradeSubscriptions7278 - wanted - lifecycleMints7420.keys
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
        apiKey7284: String = "",
    ) {
        if (!running.compareAndSet(false, true)) {
            ErrorLogger.warn(TAG, "already running — start() ignored")
            return
        }
        onNewTokenCb = onNewToken
        onMigrationCb = onMigration
        if (this.apiKey7284 != apiKey7284.trim()) {
            tradeAuthDenied7432.set(false)
            rpcFallbackObserved7432.set(false)
            unsupportedTradeDemand7485.clear()
        }
        this.apiKey7284 = apiKey7284.trim()
        tokenTradeStreamObserved7432.set(false)
        createStreamObserved7432.set(false)
        migrationStreamObserved7432.set(false)
        ErrorLogger.info(TAG, "trade stream ${if (tradeStreamKeyed7284()) "KEYED" else "NO_KEY — launches only (Settings > PumpPortal data key)"}")
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
        // V5.0.7284 — the key rides the connection URL, never a log line.
        val url7284 = if (tradeStreamKeyed7284()) "$URL?api-key=${java.net.URLEncoder.encode(apiKey7284, "UTF-8")}" else URL
        val req = Request.Builder().url(url7284).build()
        ErrorLogger.info(TAG, "🔌 connecting to $URL${if (tradeStreamKeyed7284()) " (keyed)" else ""} (attempt ${reconnectAttempt.get() + 1})")
        ws = client?.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt.set(0)
            tokenTradeStreamObserved7432.set(false)
            ErrorLogger.info(TAG, "✅ connected — subscribing to subscribeNewToken + subscribeMigration (free)")
            webSocket.send(JSONObject().put("method", "subscribeNewToken").toString())
            webSocket.send(JSONObject().put("method", "subscribeMigration").toString())
            // V5.0.7278 — re-arm the held-mint trade stream after a reconnect.
            val held7278 = tradeSubscriptions7278.toList()
            if (held7278.isNotEmpty() && tradeStreamKeyed7284()) {
                webSocket.send(JSONObject().put("method", "subscribeTokenTrade").put("keys", JSONArray(held7278)).toString())
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val j = JSONObject(text)
                val txType = j.optString("txType", "")
                // A socket write is not a subscription acceptance. An auth/funding
                // refusal disables keyed methods for this credential state, while
                // free create/migration and the independent RPC curve ladder remain.
                val error7432 = listOf(j.optString("message"), j.optString("error"),
                    j.optString("errors")).joinToString(" ").lowercase()
                if (!tradeAuthDenied7432.get() && isTradeAuthRefusal7432(error7432)) {
                    tradeAuthDenied7432.set(true)
                    tokenTradeStreamObserved7432.set(false)
                    tradeSubscriptions7278.clear()
                    subFail7420.incrementAndGet()
                    try {
                        com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TOKEN_TRADE_STREAM_UNAVAILABLE_AUTH_7432")
                        ErrorLogger.warn(TAG, "token trade subscription unavailable for current key; RPC curve ladder remains independent")
                    } catch (_: Throwable) {}
                }
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
                        rememberUntyped7286(text, isError = j.has("errors") || j.has("error"))
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
                        if (!tradeAuthDenied7432.get()) tokenTradeStreamObserved7432.set(true)
                        try {
                            if (tokenTradeStreamActive7432())
                                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TOKEN_TRADE_STREAM_ACTIVE_7432")
                            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_EVENT_7278")
                            if (txType == "buy") {
                                eventBuy7420.incrementAndGet()
                                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_EVENT_BUY")
                            } else {
                                eventSell7420.incrementAndGet()
                                com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_TRADE_EVENT_SELL")
                            }
                        } catch (_: Throwable) {}
                        onTradeCb?.invoke(mint, priceSol, mcapSol, txType == "buy")
                        try {
                            val sol7743 = j.optDouble("solAmount", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                            val tok7743 = j.optDouble("tokenAmount", 0.0)
                            val left7743 = j.optDouble("newTokenBalance", -1.0)
                            val soldFrac7743 = if (txType == "sell" && tok7743.isFinite() && tok7743 > 0.0 && left7743.isFinite() && left7743 >= 0.0)
                                tok7743 / (tok7743 + left7743) else 0.0
                            onTradeDetailCb7743?.invoke(mint, j.optString("traderPublicKey", ""), sol7743, txType == "buy", soldFrac7743)
                        } catch (_: Throwable) {}
                    }
                    txType == "create" || j.has("name") && j.has("symbol") && j.has("mint") -> {
                        val mint = j.optString("mint", "")
                        val symbol = j.optString("symbol", "?")
                        val name = j.optString("name", "?")
                        val marketCapSol = j.optDouble("marketCapSol", 0.0)
                        if (mint.isBlank()) return
                        // V5.0.7401 — creator identity is launch timing evidence.
                        // Persist it on the fastest create socket BEFORE any
                        // adaptive create throttle or downstream enrichment.
                        try {
                            com.lifecyclebot.engine.OperatorRegistry.set(
                                mint, j.optString("traderPublicKey", "")
                            )
                        } catch (_: Throwable) {}
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
                        createStreamObserved7432.set(true)
                        registerFreshLifecycle7420(mint)
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_CREATE_STREAM_ACTIVE_7432") } catch (_: Throwable) {}
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
                        migrationStreamObserved7432.set(true)
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PUMP_MIGRATION_STREAM_ACTIVE_7432") } catch (_: Throwable) {}
                        onMigrationCb?.invoke(mint)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "parse error: ${e.message?.take(80)}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (ws === webSocket) { ws = null; tokenTradeStreamObserved7432.set(false) }
            ErrorLogger.info(TAG, "closing code=$code reason=$reason")
            webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (ws === webSocket) { ws = null; tokenTradeStreamObserved7432.set(false) }
            ErrorLogger.warn(TAG, "❌ failure: ${t.message?.take(100)} — will reconnect")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (ws === webSocket) { ws = null; tokenTradeStreamObserved7432.set(false) }
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

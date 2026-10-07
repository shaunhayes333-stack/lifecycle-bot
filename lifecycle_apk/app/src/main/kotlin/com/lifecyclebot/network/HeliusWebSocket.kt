package com.lifecyclebot.network

import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

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
    private val client = SharedHttpClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    // Keep user callbacks off the socket reader: ACK/pong progress must not
    // wait for downstream token processing. One ordered, bounded worker.
    private val callbacks7863 = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(256),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "HeliusCallbacks7863").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }

    private fun dispatchCallback7863(callback: () -> Unit) {
        val socket = ws
        try {
            callbacks7863.execute {
                if (running && ws === socket) try { callback() } catch (_: Throwable) {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_CALLBACK_FAILED_7863")
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_CALLBACK_QUEUE_FULL_7863")
        }
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var running = false
    private val idCounter = AtomicInteger(1)
    // V5.0.7794 — bounded subscription authority.
    // Runtime 5.0.7792 OOM'd inside subscribeToken after the watchlist had churned
    // tens of thousands of mints. A WebSocket subscription is a scarce live resource,
    // not a permanent historical registry. Keep only the freshest launch tape and
    // explicitly unsubscribe the oldest server-side subscription when the cap rolls.
    private companion object {
        const val MAX_TOKEN_SUBSCRIPTIONS_7794 = 128
        const val MAX_WALLET_SUBSCRIPTIONS_7794 = 64
        // V5.0.7807 — a subscribe request whose ACK never arrives (dropped frame,
        // JSON-RPC error, socket died mid-flight) must not pin a request-map entry
        // and a dead desired slot forever. Field Manual L190: stale is unknown.
        const val REQUEST_ACK_TIMEOUT_MS_7807 = 60_000L
        const val MAX_PENDING_REQUESTS_7807 = 512
        const val MAX_UNSUB_DEDUPE_7807 = 512
        const val HEALTHY_INBOUND_WINDOW_MS_7819 = 120_000L
        val swapBuyRegex7807 = Regex("""Buy\s+([\d.]+)\s+tokens?\s+for\s+([\d.]+)\s+SOL""", RegexOption.IGNORE_CASE)
        val swapSellRegex7807 = Regex("""Sell\s+([\d.]+)\s+tokens?\s+for\s+([\d.]+)\s+SOL""", RegexOption.IGNORE_CASE)
        val reconnectScheduler7803: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "AATE-HeliusWS-Reconnect").apply { isDaemon = true }
            }
    }
    // Desired membership and server subscription identity are distinct.
    // null means the mint is desired but the current socket has not ACKed it yet.
    private val subscriptions = java.util.LinkedHashMap<String, Int?>(256, 0.75f, true)
    private val watchedWallets = java.util.LinkedHashSet<String>()
    private val requestToWallet7803 = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val subscriptionToWallet7803 = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val walletServerSubscription7803 = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val reconnectScheduled7803 = AtomicBoolean(false)
    // V5.0.7807 — request id -> send time, for both token and wallet requests.
    private val requestSentAtMs7807 = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    // V5.0.7807 — "method:serverId" already unsubscribed on this socket, so an
    // in-flight notification for an evicted sub does not trigger a second send.
    private val recentUnsubscribed7807 = java.util.LinkedHashSet<String>()
    // V5.0.7807 — held positions are subscription-priority. The caller (DataOrchestrator)
    // supplies the held-mint set from canonical position truth; those mints are never
    // the LRU victim, discovery mints are. Field Manual L153: stale launch data costs most.
    @Volatile private var pinnedMintsProvider7807: (() -> Set<String>)? = null
    // V5.0.7819 — Helius is a Solana feed. The owner (DataOrchestrator) supplies the
    // eligibility test (base58 Solana mint AND not a STOCK/FX/METAL/COMMODITY/PERPS/
    // CRYPTO_ALT canonical row). Runtime 5.0.7813 pinned 100 of 128 slots to stock
    // tickers (SPOT, KO, UNH): each logsSubscribe was a JSON-RPC error, released,
    // re-armed 10 s later, and evicted a real meme mint every time (evictions=3214).
    // Field Manual L186: every data source must refer to the same asset and market.
    @Volatile private var mintEligibility7819: ((String) -> Boolean)? = null
    // V5.0.7819 — liveness of the CURRENT socket, so an external "reconnect streams"
    // request does not tear down a healthy subscription set (Field Manual L240).
    @Volatile private var openSocket7819: WebSocket? = null
    @Volatile private var lastInboundMs7819: Long = 0L
    @Volatile private var everOpened7819 = false
    // V5.0.7819 — the socket disconnect() closed on purpose; its close/failure
    // callbacks are not a reconnect cause and must not trip the circuit breaker.
    @Volatile private var clientClosedSocket7819: WebSocket? = null
    private var reconnectDelay = 2_000L
    private val networkRetry = NetworkRetry("HeliusWS", maxRetries = 5, baseDelayMs = 2_000L, maxDelayMs = 30_000L, failureThreshold = 3, openDurationMs = 120_000L)

    val isConnected get() = ws != null && running

    fun connect() {
        if (apiKey.isBlank()) {
            onLog("Helius: no API key — real-time stream disabled (add key in settings)")
            return
        }
        if (running) return
        running = true
        if (networkRetry.isOpen) {
            onLog("Helius: circuit open — backing off after repeated failures")
            // V5.0.7807 — back off, don't die: running=true with no socket and no
            // scheduled retry left every desired subscription unserved until restart.
            scheduleReconnect()
            return
        }
        doConnect()
    }

    fun disconnect() {
        running = false
        callbacks7863.queue.clear()
        reconnectScheduled7803.set(false)
        clientClosedSocket7819 = ws
        ws?.close(1000, "Bot stopped")
        ws = null
        openSocket7819 = null
        requestToMint7765.clear()
        subscriptionToMint7765.clear()
        requestToWallet7803.clear()
        subscriptionToWallet7803.clear()
        walletServerSubscription7803.clear()
        requestSentAtMs7807.clear()
        synchronized(subscriptions) {
            subscriptions.keys.toList().forEach { subscriptions[it] = null }
        }
        publishGauges7807()
    }

    /**
     * V5.0.7807 — the bounded LRU below is the SOLE token-subscription authority
     * (DataOrchestrator.onTokenRemoved had no production caller and was deleted).
     * The provider returns the mints of currently held positions; they are never
     * evicted to make room for a scanner/discovery mint.
     */
    fun setPinnedMintsProvider7807(provider: () -> Set<String>) { pinnedMintsProvider7807 = provider }

    private fun pinnedMints7807(): Set<String> =
        try {
            val raw7819 = pinnedMintsProvider7807?.invoke() ?: emptySet()
            val gate7819 = mintEligibility7819
            if (gate7819 == null) raw7819 else raw7819.filterTo(LinkedHashSet()) { gate7819(it) }
        } catch (_: Throwable) { emptySet() }

    /** V5.0.7819 — only Solana mints may consume a Helius subscription slot. */
    fun setMintEligibility7819(predicate: (String) -> Boolean) { mintEligibility7819 = predicate }

    private fun admitMint7819(mint: String): Boolean {
        val gate7819 = mintEligibility7819 ?: return true
        val ok7819 = try { gate7819(mint) } catch (_: Throwable) { false }
        if (!ok7819) {
            HeliusSubscriptionTelemetry7807.nonSolanaRejected7819.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_NON_SOLANA_SUB_REJECTED_7819") } catch (_: Throwable) {}
        }
        return ok7819
    }

    /**
     * V5.0.7819 — DataOrchestrator.reconnectStreams (network onAvailable — which
     * Android fires on registration and on every wifi/cell handover — the inert-
     * scanner watchdog, the freeze detector) used to disconnect()+connect() Helius
     * unconditionally: a healthy socket was torn down and all 128 subscriptions
     * re-sent each time. A socket that is open and has delivered a frame within
     * [HEALTHY_INBOUND_WINDOW_MS_7819] is kept; OkHttp's 15 s ping already fails a
     * dead socket into onFailure -> scheduleReconnect.
     */
    fun reconnectIfStale7819(reason: String) {
        val now7819 = System.currentTimeMillis()
        val open7819 = running && ws != null && openSocket7819 === ws
        if (open7819 && now7819 - lastInboundMs7819 <= HEALTHY_INBOUND_WINDOW_MS_7819) {
            HeliusSubscriptionTelemetry7807.noteReconnect7819("SKIPPED_HEALTHY_$reason")
            return
        }
        HeliusSubscriptionTelemetry7807.noteReconnect7819(reason)
        disconnect()
        connect()
    }

    /**
     * V5.0.7807 — housekeeping tick (DataOrchestrator, every 10 s): expire ACK-less
     * requests, and re-arm any held mint whose subscription was evicted before it
     * was bought (or dropped by an error ACK).
     */
    fun ensurePinnedSubscriptions7807() {
        expireStaleRequests7807(System.currentTimeMillis())
        val pinned = pinnedMints7807()
        HeliusSubscriptionTelemetry7807.pinnedHeld = pinned.size
        if (pinned.isEmpty()) { publishGauges7807(); return }
        val missing = synchronized(subscriptions) { pinned.filter { it.isNotBlank() && !subscriptions.containsKey(it) } }
        missing.forEach { mint ->
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_HELD_SUB_REARMED_7807") } catch (_: Throwable) {}
            subscribeToken(mint)
        }
        publishGauges7807()
    }

    /** Subscribe to all swaps for a specific token mint. */
    fun subscribeToken(mint: String) {
        if (mint.isBlank()) return
        if (!admitMint7819(mint)) return // V5.0.7819 — Field Manual L186: Solana mints only
        expireStaleRequests7807(System.currentTimeMillis())
        val pinned = pinnedMints7807()
        var evictedMint: String? = null
        var evictedServerId: Int? = null
        var requestId = -1
        var deferred = false
        var overCap = false
        synchronized(subscriptions) {
            if (subscriptions.containsKey(mint)) {
                subscriptions.get(mint) // V5.0.7807 — access-order touch: re-added mint is fresh again
                return
            }
            if (subscriptions.size >= MAX_TOKEN_SUBSCRIPTIONS_7794) {
                // V5.0.7807 — eldest NON-held mint is the victim; discovery goes first.
                val victim = subscriptions.keys.firstOrNull { it !in pinned }
                if (victim != null) {
                    evictedMint = victim
                    evictedServerId = subscriptions.remove(victim)
                } else if (mint in pinned) {
                    overCap = true // every slot is a held position; held set is bounded by open positions
                } else {
                    deferred = true
                }
            }
            if (!deferred) {
                requestId = idCounter.getAndIncrement()
                subscriptions[mint] = null
            }
        }
        if (deferred) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_DISCOVERY_SUB_DEFERRED_ALL_HELD_7807") } catch (_: Throwable) {}
            return
        }
        if (overCap) try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_HELD_SUB_OVER_CAP_7807") } catch (_: Throwable) {}
        evictedMint?.let { old ->
            // V5.0.7807 — the pending request for `old` is deliberately KEPT: its ACK
            // then finds the mint unwanted and unsubscribes server-side immediately.
            subscriptionToMint7765.entries.removeIf { it.value == old }
            HeliusSubscriptionTelemetry7807.evictions.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_TOKEN_SUB_EVICTED_7794") } catch (_: Throwable) {}
        }
        evictedServerId?.let { sendUnsubscribe7803("logsUnsubscribe", it) }
        sendSubscribe(requestId, listOf(mint))
        publishGauges7807()
    }

    /** Track a wallet address for large moves (dev wallet, top holders). */
    fun watchWallet(address: String) {
        if (address.isBlank()) return
        var evicted: String? = null
        val shouldSubscribe = synchronized(watchedWallets) {
            if (address in watchedWallets) false
            else {
                if (watchedWallets.size >= MAX_WALLET_SUBSCRIPTIONS_7794) {
                    val it = watchedWallets.iterator()
                    if (it.hasNext()) {
                        evicted = it.next()
                        it.remove()
                    }
                }
                watchedWallets.add(address)
                true
            }
        }
        evicted?.let { old ->
            // V5.0.7807 — pending request kept so its late ACK is unsubscribed.
            subscriptionToWallet7803.entries.removeIf { it.value == old }
            walletServerSubscription7803.remove(old)?.let { sendUnsubscribe7803("accountUnsubscribe", it) }
            HeliusSubscriptionTelemetry7807.evictions.incrementAndGet()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_WALLET_SUB_EVICTED_7794") } catch (_: Throwable) {}
        }
        if (shouldSubscribe) {
            expireStaleRequests7807(System.currentTimeMillis())
            sendAccountSubscribe(idCounter.getAndIncrement(), address)
        }
        publishGauges7807()
    }

    fun unwatchWallet(address: String) {
        synchronized(watchedWallets) { watchedWallets.remove(address) }
        // V5.0.7807 — pending request kept so its late ACK is unsubscribed.
        subscriptionToWallet7803.entries.removeIf { it.value == address }
        walletServerSubscription7803.remove(address)?.let { sendUnsubscribe7803("accountUnsubscribe", it) }
        publishGauges7807()
    }

    private fun doConnect() {
        val url = "wss://mainnet.helius-rpc.com/?api-key=$apiKey"
        val req = Request.Builder().url(url).build()

        // V5.0.7807 — the previous socket's server ids and in-flight requests die with
        // it; reset them HERE, before the new socket exists. Clearing in onOpen (old
        // behaviour) also wiped requests already queued on the new socket before open,
        // so their ACKs arrived with unknown ids and became orphan server
        // subscriptions, and onOpen sent a duplicate subscribe for the same mint.
        requestToMint7765.clear()
        subscriptionToMint7765.clear()
        requestToWallet7803.clear()
        subscriptionToWallet7803.clear()
        walletServerSubscription7803.clear()
        requestSentAtMs7807.clear()
        synchronized(recentUnsubscribed7807) { recentUnsubscribed7807.clear() }
        synchronized(subscriptions) { subscriptions.keys.toList().forEach { subscriptions[it] = null } }

        val listener7819 = object : WebSocketListener() {
            // V5.0.7819 — set when this socket died before newWebSocket() returned,
            // so doConnect does not park a dead socket in `ws` (which would block
            // every future scheduleReconnect via its `ws == null` check).
            @Volatile var dead7819 = false
            @Volatile var closing7819 = false

            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                openSocket7819 = webSocket
                lastInboundMs7819 = System.currentTimeMillis()
                if (everOpened7819) HeliusSubscriptionTelemetry7807.reconnects7819.incrementAndGet()
                everOpened7819 = true
                onLog("Helius WebSocket connected")
                networkRetry.recordSuccess()
                reconnectDelay = 2_000L
                reconnectScheduled7803.set(false)

                // V5.0.7807 — re-register only members with no request already queued
                // on this socket: one registration per desired mint/wallet.
                // V5.0.7867 — held positions first, then newest-first. The access-ordered
                // map iterates eldest first, so on a flapping mobile socket (5.0.7866:
                // 9 SocketTimeout reconnects in 330 s, 8 of 128 subs acked) the freshest
                // launches — the ones whose tape decides an entry — were re-sent last
                // and lost to the next drop.
                val tokenSnapshot = resubscribeOrder7867(
                    synchronized(subscriptions) { subscriptions.keys.toList() }, pinnedMints7807(),
                )
                tokenSnapshot.forEach { mint ->
                    if (!requestToMint7765.containsValue(mint)) {
                        HeliusSubscriptionTelemetry7807.reconnectResubscribes.incrementAndGet()
                        sendSubscribe(idCounter.getAndIncrement(), listOf(mint))
                    }
                }

                val walletSnapshot = synchronized(watchedWallets) { watchedWallets.toList() }
                walletSnapshot.forEach { addr ->
                    if (!requestToWallet7803.containsValue(addr)) {
                        HeliusSubscriptionTelemetry7807.reconnectResubscribes.incrementAndGet()
                        sendAccountSubscribe(idCounter.getAndIncrement(), addr)
                    }
                }
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_RECONNECT_RESUBSCRIBE_PASS_7807") } catch (_: Throwable) {}
                publishGauges7807()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // V5.0.7807 — a late frame from a replaced socket carries that socket's
                // ids; never let it touch the current socket's maps.
                val current = ws
                if (current != null && current !== webSocket) return
                lastInboundMs7819 = System.currentTimeMillis()
                parseMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                dead7819 = true
                if (closing7819 || webSocket === clientClosedSocket7819) return // already handled / closed by us
                // V5.0.7819 — a superseded socket (closed by disconnect/reconnect) failing
                // late is not a failure of the current connection: it must not trip the
                // circuit breaker or be counted as a reconnect cause.
                val current7819 = ws
                if (current7819 != null && current7819 !== webSocket) return
                ws = null
                if (openSocket7819 === webSocket) openSocket7819 = null
                if (!running) return
                HeliusSubscriptionTelemetry7807.noteReconnect7819(
                    "FAILURE_" + (if (response != null) "HTTP${response.code}_" else "") + t.javaClass.simpleName,
                )
                onLog("Helius WS error: ${t.message?.take(60)} — reconnecting in ${reconnectDelay/1000}s")
                networkRetry.recordFailure()
                scheduleReconnect()
            }

            // V5.0.7819 — the server's close frame. Without this override OkHttp left the
            // socket half-closed (reader stopped, ws still non-null) until the next ping
            // timed out; and the server's close code — the actual reason Helius dropped
            // us — was never recorded. Complete the close and reconnect now.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                closing7819 = true
                dead7819 = true
                if (webSocket === clientClosedSocket7819) return // reply to our own close
                val current7819 = ws
                if (current7819 != null && current7819 !== webSocket) return
                ws = null
                if (openSocket7819 === webSocket) openSocket7819 = null
                try { webSocket.close(1000, null) } catch (_: Throwable) {}
                if (!running) return
                HeliusSubscriptionTelemetry7807.noteReconnect7819("SERVER_CLOSE_$code")
                onLog("Helius WS closed by server code=$code ${reason.take(60)} — reconnecting")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                dead7819 = true
                if (closing7819 || webSocket === clientClosedSocket7819) return // already handled / closed by us
                val current7819 = ws
                if (current7819 != null && current7819 !== webSocket) return
                ws = null
                if (openSocket7819 === webSocket) openSocket7819 = null
                if (running) {
                    HeliusSubscriptionTelemetry7807.noteReconnect7819("CLOSED_$code")
                    scheduleReconnect()
                }
            }
        }
        val socket7819 = client.newWebSocket(req, listener7819)
        ws = socket7819
        if (listener7819.dead7819 && ws === socket7819) {
            ws = null
            scheduleReconnect()
        }
    }

    // V5.0.7765 §ONE_MINT_PER_LOG_SUBSCRIPTION. logsSubscribe's `mentions` filter takes
    // exactly one address; this sent the mint plus four program ids, and every swap was
    // then emitted with a blank mint and wallet, so DataOrchestrator matched nothing.
    // One subscription per mint; the server's subscription id maps each notification
    // back to its mint (and is what logsUnsubscribe needs).
    private val requestToMint7765 = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val subscriptionToMint7765 = java.util.concurrent.ConcurrentHashMap<Int, String>()

    private fun sendSubscribe(id: Int, mints: List<String>) {
        val mint = mints.firstOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (id < 0) return
        // V5.0.7807 — no socket: register nothing; onOpen re-subscribes every
        // desired mint. One in-flight request per mint, never two.
        val socket = ws ?: return
        if (requestToMint7765.containsValue(mint)) return
        requestToMint7765[id] = mint
        requestSentAtMs7807[id] = System.currentTimeMillis()
        val mentions = listOf(mint)
        val sent7807 = socket.send(JSONObject().apply {
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
        if (!sent7807) { requestToMint7765.remove(id); requestSentAtMs7807.remove(id) }
    }

    private fun sendAccountSubscribe(id: Int, address: String) {
        val socket = ws ?: return
        if (requestToWallet7803.containsValue(address)) return
        requestToWallet7803[id] = address
        requestSentAtMs7807[id] = System.currentTimeMillis()
        val sent7807 = socket.send(JSONObject().apply {
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
        if (!sent7807) { requestToWallet7803.remove(id); requestSentAtMs7807.remove(id) }
    }

    private fun parseMessage(text: String) {
        try {
            val msg    = JSONObject(text)
            val method = msg.optString("method", "")
            // V5.0.7803 — request id -> server subscription id. Late ACKs for
            // evicted members are immediately unsubscribed instead of resurrected.
            // V5.0.7807 — a JSON-RPC error reply (or any non-numeric reply to one of our
            // subscribe requests) used to fall through and leave the request entry and a
            // dead null-id desired slot behind forever. Release both now.
            if (method.isBlank() && msg.has("id") && msg.opt("result") !is Number) {
                val failedId = msg.optInt("id", -1)
                requestSentAtMs7807.remove(failedId)
                requestToMint7765.remove(failedId)?.let { mint ->
                    releaseUnackedMint7807(mint)
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_SUB_ERROR_REPLY_7807") } catch (_: Throwable) {}
                }
                requestToWallet7803.remove(failedId)?.let { wallet ->
                    releaseUnackedWallet7807(wallet)
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_SUB_ERROR_REPLY_7807") } catch (_: Throwable) {}
                }
                publishGauges7807()
                return
            }
            if (method.isBlank() && msg.has("id") && msg.opt("result") is Number) {
                val requestId = msg.optInt("id", -1)
                val sub = msg.optInt("result", -1)
                requestSentAtMs7807.remove(requestId)
                requestToMint7765.remove(requestId)?.let { mint ->
                    // V5.0.7807 — accept only into an unfilled desired slot, atomically with
                    // the reverse map. Unwanted (evicted) => late ACK; slot already holds a
                    // server id => duplicate. Both are unsubscribed server-side at once.
                    val verdict = synchronized(subscriptions) {
                        if (sub < 0) 3
                        else if (!subscriptions.containsKey(mint)) 1
                        else if (subscriptions[mint] != null) 2
                        else {
                            subscriptions[mint] = sub
                            subscriptionToMint7765[sub] = mint
                            0
                        }
                    }
                    if (verdict == 1 || verdict == 2) {
                        sendUnsubscribe7803("logsUnsubscribe", sub)
                        HeliusSubscriptionTelemetry7807.lateAckUnsubscribes.incrementAndGet()
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(if (verdict == 1) "HELIUS_WS_LATE_ACK_UNSUBSCRIBED_7807" else "HELIUS_WS_DUPLICATE_ACK_UNSUBSCRIBED_7807") } catch (_: Throwable) {}
                    }
                    publishGauges7807()
                    return
                }
                requestToWallet7803.remove(requestId)?.let { wallet ->
                    val verdict = synchronized(watchedWallets) {
                        if (sub < 0) 3
                        else if (wallet !in watchedWallets) 1
                        else if (walletServerSubscription7803.containsKey(wallet)) 2
                        else {
                            subscriptionToWallet7803[sub] = wallet
                            walletServerSubscription7803[wallet] = sub
                            0
                        }
                    }
                    if (verdict == 1 || verdict == 2) {
                        sendUnsubscribe7803("accountUnsubscribe", sub)
                        HeliusSubscriptionTelemetry7807.lateAckUnsubscribes.incrementAndGet()
                        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(if (verdict == 1) "HELIUS_WS_LATE_ACK_UNSUBSCRIBED_7807" else "HELIUS_WS_DUPLICATE_ACK_UNSUBSCRIBED_7807") } catch (_: Throwable) {}
                    }
                    publishGauges7807()
                    return
                }
                return
            }
            val params = msg.optJSONObject("params") ?: return
            val result = params.optJSONObject("result") ?: return
            val value  = result.optJSONObject("value") ?: return

            when (method) {
                "logsNotification" -> {
                    // V5.0.7807 — a notification for a server id we do not own (expired
                    // request, pre-7807 orphan) is a live server subscription nobody
                    // reads: unsubscribe it instead of silently paying for it.
                    if (!subscriptionToMint7765.containsKey(params.optInt("subscription", -1))) unsubscribeOrphan7807("logsUnsubscribe", params.optInt("subscription", -1))
                    parseLogsNotification(value, subscriptionToMint7765[params.optInt("subscription", -1)].orEmpty())
                }
                "accountNotification" -> {
                    if (!subscriptionToWallet7803.containsKey(params.optInt("subscription", -1))) unsubscribeOrphan7807("accountUnsubscribe", params.optInt("subscription", -1))
                    parseAccountNotification(
                        value,
                        subscriptionToWallet7803[params.optInt("subscription", -1)].orEmpty(),
                    )
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Parse transaction log notifications.
     * We look for swap-related log messages to extract trade data.
     * Helius enhanced API enriches these with parsed token amounts.
     */
    private fun parseLogsNotification(value: JSONObject, mint: String) {
        val logs = value.optJSONArray("logs") ?: return
        val sig  = value.optString("signature", "")
        if (sig.isBlank() || mint.isBlank()) return
        if (value.opt("err") != null && value.opt("err") != JSONObject.NULL) return
        // V5.0.7765 — pump.fun emits its TradeEvent as an Anchor `Program data:` log:
        // 8-byte discriminator, mint(32), solAmount u64, tokenAmount u64, isBuy u8,
        // user(32). Decoded here: the real mint, side, size and trader wallet.
        val mintBytes = try { io.github.novacrypto.base58.Base58.base58Decode(mint) } catch (_: Throwable) { null }
        for (i in 0 until logs.length()) {
            val line = logs.optString(i, "")
            if (!line.startsWith("Program data: ") || mintBytes == null) continue
            val b = try { android.util.Base64.decode(line.removePrefix("Program data: ").trim(), android.util.Base64.DEFAULT) } catch (_: Throwable) { continue }
            if (b.size < 89 || !b.copyOfRange(8, 40).contentEquals(mintBytes)) continue
            val solAmt = u64le7765(b, 40) / 1_000_000_000.0
            val tokenAmt = u64le7765(b, 48) / 1_000_000.0
            val isBuy = (b[56].toInt() and 0xFF) == 1
            val wallet = try { io.github.novacrypto.base58.Base58.base58Encode(b.copyOfRange(57, 89)) } catch (_: Throwable) { "" }
            if (solAmt > 0.0) { dispatchCallback7863 { onSwap(mint, isBuy, solAmt, tokenAmt, wallet, sig) }; return }
        }

        // Detect swap direction from log messages
        val logList = (0 until logs.length()).map { logs.optString(it, "") }
        val isSwap  = logList.any {
            it.contains("Swap") || it.contains("swap") ||
            it.contains("Buy")  || it.contains("Sell")
        }
        if (!isSwap) return

        // Extract amounts from log lines
        // Pump.fun logs format: "Program log: Buy {token_amount} tokens for {sol_amount} SOL"
        // V5.0.7807 — compiled once (companion), not two Pattern compiles per notification.
        val buyPattern  = swapBuyRegex7807
        val sellPattern = swapSellRegex7807

        for (log in logList) {
            val buyMatch = buyPattern.find(log)
            if (buyMatch != null) {
                val tokenAmt = buyMatch.groupValues[1].toDoubleOrNull() ?: continue
                val solAmt   = buyMatch.groupValues[2].toDoubleOrNull() ?: continue
                // V5.0.7765 — the mint is known from the subscription.
                dispatchCallback7863 { onSwap(mint, true, solAmt, tokenAmt, "", sig) }
                return
            }
            val sellMatch = sellPattern.find(log)
            if (sellMatch != null) {
                val tokenAmt = sellMatch.groupValues[1].toDoubleOrNull() ?: continue
                val solAmt   = sellMatch.groupValues[2].toDoubleOrNull() ?: continue
                dispatchCallback7863 { onSwap(mint, false, solAmt, tokenAmt, "", sig) }
                return
            }
        }
    }

    private fun u64le7765(b: ByteArray, off: Int): Double {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (b[off + k].toLong() and 0xFF)
        return if (v < 0) v.toDouble() + 18446744073709551616.0 else v.toDouble()
    }

    /**
     * Parse account change notifications — detect large wallet moves.
     * We track dev wallets and large holders for early warning.
     */
    private fun parseAccountNotification(value: JSONObject, wallet: String) {
        if (wallet.isBlank()) return
        val lamports = value.optLong("lamports", 0L)
        val solBalance = lamports / 1_000_000_000.0
        if (solBalance > 0) dispatchCallback7863 { onLargeWalletMove(wallet, "", solBalance, false) }
    }

    /** V5.0.7807 — drop a desired mint whose subscribe was refused/never ACKed, if still unfilled. */
    private fun releaseUnackedMint7807(mint: String) {
        synchronized(subscriptions) {
            if (subscriptions.containsKey(mint) && subscriptions[mint] == null) subscriptions.remove(mint)
        }
    }

    /** V5.0.7807 — same for a wallet: free the slot so a later watchWallet can retry. */
    private fun releaseUnackedWallet7807(wallet: String) {
        if (walletServerSubscription7803.containsKey(wallet)) return
        synchronized(watchedWallets) { watchedWallets.remove(wallet) }
    }

    /**
     * V5.0.7807 — pending requests whose ACK never arrived expire after
     * REQUEST_ACK_TIMEOUT_MS_7807; a hard cap trims the oldest ids beyond
     * MAX_PENDING_REQUESTS_7807. Request maps can no longer grow without bound.
     */
    private fun expireStaleRequests7807(now: Long) {
        var expired = 0
        val stale = requestSentAtMs7807.entries.filter { now - it.value > REQUEST_ACK_TIMEOUT_MS_7807 }.map { it.key }
        val overflow = (requestToMint7765.size + requestToWallet7803.size) - MAX_PENDING_REQUESTS_7807
        val trimmed = if (overflow > 0) (requestToMint7765.keys + requestToWallet7803.keys).sorted().take(overflow) else emptyList()
        (stale + trimmed).toSet().forEach { id ->
            requestSentAtMs7807.remove(id)
            requestToMint7765.remove(id)?.let { mint -> releaseUnackedMint7807(mint); expired++ }
            requestToWallet7803.remove(id)?.let { wallet -> releaseUnackedWallet7807(wallet); expired++ }
        }
        if (expired > 0) {
            HeliusSubscriptionTelemetry7807.expiredRequests.addAndGet(expired.toLong())
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_SUB_ACK_EXPIRED_7807") } catch (_: Throwable) {}
        }
    }

    private fun unsubscribeOrphan7807(method: String, serverSubscriptionId: Int) {
        if (serverSubscriptionId < 0) return
        val key = "$method:$serverSubscriptionId"
        val already = synchronized(recentUnsubscribed7807) { key in recentUnsubscribed7807 }
        if (already) return
        HeliusSubscriptionTelemetry7807.orphanUnsubscribes.incrementAndGet()
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_ORPHAN_NOTIFICATION_UNSUBSCRIBED_7807") } catch (_: Throwable) {}
        sendUnsubscribe7803(method, serverSubscriptionId)
    }

    /** V5.0.7807 — cardinality gauges for the PipelineHealthCollector dump. */
    private fun publishGauges7807() {
        try {
            HeliusSubscriptionTelemetry7807.desiredTokenSubs = synchronized(subscriptions) { subscriptions.size }
            HeliusSubscriptionTelemetry7807.serverTokenSubs = subscriptionToMint7765.size
            HeliusSubscriptionTelemetry7807.requestMapSize = requestToMint7765.size + requestToWallet7803.size
            HeliusSubscriptionTelemetry7807.walletSubs = synchronized(watchedWallets) { watchedWallets.size }
            HeliusSubscriptionTelemetry7807.serverWalletSubs = walletServerSubscription7803.size
        } catch (_: Throwable) {}
    }

    private fun sendUnsubscribe7803(method: String, serverSubscriptionId: Int) {
        if (serverSubscriptionId < 0) return
        // V5.0.7807 — remember (bounded) so an in-flight notification for the same id
        // does not trigger a second unsubscribe through the orphan path.
        synchronized(recentUnsubscribed7807) {
            recentUnsubscribed7807.add("$method:$serverSubscriptionId")
            if (recentUnsubscribed7807.size > MAX_UNSUB_DEDUPE_7807) {
                val it = recentUnsubscribed7807.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
        }
        ws?.send(JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", idCounter.getAndIncrement())
            put("method", method)
            put("params", JSONArray().put(serverSubscriptionId))
        }.toString())
    }

    private fun scheduleReconnect() {
        if (!running) return
        if (!reconnectScheduled7803.compareAndSet(false, true)) return
        val delay = reconnectDelay
        reconnectDelay = (reconnectDelay * 2).coerceAtMost(30_000L)
        reconnectScheduler7803.schedule({
            reconnectScheduled7803.set(false)
            if (running && ws == null) doConnect()
        }, delay, TimeUnit.MILLISECONDS)
    }
}

/**
 * V5.0.7807 — Helius subscription memory/cardinality telemetry, surfaced as one line
 * in PipelineHealthCollector's dump. Gauges are written by HeliusWebSocket on every
 * membership change; counters are monotonic for the process. Field Manual L240:
 * measure the feed, don't assume it.
 */
object HeliusSubscriptionTelemetry7807 {
    @Volatile internal var desiredTokenSubs: Int = 0
    @Volatile internal var serverTokenSubs: Int = 0
    @Volatile internal var requestMapSize: Int = 0
    @Volatile internal var walletSubs: Int = 0
    @Volatile internal var serverWalletSubs: Int = 0
    @Volatile internal var pinnedHeld: Int = 0
    internal val evictions = AtomicInteger(0)
    internal val lateAckUnsubscribes = AtomicInteger(0)
    internal val reconnectResubscribes = AtomicInteger(0)
    internal val expiredRequests = java.util.concurrent.atomic.AtomicLong(0L)
    internal val orphanUnsubscribes = AtomicInteger(0)
    // V5.0.7819 — subscribe attempts refused because the id is not a Solana mint
    // (stock/FX/metal/commodity/perps tickers, non-Solana crypto), held positions
    // dropped from the pinned set for the same reason, socket re-opens after the
    // first, and why each reconnect happened. Field Manual L240: measure the feed.
    internal val nonSolanaRejected7819 = AtomicInteger(0)
    @Volatile internal var pinnedExcludedNonSolana7819: Int = 0
    internal val reconnects7819 = AtomicInteger(0)
    private val reconnectReasons7819 = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private const val MAX_REASON_KEYS_7819 = 24

    internal fun noteReconnect7819(reason: String) {
        val key7819 = reason.take(48)
        val slot7819 = reconnectReasons7819[key7819]
            ?: if (reconnectReasons7819.size < MAX_REASON_KEYS_7819) reconnectReasons7819.getOrPut(key7819) { AtomicInteger(0) }
            else reconnectReasons7819.getOrPut("OTHER") { AtomicInteger(0) }
        slot7819.incrementAndGet()
    }

    internal fun reconnectReasonsText7819(): String =
        reconnectReasons7819.entries.sortedByDescending { it.value.get() }
            .joinToString(",", "[", "]") { "${it.key}=${it.value.get()}" }

    fun line7807(): String =
        "desiredTokenSubs=$desiredTokenSubs serverTokenSubs=$serverTokenSubs requestMapSize=$requestMapSize " +
            "walletSubs=$walletSubs serverWalletSubs=$serverWalletSubs pinnedHeld=$pinnedHeld " +
            "evictions=${evictions.get()} lateAckUnsubscribes=${lateAckUnsubscribes.get()} " +
            "reconnectResubscribes=${reconnectResubscribes.get()} expiredRequests=${expiredRequests.get()} " +
            "orphanUnsubscribes=${orphanUnsubscribes.get()} " +
            "nonSolanaRejected=${nonSolanaRejected7819.get()} pinnedExcludedNonSolana=$pinnedExcludedNonSolana7819 " +
            "reconnects=${reconnects7819.get()} reconnectReasons=${reconnectReasonsText7819()}"
}

/**
 * V5.0.7819 — Helius (WS logsSubscribe, DAS getAssetBatch, pump-curve RPC) is a
 * Solana data source. A base58 32–44 char id is the backstop identity test: stock,
 * FX, metal, commodity and perps tickers, `solana|`-prefixed alt identities, `cg:`
 * ids and EVM 0x addresses all fail it. Field Manual L186.
 */
object HeliusSolanaScope7819 {
    private val BASE58_SOLANA_MINT_7819 = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

    fun isSolanaMint7819(id: String?): Boolean = id != null && BASE58_SOLANA_MINT_7819.matches(id)

    /** Filter a batch to Solana mints; counts what was dropped. */
    fun solanaMintsOnly7819(ids: List<String>): List<String> {
        val kept7819 = ids.filter { isSolanaMint7819(it) }
        val dropped7819 = ids.size - kept7819.size
        if (dropped7819 > 0) {
            HeliusSubscriptionTelemetry7807.nonSolanaRejected7819.addAndGet(dropped7819)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_RPC_NON_SOLANA_ID_DROPPED_7819") } catch (_: Throwable) {}
        }
        return kept7819
    }
}

/** V5.0.7867 — pure: held mints first, then the rest newest-first ([eldestFirst] is map order). */
internal fun resubscribeOrder7867(eldestFirst: List<String>, held: Set<String>): List<String> {
    val newestFirst = eldestFirst.asReversed()
    return newestFirst.filter { it in held } + newestFirst.filterNot { it in held }
}

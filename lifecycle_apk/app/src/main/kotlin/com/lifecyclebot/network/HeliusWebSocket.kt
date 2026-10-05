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
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
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
            return
        }
        doConnect()
    }

    fun disconnect() {
        running = false
        reconnectScheduled7803.set(false)
        ws?.close(1000, "Bot stopped")
        ws = null
        requestToMint7765.clear()
        subscriptionToMint7765.clear()
        requestToWallet7803.clear()
        subscriptionToWallet7803.clear()
        walletServerSubscription7803.clear()
        synchronized(subscriptions) {
            subscriptions.keys.toList().forEach { subscriptions[it] = null }
        }
    }

    /** Subscribe to all swaps for a specific token mint. */
    fun subscribeToken(mint: String) {
        if (mint.isBlank()) return
        var evictedMint: String? = null
        var evictedServerId: Int? = null
        val requestId: Int
        synchronized(subscriptions) {
            if (subscriptions.containsKey(mint)) return
            if (subscriptions.size >= MAX_TOKEN_SUBSCRIPTIONS_7794) {
                val it = subscriptions.entries.iterator()
                if (it.hasNext()) {
                    val eldest = it.next()
                    evictedMint = eldest.key
                    evictedServerId = eldest.value
                    it.remove()
                }
            }
            requestId = idCounter.getAndIncrement()
            subscriptions[mint] = null
        }
        evictedMint?.let { old ->
            requestToMint7765.entries.removeIf { it.value == old }
            subscriptionToMint7765.entries.removeIf { it.value == old }
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_TOKEN_SUB_EVICTED_7794") } catch (_: Throwable) {}
        }
        evictedServerId?.let { sendUnsubscribe7803("logsUnsubscribe", it) }
        sendSubscribe(requestId, listOf(mint))
    }

    fun unsubscribeToken(mint: String) {
        val serverId = synchronized(subscriptions) {
            if (!subscriptions.containsKey(mint)) return
            subscriptions.remove(mint)
        }
        requestToMint7765.entries.removeIf { it.value == mint }
        subscriptionToMint7765.entries.removeIf { it.value == mint }
        serverId?.let { sendUnsubscribe7803("logsUnsubscribe", it) }
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
            requestToWallet7803.entries.removeIf { it.value == old }
            subscriptionToWallet7803.entries.removeIf { it.value == old }
            walletServerSubscription7803.remove(old)?.let { sendUnsubscribe7803("accountUnsubscribe", it) }
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_WS_WALLET_SUB_EVICTED_7794") } catch (_: Throwable) {}
        }
        if (shouldSubscribe) sendAccountSubscribe(idCounter.getAndIncrement(), address)
    }

    fun unwatchWallet(address: String) {
        synchronized(watchedWallets) { watchedWallets.remove(address) }
        requestToWallet7803.entries.removeIf { it.value == address }
        subscriptionToWallet7803.entries.removeIf { it.value == address }
        walletServerSubscription7803.remove(address)?.let { sendUnsubscribe7803("accountUnsubscribe", it) }
    }

    private fun doConnect() {
        val url = "wss://mainnet.helius-rpc.com/?api-key=$apiKey"
        val req = Request.Builder().url(url).build()

        ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                onLog("Helius WebSocket connected")
                networkRetry.recordSuccess()
                reconnectDelay = 2_000L
                reconnectScheduled7803.set(false)

                requestToMint7765.clear()
                subscriptionToMint7765.clear()
                requestToWallet7803.clear()
                subscriptionToWallet7803.clear()
                walletServerSubscription7803.clear()

                val tokenSnapshot = synchronized(subscriptions) {
                    val keys = subscriptions.keys.toList()
                    keys.forEach { subscriptions[it] = null }
                    keys
                }
                tokenSnapshot.forEach { mint -> sendSubscribe(idCounter.getAndIncrement(), listOf(mint)) }

                val walletSnapshot = synchronized(watchedWallets) { watchedWallets.toList() }
                walletSnapshot.forEach { addr -> sendAccountSubscribe(idCounter.getAndIncrement(), addr) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (ws === webSocket) ws = null
                onLog("Helius WS error: ${t.message?.take(60)} — reconnecting in ${reconnectDelay/1000}s")
                networkRetry.recordFailure()
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (ws === webSocket) ws = null
                if (running) scheduleReconnect()
            }
        })
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
        requestToMint7765[id] = mint
        val mentions = listOf(mint)
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
        requestToWallet7803[id] = address
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
            // V5.0.7803 — request id -> server subscription id. Late ACKs for
            // evicted members are immediately unsubscribed instead of resurrected.
            if (method.isBlank() && msg.has("id") && msg.opt("result") is Number) {
                val requestId = msg.optInt("id", -1)
                val sub = msg.optInt("result", -1)
                requestToMint7765.remove(requestId)?.let { mint ->
                    val wanted = synchronized(subscriptions) { subscriptions.containsKey(mint) }
                    if (sub >= 0 && wanted) {
                        subscriptionToMint7765[sub] = mint
                        synchronized(subscriptions) { if (subscriptions.containsKey(mint)) subscriptions[mint] = sub }
                    } else if (sub >= 0) sendUnsubscribe7803("logsUnsubscribe", sub)
                    return
                }
                requestToWallet7803.remove(requestId)?.let { wallet ->
                    val wanted = synchronized(watchedWallets) { wallet in watchedWallets }
                    if (sub >= 0 && wanted) {
                        subscriptionToWallet7803[sub] = wallet
                        walletServerSubscription7803[wallet] = sub
                    } else if (sub >= 0) sendUnsubscribe7803("accountUnsubscribe", sub)
                    return
                }
                return
            }
            val params = msg.optJSONObject("params") ?: return
            val result = params.optJSONObject("result") ?: return
            val value  = result.optJSONObject("value") ?: return

            when (method) {
                "logsNotification" -> parseLogsNotification(value, subscriptionToMint7765[params.optInt("subscription", -1)].orEmpty())
                "accountNotification" -> parseAccountNotification(
                    value,
                    subscriptionToWallet7803[params.optInt("subscription", -1)].orEmpty(),
                )
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
            if (solAmt > 0.0) { onSwap(mint, isBuy, solAmt, tokenAmt, wallet, sig); return }
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
        val buyPattern  = Regex("""Buy\s+([\d.]+)\s+tokens?\s+for\s+([\d.]+)\s+SOL""", RegexOption.IGNORE_CASE)
        val sellPattern = Regex("""Sell\s+([\d.]+)\s+tokens?\s+for\s+([\d.]+)\s+SOL""", RegexOption.IGNORE_CASE)

        for (log in logList) {
            val buyMatch = buyPattern.find(log)
            if (buyMatch != null) {
                val tokenAmt = buyMatch.groupValues[1].toDoubleOrNull() ?: continue
                val solAmt   = buyMatch.groupValues[2].toDoubleOrNull() ?: continue
                // V5.0.7765 — the mint is known from the subscription.
                onSwap(mint, true, solAmt, tokenAmt, "", sig)
                return
            }
            val sellMatch = sellPattern.find(log)
            if (sellMatch != null) {
                val tokenAmt = sellMatch.groupValues[1].toDoubleOrNull() ?: continue
                val solAmt   = sellMatch.groupValues[2].toDoubleOrNull() ?: continue
                onSwap(mint, false, solAmt, tokenAmt, "", sig)
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
        if (solBalance > 0) onLargeWalletMove(wallet, "", solBalance, false)
    }

    private fun sendUnsubscribe7803(method: String, serverSubscriptionId: Int) {
        if (serverSubscriptionId < 0) return
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

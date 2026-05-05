package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.484 — Pyth Hermes streaming price oracle (free, ~400ms latency).
 *
 * The user requested Pyth Lazer for sub-100ms feeds — Lazer is now Pyth
 * Pro and is subscription-only. Hermes (the free public Pyth streaming
 * gateway) delivers ~400ms updates and is the practical free
 * equivalent. Use case: feeds the bot's crypto/perps/stocks/forex/metals
 * price oracle with push updates instead of REST polling, giving
 * faster reaction time on trailing-stop and profit-lock triggers.
 *
 * Endpoint: https://hermes.pyth.network/v2/updates/price/stream?ids[]=<feedId>&...
 * Format: Server-Sent Events (SSE) — each frame is a JSON envelope with
 *         { parsed: [{ id, price: { price, expo, conf, publish_time }, ... }] }
 *
 * Pyth feed IDs (Solana mainnet hex):
 *   SOL/USD: ef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d
 *   BTC/USD: e62df6c8b4a85fe1a67db44dc12de5db330f7ac66b72dc658afedf0f4a415b43
 *   ETH/USD: ff61491a931112ddf1bd8147cd1b641375f79f5825126d665480874634fd0ace
 *   ...
 *
 * Usage:
 *   PythHermesStream.subscribe(listOf("ef0d…", "e62d…")) { feedId, priceUsd, confUsd, publishTime ->
 *     // update price oracle
 *   }
 *   PythHermesStream.unsubscribeAll()
 */
object PythHermesStream {
    private const val TAG = "PythHermesStream"
    private const val BASE = "https://hermes.pyth.network/v2/updates/price/stream"

    private val running = AtomicBoolean(false)
    private val reconnectAttempt = AtomicLong(0L)
    @Volatile private var eventSource: EventSource? = null
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var feedIds: List<String> = emptyList()
    @Volatile private var onPriceCb: ((feedId: String, priceUsd: Double, confUsd: Double, publishTime: Long) -> Unit)? = null
    private val lastPrice = ConcurrentHashMap<String, Double>()

    fun subscribe(
        feedHexIds: List<String>,
        onPrice: (feedId: String, priceUsd: Double, confUsd: Double, publishTime: Long) -> Unit,
    ) {
        if (feedHexIds.isEmpty()) return
        if (!running.compareAndSet(false, true)) {
            ErrorLogger.warn(TAG, "already running — re-subscribing with merged feed list")
        }
        feedIds = feedHexIds.distinct()
        onPriceCb = onPrice
        if (client == null) {
            client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        }
        connect()
    }

    fun unsubscribeAll() {
        running.set(false)
        try { eventSource?.cancel() } catch (_: Exception) {}
        eventSource = null
        try { client?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        client = null
        feedIds = emptyList()
        ErrorLogger.info(TAG, "unsubscribed all")
    }

    /** Returns the most recent price seen for a feedId, or null if no update received. */
    fun lastPrice(feedHexId: String): Double? = lastPrice[feedHexId]

    private fun connect() {
        if (!running.get() || feedIds.isEmpty()) return
        val url = StringBuilder(BASE).append("?")
        feedIds.forEachIndexed { i, id ->
            if (i > 0) url.append("&")
            // Hermes accepts ids without 0x prefix; strip if present.
            val clean = if (id.startsWith("0x")) id.substring(2) else id
            url.append("ids[]=").append(clean)
        }
        url.append("&parsed=true")  // include parsed price object in each frame
        val req = Request.Builder()
            .url(url.toString())
            .header("Accept", "text/event-stream")
            .build()
        ErrorLogger.info(TAG, "🔌 connecting to Hermes SSE for ${feedIds.size} feeds (attempt ${reconnectAttempt.get() + 1})")
        eventSource = client?.let { EventSources.createFactory(it).newEventSource(req, listener) }
    }

    private val listener = object : EventSourceListener() {
        override fun onOpen(eventSource: EventSource, response: Response) {
            reconnectAttempt.set(0)
            ErrorLogger.info(TAG, "✅ Hermes SSE connected (${feedIds.size} feeds, free ~400ms latency)")
        }

        override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
            try {
                val j = JSONObject(data)
                val parsed: JSONArray = j.optJSONArray("parsed") ?: return
                for (i in 0 until parsed.length()) {
                    val item = parsed.optJSONObject(i) ?: continue
                    val feedId = item.optString("id", "")
                    val priceObj = item.optJSONObject("price") ?: continue
                    // price is integer string + expo (negative). actual = price * 10^expo
                    val priceRaw = priceObj.optString("price", "0").toLongOrNull() ?: continue
                    val confRaw = priceObj.optString("conf", "0").toLongOrNull() ?: 0L
                    val expo = priceObj.optInt("expo", 0)
                    val publishTime = priceObj.optLong("publish_time", 0L)
                    val scale = Math.pow(10.0, expo.toDouble())
                    val priceUsd = priceRaw * scale
                    val confUsd = confRaw * scale
                    if (priceUsd > 0 && feedId.isNotBlank()) {
                        lastPrice[feedId] = priceUsd
                        onPriceCb?.invoke(feedId, priceUsd, confUsd, publishTime)
                    }
                }
            } catch (e: Exception) {
                ErrorLogger.warn(TAG, "parse error: ${e.message?.take(80)}")
            }
        }

        override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
            ErrorLogger.warn(TAG, "❌ SSE failure: ${t?.message?.take(100)} — will reconnect")
            scheduleReconnect()
        }

        override fun onClosed(eventSource: EventSource) {
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

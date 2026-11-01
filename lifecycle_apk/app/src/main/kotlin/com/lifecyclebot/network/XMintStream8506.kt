package com.lifecyclebot.network

import com.lifecyclebot.engine.market.KineticFlow8506
import com.lifecyclebot.engine.market.TelegramCallSweeper7830
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Receive the operator's existing X filtered-stream rules. Only original posts with a dated, explicit CA can correlate. */
internal object XMintStream8506 {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "x-mint-stream").apply { isDaemon = true } }
    private val http by lazy { SharedHttpClient.builder().readTimeout(30, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(false).build() }
    private var generation = 0L
    private var credential = ""
    private var call: Call? = null
    @Volatile var state = "OFF"
        private set

    @Synchronized fun start(bearer: String) {
        if (bearer == credential) return
        stop()
        if (bearer.isBlank()) return
        credential = bearer
        val id = generation
        worker.execute { run(id, bearer) }
    }
    @Synchronized fun stop() {
        generation++; credential = ""; call?.cancel(); call = null; state = "OFF"
        (this as java.lang.Object).notifyAll()
    }

    private fun run(id: Long, bearer: String) {
        var delay = 2_000L
        while (true) {
            val stream = synchronized(this) {
                if (id != generation) return
                state = "CONNECTING"
                http.newCall(Request.Builder().url("https://api.x.com/2/tweets/search/stream?tweet.fields=created_at,referenced_tweets")
                    .header("Authorization", "Bearer " + bearer).build()).also { call = it }
            }
            try {
                stream.execute().use { response ->
                    synchronized(this) {
                        if (id != generation) return
                        state = if (response.isSuccessful) "RECEIVING" else "HTTP_" + response.code
                    }
                    if (response.isSuccessful) {
                        val src = response.body?.source() ?: return@use
                        while (true) {
                            val line = src.readUtf8LineStrict(128 * 1024L)
                            if (line.isNotBlank()) accept(line, System.currentTimeMillis())
                            delay = 2_000L
                        }
                    } else if (response.code in setOf(401, 403, 429)) delay = 300_000L
                }
            } catch (_: Exception) { /* EOF, keepalive timeout or cancellation */ }
            synchronized(this) {
                if (id != generation) return
                if (state == "RECEIVING" || state == "CONNECTING") state = "RETRY"
                (this as java.lang.Object).wait(delay)
                delay = (delay * 2).coerceAtMost(300_000L)
            }
        }
    }

    internal fun accept(line: String, now: Long) {
        val p = runCatching { JSONObject(line).optJSONObject("data") }.getOrNull() ?: return
        if ((p.optJSONArray("referenced_tweets")?.length() ?: 0) > 0) return
        val published = runCatching { Instant.parse(p.getString("created_at")).toEpochMilli() }.getOrNull() ?: return
        val found = TelegramCallSweeper7830.extractMints7830(p.optString("text"), emptyList())
        (found.mints - found.pairLike).forEach { KineticFlow8506.mention(it, published, now) }
    }
}

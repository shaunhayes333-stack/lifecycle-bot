package com.lifecyclebot.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8009 — keep the execution hosts' connections warm.
 *
 * A trade's first call to Helius Sender, the Helius RPC or PumpPortal after a quiet spell paid
 * DNS + TCP + TLS again (~100-400 ms on mobile) because the idle connection had been evicted.
 * While the bot runs, one cheap request per host every [PERIOD_MS] keeps a pooled connection
 * open on the shared client those hosts' execution clients are built from: Sender's /ping
 * (built for exactly this), Helius getHealth, and PumpPortal's trade endpoint (a GET it refuses,
 * which still opens the TLS session). Off the trading threads; failures are ignored.
 */
object ExecWarm8009 {
    private const val PERIOD_MS = 25_000L
    private val started = AtomicBoolean(false)
    private val pings = AtomicLong(0)
    private val http by lazy { SharedHttpClient.builder().callTimeout(4, TimeUnit.SECONDS).build() }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            while (true) {
                try {
                    val running = try { com.lifecyclebot.engine.BotService.status.running } catch (_: Throwable) { true }
                    if (running) warmOnce()
                } catch (_: Throwable) {}
                try { Thread.sleep(PERIOD_MS) } catch (_: InterruptedException) { return@Thread }
            }
        }, "ExecWarm8009").apply { isDaemon = true }.start()
    }

    fun statusLine(): String = "pings=${pings.get()} every ${PERIOD_MS / 1000}s"

    private fun warmOnce() {
        hit(Request.Builder().url("https://sender.helius-rpc.com/ping").get().build())
        hit(Request.Builder().url("https://pumpportal.fun/api/trade-local").get().build())
        val rpc = try { com.lifecyclebot.engine.RuntimeProviderAuthority6685.configuredHeliusRpc() } catch (_: Throwable) { "" }
        if (rpc.isNotBlank()) hit(Request.Builder().url(rpc)
            .post("""{"jsonrpc":"2.0","id":"warm8009","method":"getHealth"}""".toRequestBody("application/json".toMediaType())).build())
    }

    private fun hit(req: Request) {
        try { http.newCall(req).execute().use { it.body?.close() }; pings.incrementAndGet() } catch (_: Throwable) {}
    }
}

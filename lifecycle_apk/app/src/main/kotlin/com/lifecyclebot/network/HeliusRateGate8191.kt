package com.lifecyclebot.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8191 §HELIUS_RPS_IS_NOT_HELIUS_CREDITS.
 *
 * 5.0.8186: the Helius credential check answered HTTP 429 while the internal credit budget read 23%. They are two
 * different limits: credits are a monthly/daily allowance (HeliusCreditEconomy7881), a 429 is the per-second request
 * rate of the key. Nothing in the app paced Helius per second: the startup fan-out (key probe, wallet reads, bundle
 * scans, holder concentration, birth hydrator, DAS batches, the chain launch feed) all left at once, the key answered
 * 429, and the circuit then cooled the whole host (the WebSocket upgrade included).
 *
 * One shared token bucket per Helius limit ([Bucket8191]) now paces every Helius HTTP call that goes through
 * SharedHttpClient: "rpc" (mainnet.helius-rpc.com and other *.helius-rpc.com RPC hosts) and "enhanced"
 * (api.helius.xyz, a lower limit). The rate adapts to what the key proves (AIMD): a real 429 halves it and pauses the
 * bucket for Retry-After (or a short default); every quiet interval adds one request per second back, up to a ceiling.
 * Execution calls (sends, balances, blockhash, fee reads, exits — the 8144 EXEC lane) and the WebSocket upgrade are
 * never delayed or refused; they still take a token (the bucket may go into debt) so background work yields to them.
 * Background calls wait for a token up to [MAX_WAIT_MS_8191]; past that they are answered locally with a synthetic 599
 * (X-AATE-Synthetic) that every caller already treats as "this app declined to ask", never as a provider verdict.
 * A local AATE_* 429 (the 8066 / 8094 credit gates) is a credit decision, not a rate signal, and never slows the bucket.
 */
object HeliusRateGate8191 {
    private const val MAX_WAIT_MS_8191 = 1_500L
    private const val DEFAULT_PAUSE_MS_8191 = 1_000L
    private const val MAX_PAUSE_MS_8191 = 30_000L
    const val RECOVER_EVERY_MS_8191 = 20_000L

    /**
     * Thread-safe adaptive token bucket. [rate] tokens per second, capacity = max(1, rate). [take8191] returns how long
     * the caller must wait before its request may go (0 = now), or -1 when the wait would exceed [maxWaitMs].
     */
    class Bucket8191(
        val name: String,
        initialRate: Double,
        private val minRate: Double,
        private val maxRate: Double,
    ) {
        private var rate = initialRate.coerceIn(minRate, maxRate)
        private var tokens = rate
        private var lastRefillMs = 0L
        private var pausedUntilMs = 0L
        private var lastLimitMs = 0L
        private var lastRecoverMs = 0L

        @Synchronized fun rate8191(): Double = rate
        @Synchronized fun pausedUntil8191(): Long = pausedUntilMs

        private fun refill(nowMs: Long) {
            if (lastRefillMs == 0L) { lastRefillMs = nowMs; return }
            val dt = (nowMs - lastRefillMs).coerceAtLeast(0L)
            lastRefillMs = nowMs
            tokens = (tokens + dt / 1000.0 * rate).coerceAtMost(maxOf(1.0, rate))
            // Additive recovery: one more request per second for every quiet interval since the last 429.
            if (nowMs - lastLimitMs >= RECOVER_EVERY_MS_8191 && nowMs - lastRecoverMs >= RECOVER_EVERY_MS_8191 && rate < maxRate) {
                rate = (rate + 1.0).coerceAtMost(maxRate)
                lastRecoverMs = nowMs
            }
        }

        /**
         * Reserve one token. [priority] (execution / WebSocket upgrade) always gets 0 and may push the bucket into debt
         * (never below -capacity). Otherwise the wait until a token frees (and any 429 pause has ended), or -1 when that
         * is longer than [maxWaitMs] (nothing is reserved then).
         */
        @Synchronized fun take8191(nowMs: Long, priority: Boolean, maxWaitMs: Long = MAX_WAIT_MS_8191): Long {
            refill(nowMs)
            if (priority) {
                tokens = (tokens - 1.0).coerceAtLeast(-maxOf(1.0, rate))
                return 0L
            }
            val pauseWait = (pausedUntilMs - nowMs).coerceAtLeast(0L)
            val tokenWait = if (tokens >= 1.0) 0L else Math.ceil((1.0 - tokens) / rate * 1000.0).toLong()
            val wait = maxOf(pauseWait, tokenWait)
            if (wait > maxWaitMs) return -1L
            tokens -= 1.0
            return wait
        }

        /** A real 429 from the provider: halve the rate (floor [minRate]) and pause for Retry-After (else the default). */
        @Synchronized fun onRateLimited8191(nowMs: Long, retryAfterSec: Long?) {
            val pause = if (retryAfterSec != null && retryAfterSec > 0L) (retryAfterSec * 1000L).coerceAtMost(MAX_PAUSE_MS_8191) else DEFAULT_PAUSE_MS_8191
            pausedUntilMs = maxOf(pausedUntilMs, nowMs + pause)
            // One burst of parallel 429s halves once, not once per caller.
            if (nowMs - lastLimitMs >= 1_000L) rate = (rate / 2.0).coerceAtLeast(minRate)
            lastLimitMs = nowMs
            tokens = minOf(tokens, 0.0)
        }
    }

    val rpc8191 = Bucket8191("rpc", initialRate = 8.0, minRate = 2.0, maxRate = 40.0)
    val enhanced8191 = Bucket8191("enhanced", initialRate = 2.0, minRate = 1.0, maxRate = 10.0)

    private val waited = AtomicLong(0L)
    private val waitedMs = AtomicLong(0L)
    private val refused = AtomicLong(0L)
    private val priorityCalls = AtomicLong(0L)
    private val limited = AtomicLong(0L)
    private val localCreditAnswers = AtomicLong(0L)
    private val calls = AtomicLong(0L)

    /** Pure: which bucket a Helius host answers to; null = not paced (not Helius, or the Sender transaction relay). */
    fun bucketFor8191(host: String): Bucket8191? {
        val h = host.lowercase()
        if (!(h.contains("helius-rpc.com") || h.contains("helius.xyz"))) return null
        if (h.startsWith("sender") || h.contains("-sender.")) return null
        return if (h.contains("helius.xyz")) enhanced8191 else rpc8191
    }

    /** Pure: a provider 429 (not the app's own AATE_* credit-gate answer). */
    fun providerRateLimit8191(code: Int, message: String): Boolean = code == 429 && !message.startsWith("AATE_")

    /** Pure: Retry-After in seconds (delta-seconds form only), or null. */
    fun retryAfterSec8191(header: String?): Long? = header?.trim()?.toLongOrNull()?.takeIf { it > 0L }

    internal fun noteWait8191(ms: Long) { if (ms > 0L) { waited.incrementAndGet(); waitedMs.addAndGet(ms) } }
    internal fun noteRefused8191() { refused.incrementAndGet() }
    internal fun notePriority8191() { priorityCalls.incrementAndGet() }
    internal fun noteCall8191() { calls.incrementAndGet() }
    internal fun noteLimited8191(b: Bucket8191, nowMs: Long, retryAfterSec: Long?) { limited.incrementAndGet(); b.onRateLimited8191(nowMs, retryAfterSec) }
    internal fun noteLocalCredit8191() { localCreditAnswers.incrementAndGet() }

    fun statusLine8191(nowMs: Long = System.currentTimeMillis()): String =
        "heliusRps8191[calls=${calls.get()} priority=${priorityCalls.get()} waited=${waited.get()}/${waitedMs.get()}ms " +
            "refusedLocal=${refused.get()} provider429=${limited.get()} localCreditAnswers=${localCreditAnswers.get()} " +
            "rpcRate=${"%.1f".format(rpc8191.rate8191())}/s rpcPauseMs=${(rpc8191.pausedUntil8191() - nowMs).coerceAtLeast(0L)} " +
            "enhancedRate=${"%.1f".format(enhanced8191.rate8191())}/s enhancedPauseMs=${(enhanced8191.pausedUntil8191() - nowMs).coerceAtLeast(0L)}]"
}

/**
 * V5.0.8191 — SharedHttpClient's Helius pacing step (after HostCircuitInterceptor, before the credit meter). See
 * [HeliusRateGate8191].
 */
object HeliusRateInterceptor8191 : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val bucket = HeliusRateGate8191.bucketFor8191(req.url.host) ?: return chain.proceed(req)
        HeliusRateGate8191.noteCall8191()
        val wsUpgrade = req.header("Upgrade")?.equals("websocket", ignoreCase = true) == true
        val exec = ExitHttpScope7314.active() || try {
            ProofDataLanes8144.laneFor8144(null, false, req.url.host, req.url.encodedPath, ProofDataLanes8144.peekRpcMethod8144(req)) ==
                ProofDataLanes8144.Lane8144.EXEC
        } catch (_: Throwable) { false }
        val priority = wsUpgrade || exec
        val wait = bucket.take8191(System.currentTimeMillis(), priority)
        if (priority) HeliusRateGate8191.notePriority8191()
        if (wait < 0L) {
            HeliusRateGate8191.noteRefused8191()
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_RPS_LOCAL_REFUSED_8191") } catch (_: Throwable) {}
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(599)
                .message("HELIUS_RPS_GATE_8191 bucket=${bucket.name}")
                .header(HostCircuitInterceptor.SYNTHETIC_HEADER_6969, "1")
                .body("".toResponseBody(null))
                .build()
        }
        if (wait > 0L) {
            HeliusRateGate8191.noteWait8191(wait)
            try { Thread.sleep(wait) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("helius rate wait interrupted") }
        }
        val resp = chain.proceed(req)
        if (resp.code == 429) {
            if (HeliusRateGate8191.providerRateLimit8191(resp.code, resp.message)) {
                HeliusRateGate8191.noteLimited8191(bucket, System.currentTimeMillis(), HeliusRateGate8191.retryAfterSec8191(resp.header("Retry-After")))
                try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HELIUS_RPS_PROVIDER_429_8191") } catch (_: Throwable) {}
            } else HeliusRateGate8191.noteLocalCredit8191()
        }
        return resp
    }
}

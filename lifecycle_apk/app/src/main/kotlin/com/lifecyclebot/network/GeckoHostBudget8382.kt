package com.lifecyclebot.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8382 §GECKO_ONE_HOST_ONE_BUDGET — GeckoTerminal's public API is "approximately 10 calls/minute"
 * (api.geckoterminal.com/docs, Rate Limit). 5.0.8381: the keyless OHLCV feed paced itself at 24/min on its own, and the
 * scanner (trending_pools, new_pools, pools pages, meteora), ChartSources7955 and PriceResolverFallback called the same host
 * with no common budget; the host answered 429, geckoBackoff reached level 3, and 540 OHLCV fetches delivered 0 bars
 * (shape missing on 4,727 setup reads). One OHLCV call returns up to 1,000 one-minute bars: it is the most valuable call
 * this host serves, and discovery lists were spending its share.
 *
 * One token bucket for the host ([PER_MIN_8382] a minute, [BURST_8382] burst; DexHostBudget8287.Bucket8287): candle and
 * trade reads ([decisionPath8382]) may wait up to [DECISION_WAIT_MS_8382] for a token; every other read takes a token only
 * while [BULK_RESERVE_8382] remain; an exit-scoped call is never refused. A provider 429 pauses every non-exit caller for
 * its Retry-After. A refusal never reaches the wire (synthetic 599, HostCircuitInterceptor.SYNTHETIC_HEADER_6969).
 */
object GeckoHostBudget8382 : Interceptor {
    private const val HOST_8382 = "api.geckoterminal.com"
    const val PER_MIN_8382 = 10
    const val BURST_8382 = 3.0
    const val BULK_RESERVE_8382 = 1.0
    const val DECISION_WAIT_MS_8382 = 1_500L

    private val bucket = DexHostBudget8287.Bucket8287(PER_MIN_8382, BURST_8382, BULK_RESERVE_8382, 0, Long.MAX_VALUE)
    private val takenDecision = AtomicLong(0L)
    private val takenBulk = AtomicLong(0L)
    private val refusedDecision = AtomicLong(0L)
    private val refusedBulk = AtomicLong(0L)
    private val limited = AtomicLong(0L)

    /** Pure: a candle / trade read is the decision data this host serves; lists and token rows are bulk. */
    private fun decisionPath8382(path: String): Boolean = path.contains("/ohlcv/") || path.endsWith("/trades")

    /** Pure: the bucket class of a request. */
    fun prioOf8382(path: String, exit: Boolean): DexHostBudget8287.Prio8287 = when {
        exit -> DexHostBudget8287.Prio8287.EXIT
        decisionPath8382(path) -> DexHostBudget8287.Prio8287.NEAR
        else -> DexHostBudget8287.Prio8287.BULK
    }

    private fun take8382(nowMs: Long, prio: DexHostBudget8287.Prio8287): Long {
        val w = bucket.take8287(nowMs, prio, DECISION_WAIT_MS_8382)
        val decision = prio != DexHostBudget8287.Prio8287.BULK
        when {
            w < 0L && decision -> refusedDecision.incrementAndGet()
            w < 0L -> refusedBulk.incrementAndGet()
            decision -> takenDecision.incrementAndGet()
            else -> takenBulk.incrementAndGet()
        }
        return w
    }

    private fun noteLimited8382(nowMs: Long, retryAfterMs: Long) {
        limited.incrementAndGet()
        bucket.noteLimited8287(nowMs, retryAfterMs)
    }

    /**
     * V5.0.8439 — true when the bucket is full: no live candle / trade read is waiting on this host. Learning-only readers
     * (DowntimeRecovery8430) fetch only then. 8438 (204 s): decision=26/9 — the label recovery took 10 of those 26 tokens
     * (all empty) while live chart reads were refused 9 times.
     */
    fun spare8439(nowMs: Long = System.currentTimeMillis()): Boolean =
        bucket.pausedLeftMs8287(nowMs) <= 0L && bucket.tokens8287(nowMs) >= BURST_8382 - 0.01

    /**
     * V5.0.8457 — DowntimeRecovery8430's one-a-minute trickle. Superseded by V5.0.8458: 8457 asked for 3 tokens, which is
     * [BURST_8382] (a full bucket, the same test as [spare8439]), so the trickle never fired (8457: trickle8457=0 fetched=0
     * yieldedToLive=94 with tokens=1.7). One whole token for this call, not paused: one call a minute is a tenth of the host.
     */
    fun trickle8457(nowMs: Long = System.currentTimeMillis()): Boolean =
        bucket.pausedLeftMs8287(nowMs) <= 0L && bucket.tokens8287(nowMs) >= TRICKLE_TOKENS_8458 - 0.01
    const val TRICKLE_TOKENS_8458 = 1.0

    /** geckoHost8382[perMin=10 tokens=.. decision=taken/refused bulk=taken/refused provider429=.. pausedLeftMs=..] */
    fun statusLine8382(nowMs: Long = System.currentTimeMillis()): String =
        "geckoHost8382[perMin=$PER_MIN_8382 tokens=${"%.1f".format(bucket.tokens8287(nowMs))} decision=${takenDecision.get()}/${refusedDecision.get()} " +
            "bulk=${takenBulk.get()}/${refusedBulk.get()} provider429=${limited.get()} pausedLeftMs=${bucket.pausedLeftMs8287(nowMs)}]"

    /** Pure (V5.0.8418): may this call proceed after waiting [waitMs] (< 0 = refused by the budget) on this thread? */
    fun waitMayBlock8418(waitMs: Long, onMainThread: Boolean): Boolean = waitMs == 0L || (waitMs > 0L && !onMainThread)

    private fun onMainThread8418(): Boolean = try {
        android.os.Looper.getMainLooper()?.thread === Thread.currentThread()
    } catch (_: Throwable) { false }

    /** The shared client's GeckoTerminal budget; registered before HostCircuitInterceptor like DexRateInterceptor8287. */
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.host.equals(HOST_8382, ignoreCase = true)) return chain.proceed(req)
        val exit = req.header(ExitHttpScope7314.HEADER) != null || ExitHttpScope7314.active()
        val prio = prioOf8382(req.url.encodedPath, exit)
        val wait = take8382(System.currentTimeMillis(), prio)
        // V5.0.8418 (review df436b883) — the budget wait never blocks the main thread: a call that would wait there is
        // answered as the local budget refusal (synthetic 599, kept out of provider health) instead of a Thread.sleep.
        if (!waitMayBlock8418(wait, onMainThread8418())) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("GECKO_HOST_BUDGET_REFUSED_8382_${prio.name}") } catch (_: Throwable) {}
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(599)
                .message("GECKO_HOST_BUDGET_8382 prio=${prio.name}")
                .header(HostCircuitInterceptor.SYNTHETIC_HEADER_6969, "1")
                .body("".toResponseBody(null))
                .build()
        }
        if (wait > 0L) {
            try { Thread.sleep(wait) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("geckoterminal budget wait interrupted")
            }
        }
        val resp = chain.proceed(req)
        if (resp.code == 429) noteLimited8382(System.currentTimeMillis(), DexHostBudget8287.retryAfterMs8287(resp.header("Retry-After")))
        return resp
    }
}

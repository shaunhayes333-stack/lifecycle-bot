package com.lifecyclebot.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8287 §ONE_HOST_ONE_BUDGET — every DexScreener caller together stays well under the documented ~300 req/min.
 *
 * 5.0.8286 (uptime 425 s): featureSources8285 dexBatch a=1069 r=76 t=4754, lanes8285 dexscreener level=2 cooldowns=6,
 * ApiHealth dexscreener 4xx=106. The batch lane was not starved by DexScreener: it was starved by this app. getBestPair
 * (pairFetches=798), CandidateMarkFresh8094's own 30-mint reads (dexReads=573), the scanner feeds and the sweep
 * enrichment all shared the host with no common budget, so the host answered 429; one 429 locked the whole host in
 * ApiBackoff (>= 30 s) and the batch's lane ladder; while locked, every batch got the local synthetic 503 back, which
 * the lane read as a provider 5xx and escalated its own cooldown; and every refused batch was re-queued and counted
 * "throttled" once per mint per retry.
 *
 * One token bucket for the host ([PER_MIN_8287] a minute, [BURST_8287] burst): exit traffic (held marks) is never
 * refused (it may run the bucket into a bounded debt); held / near-decision callers may wait up to
 * [PRIORITY_WAIT_MS_8287] for a token; bulk callers (scanner, sweep, discovery best-pair) take a token only while
 * [BULK_RESERVE_8287] remain, so they can never spend the decision coins' share. A provider 429 pauses every non-exit
 * caller for its Retry-After. A refused call never reaches the wire: it is answered with the app's synthetic 599
 * (HostCircuitInterceptor.SYNTHETIC_HEADER_6969), which no health table records as a provider answer.
 */
object DexHostBudget8287 {
    const val HOST_8287 = "api.dexscreener.com"
    const val PRIO_HEADER_8287 = "X-AATE-Dex-Prio-8287"
    const val PER_MIN_8287 = 180
    /**
     * V5.0.8359 — the documented pairs limit is 300 / min. After [CLEAN_AFTER_MS_8359] of session with no provider 429
     * the bucket refills at [PER_MIN_CLEAN_8359] (still under 300 with the burst); the first 429 drops it back to
     * [PER_MIN_8287] for the rest of the session.
     */
    const val PER_MIN_CLEAN_8359 = 270
    const val CLEAN_AFTER_MS_8359 = 10L * 60_000L
    const val BURST_8287 = 12.0
    /** V5.0.8317 — 4.0 → 2.0: with launches marked near-decision at intake (IntakeFirstSight8317) bulk needs less headroom held back. */
    const val BULK_RESERVE_8287 = 2.0
    const val PRIORITY_WAIT_MS_8287 = 1_500L
    private const val DEFAULT_PAUSE_MS_8287 = 15_000L
    private const val MAX_PAUSE_MS_8287 = 120_000L

    enum class Prio8287 { EXIT, HELD, NEAR, BULK }

    /**
     * The bucket. [take8287] answers -1 (refused), 0 (go now) or the ms to sleep before going (the token is already
     * reserved). Thread-safe; [nowMs] is passed in so the arithmetic is testable.
     */
    class Bucket8287(
        private val perMin: Int = PER_MIN_8287,
        private val burst: Double = BURST_8287,
        private val reserve: Double = BULK_RESERVE_8287,
        private val cleanPerMin: Int = PER_MIN_CLEAN_8359,
        private val cleanAfterMs: Long = CLEAN_AFTER_MS_8359,
    ) {
        private var tokens = burst
        private var atMs = -1L
        private var pausedUntilMs = 0L
        private val baseRatePerMs: Double = perMin.coerceAtLeast(1) / 60_000.0
        private var ratePerMs: Double = baseRatePerMs
        private var firstMs8359 = -1L
        private var everLimited8359 = false

        /** V5.0.8359 — the refill rate now (per minute). */
        @Synchronized fun perMin8359(nowMs: Long): Int { refill(nowMs); return Math.round(ratePerMs * 60_000.0).toInt() }

        @Synchronized
        fun take8287(nowMs: Long, prio: Prio8287, maxWaitMs: Long = PRIORITY_WAIT_MS_8287): Long {
            refill(nowMs)
            if (prio == Prio8287.EXIT) {
                tokens = (tokens - 1.0).coerceAtLeast(-burst)
                return 0L
            }
            if (nowMs < pausedUntilMs) return -1L
            if (prio == Prio8287.BULK) {
                if (tokens >= 1.0 + reserve) { tokens -= 1.0; return 0L }
                return -1L
            }
            if (tokens >= 1.0) { tokens -= 1.0; return 0L }
            val wait = Math.ceil((1.0 - tokens) / ratePerMs).toLong().coerceAtLeast(1L)
            if (wait > maxWaitMs) return -1L
            tokens -= 1.0
            return wait
        }

        /** The provider answered 429: every non-exit caller rests for [retryAfterMs] (bounded) and the bucket empties. */
        @Synchronized
        fun noteLimited8287(nowMs: Long, retryAfterMs: Long) {
            refill(nowMs)
            pausedUntilMs = maxOf(pausedUntilMs, nowMs + retryAfterMs.coerceIn(1_000L, MAX_PAUSE_MS_8287))
            everLimited8359 = true          // V5.0.8359 — back to the base rate for the session
            ratePerMs = baseRatePerMs
            if (tokens > 0.0) tokens = 0.0
        }

        @Synchronized fun tokens8287(nowMs: Long): Double { refill(nowMs); return tokens }

        @Synchronized fun pausedLeftMs8287(nowMs: Long): Long = (pausedUntilMs - nowMs).coerceAtLeast(0L)

        private fun refill(nowMs: Long) {
            if (firstMs8359 < 0L) firstMs8359 = nowMs
            // V5.0.8359 — clean session long enough: the documented limit's headroom is used (never after a 429).
            ratePerMs = if (!everLimited8359 && cleanPerMin > 0 && nowMs - firstMs8359 >= cleanAfterMs)
                maxOf(baseRatePerMs, cleanPerMin / 60_000.0) else baseRatePerMs
            if (atMs < 0L) { atMs = nowMs; return }
            if (nowMs > atMs) {
                tokens = minOf(burst, tokens + (nowMs - atMs) * ratePerMs)
                atMs = nowMs
            }
        }
    }

    private val bucket = Bucket8287()
    private val taken = ConcurrentHashMap<Prio8287, AtomicLong>()
    private val refused = ConcurrentHashMap<Prio8287, AtomicLong>()
    private val waits = AtomicLong(0L)
    private val limited = AtomicLong(0L)

    /** Pure: the class of a DexScreener request from its priority header and exit scope (no header = bulk). */
    fun prioOf8287(header: String?, exit: Boolean): Prio8287 = when {
        exit -> Prio8287.EXIT
        header.equals("HELD", ignoreCase = true) -> Prio8287.HELD
        header.equals("NEAR", ignoreCase = true) -> Prio8287.NEAR
        else -> Prio8287.BULK
    }

    /** Pure: the header value for a coin's lane priority (held / near decision / hand-off read as near; bulk = none). */
    fun headerFor8287(held: Boolean, near: Boolean): String = when {
        held -> "HELD"
        near -> "NEAR"
        else -> "BULK"
    }

    /** Pure: a Retry-After header (seconds) in ms, else the default pause. */
    fun retryAfterMs8287(header: String?): Long {
        val s = header?.trim()?.toLongOrNull() ?: return DEFAULT_PAUSE_MS_8287
        return (s * 1_000L).coerceIn(1_000L, MAX_PAUSE_MS_8287)
    }

    fun take8287(nowMs: Long, prio: Prio8287): Long {
        val w = bucket.take8287(nowMs, prio)
        (if (w < 0L) refused else taken).computeIfAbsent(prio) { AtomicLong(0L) }.incrementAndGet()
        if (w > 0L) waits.incrementAndGet()
        return w
    }

    fun noteLimited8287(nowMs: Long, retryAfterMs: Long) {
        limited.incrementAndGet()
        bucket.noteLimited8287(nowMs, retryAfterMs)
    }

    /** dexHost8287[perMin=180 tokens=.. taken{..} refused{..} waits=.. provider429=.. pausedLeftMs=..] */
    fun statusLine8287(nowMs: Long = System.currentTimeMillis()): String {
        fun m(x: ConcurrentHashMap<Prio8287, AtomicLong>) =
            Prio8287.values().joinToString(",") { "${it.name.lowercase()}=${x[it]?.get() ?: 0L}" }
        return "dexHost8287[perMin=$PER_MIN_8287 effPerMin8359=${bucket.perMin8359(nowMs)} tokens=${"%.1f".format(bucket.tokens8287(nowMs))} taken{${m(taken)}} " +
            "refused{${m(refused)}} waits=${waits.get()} provider429=${limited.get()} pausedLeftMs=${bucket.pausedLeftMs8287(nowMs)}]"
    }
}

/**
 * V5.0.8287 — the shared client's DexScreener budget ([DexHostBudget8287]). Registered BEFORE HostCircuitInterceptor so a
 * local refusal never reaches it (it would otherwise read the synthetic 599 as a provider failure and lock the host).
 * The priority header is internal and is removed before the wire; the exit header is left for HostCircuitInterceptor.
 */
object DexRateInterceptor8287 : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.host.equals(DexHostBudget8287.HOST_8287, ignoreCase = true)) return chain.proceed(req)
        val exit = req.header(ExitHttpScope7314.HEADER) != null || ExitHttpScope7314.active()
        val prio = DexHostBudget8287.prioOf8287(req.header(DexHostBudget8287.PRIO_HEADER_8287), exit)
        val wire = if (req.header(DexHostBudget8287.PRIO_HEADER_8287) != null)
            req.newBuilder().removeHeader(DexHostBudget8287.PRIO_HEADER_8287).build() else req
        val wait = DexHostBudget8287.take8287(System.currentTimeMillis(), prio)
        if (wait < 0L) {
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("DEX_HOST_BUDGET_REFUSED_8287_${prio.name}") } catch (_: Throwable) {}
            return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(599)
                .message("DEX_HOST_BUDGET_8287 prio=${prio.name}")
                .header(HostCircuitInterceptor.SYNTHETIC_HEADER_6969, "1")
                .body("".toResponseBody(null))
                .build()
        }
        if (wait > 0L) {
            try { Thread.sleep(wait) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.io.InterruptedIOException("dexscreener budget wait interrupted")
            }
        }
        val resp = chain.proceed(wire)
        if (resp.code == 429) DexHostBudget8287.noteLimited8287(System.currentTimeMillis(), DexHostBudget8287.retryAfterMs8287(resp.header("Retry-After")))
        return resp
    }
}

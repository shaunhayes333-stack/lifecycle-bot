package com.lifecyclebot.network

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.GzipSource
import okio.buffer
import java.util.concurrent.TimeUnit

/**
 * SHARED HTTP CLIENT - V5.9.393 OOM mitigation.
 *
 * One ConnectionPool + Dispatcher reused across the whole app instead of
 * ~50 independent OkHttpClient instances. Each call site keeps its bespoke
 * timeouts/interceptors by routing through `SharedHttpClient.builder()`,
 * which returns `base.newBuilder()` -- shares the underlying pool while
 * letting the caller customise everything else exactly as before.
 *
 * Migration: replace `OkHttpClient.Builder()` with `SharedHttpClient.builder()`
 *
 * V5.9.1030 — RAISE PER-HOST CONCURRENCY for the supervisor chunk path.
 *
 * Operator V5.9.1029 snapshot showed SUPERVISOR_CHUNK_TIMEOUT firing every
 * cycle with `active=32 budgetMs=4800`. Diagnosis: OkHttp's default
 * Dispatcher serialises with `maxRequestsPerHost=5`, so 32 parallel
 * supervisor workers all calling api.dexscreener.com queue 27 of 32
 * requests behind the first 5 active sockets. With a 7% timeout-tail
 * (Birdeye SR=93%, DS SR=96%, helius drops), the slow ~400-700ms p95
 * stacks behind every slow request → workers wedge past 4.8s.
 *
 * Bumping maxRequestsPerHost from 5 → 32 lets every supervisor worker
 * fire its OkHttp call in parallel — the operator is on a paid DexScreener
 * tier so concurrent-request quota is no longer the binding constraint.
 * maxRequests raised proportionally so the global pool isn't the bottleneck.
 *
 * NOTE: Dispatcher is shared via `newBuilder()` (sibling clients reuse it),
 * but each call site keeps its own connect/read/write timeouts intact.
 */
object SharedHttpClient {
    /** Shared dispatcher — single instance, configured once, reused by every client. */
    private val sharedDispatcher: Dispatcher = Dispatcher().apply {
        // V5.9.1032 — RATE-LIMIT BALANCE.
        //
        // V5.9.1030 raised maxRequestsPerHost 5 → 32 to un-choke the
        // supervisor. The fix worked (processed went 0 → 7 of 96) BUT
        // operator V5.9.1031 snapshot exposed the next stair: Birdeye SR
        // crashed from 99% → 56% (424 × 4xx rate-limit responses) and
        // DexScreener went 99% → 71% (37 × 4xx). With Birdeye throttling,
        // 86% of FDG rejections are now low/zero liquidity (the bot can't
        // see liquidity data → rejects every candidate).
        //
        // Drop to 16 per host. Still 3× OkHttp's default of 5, so the
        // supervisor stays un-choked, but half the per-minute burden on
        // Birdeye's free-tier cap (100 RPM @ ~400ms avg latency).
        // maxRequests scaled proportionally.
        // V5.9.1459 — align per-host concurrency with the 48-worker supervisor pool.
        // 16 was tuned (V5.9.1032) to protect Birdeye's free-tier RPM, but it queued
        // 32 of 48 supervisor workers behind active DexScreener sockets → the stall.
        // 24 relieves the queue (only ~24 workers can wait, not 32) while staying
        // well under the burst that crashed Birdeye SR to 56% at 32. The new 7s
        // callTimeout is the real safety net; this just reduces how often it trips.
        // maxRequests scaled so the global pool isn't the new bottleneck.
        maxRequests = 96
        maxRequestsPerHost = 24
    }

    val base: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        // V5.0.4170 — HOST CIRCUIT INTERCEPTOR.
        // Every HTTP call across the app routes through SharedHttpClient
        // (BotBrain, BirdeyeSecurityProvider, TokenSafetyChecker, etc.).
        // Adding the circuit here saves bandwidth on EVERY request without
        // touching the 36+ callers individually. Short-circuits NXDOMAIN
        // and 5xx/429 storms with a 599 synthetic response — zero TLS,
        // zero TCP, zero DNS during cool-down.
        .addInterceptor(com.lifecyclebot.network.HostCircuitInterceptor)
        // V5.0.4170 / V5.0.4173 — EXPLICIT GZIP request + TRANSPARENT DECODE.
        //
        // V5.0.4170 manually added `Accept-Encoding: gzip` to every request to
        // force gzip on call sites that set custom Accept headers without an
        // encoding hint. PROBLEM: OkHttp's BridgeInterceptor only transparently
        // decompresses gzip when IT added the Accept-Encoding header itself.
        // When the application interceptor sets it, BridgeInterceptor hands raw
        // gzipped bytes back to the caller → every JSON parse gets the
        // "Value �     �V�*..." garbled blob (killed all Solana RPC reads in
        // the V5.0.4172 field snapshot — wallet died → bot fully choked).
        //
        // V5.0.4173 fix: keep the explicit `Accept-Encoding: gzip` request
        // header (so we still hit gzip on call sites that previously skipped
        // it) AND decompress the response ourselves when the server replies
        // with `Content-Encoding: gzip`. We strip `Content-Encoding` and
        // `Content-Length` from the response headers so downstream callers
        // see a clean uncompressed body, matching OkHttp's normal transparent
        // behaviour. JSON payloads still compress 60–80% — full bandwidth
        // win restored without breaking the RPCs.
        .addInterceptor { chain ->
            val req = chain.request()
            val r = if (req.header("Accept-Encoding") == null)
                req.newBuilder().header("Accept-Encoding", "gzip").build()
            else req
            val response = chain.proceed(r)
            val encoding = response.header("Content-Encoding")
            val body = response.body
            if (encoding != null && encoding.equals("gzip", ignoreCase = true) && body != null) {
                val decompressed = GzipSource(body.source()).buffer()
                    .asResponseBody(body.contentType(), -1L)
                val cleanHeaders = response.headers.newBuilder()
                    .removeAll("Content-Encoding")
                    .removeAll("Content-Length")
                    .build()
                response.newBuilder()
                    .headers(cleanHeaders)
                    .body(decompressed)
                    .build()
            } else {
                response
            }
        }
        // NOTE: deliberately NO callTimeout on the BASE client — ~36 callers
        // inherit this builder and several legitimately need long calls (LLM 30-60s,
        // Jito/Markets live execution 30s). callTimeout is applied PER-CALLER on the
        // per-token hot path only (DexscreenerApi) where the 8s worker budget binds.
        .build()

    /**
     * V5.9.1033 — HARD-CANCEL escape hatch for stopBot().
     *
     * Aborts every in-flight OkHttp call AND every queued one on the
     * shared dispatcher RIGHT NOW. Used by stopBot() so a wedged
     * supervisor chunk (32 workers blocked inside .execute()) doesn't
     * stretch UI stop response from "instant" to "30-60 seconds".
     *
     * Safe to call any time — idempotent, swallows all throwables.
     * Existing calls already in flight will see IOException("Canceled")
     * and unwind their callers normally.
     */
    fun cancelAllRequests() {
        try { sharedDispatcher.cancelAll() } catch (_: Throwable) {}
    }

    /** Returns a new builder backed by the shared connection pool/dispatcher. */
    fun builder(): OkHttpClient.Builder = base.newBuilder()
}

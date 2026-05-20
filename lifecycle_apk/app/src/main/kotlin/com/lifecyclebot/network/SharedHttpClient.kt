package com.lifecyclebot.network

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
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
        maxRequests = 64
        maxRequestsPerHost = 16
    }

    val base: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(sharedDispatcher)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Returns a new builder backed by the shared connection pool/dispatcher. */
    fun builder(): OkHttpClient.Builder = base.newBuilder()
}

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
        maxRequests = 128                 // was OkHttp default 64
        maxRequestsPerHost = 32           // was OkHttp default 5 — main supervisor un-choke
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

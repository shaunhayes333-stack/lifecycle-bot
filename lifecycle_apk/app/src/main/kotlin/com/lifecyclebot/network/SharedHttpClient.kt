package com.lifecyclebot.network

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
 */
object SharedHttpClient {
    val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Returns a new builder backed by the shared connection pool/dispatcher. */
    fun builder(): OkHttpClient.Builder = base.newBuilder()
}

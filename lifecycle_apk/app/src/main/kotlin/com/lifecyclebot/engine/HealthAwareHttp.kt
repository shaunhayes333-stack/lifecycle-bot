package com.lifecyclebot.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * V5.9.859 — Thin instrumented HTTP wrapper.
 *
 * Wraps `client.newCall(request).execute()` with three transparent things:
 *   1. AutoEndpointMigrator.rewrite() — swap dead hosts before the wire
 *   2. ApiHealthMonitor.record(host, code, latency) — per-host telemetry
 *   3. ApiHealthMonitor.recordNetworkError(host, msg) — IOException path
 *
 * USAGE — drop-in replacement for `client.newCall(req).execute()`:
 *   val resp = HealthAwareHttp.execute(client, req, host = "dexscreener")
 *
 * For hosts that DO require key auth (Groq, Gemini, Helius enhanced), the
 * caller still invokes KeyValidator.recordResult separately — this wrapper
 * is for keyless / passive observation only.
 *
 * DOCTRINE
 * ========
 * - Fail-open. If AutoEndpointMigrator throws, original URL is used.
 * - Fail-open. If ApiHealthMonitor record throws, swallowed.
 * - DOES NOT mutate response body. Caller still calls resp.body.string().
 * - Throws same IOException as raw OkHttp on network failure.
 */
object HealthAwareHttp {

    /**
     * Execute a request with health tracking + auto-migration.
     *
     * @param client      The OkHttp client to use.
     * @param request     The request to execute.
     * @param host        Short host label for ApiHealthMonitor
     *                    (e.g. "dexscreener", "jupiter", "helius", "pumpfun").
     * @return            The raw OkHttp Response. Caller must close it.
     */
    fun execute(client: OkHttpClient, request: Request, host: String): Response {
        // Apply auto-migration to the URL — rebuild request only if changed.
        val originalUrl = request.url.toString()
        val rewritten = try { AutoEndpointMigrator.rewrite(originalUrl) } catch (_: Throwable) { originalUrl }
        val finalRequest = if (rewritten == originalUrl) request
        else request.newBuilder().url(rewritten).build()

        val start = System.currentTimeMillis()
        try {
            val resp = client.newCall(finalRequest).execute()
            val latency = System.currentTimeMillis() - start
            try { ApiHealthMonitor.record(host, resp.code, latency) } catch (_: Throwable) {}
            return resp
        } catch (e: Exception) {
            try { ApiHealthMonitor.recordNetworkError(host, e.message) } catch (_: Throwable) {}
            throw e
        }
    }
}

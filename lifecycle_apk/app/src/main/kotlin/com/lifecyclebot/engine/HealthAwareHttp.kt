package com.lifecyclebot.engine

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * V5.9.859 — Thin instrumented HTTP wrapper.
 *
 * Wraps `client.newCall(request).execute()` with three transparent things:
 *   1. AutoEndpointMigrator.rewrite() — swap dead hosts before the wire
 *   2. ApiHealthMonitor.record(host, code, latency) — per-host telemetry
 *   3. ApiHealthMonitor.recordNetworkError(host, msg) — IOException path
 *
 * V5.9.1024 — added reactive ApiBackoff:
 *   4. Before sending: short-circuit with synthetic 503 if the host is in
 *      backoff lockout (4xx/5xx received within recent past).
 *   5. On 2xx success: ApiBackoff.markSuccess(host) → resets counter.
 *   6. On 4xx/5xx response: ApiBackoff.markFailure(host, code) → arms
 *      escalating lockout (5s → 15s → 30s → 60s → 120s → 300s cap; 429/403
 *      jump to ≥30s on first occurrence).
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
 * - When lockout is active, returns a synthetic 503 Response so callers
 *   that already handle `!resp.isSuccessful` naturally skip without any
 *   change at the call site. Body is empty JSON `{}`.
 * - DOES NOT mutate response body. Caller still calls resp.body.string().
 * - Throws same IOException as raw OkHttp on network failure.
 */
object HealthAwareHttp {

    private val emptyJsonMediaType = "application/json".toMediaTypeOrNull()

    /**
     * Execute a request with health tracking + auto-migration + reactive backoff.
     *
     * @param client      The OkHttp client to use.
     * @param request     The request to execute.
     * @param host        Short host label for ApiHealthMonitor / ApiBackoff
     *                    (e.g. "dexscreener", "jupiter", "helius", "pumpfun").
     * @return            The raw OkHttp Response. Caller must close it.
     */
    fun execute(client: OkHttpClient, request: Request, host: String): Response {
        // V5.9.1024 — REACTIVE BACKOFF SHORT-CIRCUIT.
        // If this host is in lockout (last call returned 429/403/4xx/5xx
        // within the backoff window), don't fire another request. Return
        // a synthetic 503 so callers see `!resp.isSuccessful` and skip.
        try {
            if (ApiBackoff.isLockedOut(host)) {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("ApiBackoff lockout (host=$host remainingMs=${ApiBackoff.lockoutRemainingMs(host)})")
                    .body("{}".toResponseBody(emptyJsonMediaType))
                    .build()
            }
        } catch (_: Throwable) { /* fail-open — never block on a bug here */ }

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
            try {
                if (resp.code in 200..299) ApiBackoff.markSuccess(host)
                else if (resp.code in 400..599) ApiBackoff.markFailure(host, resp.code)
            } catch (_: Throwable) {}
            return resp
        } catch (e: Exception) {
            try { ApiHealthMonitor.recordNetworkError(host, e.message) } catch (_: Throwable) {}
            throw e
        }
    }
}

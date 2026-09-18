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
     * @param allowDuringLockout
     *                    V5.0.7016 — skip the backoff short-circuit for THIS
     *                    call. Reserved for requests a person is waiting on.
     *
     *                    Backoff exists to stop a background loop hammering a
     *                    sore host thousands of times an hour. It was never
     *                    meant to answer a question the operator just typed,
     *                    and when it does the app reports its own refusal as
     *                    the provider's silence — see KeylessLlmClient.runChat.
     *                    A user-initiated turn is one request, rarely, so
     *                    exempting it costs the host nothing and the default
     *                    (false) leaves every background caller unchanged.
     * @return            The raw OkHttp Response. Caller must close it.
     */
    fun execute(
        client: OkHttpClient,
        request: Request,
        host: String,
        allowDuringLockout: Boolean = false,
    ): Response {
        // V5.9.1024 — REACTIVE BACKOFF SHORT-CIRCUIT.
        // If this host is in lockout (last call returned 429/403/4xx/5xx
        // within the backoff window), don't fire another request. Return
        // a synthetic 503 so callers see `!resp.isSuccessful` and skip.
        try {
            if (!allowDuringLockout && ApiBackoff.isLockedOut(host)) {
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("ApiBackoff lockout (host=$host remainingMs=${ApiBackoff.lockoutRemainingMs(host)})")
                    // V5.0.6976 — this 503 is OUR refusal, not the provider's reply.
                    // Tagging it lets every downstream consumer tell the two apart.
                    .header(com.lifecyclebot.network.HostCircuitInterceptor.SYNTHETIC_HEADER_6969, "1")
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

            // V5.0.6976 §THE_HOST_THAT_LOCKED_ITSELF_OUT_ON_ITS_OWN_REFUSALS.
            //
            // HostCircuitInterceptor sits on the shared client, so when the
            // provider "jupiter" (lite-api.jup.ag) is in lockout, this call
            // never reaches the wire — it comes back as a synthetic 599.
            // The lines below then read that 599 as evidence and wrote it
            // against the CALLER's label, which for JupiterApi is the separate
            // host key "jupiter_quote". So:
            //
            //   jupiter blips once  →  synthetic 599 on every jupiter_quote call
            //   →  markFailure("jupiter_quote", 599)  →  jupiter_quote locked out
            //   →  more 599s  →  escalating lockout  →  jupiter_quote never recovers
            //
            // The operator's 6970 log shows both halves of that sentence at once:
            //
            //     ✅ jupiter        sr=97% avg=330ms s=78 4xx=1 5xx=1
            //     🔴 jupiter_quote  sr=28%
            //     API_BACKOFF_ARMED host=jupiter_quote code=599
            //
            // 599 is not a code any provider emits. Every one of those arms was
            // this app citing itself as proof that Jupiter was down. It then fed
            // ExecutionHealthGuard.healthy("jupiter_quote"), which blocks buys.
            //
            // A synthetic response is the absence of an observation. Record
            // nothing; leave the health table describing only real replies.
            val synthetic = try {
                com.lifecyclebot.network.HostCircuitInterceptor.isSyntheticBlock(resp)
            } catch (_: Throwable) { false }

            if (synthetic) {
                try { PipelineHealthCollector.labelInc("SYNTHETIC_BLOCK_NOT_RECORDED_6976") } catch (_: Throwable) {}
                return resp
            }

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

package com.lifecyclebot.network

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Response
import okio.Buffer

/**
 * V5.0.7881 — prices every Helius REST call that reaches the wire through
 * SharedHttpClient (JSON-RPC methods read from the body, batch-aware;
 * Enhanced v0 endpoints by path). Meters it; V5.0.8066 — and past the hard daily
 * budget answers non-execution calls locally (429), see HeliusCreditEconomy7881.overBudget8066. Synthetic short-circuits (HostCircuitInterceptor sits
 * before this one) never reach it and are not counted.
 */
object HeliusCreditMeterInterceptor7881 : Interceptor {
    private const val MAX_BODY_BYTES = 64 * 1024L
    private val METHOD = Regex("\"method\"\\s*:\\s*\"([A-Za-z0-9_]+)\"")

    /** Pure: JSON-RPC method names in a request body (single or batch). */
    fun methodsIn(body: String): List<String> = METHOD.findAll(body).map { it.groupValues[1] }.toList()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val host = req.url.host
        if (!HeliusCreditEconomy7881.isHelius(host)) return chain.proceed(req)
        // V5.0.8169 — the body text is kept for the per-caller duplicate-read meter (LoadTelemetry8169).
        val bodyText8169 = try {
            val b = req.body
            if (b == null || b.isOneShot() || b.contentLength() > MAX_BODY_BYTES) ""
            else Buffer().also { b.writeTo(it) }.readUtf8()
        } catch (_: Throwable) { "" }
        val methods = if (bodyText8169.isEmpty()) emptyList() else methodsIn(bodyText8169)
        // V5.0.8066 — the hard daily budget: past the day's pace, every non-execution Helius call is answered here with
        // a 429 (no network, no credits). Callers already treat a 429 as "back off / use the next source".
        val tier8066 = try { HeliusCreditEconomy7881.tierFor(host, req.url.encodedPath, methods) } catch (_: Throwable) { null }
        if (tier8066 != null && HeliusCreditEconomy7881.budgetGated8066(tier8066) && HeliusCreditEconomy7881.overBudget8066()) {
            return Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("AATE_HELIUS_BUDGET_8066")
                .body("{\"error\":{\"code\":429,\"message\":\"AATE_HELIUS_BUDGET_8066\"}}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        // V5.0.8094 — plan credits exhausted: non-execution calls are answered locally while latched.
        if (tier8066 != null && HeliusCreditEconomy7881.budgetGated8066(tier8066) && HeliusCreditEconomy7881.exhaustedGate8094()) {
            return Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(429).message("AATE_HELIUS_EXHAUSTED_8094")
                .body("{\"error\":{\"code\":429,\"message\":\"AATE_HELIUS_EXHAUSTED_8094\"}}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        val resp = chain.proceed(req)
        try { HeliusCreditEconomy7881.meter(host, req.url.encodedPath, methods) } catch (_: Throwable) {}
        // V5.0.8169 — this process's Helius calls by caller, and repeated reads of one mint / account within 10 s.
        try { com.lifecyclebot.engine.LoadTelemetry8169.noteHelius8169(req.url.encodedPath, methods, bodyText8169, HeliusCreditEconomy7881.creditsFor(host, req.url.encodedPath, methods)) } catch (_: Throwable) {}
        if (resp.code == 402 || resp.code == 403 || resp.code == 429) try {
            val peek = resp.peekBody(4_096).string()
            if (HeliusCreditEconomy7881.isExhaustionAnswer8094(resp.code, peek)) HeliusCreditEconomy7881.noteExhausted8094()
        } catch (_: Throwable) {}
        return resp
    }
}

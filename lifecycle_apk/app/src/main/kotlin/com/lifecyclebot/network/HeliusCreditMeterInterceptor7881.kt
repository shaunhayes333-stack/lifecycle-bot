package com.lifecyclebot.network

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * V5.0.7881 — prices every Helius REST call that reaches the wire through
 * SharedHttpClient (JSON-RPC methods read from the body, batch-aware;
 * Enhanced v0 endpoints by path). Meters only: it never refuses, delays or
 * rewrites a request. Synthetic short-circuits (HostCircuitInterceptor sits
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
        val methods = try {
            val b = req.body
            if (b == null || b.isOneShot() || b.contentLength() > MAX_BODY_BYTES) emptyList()
            else Buffer().also { b.writeTo(it) }.readUtf8().let { methodsIn(it) }
        } catch (_: Throwable) { emptyList() }
        val resp = chain.proceed(req)
        try { HeliusCreditEconomy7881.meter(host, req.url.encodedPath, methods) } catch (_: Throwable) {}
        return resp
    }
}

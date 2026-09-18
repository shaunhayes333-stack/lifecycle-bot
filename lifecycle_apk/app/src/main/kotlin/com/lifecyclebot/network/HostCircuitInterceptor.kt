package com.lifecyclebot.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.4170 — HOST CIRCUIT INTERCEPTOR (mobile data conservation).
 * V5.0.6758 — provider-wide circuit authority + Birdeye quota hard-stop.
 *
 * Every SharedHttpClient request passes here, including legacy/raw callers that
 * bypass HealthAwareHttp. That makes this the only safe place to enforce provider
 * backoff without editing dozens of independent scanners.
 *
 * 6758 rules:
 *  - Map all known hot providers into ApiBackoff, not DexScreener only.
 *  - Never put a Birdeye request on the wire once the global CU budget is empty.
 *  - Preserve fail-open behaviour for unknown providers.
 *  - Keep NXDOMAIN and repeated 5xx/429 cool-downs self-clearing on success.
 */
object HostCircuitInterceptor : Interceptor {

    private const val NXDOMAIN_COOLDOWN_MS = 5 * 60_000L
    private const val SERVER_FAIL_COOLDOWN_MS = 90_000L
    private const val RATE_LIMIT_COOLDOWN_MS = 5 * 60_000L
    private const val SERVER_FAIL_TRIP_COUNT = 3
    private const val SERVER_FAIL_TRIP_WINDOW_MS = 60_000L

    private data class HostState(
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
        val recentServerFailCount: AtomicInteger = AtomicInteger(0),
        val firstServerFailAtMs: AtomicLong = AtomicLong(0L),
        val totalBypassed: AtomicLong = AtomicLong(0L),
    )

    private val states = ConcurrentHashMap<String, HostState>()
    private val totalNxBypass = AtomicLong(0L)
    private val totalServerBypass = AtomicLong(0L)

    private fun providerLabel(host: String): String = when {
        host.contains("dexscreener", ignoreCase = true) -> "dexscreener"
        host.contains("birdeye", ignoreCase = true) -> "birdeye"
        host.contains("coingecko", ignoreCase = true) -> "coingecko"
        host.contains("dexpaprika", ignoreCase = true) -> "dexpaprika"
        host.contains("geckoterminal", ignoreCase = true) -> "geckoterminal"
        host.endsWith("jup.ag", ignoreCase = true) -> "jupiter"
        host.contains("groq.com", ignoreCase = true) -> "groq"
        host.contains("pump.fun", ignoreCase = true) -> "pumpfun"
        else -> ""
    }

    /**
     * V5.0.6969 — every synthetic response now carries X-AATE-Synthetic: 1.
     *
     * A synthetic 599 means THIS APP declined to make the call. It is not
     * evidence about the provider, and recording it as one corrupts the health
     * table that routing decisions are made from. Callers use
     * [isSyntheticBlock] to tell the two apart.
     */
    const val SYNTHETIC_HEADER_6969 = "X-AATE-Synthetic"

    /** True when this response is a local circuit block, not a provider reply. */
    fun isSyntheticBlock(resp: Response): Boolean =
        resp.header(SYNTHETIC_HEADER_6969) != null

    private fun synthetic(req: okhttp3.Request, code: Int, message: String): Response =
        Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .header(SYNTHETIC_HEADER_6969, "1")
            .body("".toResponseBody(null))
            .build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val host = req.url.host
        val now = System.currentTimeMillis()
        val state = states.getOrPut(host) { HostState() }
        val provider = providerLabel(host)

        // V5.0.6758 — Birdeye is a paid/escalation source. The runtime snapshot
        // showed 150000/150000 daily CU while raw callers were still reaching the
        // network. Enforce the shared budget at the connection boundary so callers
        // that forgot BirdeyeBudgetGate cannot burn latency or quota. Other free
        // sources remain available and callers naturally fall through to them.
        if (provider == "birdeye") {
            val affordable = try {
                com.lifecyclebot.engine.BirdeyeBudgetGate.canAfford(1)
            } catch (_: Throwable) {
                true // fail-open if the budget gate itself faults
            }
            if (!affordable) {
                state.totalBypassed.incrementAndGet()
                totalServerBypass.incrementAndGet()
                try {
                    com.lifecyclebot.engine.PipelineHealthCollector.labelInc("BIRDEYE_SHARED_BUDGET_BYPASS_6758")
                } catch (_: Throwable) {}
                return synthetic(req, 599, "Birdeye budget exhausted — free-provider fallback")
            }
        }

        // V5.0.6758 — all mapped providers now share ApiBackoff, so direct
        // SharedHttpClient users cannot bypass the reactive health authority.
        if (provider.isNotBlank() && try {
                com.lifecyclebot.engine.ApiBackoff.isLockedOut(provider)
            } catch (_: Throwable) { false }) {
            state.totalBypassed.incrementAndGet()
            totalServerBypass.incrementAndGet()
            return synthetic(
                req,
                599,
                "ApiBackoff shared-client lockout for $provider remainingMs=" +
                    (try { com.lifecyclebot.engine.ApiBackoff.lockoutRemainingMs(provider) } catch (_: Throwable) { 0L }),
            )
        }

        val cooldownUntil = state.cooldownUntilMs.get()
        if (now < cooldownUntil) {
            state.totalBypassed.incrementAndGet()
            val remaining = cooldownUntil - now
            if (remaining > SERVER_FAIL_COOLDOWN_MS) totalNxBypass.incrementAndGet()
            else totalServerBypass.incrementAndGet()
            return synthetic(req, 599, "HostCircuit cool-down active for $host (${remaining}ms remaining)")
        }

        val response: Response = try {
            chain.proceed(req)
        } catch (uhe: UnknownHostException) {
            state.cooldownUntilMs.set(now + NXDOMAIN_COOLDOWN_MS)
            throw uhe
        }

        if (provider.isNotBlank()) {
            try {
                if (response.isSuccessful) com.lifecyclebot.engine.ApiBackoff.markSuccess(provider)
                else if (response.code in 400..599) com.lifecyclebot.engine.ApiBackoff.markFailure(provider, response.code)
            } catch (_: Throwable) {}
        }

        // 429 is a rate/quota signal, not a transient 5xx. Give it a longer
        // connection-level rest window so dozens of parallel callers do not all
        // wake up after 90 seconds and recreate the same storm.
        if (response.code == 429) {
            state.cooldownUntilMs.set(now + RATE_LIMIT_COOLDOWN_MS)
            state.recentServerFailCount.set(0)
            state.firstServerFailAtMs.set(0L)
        } else if (response.code in 500..599 || response.code == 403) {
            val firstFail = state.firstServerFailAtMs.get()
            if (firstFail == 0L || (now - firstFail) > SERVER_FAIL_TRIP_WINDOW_MS) {
                state.firstServerFailAtMs.set(now)
                state.recentServerFailCount.set(1)
            } else {
                val c = state.recentServerFailCount.incrementAndGet()
                if (c >= SERVER_FAIL_TRIP_COUNT) {
                    state.cooldownUntilMs.set(now + SERVER_FAIL_COOLDOWN_MS)
                    state.recentServerFailCount.set(0)
                    state.firstServerFailAtMs.set(0L)
                }
            }
        } else if (response.isSuccessful) {
            state.recentServerFailCount.set(0)
            state.firstServerFailAtMs.set(0L)
            state.cooldownUntilMs.set(0L)
        }

        return response
    }

    data class HostSnapshot(
        val host: String,
        val cooldownRemainingMs: Long,
        val totalBypassed: Long,
    )

    fun snapshot(): List<HostSnapshot> {
        val now = System.currentTimeMillis()
        return states.entries
            .map { (h, s) ->
                HostSnapshot(
                    host = h,
                    cooldownRemainingMs = maxOf(0L, s.cooldownUntilMs.get() - now),
                    totalBypassed = s.totalBypassed.get(),
                )
            }
            .filter { it.totalBypassed > 0L || it.cooldownRemainingMs > 0L }
            .sortedByDescending { it.totalBypassed }
    }

    fun totalNxBypassedRequests(): Long = totalNxBypass.get()
    fun totalServerBypassedRequests(): Long = totalServerBypass.get()
}

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

    /**
     * V5.0.7297 §ONE_429_SILENCED_A_PROVIDER_FOR_FIVE_MINUTES.
     *
     * Every 429 set a flat [RATE_LIMIT_COOLDOWN_MS] on the host. DexScreener
     * serves every DexScreener scanner, the pair lookups and the price path
     * from one host, so a single 429 from any caller returned a synthetic 599
     * to all of them for five minutes and the deep scan read raw=0 in 0 ms.
     * The rest window now honours the provider's Retry-After and otherwise
     * climbs with consecutive 429s (30 s, 60 s, 2 min, then 5 min); a success
     * resets the climb. A provider that keeps refusing still reaches the full
     * five minutes, so parallel callers still cannot storm it.
     */
    private val RATE_LIMIT_LADDER_MS_7297 = longArrayOf(30_000L, 60_000L, 120_000L, RATE_LIMIT_COOLDOWN_MS)

    /** Pure: cooldown for the [streak]th consecutive 429 (1-based), or Retry-After when given. */
    fun rateLimitCooldownMs7297(streak: Int, retryAfterSec: Long?): Long {
        if (retryAfterSec != null && retryAfterSec > 0L) {
            return (retryAfterSec * 1000L).coerceIn(5_000L, RATE_LIMIT_COOLDOWN_MS)
        }
        val i = (streak - 1).coerceIn(0, RATE_LIMIT_LADDER_MS_7297.lastIndex)
        return RATE_LIMIT_LADDER_MS_7297[i]
    }
    private const val SERVER_FAIL_TRIP_COUNT = 3
    private const val SERVER_FAIL_TRIP_WINDOW_MS = 60_000L

    private data class HostState(
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
        val recentServerFailCount: AtomicInteger = AtomicInteger(0),
        val firstServerFailAtMs: AtomicLong = AtomicLong(0L),
        val totalBypassed: AtomicLong = AtomicLong(0L),
        val rateLimitStreak7297: AtomicInteger = AtomicInteger(0),
    )

    private val states = ConcurrentHashMap<String, HostState>()
    private val totalNxBypass = AtomicLong(0L)
    private val totalServerBypass = AtomicLong(0L)

    /**
     * V5.0.7297 — Jupiter's token lists (lite-api.jup.ag/tokens/...) feed
     * market discovery; its quote and swap paths feed execution. They share a
     * host, so a discovery 429 used to lock quotes out and the reverse. The
     * token lists answer to their own label now.
     */
    private fun providerLabelFor(host: String, path: String): String =
        if (host.endsWith("jup.ag", ignoreCase = true) && path.startsWith("/tokens/")) "jupiter_tokens"
        else providerLabel(host)

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

    /**
     * V5.0.6976 — a health PROBE opts out of the circuit.
     *
     * A circuit exists to stop ordinary traffic from hammering a provider that
     * is already failing. A readiness probe is the opposite of ordinary traffic:
     * it is the single low-rate call whose entire purpose is to discover whether
     * the provider came back. Blocking it means the circuit can never observe
     * recovery through that path, and — worse — the block is indistinguishable
     * from the provider being down, so the UI renders "JUPITER UNREACHABLE"
     * when what actually happened is that this app declined to look.
     *
     * Probes still record their outcome, so a successful probe clears the
     * provider's ApiBackoff lockout. That is a correct half-open, done by the
     * one caller that is rate-limited by construction.
     *
     * Birdeye is exempt: its budget is metered currency, not latency, and a
     * probe would spend it.
     */
    const val PROBE_HEADER_6976 = "X-AATE-Probe"

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
        val provider = providerLabelFor(host, req.url.encodedPath)
        // V5.0.6976 — probes bypass the circuit (never the Birdeye budget).
        val isProbe = req.header(PROBE_HEADER_6976) != null && provider != "birdeye"
        // V5.0.7314 — an exit is never refused by the local lockout/cool-down.
        val isExit7314 = req.header(ExitHttpScope7314.HEADER) != null || ExitHttpScope7314.active()

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
        if (isExit7314 && provider.isNotBlank() && try {
                com.lifecyclebot.engine.ApiBackoff.isLockedOut(provider)
            } catch (_: Throwable) { false }) ExitHttpScope7314.noteBypass("SHARED_CLIENT")
        if (!isProbe && !isExit7314 && provider.isNotBlank() && try {
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
        if (isExit7314 && now < cooldownUntil) ExitHttpScope7314.noteBypass("HOST_COOLDOWN")
        if (!isProbe && !isExit7314 && now < cooldownUntil) {
            state.totalBypassed.incrementAndGet()
            val remaining = cooldownUntil - now
            if (remaining > SERVER_FAIL_COOLDOWN_MS) totalNxBypass.incrementAndGet()
            else totalServerBypass.incrementAndGet()
            return synthetic(req, 599, "HostCircuit cool-down active for $host (${remaining}ms remaining)")
        }

        // The probe marker is an internal routing hint — never put it on the wire.
        val wireReq = if (req.header(PROBE_HEADER_6976) != null || req.header(ExitHttpScope7314.HEADER) != null) {
            req.newBuilder().removeHeader(PROBE_HEADER_6976).removeHeader(ExitHttpScope7314.HEADER).build()
        } else req

        val response: Response = try {
            chain.proceed(wireReq)
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
            val streak7297 = state.rateLimitStreak7297.incrementAndGet()
            val retryAfter7297 = response.header("Retry-After")?.trim()?.toLongOrNull()
            state.cooldownUntilMs.set(now + rateLimitCooldownMs7297(streak7297, retryAfter7297))
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
            state.rateLimitStreak7297.set(0)
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

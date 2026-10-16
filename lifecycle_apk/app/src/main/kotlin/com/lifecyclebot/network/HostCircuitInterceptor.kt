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
 *
 * V5.0.8144 — unmapped hosts cool down per traffic lane ([ProofDataLanes8144]): execution (sends, balances, fees,
 * exits, PumpPortal trades), speculative intelligence, bulk candles and shared reads each hold their own key, so an
 * intelligence backoff never starves an execution RPC or a held-position mark on the same host.
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
    /**
     * V5.0.8136 — one DNS miss on a mobile network switch put the host (an RPC the live authority proof needs) behind a
     * flat five-minute wall. NXDOMAIN now climbs 15 s, 60 s, 5 min on consecutive misses and a success resets it.
     */
    private val NX_LADDER_MS_8136 = longArrayOf(15_000L, 60_000L, NXDOMAIN_COOLDOWN_MS)

    /** Pure: cooldown for the [streak]th consecutive NXDOMAIN (1-based). */
    fun nxCooldownMs8136(streak: Int): Long = NX_LADDER_MS_8136[(streak - 1).coerceIn(0, NX_LADDER_MS_8136.lastIndex)]

    /**
     * V5.0.8136 — pure: the 429 streak after one more 429. When the cool-down is still running, a parallel caller's
     * 429 from the same burst does not climb the ladder again: N callers released together at the end of a window
     * used to jump the provider straight to the five-minute rung in one burst.
     */
    fun nextRateLimitStreak8136(prevStreak: Int, nowMs: Long, cooldownUntilMs: Long): Int =
        if (cooldownUntilMs > nowMs && prevStreak > 0) prevStreak else prevStreak + 1

    private const val SERVER_FAIL_TRIP_COUNT = 3
    private const val SERVER_FAIL_TRIP_WINDOW_MS = 60_000L

    private data class HostState(
        val cooldownUntilMs: AtomicLong = AtomicLong(0L),
        val recentServerFailCount: AtomicInteger = AtomicInteger(0),
        val firstServerFailAtMs: AtomicLong = AtomicLong(0L),
        val totalBypassed: AtomicLong = AtomicLong(0L),
        val rateLimitStreak7297: AtomicInteger = AtomicInteger(0),
        val nxStreak8136: AtomicInteger = AtomicInteger(0),
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
        // V5.0.7925 — the keyed host (api.jup.ag) has its own label: a keyless lite-api
        // refusal used to open the shared circuit and lock out the keyed fallback too.
        if (host.endsWith("jup.ag", ignoreCase = true) && path.startsWith("/tokens/"))
            (if (host.startsWith("lite-api", ignoreCase = true)) "jupiter_tokens" else "jupiter_tokens_keyed")
        else endpointClass8125(providerLabel(host), path)

    /**
     * V5.0.8125 — owner: "we are meant to have multiple multiple sources of data". One label per provider meant a 429
     * on a low-value call (DexScreener boosts/profiles/search for discovery, the chart library's seed jobs) locked out
     * the pair and token lookups that price and size candidates and positions. Discovery paths answer to their own
     * label, and Jupiter's price API no longer shares a lock with the keyless quote/swap path.
     */
    internal fun endpointClass8125(base: String, path: String): String = when (base) {
        "dexscreener" -> if (DEX_PRICE_PATHS_8125.any { path.startsWith(it) }) base else "dexscreener_discovery"
        "jupiter" -> if (path.startsWith("/price/")) "jupiter_price" else base
        else -> base
    }

    private val DEX_PRICE_PATHS_8125 = listOf("/latest/dex/pairs", "/latest/dex/tokens", "/tokens/", "/token-pairs/")

    /**
     * V5.0.8125 — a caller that is background work (the chart library) tags its requests; their failures cool down
     * "<provider>_<lane>" and never the provider's live lookups. Stripped before the request goes on the wire.
     */
    const val LANE_HEADER_8125 = "X-AATE-Lane"

    /** Pure: the lockout label a request answers to. */
    fun labelFor8125(host: String, path: String, lane: String?): String {
        val base = providerLabelFor(host, path)
        return if (base.isBlank() || lane.isNullOrBlank()) base else "${base}_$lane"
    }

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
        val wireHost8144 = req.url.host
        val now = System.currentTimeMillis()
        val provider = labelFor8125(wireHost8144, req.url.encodedPath, req.header(LANE_HEADER_8125))
        // V5.0.7314 — an exit is never refused by the local lockout/cool-down.
        val isExit7314 = req.header(ExitHttpScope7314.HEADER) != null || ExitHttpScope7314.active()
        // V5.0.8144 — an unmapped host (Solana RPCs, PumpPortal) cools down per traffic lane: a 429 earned by speculative
        // intelligence no longer refuses the next send / balance / held-position read on the same host.
        val lane8144 = ProofDataLanes8144.laneFor8144(
            req.header(LANE_HEADER_8125), isExit7314, wireHost8144, req.url.encodedPath,
            if (provider.isBlank()) ProofDataLanes8144.peekRpcMethod8144(req) else null,
        )
        ProofDataLanes8144.noteCall8144(lane8144)
        val host = ProofDataLanes8144.circuitHost8144(wireHost8144, provider, lane8144)
        // V5.0.7719 — the cool-down state is keyed by host AND provider label.
        // 7297 split the ApiBackoff label (jupiter_tokens vs jupiter) but this
        // host state stayed per host, so three 403s from the market sweep's
        // token lists on lite-api.jup.ag put the QUOTE path on the same host
        // into a 90 s cool-down every sweep (5.0.7716: jupiter_quote
        // transport 14%, route acceptance 8%, preflight REFUSE, while the
        // lists reported LOCAL_CIRCUIT). Discovery and execution now cool
        // down separately.
        val stateKey7719 = if (provider.isNotBlank()) "$host|$provider" else host
        val state = states.getOrPut(stateKey7719) { HostState() }
        // V5.0.6976 — probes bypass the circuit (never the Birdeye budget).
        val isProbe = req.header(PROBE_HEADER_6976) != null && provider != "birdeye"

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
            ProofDataLanes8144.noteBypass8144(lane8144)
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
            ProofDataLanes8144.noteBypass8144(lane8144)
            val remaining = cooldownUntil - now
            if (remaining > SERVER_FAIL_COOLDOWN_MS) totalNxBypass.incrementAndGet()
            else totalServerBypass.incrementAndGet()
            return synthetic(req, 599, "HostCircuit cool-down active for $host (${remaining}ms remaining)")
        }

        // The probe marker is an internal routing hint — never put it on the wire.
        val wireReq = if (req.header(PROBE_HEADER_6976) != null || req.header(ExitHttpScope7314.HEADER) != null || req.header(LANE_HEADER_8125) != null) {
            req.newBuilder().removeHeader(PROBE_HEADER_6976).removeHeader(ExitHttpScope7314.HEADER).removeHeader(LANE_HEADER_8125).build()
        } else req

        val response: Response = try {
            chain.proceed(wireReq)
        } catch (uhe: UnknownHostException) {
            state.cooldownUntilMs.set(now + nxCooldownMs8136(state.nxStreak8136.incrementAndGet()))
            throw uhe
        }
        state.nxStreak8136.set(0)

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
            val streak7297 = state.rateLimitStreak7297.updateAndGet { nextRateLimitStreak8136(it, now, state.cooldownUntilMs.get()) }
            val retryAfter7297 = response.header("Retry-After")?.trim()?.toLongOrNull()
            val until8136 = now + rateLimitCooldownMs7297(streak7297, retryAfter7297)
            state.cooldownUntilMs.accumulateAndGet(until8136) { old, fresh -> maxOf(old, fresh) }
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

    /** V5.0.8144 — is the circuit cooling down [stateKey] (a host, "host|provider" or "host#lane") at [nowMs]? */
    fun coolingDown8144(stateKey: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        (states[stateKey]?.cooldownUntilMs?.get() ?: 0L) > nowMs

    fun totalNxBypassedRequests(): Long = totalNxBypass.get()
    fun totalServerBypassedRequests(): Long = totalServerBypass.get()
}

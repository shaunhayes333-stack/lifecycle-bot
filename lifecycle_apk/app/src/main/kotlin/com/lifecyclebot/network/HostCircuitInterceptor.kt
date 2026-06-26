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
 *
 * Operator: AATE used 281 GB in 29 days. A material slice is wasted on
 * retry storms when a host is genuinely dead — every Jupiter `tokens.jup.ag`
 * NXDOMAIN burns a full TLS handshake (5–10 KB) before failing, and every
 * 503/429 from a saturated provider triggers a same-second retry. Across
 * a multi-hour DNS blackout that's thousands of wasted handshakes.
 *
 * Two surgical defenses, both fail-open by design:
 *
 *  1. NXDOMAIN cool-down: when a host throws UnknownHostException, mark
 *     it failed and short-circuit every subsequent request to that host
 *     with a synthetic 599 response for [NXDOMAIN_COOLDOWN_MS] (5 min).
 *     The interceptor never touches the wire during the cool-down — zero
 *     TLS, zero TCP, zero DNS.
 *
 *  2. 503 / 429 cool-down: same idea, shorter window ([SERVER_FAIL_COOLDOWN_MS]
 *     90 s). After three consecutive 5xx/429 from the same host within a
 *     minute, short-circuit further requests to that host for 90 s. Lets
 *     the upstream recover instead of getting hammered.
 *
 * Self-clearing: one successful request resets the host's failure state.
 * Counters exposed via [snapshot] for operator telemetry.
 */
object HostCircuitInterceptor : Interceptor {

    private const val NXDOMAIN_COOLDOWN_MS = 5 * 60_000L
    private const val SERVER_FAIL_COOLDOWN_MS = 90_000L
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

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val host = req.url.host
        val now = System.currentTimeMillis()
        val state = states.getOrPut(host) { HostState() }
        val cooldownUntil = state.cooldownUntilMs.get()

        // Fast-path short-circuit: host is in cool-down, fail immediately
        // without touching the network. This is THE bandwidth saver —
        // no TLS handshake, no SYN/ACK, no DNS retry.
        if (now < cooldownUntil) {
            state.totalBypassed.incrementAndGet()
            // Heuristic: NXDOMAIN cool-downs are longer than server-fail
            // ones, so distinguish via the remaining duration.
            val remaining = cooldownUntil - now
            if (remaining > SERVER_FAIL_COOLDOWN_MS) totalNxBypass.incrementAndGet()
            else totalServerBypass.incrementAndGet()
            return Response.Builder()
                .request(req)
                .protocol(Protocol.HTTP_1_1)
                .code(599)
                .message("HostCircuit cool-down active for $host (${remaining}ms remaining)")
                .body("".toResponseBody(null))
                .build()
        }

        val response: Response = try {
            chain.proceed(req)
        } catch (uhe: UnknownHostException) {
            // V5.0.4170: trip NXDOMAIN cool-down. The host is unreachable
            // at the DNS layer — likely a long-lived outage on the device's
            // resolver, a carrier-level block, or the host being moved.
            // 5 minutes lets the resolver pick up the change without us
            // hammering it once per scan cycle.
            state.cooldownUntilMs.set(now + NXDOMAIN_COOLDOWN_MS)
            throw uhe
        }

        // 5xx / 429 tracking — three within a minute trips a short cool-down.
        if (response.code in 500..599 || response.code == 429) {
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
            // Reset on success — one good response and we're back in business.
            state.recentServerFailCount.set(0)
            state.firstServerFailAtMs.set(0L)
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

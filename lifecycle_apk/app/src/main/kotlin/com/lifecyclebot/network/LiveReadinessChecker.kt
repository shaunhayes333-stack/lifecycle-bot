package com.lifecyclebot.network

import com.lifecyclebot.engine.ErrorLogger
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * V5.9.29: Live trading readiness checker.
 *
 * Pings the critical endpoints the bot needs to execute live trades:
 *   - Jupiter quote API (lite-api.jup.ag/swap/v1)
 *   - Solana RPC (from config, whatever the user has set)
 *   - Pyth Hermes (oracle backbone for majors)
 *
 * Returns a snapshot the UI can render as a traffic-light banner. Runs on
 * a background coroutine, caches result for [RECHECK_MS], exposes a manual
 * refresh for the UI's tap-to-recheck.
 */
object LiveReadinessChecker {
    private const val TAG = "Readiness"
    private const val RECHECK_MS = 30_000L
    private const val TIMEOUT_MS = 4_000L

    private val http = SharedHttpClient.builder()
        .dns(CloudflareDns.INSTANCE)
        .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    enum class State { GREEN, YELLOW, RED, UNKNOWN }

    data class Snapshot(
        val state: State,
        val summary: String,
        val jupiterOk: Boolean,
        val jupiterLatencyMs: Long,
        val pythOk: Boolean,
        val pythLatencyMs: Long,
        val lastCheckAt: Long,
    )

    @Volatile
    private var latest: Snapshot = Snapshot(
        state = State.UNKNOWN,
        summary = "Checking live readiness…",
        jupiterOk = false,
        jupiterLatencyMs = -1,
        pythOk = false,
        pythLatencyMs = -1,
        lastCheckAt = 0L,
    )

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastCheckJob: Job? = null

    /** V5.0.6976 — consecutive REAL Jupiter ping failures; synthetic blocks never count. */
    @Volatile
    private var consecutiveJupFails: Int = 0
    private const val JUP_FAIL_CONFIRM = 2

    /** Returns the cached snapshot, triggering a refresh if stale. */
    fun current(): Snapshot {
        if (System.currentTimeMillis() - latest.lastCheckAt > RECHECK_MS) {
            triggerCheck()
        }
        return latest
    }

    /** Force an immediate re-check (used by tap-to-retry on the banner). */
    fun triggerCheck() {
        if (lastCheckJob?.isActive == true) return
        lastCheckJob = scope.launch { runCheck() }
    }

    private suspend fun runCheck() {
        val jupiter = pingJupiter()
        // V5.0.6976 — a circuit block is the absence of an observation. Hold the
        // previous verdict rather than converting our own refusal into an outage.
        if (lastPingWasSynthetic) {
            latest = latest.copy(lastCheckAt = System.currentTimeMillis())
            ErrorLogger.debug(TAG, "readiness: jupiter ping blocked locally — previous verdict held")
            return
        }
        val pyth = pingPyth()

        val jupOk = jupiter.first
        val pythOk = pyth.first

        // V5.0.6976 §THE_BANNER_THAT_REPORTED_OUR_OWN_SILENCE_AS_THEIR_OUTAGE.
        //
        // One failed ping used to paint the banner red immediately and it stayed
        // red for the full 30s recheck interval. Jupiter ran at sr=97% in the
        // operator's 6970 log — 1 4xx and 1 5xx across 78 calls — and the banner
        // still read "JUPITER UNREACHABLE", because a single unlucky sample is
        // enough and because the ping was being answered by our own circuit
        // rather than by Jupiter (now fixed by the probe bypass below).
        //
        // Require two consecutive real failures before declaring an outage. A
        // lone miss holds the previous verdict and says so.
        val fails = if (jupOk) { consecutiveJupFails = 0; 0 } else ++consecutiveJupFails
        val jupDown = fails >= JUP_FAIL_CONFIRM

        val state = when {
            jupOk && pythOk -> State.GREEN
            jupOk && !pythOk -> State.YELLOW  // oracle down but can still swap
            !jupDown -> State.YELLOW          // one miss — unconfirmed, not an outage
            else -> State.RED
        }
        val summary = when (state) {
            State.GREEN -> "🟢 APIs READY · Jupiter + Pyth healthy"
            State.YELLOW -> when {
                !jupOk -> "🟡 Jupiter ping missed (1/$JUP_FAIL_CONFIRM) · re-checking"
                else -> "🟡 DEGRADED · Jupiter OK · Oracle slow/down"
            }
            State.RED -> if (!jupOk) "🔴 JUPITER UNREACHABLE · live swaps blocked" else "🔴 Oracle & swap down · stay in paper"
            State.UNKNOWN -> "Checking live readiness…"
        }

        latest = Snapshot(
            state = state,
            summary = summary,
            jupiterOk = jupOk,
            jupiterLatencyMs = jupiter.second,
            pythOk = pythOk,
            pythLatencyMs = pyth.second,
            lastCheckAt = System.currentTimeMillis(),
        )
        ErrorLogger.debug(TAG, "readiness: $summary (jup=${jupiter.second}ms pyth=${pyth.second}ms)")
    }

    /** Returns (ok, latencyMs). */
    private fun pingJupiter(): Pair<Boolean, Long> {
        // Cheap real call — tiny SOL->USDC quote. This verifies DNS + TLS + route.
        val url = "https://lite-api.jup.ag/swap/v1/quote" +
            "?inputMint=So11111111111111111111111111111111111111112" +
            "&outputMint=EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" +
            "&amount=1000000&slippageBps=50"
        return httpPing(url)
    }

    private fun pingPyth(): Pair<Boolean, Long> {
        // SOL price feed — lightest-weight Pyth call possible
        val url = "https://hermes.pyth.network/v2/updates/price/latest" +
            "?ids[]=0xef0d8b6fda2ceba41da15d4095d1da392a0d2f8ed0c6c7bc0f4cfac8c280b56d"
        return httpPing(url)
    }

    /** V5.0.6976 — true when the last ping was answered by our own circuit. */
    @Volatile
    private var lastPingWasSynthetic: Boolean = false

    private fun httpPing(url: String): Pair<Boolean, Long> {
        val start = System.currentTimeMillis()
        return try {
            // V5.0.6976 — mark as a probe so HostCircuitInterceptor lets it through.
            // This client shares the app-wide interceptor stack, so before this
            // header existed the readiness check was frequently answered by our
            // own 599 and rendered as "Jupiter unreachable". A probe is exactly
            // the call a circuit should permit: one low-rate request whose job is
            // to find out whether the provider is back.
            val req = Request.Builder()
                .url(url)
                .header(HostCircuitInterceptor.PROBE_HEADER_6976, "1")
                .build()
            http.newCall(req).execute().use { r ->
                val elapsed = System.currentTimeMillis() - start
                lastPingWasSynthetic = try { HostCircuitInterceptor.isSyntheticBlock(r) } catch (_: Throwable) { false }
                r.isSuccessful to elapsed
            }
        } catch (_: Exception) {
            lastPingWasSynthetic = false
            false to (System.currentTimeMillis() - start)
        }
    }
}

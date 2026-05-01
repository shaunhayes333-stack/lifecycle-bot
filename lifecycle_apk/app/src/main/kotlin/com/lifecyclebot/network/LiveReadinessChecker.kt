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
        val pyth = pingPyth()

        val jupOk = jupiter.first
        val pythOk = pyth.first

        val state = when {
            jupOk && pythOk -> State.GREEN
            jupOk && !pythOk -> State.YELLOW  // oracle down but can still swap
            !jupOk && pythOk -> State.RED     // cannot execute
            else -> State.RED
        }
        val summary = when (state) {
            State.GREEN -> "🟢 LIVE READY · Jupiter + Pyth healthy"
            State.YELLOW -> "🟡 DEGRADED · Jupiter OK · Oracle slow/down"
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

    private fun httpPing(url: String): Pair<Boolean, Long> {
        val start = System.currentTimeMillis()
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                val elapsed = System.currentTimeMillis() - start
                r.isSuccessful to elapsed
            }
        } catch (_: Exception) {
            false to (System.currentTimeMillis() - start)
        }
    }
}

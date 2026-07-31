package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6402 §D — PROVIDER CIRCUIT BREAKERS.
 *
 * OPERATOR DIRECTIVE
 * ───────────────────
 * Birdeye HTTP 401 is a terminal credential failure. On the first
 * authenticated 401, open the Birdeye circuit immediately, stop all
 * Birdeye requests, do not retry per token, keep the circuit open
 * until credentials change or a manual health probe succeeds.
 *
 * Helius HTTP 429 must activate a shared provider-wide backoff.
 * Do not retry separately for every mint. Use exponential backoff
 * with jitter and respect Retry-After.
 *
 * DESIGN
 * ──────
 * Callers ask [shouldSkip] BEFORE any provider I/O. On response:
 *   - HTTP 401 → [onAuthTerminal] permanently opens the circuit.
 *   - HTTP 429 → [onRateLimited] activates a shared backoff window.
 *   - HTTP 5xx → [onServerError] adds a small transient cool-down.
 *   - Success → [onSuccess] clears transient state (auth-terminal
 *     is only cleared by [resetAuthTerminal], which represents
 *     credential rotation or an operator manual health probe).
 *
 * All decisions are pure functions over process-local counters.
 * NEVER blocks — [shouldSkip] returns a Boolean without any I/O.
 */
object ProviderCircuitBreaker6402 {

    /** Providers we care about breaking. Extend as needed. */
    enum class Provider { BIRDEYE, HELIUS, GROQ, DEXSCREENER, GECKOTERMINAL, COINGECKO }

    /** Backoff floor for a rate-limit trip. */
    const val RATE_LIMIT_BASE_BACKOFF_MS: Long = 5_000L
    /** Backoff ceiling — never wait longer than this between attempts. */
    const val RATE_LIMIT_MAX_BACKOFF_MS: Long = 60_000L
    /** Transient 5xx cool-down before retrying. */
    const val SERVER_ERROR_BACKOFF_MS: Long = 2_000L

    private data class State(
        // AUTH-terminal: sticky. Only [resetAuthTerminal] clears it.
        var authTerminal: Boolean = false,
        var authTerminalSince: Long = 0L,
        // Rate-limit: sticky until backoff elapses. Exponential.
        var rateLimitBackoffUntil: Long = 0L,
        var consecutiveRateLimits: Int = 0,
        var lastRateLimitAt: Long = 0L,
        // Server error: shorter transient cool-down.
        var serverErrorBackoffUntil: Long = 0L,
    )

    private val states = ConcurrentHashMap<Provider, State>().apply {
        Provider.entries.forEach { put(it, State()) }
    }
    private val skipEvents = AtomicLong(0L)

    private fun st(p: Provider): State = states.getValue(p)

    /**
     * Returns true iff the caller should SKIP the provider call
     * because the circuit is open (auth terminal, rate-limited, or
     * server cooling down). Callers must respect this pre-check.
     */
    fun shouldSkip(p: Provider, nowMs: Long = System.currentTimeMillis()): Boolean {
        val s = st(p)
        val skip = s.authTerminal ||
                nowMs < s.rateLimitBackoffUntil ||
                nowMs < s.serverErrorBackoffUntil
        if (skip) {
            skipEvents.incrementAndGet()
            try {
                val reason = when {
                    s.authTerminal -> "AUTH_TERMINAL"
                    nowMs < s.rateLimitBackoffUntil -> "RATE_LIMITED"
                    else -> "SERVER_ERROR_COOLDOWN"
                }
                PipelineHealthCollector.labelInc("PROVIDER_CIRCUIT_SKIP_${p.name}_${reason}_6402")
            } catch (_: Throwable) {}
        }
        return skip
    }

    /** HTTP 401 — permanent circuit open until [resetAuthTerminal]. */
    fun onAuthTerminal(p: Provider, nowMs: Long = System.currentTimeMillis()) {
        val s = st(p)
        if (s.authTerminal) return
        s.authTerminal = true
        s.authTerminalSince = nowMs
        try {
            PipelineHealthCollector.labelInc("PROVIDER_CIRCUIT_OPENED_${p.name}_AUTH_TERMINAL_6402")
            ForensicLogger.lifecycle(
                "PROVIDER_CIRCUIT_OPENED_${p.name}_AUTH_TERMINAL_6402",
                "provider=${p.name} sinceMs=$nowMs directive=V5.0.6402_D",
            )
        } catch (_: Throwable) {}
    }

    /**
     * HTTP 429 — activate exponential shared backoff. Honours
     * `Retry-After` if the caller supplies [retryAfterMs].
     */
    fun onRateLimited(
        p: Provider,
        retryAfterMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val s = st(p)
        s.consecutiveRateLimits++
        s.lastRateLimitAt = nowMs
        val exp = RATE_LIMIT_BASE_BACKOFF_MS * (1L shl minOf(s.consecutiveRateLimits - 1, 6))
        val jitter = (100L..500L).random()
        val serverHint = retryAfterMs ?: 0L
        val backoff = maxOf(serverHint, exp + jitter).coerceAtMost(RATE_LIMIT_MAX_BACKOFF_MS)
        s.rateLimitBackoffUntil = maxOf(s.rateLimitBackoffUntil, nowMs + backoff)
        try {
            PipelineHealthCollector.labelInc("PROVIDER_CIRCUIT_RATE_LIMITED_${p.name}_6402")
            ForensicLogger.lifecycle(
                "PROVIDER_CIRCUIT_RATE_LIMITED_${p.name}_6402",
                "provider=${p.name} backoffMs=$backoff consecutive=${s.consecutiveRateLimits} retryAfterMs=$retryAfterMs",
            )
        } catch (_: Throwable) {}
    }

    /** HTTP 5xx — brief transient cool-down. */
    fun onServerError(p: Provider, nowMs: Long = System.currentTimeMillis()) {
        val s = st(p)
        s.serverErrorBackoffUntil = maxOf(s.serverErrorBackoffUntil, nowMs + SERVER_ERROR_BACKOFF_MS)
        try { PipelineHealthCollector.labelInc("PROVIDER_CIRCUIT_SERVER_ERROR_${p.name}_6402") } catch (_: Throwable) {}
    }

    /** Success clears transient state (auth-terminal is separately gated). */
    fun onSuccess(p: Provider) {
        val s = st(p)
        s.consecutiveRateLimits = 0
        s.rateLimitBackoffUntil = 0L
        s.serverErrorBackoffUntil = 0L
    }

    /**
     * Manual reset for auth-terminal (credential rotation or
     * operator health probe). Never invoked automatically.
     */
    fun resetAuthTerminal(p: Provider) {
        val s = st(p)
        s.authTerminal = false
        s.authTerminalSince = 0L
        try { PipelineHealthCollector.labelInc("PROVIDER_CIRCUIT_AUTH_RESET_${p.name}_6402") } catch (_: Throwable) {}
    }

    // ── Observability ──────────────────────────────────────────────

    fun isAuthTerminal(p: Provider): Boolean = st(p).authTerminal
    fun isRateLimited(p: Provider, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < st(p).rateLimitBackoffUntil
    fun isServerCoolingDown(p: Provider, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs < st(p).serverErrorBackoffUntil

    /** Diagnosis for the RuntimeDoctor / Provider Doctor (directive §L). */
    fun classify(p: Provider, nowMs: Long = System.currentTimeMillis()): String = when {
        isAuthTerminal(p) -> "AUTH_TERMINAL"
        isRateLimited(p, nowMs) -> "RATE_LIMITED"
        isServerCoolingDown(p, nowMs) -> "SERVER_COOLDOWN"
        else -> "OK"
    }

    fun totalSkipEvents(): Long = skipEvents.get()

    internal fun clearAllForTest() {
        Provider.entries.forEach { states[it] = State() }
        skipEvents.set(0L)
    }
}

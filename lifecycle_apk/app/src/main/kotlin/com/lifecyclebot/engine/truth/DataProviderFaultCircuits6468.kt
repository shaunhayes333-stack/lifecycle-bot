package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6468 §P0 (item 18) — DATA PROVIDER FAULT CIRCUITS.
 *
 * OPERATOR MANDATE:
 *   "Update Provider failure circuits (Birdeye 401, Groq 404, Helius 429).
 *    Treat Auth failures as circuit-open, cache data. Provider faults
 *    must not crash the bot loop."
 *
 * COMPLEMENTS
 *   `ProviderDomainCircuits6411` — that module is keyed by
 *   `ExecutionAdapter6411` (Jupiter, PumpFun, RPC send).
 *   THIS module covers READ-side data providers: Birdeye, Groq,
 *   Helius, Solscan and generic HTTP fetches. They fail with distinct
 *   semantics that must be handled distinctly:
 *
 *     401 / 403 — auth broken; RETRY IS FUTILE. Circuit open long,
 *                 escalate to operator, run in cache-only mode.
 *     404       — endpoint gone / bad path. Circuit open medium; cache.
 *     429       — rate-limited; exponential backoff.
 *     5xx / IO  — transient; short backoff.
 *
 * The bot loop consults `mode(provider)` to decide whether to hit the
 * network or read from cache. `recordHttpStatus` is called by HTTP
 * client wrappers after every response.
 */
object DataProviderFaultCircuits6468 {

    enum class Provider { BIRDEYE, GROQ, HELIUS, SOLSCAN, GENERIC }

    enum class Mode {
        LIVE,           // network allowed
        CACHE_ONLY,     // return only cached data; no network calls
        AUTH_LOCKOUT,   // auth broken; needs operator rotation
    }

    private const val AUTH_LOCKOUT_MS = 15L * 60_000L      // 15 min
    private const val NOT_FOUND_MS = 5L * 60_000L          // 5 min
    private const val RATE_LIMIT_MIN_MS = 30_000L          // 30s
    private const val RATE_LIMIT_MAX_MS = 10L * 60_000L    // 10 min
    private const val TRANSIENT_MIN_MS = 10_000L           // 10s
    private const val TRANSIENT_MAX_MS = 3L * 60_000L      // 3 min

    private data class Circuit(
        val provider: Provider,
        val mode: AtomicReference<Mode> = AtomicReference(Mode.LIVE),
        val openUntilMs: AtomicLong = AtomicLong(0L),
        val consecFailures: AtomicInteger = AtomicInteger(0),
        val consecRateLimits: AtomicInteger = AtomicInteger(0),
        val consecAuthFails: AtomicInteger = AtomicInteger(0),
        val consec404s: AtomicInteger = AtomicInteger(0),
        val lastSuccessMs: AtomicLong = AtomicLong(0L),
        val lastStatusCode: AtomicInteger = AtomicInteger(0),
        val totalFailures: AtomicLong = AtomicLong(0L),
        val totalSuccesses: AtomicLong = AtomicLong(0L),
    )

    private val circuits = ConcurrentHashMap<Provider, Circuit>()

    private fun get(p: Provider): Circuit = circuits.getOrPut(p) { Circuit(p) }

    /** Best-effort mapping from a provider-specific name/host string. */
    fun classify(providerName: String): Provider {
        val n = providerName.lowercase()
        return when {
            "birdeye" in n -> Provider.BIRDEYE
            "groq" in n -> Provider.GROQ
            "helius" in n -> Provider.HELIUS
            "solscan" in n -> Provider.SOLSCAN
            else -> Provider.GENERIC
        }
    }

    /** Current allowed operating mode for this provider. */
    fun mode(provider: Provider): Mode {
        val c = get(provider)
        val m = c.mode.get()
        if (m == Mode.LIVE) return Mode.LIVE
        val now = System.currentTimeMillis()
        if (now >= c.openUntilMs.get()) {
            // AUTH_LOCKOUT does NOT auto-clear on time alone — operator must rotate key.
            if (m == Mode.AUTH_LOCKOUT) return Mode.AUTH_LOCKOUT
            // CACHE_ONLY auto-releases into a probing LIVE window; success will close.
            c.mode.compareAndSet(m, Mode.LIVE)
            return Mode.LIVE
        }
        return m
    }

    fun isNetworkAllowed(provider: Provider): Boolean = mode(provider) == Mode.LIVE

    /** Called by HTTP clients after every non-cache response. */
    fun recordHttpStatus(provider: Provider, statusCode: Int) {
        val c = get(provider)
        c.lastStatusCode.set(statusCode)
        when {
            statusCode in 200..299 -> recordSuccess(provider)
            statusCode == 401 || statusCode == 403 -> recordAuthFailure(provider, statusCode)
            statusCode == 404 -> recordNotFound(provider)
            statusCode == 429 -> recordRateLimit(provider)
            statusCode in 500..599 -> recordTransient(provider, statusCode)
            else -> recordTransient(provider, statusCode)
        }
    }

    fun recordSuccess(provider: Provider) {
        val c = get(provider)
        c.lastSuccessMs.set(System.currentTimeMillis())
        c.totalSuccesses.incrementAndGet()
        c.consecFailures.set(0)
        c.consecRateLimits.set(0)
        c.consec404s.set(0)
        // AUTH_LOCKOUT only clears via operator (explicit rotate).
        if (c.mode.get() != Mode.AUTH_LOCKOUT) {
            if (c.mode.getAndSet(Mode.LIVE) != Mode.LIVE) {
                try { PipelineHealthCollector.labelInc("DATA_PROVIDER_CIRCUIT_CLOSED_6468_${provider.name}") } catch (_: Throwable) {}
            }
        }
    }

    private fun recordAuthFailure(provider: Provider, code: Int) {
        val c = get(provider)
        c.totalFailures.incrementAndGet()
        val streak = c.consecAuthFails.incrementAndGet()
        // Single 401/403 is enough to trigger lockout — retrying auth-broken calls
        // burns quota + generates noise for zero payoff.
        val now = System.currentTimeMillis()
        c.mode.set(Mode.AUTH_LOCKOUT)
        c.openUntilMs.set(now + AUTH_LOCKOUT_MS)
        try {
            ForensicLogger.lifecycle(
                "DATA_PROVIDER_AUTH_LOCKOUT_6468",
                "provider=${provider.name} code=$code consecAuthFails=$streak lockoutMs=$AUTH_LOCKOUT_MS action=OPERATOR_ROTATE_KEY",
            )
            PipelineHealthCollector.labelInc("DATA_PROVIDER_AUTH_LOCKOUT_6468")
            PipelineHealthCollector.labelInc("DATA_PROVIDER_AUTH_LOCKOUT_6468_${provider.name}")
        } catch (_: Throwable) {}
    }

    private fun recordNotFound(provider: Provider) {
        val c = get(provider)
        c.totalFailures.incrementAndGet()
        val n = c.consec404s.incrementAndGet()
        if (n >= 2) {
            val now = System.currentTimeMillis()
            c.mode.set(Mode.CACHE_ONLY)
            c.openUntilMs.set(now + NOT_FOUND_MS)
            try {
                ForensicLogger.lifecycle(
                    "DATA_PROVIDER_404_CACHE_ONLY_6468",
                    "provider=${provider.name} consec404=$n cacheMs=$NOT_FOUND_MS",
                )
                PipelineHealthCollector.labelInc("DATA_PROVIDER_404_CACHE_ONLY_6468_${provider.name}")
            } catch (_: Throwable) {}
        }
    }

    private fun recordRateLimit(provider: Provider) {
        val c = get(provider)
        c.totalFailures.incrementAndGet()
        val n = c.consecRateLimits.incrementAndGet()
        val backoff = (RATE_LIMIT_MIN_MS * (1L shl (n - 1).coerceIn(0, 5))).coerceAtMost(RATE_LIMIT_MAX_MS)
        val now = System.currentTimeMillis()
        c.mode.set(Mode.CACHE_ONLY)
        c.openUntilMs.set(now + backoff)
        try {
            ForensicLogger.lifecycle(
                "DATA_PROVIDER_429_BACKOFF_6468",
                "provider=${provider.name} consecRateLimits=$n backoffMs=$backoff",
            )
            PipelineHealthCollector.labelInc("DATA_PROVIDER_429_BACKOFF_6468_${provider.name}")
        } catch (_: Throwable) {}
    }

    private fun recordTransient(provider: Provider, code: Int) {
        val c = get(provider)
        c.totalFailures.incrementAndGet()
        val n = c.consecFailures.incrementAndGet()
        if (n >= 3) {
            val backoff = (TRANSIENT_MIN_MS * (1L shl (n - 3).coerceIn(0, 4))).coerceAtMost(TRANSIENT_MAX_MS)
            val now = System.currentTimeMillis()
            if (c.mode.get() != Mode.AUTH_LOCKOUT) {
                c.mode.set(Mode.CACHE_ONLY)
                c.openUntilMs.set(now + backoff)
                try {
                    ForensicLogger.lifecycle(
                        "DATA_PROVIDER_TRANSIENT_BACKOFF_6468",
                        "provider=${provider.name} code=$code consecFails=$n backoffMs=$backoff",
                    )
                    PipelineHealthCollector.labelInc("DATA_PROVIDER_TRANSIENT_BACKOFF_6468_${provider.name}")
                } catch (_: Throwable) {}
            }
        }
    }

    /** Explicit reset for the operator after rotating an auth key. */
    fun releaseAuthLockout(provider: Provider) {
        val c = get(provider)
        if (c.mode.get() == Mode.AUTH_LOCKOUT) {
            c.mode.set(Mode.LIVE)
            c.consecAuthFails.set(0)
            c.openUntilMs.set(0L)
            try {
                ForensicLogger.lifecycle(
                    "DATA_PROVIDER_AUTH_LOCKOUT_RELEASED_6468",
                    "provider=${provider.name} action=OPERATOR_RELEASE",
                )
                PipelineHealthCollector.labelInc("DATA_PROVIDER_AUTH_LOCKOUT_RELEASED_6468")
            } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String {
        val parts = Provider.values().map { p ->
            val c = get(p)
            "${p.name}=${c.mode.get().name.first()}(s=${c.totalSuccesses.get()},f=${c.totalFailures.get()},code=${c.lastStatusCode.get()})"
        }
        return parts.joinToString(" ")
    }

    internal fun resetForTest() { circuits.clear() }
}

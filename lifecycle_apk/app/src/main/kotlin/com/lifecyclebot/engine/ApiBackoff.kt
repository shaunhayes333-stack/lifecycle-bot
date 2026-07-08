package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.1024 — REACTIVE PER-HOST BACKOFF.
 *
 * Operator V5.9.1023 snapshot showed:
 *   • dexscreener sr= 49%  4xx=406    (paid-tier rate limit storm)
 *   • groq        sr=  0%  4xx=13     (model-level rate limit reached)
 *   • SUPERVISOR_CHUNK_TIMEOUT firing every cycle, processed=0 deferred=96
 *
 * Existing RateLimiter is PROACTIVE only — counts our own requests in a
 * sliding window. It does NOT react to actual 429/403 responses from the
 * provider. When DexScreener returns 429, our limiter happily fires the
 * next request immediately, burning paid-tier credits and choking the
 * supervisor.
 *
 * This object adds REACTIVE backoff. On each 4xx response we increment a
 * per-host failure counter and set a "do-not-call-until" timestamp. The
 * backoff grows on consecutive failures (5s → 15s → 30s → 60s → 120s
 * capped at 300s). A single 2xx success resets the counter.
 *
 * Wired into HealthAwareHttp: every keyless REST call already routes
 * through that wrapper, so one edit covers DexScreener, PumpFun, Birdeye
 * (REST), Jupiter, and any future host that uses the same path.
 *
 * Doctrine: fail-open. If any internal state read/write throws we treat
 * the host as healthy so a buggy ApiBackoff can never *prevent* the bot
 * from making necessary calls.
 */
object ApiBackoff {

    private data class State(
        val consecutiveFailures: AtomicInteger = AtomicInteger(0),
        val lockoutUntilMs: AtomicLong = AtomicLong(0L),
        val lastFailureCode: AtomicInteger = AtomicInteger(0),
        val totalLockouts: AtomicLong = AtomicLong(0L),
    )

    private val state = ConcurrentHashMap<String, State>()

    /** Backoff schedule for HARD rate-limit / auth signals (429, 403).
     *  Index = consecutiveFailures-1, clamped. */
    private val hardBackoffSchedule = longArrayOf(
        5_000L,      // 1st failure → 5s
        15_000L,     // 2nd → 15s
        30_000L,     // 3rd → 30s
        60_000L,     // 4th → 1 min
        120_000L,    // 5th → 2 min
        300_000L,    // 6th+ → 5 min cap
    )

    /** V5.0.4020 — SOFT backoff schedule for TRANSIENT codes (5xx / 408
     *  Request Timeout / 425 Too Early). The previous schedule treated a
     *  flaky upstream the same as a paid-tier 429, which left dexscreener
     *  and geckoterminal stuck in 5-min lockouts after a single boot
     *  hiccup (snapshot sr=0% with only 5-17 attempts). Per operator P0
     *  doctrine "use the whole api stack as its intended for", a single
     *  503 must not silence a non-rate-limited provider for 5 minutes.
     *  Soft schedule caps at 30s.
     *
     *  V5.0.6203 — CIRCUIT BREAKER TIER for repeat-offender providers.
     *  Report 2026-07-08 21:13 showed dexscreener SR=22% with 950 5xx
     *  vs 273 successes — the SOFT schedule kept probing every 30s and
     *  eating scanner cycle time (max=45.8s). New tier applied only after
     *  n >= 8 consecutive failures: 60s lockout, then 120s, then 300s cap.
     *  Fast-fail path so hot scan cycles don't wait on a dead upstream.
     */
    private val softBackoffSchedule = longArrayOf(
        2_000L,
        5_000L,
        10_000L,
        20_000L,
        30_000L,
    )
    private val softCircuitBreakerSchedule = longArrayOf(
        60_000L,   // n=8  → 1 min
        120_000L,  // n=9  → 2 min
        300_000L,  // n=10+ → 5 min cap
    )

    /** Legacy alias for any code reading the old `backoffSchedule`. */
    private val backoffSchedule get() = hardBackoffSchedule

    private fun key(host: String): String = host.trim().lowercase()
    private fun stateFor(host: String): State =
        state.getOrPut(key(host)) { State() }

    /**
     * Mark a 4xx / 5xx failure. 429 and 403 are the strongest signals
     * (paid-tier rate limit, auth refused). 5xx is also backed off because
     * a flailing upstream is no better than a rate-limited one.
     */
    fun markFailure(host: String, code: Int) {
        try {
            if (host.isBlank()) return
            // Only back off on response codes that signal "stop calling me".
            // 4xx (client errors) and 5xx (server errors). 401/404 are
            // included even though they aren't strict rate-limit signals —
            // banging on the same dead URL serves nobody.
            if (code !in 400..599) return
            val s = stateFor(host)
            val n = s.consecutiveFailures.incrementAndGet()
            s.lastFailureCode.set(code)
            // V5.0.4020 — code-aware schedule selection.
            //   429 / 403            → HARD schedule (5s..300s)
            //   401 / 404            → MILD; treat as 1-step hard but cap at 30s
            //   5xx / 408 / 425      → SOFT schedule (2s..30s, never 5min)
            //   other 4xx            → SOFT to keep the provider in rotation
            val isHard = (code == 429 || code == 403)
            // V5.0.6203 — CIRCUIT BREAKER: after 8 consecutive SOFT failures,
            // upgrade to circuit-breaker schedule (60s → 5min cap). Prevents
            // hot scan cycles from waiting on chronically-degraded providers
            // (report showed dexscreener sr=22% s=273 5xx=950).
            val useCircuitBreaker = !isHard && n >= 8
            val schedule = when {
                isHard -> hardBackoffSchedule
                useCircuitBreaker -> softCircuitBreakerSchedule
                else -> softBackoffSchedule
            }
            val idx = if (useCircuitBreaker) (n - 8).coerceIn(0, schedule.size - 1) else (n - 1).coerceIn(0, schedule.size - 1)
            val baseDelay = schedule[idx]
            val effectiveDelayMs = when (code) {
                429, 403 -> maxOf(baseDelay, 30_000L)
                401, 404 -> minOf(baseDelay, 30_000L)
                else     -> baseDelay
            }
            val until = System.currentTimeMillis() + effectiveDelayMs
            s.lockoutUntilMs.set(until)
            s.totalLockouts.incrementAndGet()
            if (n == 1 || n % 5 == 0) {
                try {
                    ForensicLogger.lifecycle(
                        "API_BACKOFF_ARMED",
                        "host=${key(host)} code=$code n=$n untilSec=${effectiveDelayMs / 1000} mode=${if (isHard) "HARD" else if (useCircuitBreaker) "CIRCUIT_BREAKER_6203" else "SOFT"}"
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    /** Mark a 2xx success. Resets the consecutive-failure counter. */
    fun markSuccess(host: String) {
        try {
            if (host.isBlank()) return
            val s = state[key(host)] ?: return
            if (s.consecutiveFailures.get() > 0) {
                s.consecutiveFailures.set(0)
                s.lockoutUntilMs.set(0L)
                try {
                    ForensicLogger.lifecycle(
                        "API_BACKOFF_CLEARED",
                        "host=${key(host)}"
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    /** True if the host is currently in backoff lockout.
     *
     *  V5.0.4020 — HALF-OPEN PROBE. When the lockout has been in effect
     *  for ≥30s AND the schedule index suggests we're in the SOFT range,
     *  return `false` for ONE call (the next caller) so a recovering
     *  provider gets a chance to prove itself instead of staying frozen
     *  for 5 minutes after a single transient blip. Hard (429/403) holds
     *  always honor the full lockout. */
    fun isLockedOut(host: String): Boolean {
        return try {
            val s = state[key(host)] ?: return false
            val until = s.lockoutUntilMs.get()
            val now = System.currentTimeMillis()
            if (now >= until) return false
            val remaining = until - now
            val lastCode = s.lastFailureCode.get()
            // Hard signals (429/403) — honor full lockout.
            if (lastCode == 429 || lastCode == 403) return true
            // Soft signals — once the lockout has been live > 30s AND
            // > 10s of remaining time, allow ONE probe by atomically
            // clearing the lockout marker. The caller will record
            // success/failure and the state will re-arm accordingly.
            val totalDuration = remaining + 30_000L
            if (remaining > 10_000L && totalDuration > 30_000L) {
                // Use compareAndSet on the until value to ensure only one
                // probe slips through. If we lose the race, stay locked.
                if (s.lockoutUntilMs.compareAndSet(until, now + 5_000L)) {
                    try {
                        ForensicLogger.lifecycle(
                            "API_BACKOFF_HALF_OPEN_PROBE",
                            "host=${key(host)} lastCode=$lastCode remainingMs=$remaining"
                        )
                    } catch (_: Throwable) {}
                    return false
                }
                return true
            }
            true
        } catch (_: Throwable) { false }
    }

    /** ms remaining in lockout, or 0 if not locked out. */
    fun lockoutRemainingMs(host: String): Long {
        return try {
            val s = state[key(host)] ?: return 0L
            (s.lockoutUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)
        } catch (_: Throwable) { 0L }
    }

    /** For the in-app diagnostic dump. */
    fun snapshot(): Map<String, Triple<Int, Long, Int>> {
        val out = HashMap<String, Triple<Int, Long, Int>>()
        val now = System.currentTimeMillis()
        state.forEach { (host, s) ->
            val n = s.consecutiveFailures.get()
            val remaining = (s.lockoutUntilMs.get() - now).coerceAtLeast(0L)
            val totalLockouts = s.totalLockouts.get().toInt()
            out[host] = Triple(n, remaining, totalLockouts)
        }
        return out
    }
}

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

    /** Backoff schedule (ms). Index = consecutiveFailures-1, clamped. */
    private val backoffSchedule = longArrayOf(
        5_000L,      // 1st failure → 5s
        15_000L,     // 2nd → 15s
        30_000L,     // 3rd → 30s
        60_000L,     // 4th → 1 min
        120_000L,    // 5th → 2 min
        300_000L,    // 6th+ → 5 min cap
    )

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
            val idx = (n - 1).coerceIn(0, backoffSchedule.size - 1)
            val delayMs = backoffSchedule[idx]
            // 429 and 403 are the strongest signals → use max delay
            // (skip ahead on the schedule on first occurrence of these).
            val effectiveDelayMs = when (code) {
                429, 403 -> maxOf(delayMs, 30_000L)
                else -> delayMs
            }
            val until = System.currentTimeMillis() + effectiveDelayMs
            s.lockoutUntilMs.set(until)
            s.totalLockouts.incrementAndGet()
            if (n == 1 || n % 5 == 0) {
                try {
                    ForensicLogger.lifecycle(
                        "API_BACKOFF_ARMED",
                        "host=${key(host)} code=$code n=$n untilSec=${effectiveDelayMs / 1000}"
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

    /** True if the host is currently in backoff lockout. */
    fun isLockedOut(host: String): Boolean {
        return try {
            val s = state[key(host)] ?: return false
            System.currentTimeMillis() < s.lockoutUntilMs.get()
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

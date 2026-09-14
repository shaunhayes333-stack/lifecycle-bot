package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Reactive per-provider backoff.
 *
 * V5.0.6758 separates rate-limit/quota failures from transient upstream errors.
 * A 429 is not a 2-second network wobble: retrying it every 30 seconds from many
 * concurrent callers recreates the same provider storm and steals trading-loop
 * time. 429s therefore receive a much longer exponential quiet period, while
 * 5xx/network-style HTTP failures remain quick to half-open and recover.
 *
 * All methods are fail-open: a bookkeeping failure must never become a trading
 * kill switch.
 */
object ApiBackoff {

    private data class State(
        val consecutiveFailures: AtomicInteger = AtomicInteger(0),
        val lockoutUntilMs: AtomicLong = AtomicLong(0L),
        val lastFailureCode: AtomicInteger = AtomicInteger(0),
        val totalLockouts: AtomicLong = AtomicLong(0L),
    )

    private val state = ConcurrentHashMap<String, State>()

    // V5.0.6758 — dedicated quota/rate schedule. A provider that explicitly
    // answers 429 must be given time for its minute/token window to recover.
    private val rateLimitSchedule = longArrayOf(
        120_000L,    // 1st 429 -> 2 min
        300_000L,    // 2nd -> 5 min
        600_000L,    // 3rd -> 10 min
        900_000L,    // 4th -> 15 min
        1_800_000L,  // 5th+ -> 30 min
    )

    // Auth/forbidden failures are generally configuration/quota state, not
    // latency. Back off firmly without permanently disabling the provider.
    private val authBackoffSchedule = longArrayOf(
        60_000L,
        120_000L,
        300_000L,
        600_000L,
    )

    // Transient 5xx/408/425/other HTTP failures recover aggressively.
    private val softBackoffSchedule = longArrayOf(
        2_000L,
        5_000L,
        10_000L,
        20_000L,
        30_000L,
    )

    private fun key(host: String): String = host.trim().lowercase()
    private fun stateFor(host: String): State = state.getOrPut(key(host)) { State() }

    fun markFailure(host: String, code: Int) {
        try {
            if (host.isBlank() || code !in 400..599) return
            val s = stateFor(host)
            val n = s.consecutiveFailures.incrementAndGet()
            s.lastFailureCode.set(code)

            val schedule = when (code) {
                429 -> rateLimitSchedule
                401, 403 -> authBackoffSchedule
                else -> softBackoffSchedule
            }
            val idx = (n - 1).coerceIn(0, schedule.lastIndex)
            val delayMs = schedule[idx]
            val until = System.currentTimeMillis() + delayMs
            s.lockoutUntilMs.accumulateAndGet(until) { old, fresh -> maxOf(old, fresh) }
            s.totalLockouts.incrementAndGet()

            if (n == 1 || n % 5 == 0) {
                try {
                    ForensicLogger.lifecycle(
                        "API_BACKOFF_ARMED",
                        "host=${key(host)} code=$code n=$n untilSec=${delayMs / 1000} " +
                            "mode=${when (code) { 429 -> "RATE_LIMIT"; 401, 403 -> "AUTH"; else -> "SOFT" }}",
                    )
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    fun markSuccess(host: String) {
        try {
            if (host.isBlank()) return
            val s = state[key(host)] ?: return
            if (s.consecutiveFailures.get() > 0 || s.lockoutUntilMs.get() > 0L) {
                s.consecutiveFailures.set(0)
                s.lockoutUntilMs.set(0L)
                s.lastFailureCode.set(0)
                try { ForensicLogger.lifecycle("API_BACKOFF_CLEARED", "host=${key(host)}") } catch (_: Throwable) {}
            }
        } catch (_: Throwable) { /* fail-open */ }
    }

    /**
     * True while provider should not be called.
     *
     * RATE_LIMIT/AUTH states honour the full quiet window. Transient failures
     * may half-open once so recovering free providers re-enter rotation quickly.
     */
    fun isLockedOut(host: String): Boolean {
        return try {
            val s = state[key(host)] ?: return false
            val until = s.lockoutUntilMs.get()
            val now = System.currentTimeMillis()
            if (now >= until) return false

            val lastCode = s.lastFailureCode.get()
            if (lastCode == 429 || lastCode == 401 || lastCode == 403) return true

            val remaining = until - now
            if (remaining > 10_000L) {
                // One soft half-open probe. CAS prevents a fan-out stampede.
                if (s.lockoutUntilMs.compareAndSet(until, now + 5_000L)) {
                    try {
                        ForensicLogger.lifecycle(
                            "API_BACKOFF_HALF_OPEN_PROBE",
                            "host=${key(host)} lastCode=$lastCode remainingMs=$remaining",
                        )
                    } catch (_: Throwable) {}
                    return false
                }
            }
            true
        } catch (_: Throwable) { false }
    }

    fun lockoutRemainingMs(host: String): Long {
        return try {
            val s = state[key(host)] ?: return 0L
            (s.lockoutUntilMs.get() - System.currentTimeMillis()).coerceAtLeast(0L)
        } catch (_: Throwable) { 0L }
    }

    fun snapshot(): Map<String, Triple<Int, Long, Int>> {
        val out = HashMap<String, Triple<Int, Long, Int>>()
        val now = System.currentTimeMillis()
        state.forEach { (host, s) ->
            out[host] = Triple(
                s.consecutiveFailures.get(),
                (s.lockoutUntilMs.get() - now).coerceAtLeast(0L),
                s.totalLockouts.get().toInt(),
            )
        }
        return out
    }
}

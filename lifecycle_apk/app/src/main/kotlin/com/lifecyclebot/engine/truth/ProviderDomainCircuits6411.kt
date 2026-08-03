package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6411 §3.1 + §3.6 — SPLIT-DOMAIN CIRCUIT BREAKERS.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "A provider failure in one domain must affect only that domain.
 *  Jupiter quote failure must NOT disable Pump.fun direct. Helius
 *  read degradation must NOT disable a healthy fallback RPC send."
 *
 * DESIGN
 * ──────
 * Every adapter/provider gets an isolated circuit breaker keyed by
 * [ExecutionAdapter6411]. Policy:
 *
 *   open  after  3 consecutive transport failures OR
 *                2 consecutive hard rate-limits    OR
 *                5 failures within a rolling 60s window
 *   half-open   one controlled probe only
 *   close       two consecutive valid successes
 *   min-open    20 seconds
 *   max-backoff 5 minutes
 *
 * Jupiter being open must NOT affect PUMP_FUN_DIRECT. This is the
 * single most important fix for the "0 wallet buys" incident.
 */
object ProviderDomainCircuits6411 {

    enum class State { CLOSED, HALF_OPEN, OPEN }

    private const val OPEN_MIN_MS = 20_000L
    private const val OPEN_MAX_MS = 5L * 60_000L
    private const val ROLLING_WINDOW_MS = 60_000L
    private const val OPEN_FAILURES_STREAK = 3
    private const val OPEN_RATE_LIMIT_STREAK = 2
    private const val OPEN_ROLLING_FAILS = 5
    private const val CLOSE_SUCCESS_STREAK = 2

    private data class Breaker(
        val state: AtomicReference<State> = AtomicReference(State.CLOSED),
        val consecFailures: AtomicInteger = AtomicInteger(0),
        val consecSuccesses: AtomicInteger = AtomicInteger(0),
        val consecRateLimits: AtomicInteger = AtomicInteger(0),
        val lastSuccessMs: AtomicLong = AtomicLong(0L),
        val lastFailureMs: AtomicLong = AtomicLong(0L),
        val openedAtMs: AtomicLong = AtomicLong(0L),
        val nextProbeMs: AtomicLong = AtomicLong(0L),
        val rollingFailures: java.util.concurrent.ConcurrentLinkedDeque<Long> =
            java.util.concurrent.ConcurrentLinkedDeque(),
    )

    private val breakers = ConcurrentHashMap<ExecutionAdapter6411, Breaker>()

    private fun get(adapter: ExecutionAdapter6411): Breaker =
        breakers.getOrPut(adapter) { Breaker() }

    fun state(adapter: ExecutionAdapter6411): State = get(adapter).state.get()

    /** True when the adapter may be attempted right now (closed or eligible half-open probe). */
    fun isAvailable(adapter: ExecutionAdapter6411): Boolean {
        val b = get(adapter)
        val s = b.state.get()
        if (s == State.CLOSED) return true
        if (s == State.OPEN) {
            val now = System.currentTimeMillis()
            if (now >= b.nextProbeMs.get()) {
                if (b.state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    try {
                        ForensicLogger.lifecycle(
                            "PROVIDER_DOMAIN_HALF_OPEN_6411",
                            "adapter=$adapter openedAgoMs=${now - b.openedAtMs.get()}",
                        )
                        PipelineHealthCollector.labelInc("PROVIDER_DOMAIN_HALF_OPEN_6411")
                    } catch (_: Throwable) {}
                    return true
                }
            }
            return false
        }
        return true // half-open — allow the single probe
    }

    fun recordSuccess(adapter: ExecutionAdapter6411) {
        val b = get(adapter)
        b.lastSuccessMs.set(System.currentTimeMillis())
        b.consecFailures.set(0)
        b.consecRateLimits.set(0)
        val n = b.consecSuccesses.incrementAndGet()
        val prior = b.state.get()
        if (prior != State.CLOSED && n >= CLOSE_SUCCESS_STREAK) {
            if (b.state.compareAndSet(prior, State.CLOSED)) {
                try {
                    ForensicLogger.lifecycle(
                        "PROVIDER_DOMAIN_CIRCUIT_CLOSED_6411",
                        "adapter=$adapter successes=$n from=$prior",
                    )
                    PipelineHealthCollector.labelInc("PROVIDER_DOMAIN_CIRCUIT_CLOSED_6411")
                } catch (_: Throwable) {}
            }
        }
    }

    fun recordFailure(adapter: ExecutionAdapter6411, reason: String, rateLimited: Boolean = false) {
        val b = get(adapter)
        val now = System.currentTimeMillis()
        b.lastFailureMs.set(now)
        b.consecSuccesses.set(0)
        val consecFails = b.consecFailures.incrementAndGet()
        val consecRateLimits = if (rateLimited) b.consecRateLimits.incrementAndGet() else b.consecRateLimits.get()
        // Rolling window
        b.rollingFailures.addLast(now)
        while (true) {
            val head = b.rollingFailures.peekFirst() ?: break
            if (now - head > ROLLING_WINDOW_MS) b.rollingFailures.pollFirst() else break
        }
        val rollingSize = b.rollingFailures.size
        val shouldOpen = consecFails >= OPEN_FAILURES_STREAK ||
            consecRateLimits >= OPEN_RATE_LIMIT_STREAK ||
            rollingSize >= OPEN_ROLLING_FAILS
        if (shouldOpen) {
            val prior = b.state.get()
            if (prior != State.OPEN && b.state.compareAndSet(prior, State.OPEN)) {
                val backoff = computeBackoff(b)
                b.openedAtMs.set(now)
                b.nextProbeMs.set(now + backoff)
                try {
                    ForensicLogger.lifecycle(
                        "PROVIDER_DOMAIN_CIRCUIT_OPEN_6411",
                        "adapter=$adapter reason=$reason consecFails=$consecFails consecRateLimits=$consecRateLimits rollingFails=$rollingSize backoffMs=$backoff",
                    )
                    PipelineHealthCollector.labelInc("PROVIDER_DOMAIN_CIRCUIT_OPEN_6411")
                } catch (_: Throwable) {}
            }
        }
    }

    private fun computeBackoff(b: Breaker): Long {
        val fails = b.consecFailures.get().coerceAtLeast(1)
        val exp = OPEN_MIN_MS * (1L shl (fails - 1).coerceIn(0, 5))
        return exp.coerceIn(OPEN_MIN_MS, OPEN_MAX_MS)
    }

    fun statusLine(): String {
        val parts = ExecutionAdapter6411.values().map { adapter ->
            val b = get(adapter)
            val s = b.state.get()
            "${adapter.name}=${s.name.first()}(f${b.consecFailures.get()}/s${b.consecSuccesses.get()})"
        }
        return parts.joinToString(" ")
    }

    internal fun resetForTest() {
        breakers.clear()
    }
}

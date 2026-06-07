package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.9.856 — Passive API health tracker.
 *
 * Companion to KeyValidator (V5.9.855). Where KeyValidator answers
 * "is this key still authorized?", ApiHealthMonitor answers
 * "is this host still reachable?".
 *
 * Tracks per-host counters: successes, failures by class (4xx/5xx/network),
 * average latency, last-seen-success timestamp. Snapshot is consumed by
 * UniverseHealthActivity / Pipeline Health panel.
 *
 * USAGE
 * -----
 *   val start = System.currentTimeMillis()
 *   try {
 *       http.newCall(req).execute().use { resp ->
 *           ApiHealthMonitor.record("dexscreener", resp.code, System.currentTimeMillis()-start)
 *           ...
 *       }
 *   } catch (e: Exception) {
 *       ApiHealthMonitor.recordNetworkError("dexscreener", e.message)
 *   }
 *
 * DOCTRINE
 * - Pure observability. Doesn't gate anything by itself — that's KeyValidator's job.
 * - Bounded — keeps recent 50 latency samples per host (sliding window).
 * - Thread-safe — ConcurrentHashMap + Atomics, no locks on hot path.
 */
object ApiHealthMonitor {
    private const val LATENCY_WINDOW = 50

    data class HostStats(
        val successes: AtomicInteger = AtomicInteger(0),
        val failures4xx: AtomicInteger = AtomicInteger(0),
        val failures5xx: AtomicInteger = AtomicInteger(0),
        val networkErrors: AtomicInteger = AtomicInteger(0),
        val lastSuccessMs: AtomicLong = AtomicLong(0L),
        val lastFailureMs: AtomicLong = AtomicLong(0L),
        val lastErrorMessage: java.util.concurrent.atomic.AtomicReference<String?> =
            java.util.concurrent.atomic.AtomicReference(null),
        val latencyRing: java.util.concurrent.ConcurrentLinkedDeque<Long> =
            java.util.concurrent.ConcurrentLinkedDeque(),
    ) {
        fun avgLatencyMs(): Double {
            val list = latencyRing.toList()
            return if (list.isEmpty()) 0.0 else list.average()
        }

        fun successRate(): Double {
            val total = successes.get() + failures4xx.get() + failures5xx.get() + networkErrors.get()
            return if (total == 0) 1.0 else successes.get().toDouble() / total
        }
    }

    private val hosts = ConcurrentHashMap<String, HostStats>()

    // V5.9.1395 — P1-5 Scanner cycle budget + per-provider circuit break.
    // Spec: 'per-provider timeout budget, hard circuit-break repeated 4xx
    // providers, stop unlimited retries in scanner cycle. Target avg <12s.'
    //
    // Per-host 4xx circuit-break delegates to [ApiBackoff.isLockedOut]
    // (V5.9.1024 — same exponential schedule, same forensic events). We
    // don't maintain a parallel counter; we just expose a uniform API for
    // scanners to call. Cycle-level budget is the new bit: scanner code
    // calls [cycleStart] when a scanner cycle begins and [shouldAbortCycle]
    // periodically to bail out before the 12s soft cap is exceeded.
    private const val CYCLE_BUDGET_DEFAULT_MS = 12_000L
    @Volatile private var cycleStartMs: Long = 0L
    private val cyclesAborted = AtomicLong(0L)
    private val cyclesCompleted = AtomicLong(0L)

    /** True when ApiBackoff has the host in lockout — caller should skip. */
    fun isCircuitOpen(host: String): Boolean = try {
        ApiBackoff.isLockedOut(host)
    } catch (_: Throwable) { false }

    fun cycleStart() {
        cycleStartMs = System.currentTimeMillis()
    }

    fun cycleElapsedMs(): Long {
        val s = cycleStartMs
        return if (s == 0L) 0L else System.currentTimeMillis() - s
    }

    /**
     * Returns true once the cycle has exceeded [budgetMs]. Scanner loops
     * should check this between provider calls and bail out cleanly to
     * keep avg cycle <12s under provider degradation.
     */
    fun shouldAbortCycle(budgetMs: Long = CYCLE_BUDGET_DEFAULT_MS): Boolean {
        if (cycleStartMs == 0L) return false
        val elapsed = System.currentTimeMillis() - cycleStartMs
        return elapsed > budgetMs
    }

    fun cycleComplete(aborted: Boolean = false) {
        if (aborted) cyclesAborted.incrementAndGet() else cyclesCompleted.incrementAndGet()
        cycleStartMs = 0L
    }

    fun cycleBudgetSummary(): String {
        val a = cyclesAborted.get()
        val c = cyclesCompleted.get()
        val total = a + c
        val openHosts = ApiBackoff.snapshot()
            .filter { it.value.second > 0L }
            .keys
            .sorted()
        return "cycles total=$total completed=$c aborted=$a | circuits open=${openHosts.joinToString(",").ifBlank { "none" }}"
    }

    private fun stats(host: String): HostStats =
        hosts.getOrPut(host.lowercase()) { HostStats() }

    /** Record a completed HTTP response. */
    fun record(host: String, httpStatus: Int, latencyMs: Long = 0L, errorBody: String? = null) {
        val st = stats(host)
        when (httpStatus) {
            in 200..399 -> {
                st.successes.incrementAndGet()
                st.lastSuccessMs.set(System.currentTimeMillis())
            }
            in 400..499 -> {
                st.failures4xx.incrementAndGet()
                st.lastFailureMs.set(System.currentTimeMillis())
                st.lastErrorMessage.set(errorBody?.take(140))
            }
            in 500..599 -> {
                st.failures5xx.incrementAndGet()
                st.lastFailureMs.set(System.currentTimeMillis())
                st.lastErrorMessage.set(errorBody?.take(140))
            }
            else -> {
                st.networkErrors.incrementAndGet()
                st.lastFailureMs.set(System.currentTimeMillis())
                st.lastErrorMessage.set(errorBody?.take(140))
            }
        }
        if (latencyMs > 0L) {
            st.latencyRing.addLast(latencyMs)
            while (st.latencyRing.size > LATENCY_WINDOW) st.latencyRing.pollFirst()
        }
    }

    /** Record a network-layer failure (IOException, timeout, DNS, ConnectException). */
    fun recordNetworkError(host: String, errorMessage: String? = null) {
        val st = stats(host)
        st.networkErrors.incrementAndGet()
        st.lastFailureMs.set(System.currentTimeMillis())
        st.lastErrorMessage.set(errorMessage?.take(140))
    }

    /** UI snapshot. Returns one entry per tracked host. */
    fun snapshot(): Map<String, HostStats> = hosts.toMap()

    /** Convenience helper for one-line summary tile. */
    fun summary(host: String): String {
        val s = hosts[host.lowercase()] ?: return "[$host] no samples"
        val sr = (s.successRate() * 100).toInt()
        val avg = s.avgLatencyMs().toInt()
        return "[$host] sr=$sr%  avg=${avg}ms  s=${s.successes.get()}  4xx=${s.failures4xx.get()}  5xx=${s.failures5xx.get()}  net=${s.networkErrors.get()}"
    }

    /** Reset — exposed so QA can clear state between test runs. */
    fun reset() {
        hosts.clear()
        cycleStartMs = 0L
        cyclesAborted.set(0L)
        cyclesCompleted.set(0L)
    }
}

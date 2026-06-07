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
    fun reset() = hosts.clear()
}

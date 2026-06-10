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
                st.lastErrorMessage.set(redactSecrets(errorBody)?.take(140))
            }
            in 500..599 -> {
                st.failures5xx.incrementAndGet()
                st.lastFailureMs.set(System.currentTimeMillis())
                st.lastErrorMessage.set(redactSecrets(errorBody)?.take(140))
            }
            else -> {
                st.networkErrors.incrementAndGet()
                st.lastFailureMs.set(System.currentTimeMillis())
                st.lastErrorMessage.set(redactSecrets(errorBody)?.take(140))
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
        st.lastErrorMessage.set(redactSecrets(errorMessage)?.take(140))
    }

    // ── V5.9.1484 — SECRET REDACTION ───────────────────────────────────
    // Snapshot 5.0.3490 leaked a Groq API key into the health export: the
    // provider's 4xx error BODY embedded the key, and we stored it verbatim in
    // lastErrorMessage, which is printed in the pipeline-health snapshot. NEVER
    // persist or surface raw provider error bodies — they routinely echo the
    // Authorization header / key. Strip anything that looks like a key/token
    // before it can reach a log, a snapshot, or a journal.
    private val SECRET_PATTERNS = listOf(
        Regex("""(?i)(api[_-]?key|key|token|bearer|authorization|secret)\s*[:=]\s*[A-Za-z0-9._\-]{6,}"""),
        Regex("""(?i)\bsk-[A-Za-z0-9]{8,}"""),          // OpenAI-style
        Regex("""(?i)\bgsk_[A-Za-z0-9]{8,}"""),         // Groq-style
        Regex("""\b[A-Za-z0-9_\-]{24,}\b"""),          // long opaque blobs
    )
    private fun redactSecrets(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        var out = raw
        for (re in SECRET_PATTERNS) out = re.replace(out) { m ->
            // Keep a short prefix so the error is still debuggable, mask the rest.
            val t = m.value
            val cut = t.indexOfFirst { it == ':' || it == '=' }
            if (cut in 0 until t.length - 1) t.substring(0, cut + 1) + " [REDACTED]"
            else "[REDACTED]"
        }
        return out
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

package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6727 — §PROVIDER_INFERENCE_HEALTH.
 *
 * Operator diagnostic from 6726: "the Groq key validator says HTTP 200 /
 * HEALTHY, while actual inference telemetry says 0% success due to rate
 * limiting." Same pattern applies to any provider whose health check
 * validates connectivity/authentication rather than usable capacity.
 *
 * This authority owns the single-source-of-truth for provider health
 * defined as ACTUAL INFERENCE OUTCOME RATIO, not "did we get an HTTP
 * response". Callers record every attempt (`recordSuccess` /
 * `recordFailure`) and the authority exposes a rolling-window success
 * ratio plus a `isHealthy()` verdict downstream consumers key on
 * without re-implementing the ratio math.
 *
 * Fail-safe: an unattached provider that has never recorded anything
 * returns `isHealthy() = true` (fail-open) so a first-boot cold state
 * cannot brick discovery. A provider that has recorded activity and
 * has success ratio under the floor returns `isHealthy() = false`,
 * with a break glass timer so we can attempt reprobe periodically.
 */
object ProviderInferenceHealth6727 {

    /** Minimum successful-inference ratio to be considered healthy. */
    private const val HEALTHY_RATIO_FLOOR = 0.30
    /** Minimum recorded samples before the ratio is trusted. */
    private const val MIN_SAMPLES = 5
    /** Reprobe window in ms — after this a fresh sample can flip verdict. */
    private const val REPROBE_MS = 60_000L

    data class Health(
        val provider: String,
        val successes: Long,
        val failures: Long,
        val successRatio: Double,
        val lastUpdateMs: Long,
        val isHealthy: Boolean,
    )

    private data class Counters(
        val successes: AtomicLong = AtomicLong(0),
        val failures: AtomicLong = AtomicLong(0),
        val lastUpdateMs: AtomicLong = AtomicLong(0),
    )

    private val byProvider = ConcurrentHashMap<String, Counters>()

    fun recordSuccess(provider: String) {
        val p = provider.trim().uppercase().take(24)
        if (p.isBlank()) return
        val c = byProvider.computeIfAbsent(p) { Counters() }
        c.successes.incrementAndGet()
        c.lastUpdateMs.set(System.currentTimeMillis())
        try { PipelineHealthCollector.labelInc("PROVIDER_INFERENCE_SUCCESS_6727_$p") } catch (_: Throwable) {}
    }

    fun recordFailure(provider: String, reason: String = "") {
        val p = provider.trim().uppercase().take(24)
        if (p.isBlank()) return
        val c = byProvider.computeIfAbsent(p) { Counters() }
        c.failures.incrementAndGet()
        c.lastUpdateMs.set(System.currentTimeMillis())
        try { PipelineHealthCollector.labelInc("PROVIDER_INFERENCE_FAILURE_6727_$p") } catch (_: Throwable) {}
    }

    fun health(provider: String): Health {
        val p = provider.trim().uppercase().take(24)
        val c = byProvider[p]
        if (c == null) {
            return Health(p, 0, 0, 1.0, 0, isHealthy = true) // fail-open when unknown
        }
        val s = c.successes.get()
        val f = c.failures.get()
        val total = s + f
        val ratio = if (total > 0) s.toDouble() / total.toDouble() else 1.0
        val healthy = total < MIN_SAMPLES || ratio >= HEALTHY_RATIO_FLOOR
        return Health(p, s, f, ratio, c.lastUpdateMs.get(), healthy)
    }

    fun isHealthy(provider: String): Boolean = health(provider).isHealthy

    /** Should we retry a currently-unhealthy provider? True after REPROBE_MS since last record. */
    fun shouldReprobe(provider: String): Boolean {
        val h = health(provider)
        if (h.isHealthy) return false
        return System.currentTimeMillis() - h.lastUpdateMs >= REPROBE_MS
    }

    /** Snapshot all known providers for diagnostic display. */
    fun snapshot(): List<Health> = byProvider.keys.sorted().map { health(it) }
}

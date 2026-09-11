package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** Actual parsed inference outcomes, independent of credential/connectivity checks.
 * Unknown providers may be tried; a capacity failure never vetoes a trade. */
object ProviderInferenceHealth6727 {
    private const val WINDOW_MS = 300_000L
    private const val MAX_SAMPLES = 100
    private const val MIN_SAMPLES = 5
    private const val REPROBE_MS = 60_000L
    private data class Attempt(val at: Long, val ok: Boolean, val reason: String)
    private val attempts = ConcurrentHashMap<String, ArrayDeque<Attempt>>()
    data class Health(val provider: String, val successes: Long, val failures: Long,
                      val successRatio: Double, val lastUpdateMs: Long, val isHealthy: Boolean)
    private fun key(provider: String) = provider.trim().uppercase().take(24)
    fun recordSuccess(provider: String) = record(provider, true, "OK")
    fun recordFailure(provider: String, reason: String = "") = record(provider, false, reason)
    private fun record(provider: String, ok: Boolean, reason: String) {
        val p = key(provider)
        if (p.isBlank()) return
        val now = System.currentTimeMillis()
        val q = attempts.computeIfAbsent(p) { ArrayDeque() }
        synchronized(q) {
            q.addLast(Attempt(now, ok, reason.take(48)))
            while (q.size > MAX_SAMPLES || (q.isNotEmpty() && now - q.first.at > WINDOW_MS)) q.removeFirst()
        }
        try { PipelineHealthCollector.labelInc("PROVIDER_INFERENCE_${if (ok) "SUCCESS" else "FAILURE"}_6727_$p") } catch (_: Throwable) {}
    }
    fun health(provider: String, nowMs: Long = System.currentTimeMillis()): Health {
        val p = key(provider)
        val q = attempts[p] ?: return Health(p, 0, 0, 1.0, 0, true)
        val rows = synchronized(q) { q.filter { nowMs - it.at in 0..WINDOW_MS } }
        val s = rows.count { it.ok }.toLong(); val f = rows.size.toLong() - s
        val last = rows.lastOrNull()
        val ratio = if (rows.isEmpty()) 1.0 else s.toDouble() / rows.size
        val hardCapacity = last != null && !last.ok && last.reason in setOf("HTTP_401", "HTTP_403", "HTTP_429")
        return Health(p, s, f, ratio, last?.at ?: 0L,
            !hardCapacity && (rows.size < MIN_SAMPLES || ratio >= 0.30))
    }
    fun isHealthy(provider: String): Boolean = health(provider).isHealthy
    fun shouldReprobe(provider: String): Boolean {
        val h = health(provider)
        return !h.isHealthy && System.currentTimeMillis() - h.lastUpdateMs >= REPROBE_MS
    }
    fun snapshot(): List<Health> = attempts.keys.sorted().map { health(it) }
    internal fun resetForTest6734() { attempts.clear() }
}

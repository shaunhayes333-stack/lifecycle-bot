package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/** V5.0.3902 — endpoint-scoped execution provider health. */
object ExecutionEndpointHealth {
    private data class Cooldown(val untilMs: Long, val reason: String, val failures: Int)
    private val disabled = ConcurrentHashMap<String, Cooldown>()
    private fun now() = System.currentTimeMillis()
    private fun key(endpoint: String, mint: String = ""): String = endpoint.uppercase() + if (mint.isBlank()) "" else ":${mint.take(10)}"

    fun disable(endpoint: String, reason: String, cooldownMs: Long = 30_000L, mint: String = "") {
        val k = key(endpoint, mint)
        val old = disabled[k]
        val failures = (old?.failures ?: 0) + 1
        val ttl = if (reason.contains("503") || reason.contains("429") || reason.contains("4xx", true)) maxOf(cooldownMs, 30_000L) else cooldownMs
        disabled[k] = Cooldown(now() + ttl, reason.take(140), failures)
        try { PipelineHealthCollector.labelInc("${endpoint.uppercase()}_DISABLED") } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("EXEC_ENDPOINT_DISABLED", "endpoint=${endpoint.uppercase()} mint=${mint.take(10)} reason=${reason.take(120)} cooldownMs=$ttl failures=$failures") } catch (_: Throwable) {}
    }

    fun isDisabled(endpoint: String, mint: String = ""): Boolean {
        val n = now()
        val keys = if (mint.isBlank()) listOf(key(endpoint)) else listOf(key(endpoint, mint), key(endpoint))
        var locked = false
        for (k in keys) {
            val c = disabled[k] ?: continue
            if (c.untilMs <= n) disabled.remove(k) else locked = true
        }
        return locked
    }

    fun clear(endpoint: String, mint: String = "") {
        disabled.remove(key(endpoint, mint))
        if (mint.isBlank()) disabled.remove(key(endpoint))
    }

    fun reason(endpoint: String, mint: String = ""): String {
        val n = now()
        val keys = if (mint.isBlank()) listOf(key(endpoint)) else listOf(key(endpoint, mint), key(endpoint))
        for (k in keys) {
            val c = disabled[k] ?: continue
            if (c.untilMs > n) return "${c.reason} ttlMs=${c.untilMs - n}"
        }
        return ""
    }
}

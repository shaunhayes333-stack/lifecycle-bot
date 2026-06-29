package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp

/**
 * V5.0.4518 — execution route reliability memory.
 *
 * Endpoint disables / route invalidations are not just logs: repeated venue failures
 * predict quote exhaustion, slippage, and stuck opens. This memory converts recent
 * route failures into a bounded soft size multiplier. It never vetoes and never
 * calls network/LLM; executor hot paths read O(1) cached counters.
 */
object ExecutionRouteReliabilityMemory {
    private data class Cell(var failures: Int = 0, var lastMs: Long = 0L, var reason: String = "")
    private val cells = ConcurrentHashMap<String, Cell>()
    private const val DECAY_MS = 30L * 60L * 1000L

    fun recordFailure(endpoint: String, reason: String, mint: String = "") {
        val key = endpoint.trim().uppercase().ifBlank { "UNKNOWN_ENDPOINT" }
        val now = System.currentTimeMillis()
        val c = cells.getOrPut(key) { Cell() }
        synchronized(c) {
            val decay = if (c.lastMs > 0L) exp(-((now - c.lastMs).coerceAtLeast(0L).toDouble() / DECAY_MS.toDouble())) else 0.0
            c.failures = ((c.failures * decay).toInt() + 1).coerceIn(1, 50)
            c.lastMs = now
            c.reason = reason.take(120)
        }
        try { PipelineHealthCollector.labelInc("ROUTE_RELIABILITY_FAILURE_4518|$key") } catch (_: Throwable) {}
        try { ForensicLogger.lifecycle("ROUTE_RELIABILITY_FAILURE_4518", "endpoint=$key mint=${mint.take(10)} failures=${c.failures} reason=${reason.take(100)}") } catch (_: Throwable) {}
    }

    fun sizeMultiplierForSource(source: String, mint: String = ""): Double {
        val now = System.currentTimeMillis()
        val route = when {
            source.contains("PUMP", true) -> "PUMP_DIRECT_BUILD"
            source.contains("RAYDIUM", true) || source.contains("DEX", true) -> "JUPITER_SWAP"
            else -> "GENERIC_ROUTE"
        }
        val routeFailures = cells[route]?.let { c -> if (now - c.lastMs <= DECAY_MS) c.failures else 0 } ?: 0
        val pumpSimFailures = cells["PUMP_DIRECT_SIM"]?.let { c -> if (now - c.lastMs <= DECAY_MS) c.failures else 0 } ?: 0
        val total = (routeFailures + if (route.startsWith("PUMP")) pumpSimFailures else 0).coerceAtMost(12)
        return when {
            total >= 8 -> 0.62
            total >= 5 -> 0.74
            total >= 3 -> 0.86
            total >= 1 -> 0.94
            else -> 1.0
        }
    }

    fun statusLine(): String {
        val now = System.currentTimeMillis()
        val active = cells.entries
            .mapNotNull { (k, c) -> if (now - c.lastMs <= DECAY_MS) "$k=${c.failures}" else null }
            .take(8)
        return if (active.isEmpty()) "ExecutionRouteReliability: clean" else "ExecutionRouteReliability: " + active.joinToString(" · ") + " soft_size_only=true"
    }
}

package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/** Bounded TTL runtime overlay consumed by scanner/lane/auth/executor hot paths. */
object RuntimeConfigOverlay {
    data class Command(val kind: String, val target: String, val value: String, val reason: String, val expiresAtMs: Long)
    private val commands = ConcurrentHashMap<String, Command>()
    private fun now() = System.currentTimeMillis()
    private fun put(kind: String, target: String, value: String, reason: String, ttlMs: Long) {
        val key = "$kind:${target.uppercase()}"
        commands[key] = Command(kind, target.uppercase(), value, reason.take(180), now() + ttlMs.coerceIn(1_000L, 10 * 60_000L))
        try { ForensicLogger.lifecycle("RUNTIME_MITIGATION_APPLIED", "kind=$kind target=${target.uppercase()} value=$value ttlMs=$ttlMs reason=${reason.take(120)}") } catch (_: Throwable) {}
    }
    fun disableLane(lane: String, reason: String, ttlMs: Long) = put("DISABLE_LANE", lane, "true", reason, ttlMs)
    fun disableScannerSource(source: String, reason: String, ttlMs: Long) = put("DISABLE_SCANNER_SOURCE", source, "true", reason, ttlMs)
    fun quarantineSource(source: String, reason: String, ttlMs: Long) = put("QUARANTINE_SOURCE", source, "true", reason, ttlMs)
    fun reduceScannerConcurrency(value: Int, reason: String, ttlMs: Long) = put("SCANNER_CONCURRENCY", "GLOBAL", value.coerceIn(1, 6).toString(), reason, ttlMs)
    fun pauseTrading(reason: String, ttlMs: Long) = put("PAUSE_TRADING", "GLOBAL", "true", reason, ttlMs)
    fun isLaneDisabled(lane: String): Boolean = active("DISABLE_LANE:${lane.uppercase()}")
    fun isScannerSourceDisabled(source: String): Boolean = active("DISABLE_SCANNER_SOURCE:${source.uppercase()}") || active("QUARANTINE_SOURCE:${source.uppercase()}")
    fun isTradingPaused(): Boolean = active("PAUSE_TRADING:GLOBAL")
    fun scannerConcurrencyCap(): Int = activeCommand("SCANNER_CONCURRENCY:GLOBAL")?.value?.toIntOrNull() ?: 0
    fun activeCommands(): List<Command> { prune(); return commands.values.toList().sortedBy { it.kind + it.target } }
    fun resetForTests() = commands.clear()
    private fun active(key: String): Boolean = activeCommand(key) != null
    private fun activeCommand(key: String): Command? { prune(); return commands[key]?.takeIf { it.expiresAtMs > now() } }
    private fun prune() { val n = now(); commands.entries.removeIf { it.value.expiresAtMs <= n } }
}

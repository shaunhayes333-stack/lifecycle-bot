package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap

/** Bounded TTL runtime overlay consumed by scanner/lane/auth/executor hot paths. */
object RuntimeConfigOverlay {
    /** V5.9.1112 — hard QUALITY-only cage removed after 1109-1111 repairs proved containment. */
    const val HARD_QUALITY_ONLY: Boolean = false
    data class Command(val kind: String, val target: String, val value: String, val reason: String, val expiresAtMs: Long)
    private val commands = ConcurrentHashMap<String, Command>()
    private fun now() = System.currentTimeMillis()
    private fun put(kind: String, target: String, value: String, reason: String, ttlMs: Long) {
        val normalizedTarget = target.uppercase()
        val key = "$kind:$normalizedTarget"
        val n = now()
        val safeTtl = ttlMs.coerceIn(1_000L, 10 * 60_000L)
        val safeReason = reason.take(180)
        val existing = commands[key]
        // V5.9.1131 — idempotent runtime mitigations.
        // RuntimeDoctor runs every loop; if the same mitigation is already active,
        // do not rewrite/extend/log it until it is near expiry. 3098 emitted 656
        // RUNTIME_MITIGATION_APPLIED rows from stale cumulative counters, which
        // became its own main-thread/logging pressure source.
        if (existing != null && existing.expiresAtMs > n && existing.value == value && existing.reason == safeReason) {
            val remaining = existing.expiresAtMs - n
            if (remaining > safeTtl / 3) return
        }
        commands[key] = Command(kind, normalizedTarget, value, safeReason, n + safeTtl)
        try { ForensicLogger.lifecycle("RUNTIME_MITIGATION_APPLIED", "kind=$kind target=$normalizedTarget value=$value ttlMs=$ttlMs reason=${reason.take(120)}") } catch (_: Throwable) {}
    }
    fun disableLane(lane: String, reason: String, ttlMs: Long) = put("DISABLE_LANE", lane, "true", reason, ttlMs)
    fun disableScannerSource(source: String, reason: String, ttlMs: Long) = put("DISABLE_SCANNER_SOURCE", source, "true", reason, ttlMs)
    fun quarantineSource(source: String, reason: String, ttlMs: Long) = put("QUARANTINE_SOURCE", source, "true", reason, ttlMs)
    fun reduceScannerConcurrency(value: Int, reason: String, ttlMs: Long) = put("SCANNER_CONCURRENCY", "GLOBAL", value.coerceIn(1, 6).toString(), reason, ttlMs)
    fun pauseTrading(reason: String, ttlMs: Long) = put("PAUSE_TRADING", "GLOBAL", "true", reason, ttlMs)
    fun disablePreAuth(reason: String, ttlMs: Long) = put("DISABLE_PREAUTH", "GLOBAL", "true", reason, ttlMs)
    fun forcePrimaryLane(lane: String, reason: String, ttlMs: Long) = put("FORCE_PRIMARY_LANE", "GLOBAL", lane.uppercase(), reason, ttlMs)
    private fun paperRuntime(): Boolean = try { RuntimeModeAuthority.isPaper() } catch (_: Throwable) { false }

    // V5.9.1211 — paper money must keep trading/learning. Runtime mitigations
    // that pause entries, disable lanes, disable preauth, or force QUALITY-only
    // are LIVE-only controls. Scanner/API pressure mitigations still apply below
    // because they protect the bot loop without vetoing trader learning.
    fun forcedPrimaryLane(): String? = when {
        paperRuntime() -> null
        HARD_QUALITY_ONLY -> "QUALITY"
        else -> activeCommand("FORCE_PRIMARY_LANE:GLOBAL")?.value
    }
    fun isHardQualityOnlyActive(): Boolean = !paperRuntime() && (HARD_QUALITY_ONLY || normalizeLane(forcedPrimaryLane() ?: "") == "QUALITY")
    // V5.0.4070 — REVERTED lane-amputation. Operator directive: AATE is the
    // highest-information trading deck ever built. Lanes must NOT be hard-
    // disabled. Every lane trades. Toxic/bleeder lanes PIVOT into quality
    // strategies via LiveStylePivotRouter + BleederMemoryRouter + LaneToxicityGuard.
    // The system's AI stack routes and sizes — it does not amputate.
    // V5.9.1405 "never amputate lanes" doctrine RESTORED.
    fun isLaneDisabled(lane: String): Boolean {
        val normalized = normalizeLane(lane)
        // Paper mode: never disable lanes — keep learning.
        if (paperRuntime()) return false
        // Runtime mitigation overlay (existing path — short-TTL emergency only).
        return active("DISABLE_LANE:$normalized")
    }
    fun isPreAuthDisabled(): Boolean = !paperRuntime() && active("DISABLE_PREAUTH:GLOBAL")
    fun isScannerSourceDisabled(source: String): Boolean = active("DISABLE_SCANNER_SOURCE:${source.uppercase()}") || active("QUARANTINE_SOURCE:${source.uppercase()}")
    fun isTradingPaused(): Boolean = !paperRuntime() && active("PAUSE_TRADING:GLOBAL")
    fun scannerConcurrencyCap(): Int = activeCommand("SCANNER_CONCURRENCY:GLOBAL")?.value?.toIntOrNull() ?: 0
    fun activeCommands(): List<Command> {
        prune()
        val base = commands.values.toMutableList()
        if (HARD_QUALITY_ONLY) base += Command("HARD_POLICY", "PRIMARY_LANE", "QUALITY", "operator_quality_only", Long.MAX_VALUE)
        return base.sortedBy { it.kind + it.target }
    }
    fun qualityOnlySummary(): String {
        val disabled = activeCommands().filter { it.kind == "DISABLE_LANE" }.map { it.target }.joinToString("|")
        return "hardQualityOnly=${isHardQualityOnlyActive()} forcedPrimary=${forcedPrimaryLane() ?: "none"} disabled=$disabled"
    }
    fun resetForTests() = commands.clear()
    fun normalizeLane(lane: String): String = lane.uppercase().replace("-", "_").replace(" ", "_").let {
        when (it) {
            "BLUE_CHIP" -> "BLUECHIP"
            "PROJECTSNIPER", "SNIPER" -> "PROJECT_SNIPER"
            "EXPRESS", "SHITCOIN_EXPRESS" -> "EXPRESS"
            else -> it
        }
    }
    private fun active(key: String): Boolean = activeCommand(key) != null
    private fun activeCommand(key: String): Command? { prune(); return commands[key]?.takeIf { it.expiresAtMs > now() } }
    private fun prune() { val n = now(); commands.entries.removeIf { it.value.expiresAtMs <= n } }
}

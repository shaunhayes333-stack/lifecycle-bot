package com.lifecyclebot.engine

import java.security.MessageDigest

object HotfixRules {
    enum class RuleType { DISABLE_LANE, SCANNER_SOURCE_CAP, MIN_LIQUIDITY_FLOOR, REGISTRY_RESTORE_QUARANTINE, MAX_LANE_FANOUT, PAUSE_ON_FAULT }
    data class Rule(val id: String, val version: Int, val type: RuleType, val target: String, val value: String, val expiresAtMs: Long, val rollbackToken: String, val signature: String)
    data class ApplyResult(val applied: Boolean, val reason: String)

    private val active = java.util.concurrent.ConcurrentHashMap<String, Rule>()

    fun signPayload(id: String, version: Int, type: RuleType, target: String, value: String, expiresAtMs: Long, rollbackToken: String): String {
        val payload = "$id|$version|${type.name}|$target|$value|$expiresAtMs|$rollbackToken|AATE_RUNTIME_DOCTOR_V1"
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun isSignatureValid(r: Rule): Boolean = r.signature == signPayload(r.id, r.version, r.type, r.target, r.value, r.expiresAtMs, r.rollbackToken)

    fun apply(rule: Rule): ApplyResult {
        if (rule.expiresAtMs <= System.currentTimeMillis()) return ApplyResult(false, "expired")
        if (!isSignatureValid(rule)) return ApplyResult(false, "bad_signature")
        val existing = active[rule.id]
        if (existing != null && rule.version <= existing.version) return ApplyResult(false, "version_not_newer")
        active[rule.id] = rule
        when (rule.type) {
            RuleType.DISABLE_LANE -> RuntimeRepairState.disableLane(rule.target, "hotfix:${rule.id}")
            RuleType.SCANNER_SOURCE_CAP -> RuntimeRepairState.setScannerConcurrencyCap(rule.value.toIntOrNull() ?: 0, "hotfix:${rule.id}")
            RuleType.MIN_LIQUIDITY_FLOOR -> { /* observed by future intake gate; no unsafe live mutation here */ }
            RuleType.REGISTRY_RESTORE_QUARANTINE -> RuntimeRepairState.disableScannerSource("MEME_REGISTRY_RESTORE", "hotfix:${rule.id}")
            RuleType.MAX_LANE_FANOUT -> { /* diagnostic bounded policy only */ }
            RuleType.PAUSE_ON_FAULT -> RuntimeRepairState.pauseTrading("hotfix:${rule.id}:${rule.target}")
        }
        return ApplyResult(true, "applied")
    }

    fun rollback(id: String, rollbackToken: String): ApplyResult {
        val r = active[id] ?: return ApplyResult(false, "not_found")
        if (r.rollbackToken != rollbackToken) return ApplyResult(false, "bad_rollback_token")
        active.remove(id)
        return ApplyResult(true, "rolled_back")
    }

    fun activeRules(): List<Rule> = active.values.toList()
    fun resetForTests() = active.clear()
}

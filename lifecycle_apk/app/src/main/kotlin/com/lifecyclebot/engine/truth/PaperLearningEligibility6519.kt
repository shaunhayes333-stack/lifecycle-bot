package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap

/** V5.0.6519 — learner purity metadata; never execution authority. */
object PaperLearningEligibility6519 {
    data class Decision(val eligible: Boolean, val reason: String, val atMs: Long = System.currentTimeMillis())
    private val byMint = ConcurrentHashMap<String, Decision>()
    private val byPosition = ConcurrentHashMap<String, Decision>()

    fun record(mint: String, positionId: String, eligible: Boolean, reason: String) {
        val d = Decision(eligible, reason)
        if (mint.isNotBlank()) byMint[mint] = d
        if (positionId.isNotBlank()) byPosition[positionId] = d
        if (byMint.size > 8192) byMint.entries.removeIf { System.currentTimeMillis() - it.value.atMs > 7L * 24L * 60L * 60L * 1000L }
        if (byPosition.size > 8192) byPosition.entries.removeIf { System.currentTimeMillis() - it.value.atMs > 7L * 24L * 60L * 60L * 1000L }
    }

    fun decision(positionId: String?, mint: String): Decision {
        val pid = positionId.orEmpty()
        val recorded = pid.takeIf { it.isNotBlank() }?.let { byPosition[it] } ?: byMint[mint] ?: Decision(true, "DEFAULT_ELIGIBLE")
        if (!recorded.eligible) return recorded
        if (pid.isNotBlank() && !CanonicalPerformanceFilter6395.isCanonicalEligible(pid))
            return Decision(false, "CANONICAL_PERFORMANCE_QUARANTINE:${CanonicalPerformanceFilter6395.reasons(pid).joinToString("+")}")
        val reason = recorded.reason.uppercase()
        val invalid = listOf("STALE", "RESTORED_INVALID", "REPLAY", "DECIMAL", "ORPHAN", "PHANTOM", "UNRESOLVED_BASIS", "ADMINISTRATIVE", "SYNTHETIC_CLOSE").firstOrNull { reason.contains(it) }
        return if (invalid != null) Decision(false, "FORENSIC_ONLY_$invalid") else recorded
    }

    internal fun resetForTest() { byMint.clear(); byPosition.clear() }
}

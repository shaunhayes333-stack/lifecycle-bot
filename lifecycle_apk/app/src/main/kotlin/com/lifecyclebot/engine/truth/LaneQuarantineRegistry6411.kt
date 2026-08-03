package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6411 §10 — LANE QUARANTINE SCOPING.
 *
 * OPERATOR DIRECTIVE (Feb 2026)
 * ─────────────────────────────
 * "Do NOT use one quarantine mechanism for both lane-level poor
 *  performance AND token-specific unsafe state AND provider-data
 *  incompleteness AND execution-adapter unavailability. Every
 *  quarantine must have explicit expiry and owner."
 *
 * DESIGN
 * ──────
 *   • QuarantineScope enum: TOKEN / POOL / LANE / PROVIDER / EXECUTION_ADAPTER
 *   • Record includes scope, scopeId, reason, createdAt, expiresAt,
 *     sourceBuild, evidence, releaseCondition, owner
 *   • Bounded expiry always; no permanent quarantine unless the
 *     manual override reason is set.
 *   • release() / expireAll() / isQuarantined(scope, id) provide the
 *     enforcement surface.
 *
 * §10.4 — poor bucket EV rotates/dampens; it should NOT quarantine
 * a whole lane. This registry is the correct place for lane hard
 * shaping. Lane-wide quarantine is discouraged; per-bucket tactic
 * rotation already exists in [PaperEvBucketGate6405].
 */
object LaneQuarantineRegistry6411 {

    enum class QuarantineScope { TOKEN, POOL, LANE, PROVIDER, EXECUTION_ADAPTER }

    data class Record(
        val scope: QuarantineScope,
        val scopeId: String,
        val reason: String,
        val createdAtMs: Long,
        val expiresAtMs: Long,
        val sourceBuild: String,
        val evidence: String,
        val releaseCondition: String,
        val owner: String,
        val permanentOverride: Boolean = false,
    )

    private val records = ConcurrentHashMap<String, Record>()

    private fun key(scope: QuarantineScope, id: String): String = "${scope.name}|$id"

    /**
     * Register a quarantine. Rejects records with no expiry unless
     * [permanentOverride]=true (must be documented in `reason`).
     */
    fun quarantine(rec: Record): Boolean {
        val now = System.currentTimeMillis()
        if (!rec.permanentOverride && rec.expiresAtMs <= now) {
            try { PipelineHealthCollector.labelInc("LANE_QUARANTINE_REJECTED_NO_EXPIRY_6411") } catch (_: Throwable) {}
            return false
        }
        records[key(rec.scope, rec.scopeId)] = rec
        try {
            ForensicLogger.lifecycle(
                "LANE_QUARANTINE_APPLIED_6411",
                "scope=${rec.scope.name} id=${rec.scopeId.take(20)} reason=${rec.reason.take(60)} " +
                    "ttlMs=${rec.expiresAtMs - rec.createdAtMs} owner=${rec.owner.take(24)} " +
                    "release=${rec.releaseCondition.take(40)} build=${rec.sourceBuild}",
            )
            PipelineHealthCollector.labelInc("LANE_QUARANTINE_APPLIED_${rec.scope.name}_6411")
        } catch (_: Throwable) {}
        return true
    }

    /** Return true when the given scope+id is currently under quarantine. */
    fun isQuarantined(scope: QuarantineScope, id: String): Boolean {
        val rec = records[key(scope, id)] ?: return false
        val now = System.currentTimeMillis()
        if (!rec.permanentOverride && rec.expiresAtMs <= now) {
            records.remove(key(scope, id), rec)
            try { PipelineHealthCollector.labelInc("LANE_QUARANTINE_EXPIRED_${scope.name}_6411") } catch (_: Throwable) {}
            return false
        }
        return true
    }

    fun release(scope: QuarantineScope, id: String, releaseReason: String): Boolean {
        val rec = records.remove(key(scope, id)) ?: return false
        try {
            ForensicLogger.lifecycle(
                "LANE_QUARANTINE_RELEASED_6411",
                "scope=${scope.name} id=${id.take(20)} priorReason=${rec.reason.take(40)} releasedBy=${releaseReason.take(40)}",
            )
            PipelineHealthCollector.labelInc("LANE_QUARANTINE_RELEASED_${scope.name}_6411")
        } catch (_: Throwable) {}
        return true
    }

    fun expireAll() {
        val now = System.currentTimeMillis()
        val it = records.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val r = e.value
            if (!r.permanentOverride && r.expiresAtMs <= now) it.remove()
        }
    }

    fun statusLine(): String {
        val counts = QuarantineScope.values().associateWith { 0 }.toMutableMap()
        for (r in records.values) counts[r.scope] = (counts[r.scope] ?: 0) + 1
        return counts.entries.joinToString(" ") { "${it.key.name}=${it.value}" }
    }

    internal fun resetForTest() {
        records.clear()
    }
}

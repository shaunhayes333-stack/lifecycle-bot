package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6450 §P1 — QUALITY INTAKE TRACE.
 *
 * OPERATOR MANDATE:
 *   QUALITY eval=864 vs QUALITY FDG=7 vs non-QUALITY FDG=759.
 *   "Audit why QUALITY candidates disappear between LANE_EVAL and FDG.
 *    Instrument: QUALITY_INTAKE, QUALITY_METADATA_READY, QUALITY_SCORE_READY,
 *    QUALITY_LANE_ALLOW, QUALITY_FDG, QUALITY_EXEC, QUALITY_EXIT
 *    with candidateId + mint and exactly one terminal reason."
 *
 * DESIGN
 * ──────
 * Compact per-candidate ring. Callers emit `note(candidateId, phase, mint,
 * detail)` at each of the 7 canonical stages. Terminal is either
 * QUALITY_EXEC (successful entry) or QUALITY_ATTRITION (with reason).
 */
object QualityIntakeTrace6450 {

    enum class Phase { INTAKE, METADATA_READY, SCORE_READY, LANE_ALLOW, FDG, EXEC, EXIT, ATTRITION }

    private data class Row(val candidateId: String, val mint: String, val lastPhase: Phase, val lastReason: String, val stagesSeen: Set<Phase>, val startedAtMs: Long)

    private const val MAX_ROWS = 512

    private val rows = ConcurrentHashMap<String, Row>() // candidateId -> row
    private val phaseCounts = ConcurrentHashMap<Phase, AtomicLong>()
    private val attritionReasons = ConcurrentHashMap<String, AtomicLong>()
    private val notes = AtomicLong(0L)

    fun note(candidateId: String, phase: Phase, mint: String, reason: String = "") {
        if (candidateId.isBlank()) return
        notes.incrementAndGet()
        phaseCounts.getOrPut(phase) { AtomicLong(0L) }.incrementAndGet()
        val prior = rows[candidateId]
        val stages = (prior?.stagesSeen ?: emptySet()) + phase
        rows[candidateId] = Row(candidateId, mint.take(10), phase, reason.take(40), stages, prior?.startedAtMs ?: System.currentTimeMillis())
        if (phase == Phase.ATTRITION && reason.isNotBlank()) {
            attritionReasons.getOrPut(reason.take(30)) { AtomicLong(0L) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("QUALITY_ATTRITION_6450_${reason.take(24).uppercase().replace(' ', '_')}") } catch (_: Throwable) {}
        }
        if (rows.size > MAX_ROWS) {
            // trim oldest
            rows.entries.sortedBy { it.value.startedAtMs }.take(rows.size - MAX_ROWS).forEach { rows.remove(it.key) }
        }
    }

    fun statusLine(): String {
        val topAttrition = attritionReasons.entries.sortedByDescending { it.value.get() }.take(3)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        val funnel = Phase.values().joinToString(",") { "${it.name}=${phaseCounts[it]?.get() ?: 0}" }
        return "notes=${notes.get()} funnel[$funnel] topAttrition[$topAttrition]"
    }
}

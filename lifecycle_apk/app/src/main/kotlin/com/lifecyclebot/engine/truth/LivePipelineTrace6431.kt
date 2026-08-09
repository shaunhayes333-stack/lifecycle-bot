package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6431 §H + §X — LIVE PIPELINE TRACE (stage counters).
 *
 * OPERATOR (V5.0.6424 §H):
 *   'Every candidate that could potentially become live must emit
 *    exactly one step at each stage: LIVE_CANDIDATE_CREATED,
 *    LIVE_INTAKE, LIVE_LANE_SELECTED, LIVE_V3_RESULT, LIVE_FDG_RESULT,
 *    LIVE_AUTHORITY_RESULT, LIVE_EXEC_GATE_RESULT, LIVE_EXEC_ATTEMPT,
 *    LIVE_EXEC_RESULT. If candidate flow disappears between stages
 *    the report must print LIVE_PIPELINE_CHOKE: stage=X previousCount=Y
 *    currentCount=Z topReasons=[...]'
 *
 * DESIGN
 * ──────
 * Simple typed stage counter registry. Callers invoke bumpStage(...)
 * at every live-pipeline transition point. The renderer produces the
 * §X-mandated block including LIVE_PIPELINE_CHOKE where a stage drops
 * to 0 while a preceding stage has non-zero count.
 *
 * Ready for adoption — callers wire incrementally. Until every stage
 * is wired, choke detection uses a soft rule (only flag when previous
 * stage > 0 AND current stage == 0 AND stage advance is expected).
 */
object LivePipelineTrace6431 {

    enum class Stage {
        CANDIDATE, INTAKE, LANE_SELECTED, V3, FDG_ALLOW,
        AUTHORITY_ALLOW, EXEC_GATE_ALLOW, EXEC_ATTEMPT,
        BUY_CONFIRMED, BUY_FAILED,
    }

    private val counters: Map<Stage, AtomicLong> = Stage.entries.associateWith { AtomicLong(0L) }
    private val topBlockReasons = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    fun bumpStage(stage: Stage) {
        counters[stage]?.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LIVE_PIPELINE_${stage.name}_6431") } catch (_: Throwable) {}
    }

    fun bumpBlockReason(reason: String) {
        val truncated = reason.take(80)
        topBlockReasons.computeIfAbsent(truncated) { AtomicLong(0L) }.incrementAndGet()
    }

    fun render(): String = buildString {
        append("===== LIVE PIPELINE TRACE (V5.0.6431 §H) =====\n")
        var prior = -1L
        var chokeStage: Stage? = null
        for (s in Stage.entries) {
            val v = counters[s]?.get() ?: 0L
            append("  ").append("%-20s".format(s.name)).append(" = ").append(v).append('\n')
            if (chokeStage == null && prior > 0L && v == 0L && s.ordinal <= Stage.EXEC_ATTEMPT.ordinal) {
                chokeStage = s
            }
            prior = v
        }
        chokeStage?.let { stage ->
            val priorStage = Stage.entries.getOrNull(stage.ordinal - 1)
            val priorCount = priorStage?.let { counters[it]?.get() } ?: 0L
            append("  LIVE_PIPELINE_CHOKE: stage=").append(stage.name)
                .append(" previousCount=").append(priorCount)
                .append(" currentCount=0\n")
            val top = topBlockReasons.entries
                .sortedByDescending { it.value.get() }
                .take(5)
                .joinToString(",") { "${it.key}=${it.value.get()}" }
            append("  topReasons=[").append(top).append("]\n")
        }
    }

    fun statusLine(): String {
        val kv = counters.entries.joinToString(",") { "${it.key.name}=${it.value.get()}" }
        return kv
    }

    internal fun resetForTest() {
        counters.values.forEach { it.set(0L) }
        topBlockReasons.clear()
    }
}

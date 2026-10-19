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
 * Stage writers are adopted incrementally and these totals are not joined
 * by candidate identity. A zero cannot establish a causal choke. In 8236,
 * unwritten LANE_SELECTED/V3 stages reported a choke despite a confirmed BUY.
 * 8237 reports that coverage gap explicitly and leaves terminal diagnosis to
 * the same-candidate intent/execution records.
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
        val snapshot = counters.mapValues { it.value.get() }
        append("  Scope: observed stage events; not a matched-candidate conversion funnel.\n")
        if (snapshot.values.none { it > 0L } && topBlockReasons.isEmpty())
            append("  NO DATA — no stage events observed in this trace this session.\n")
        var prior = -1L
        var gapStage: Stage? = null
        for (s in Stage.entries) {
            val v = snapshot[s] ?: 0L
            append("  ").append("%-20s".format(s.name)).append(" = ").append(v).append('\n')
            if (gapStage == null && prior > 0L && v == 0L && s.ordinal <= Stage.EXEC_ATTEMPT.ordinal) {
                gapStage = s
            }
            prior = v
        }
        gapStage?.let { stage ->
            val priorStage = Stage.entries.getOrNull(stage.ordinal - 1)
            val priorCount = priorStage?.let { snapshot[it] } ?: 0L
            val downstream = Stage.entries.filter {
                it.ordinal > stage.ordinal && it != Stage.BUY_FAILED && (snapshot[it] ?: 0L) > 0L
            }
            append(if (downstream.isNotEmpty()) "  LIVE_PIPELINE_COVERAGE_GAP_8237: stage="
                else "  LIVE_PIPELINE_GAP_UNRESOLVED_8237: stage=").append(stage.name)
                .append(" previousCount=").append(priorCount)
                .append(" currentCount=0\n")
            if (downstream.isNotEmpty()) append("  downstreamActivity=[")
                .append(downstream.joinToString(",") { "${it.name}=${snapshot[it]}" }).append("]\n")
            append("  Cause needs same-candidate outcomes; zero may reflect incomplete stage instrumentation.\n")
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

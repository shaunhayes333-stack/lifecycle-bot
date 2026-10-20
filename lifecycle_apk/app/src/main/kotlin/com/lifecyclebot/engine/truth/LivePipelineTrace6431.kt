package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/** Supplemental event counters. These populations are not a candidate-normalized funnel. */
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

    // Audited production call sites: BotService, ExecutableOpenGate, LiveEdgeGate,
    // Executor and ExecutorCanonicalMirror. Unwired stages must never appear as measured zero.
    private val instrumented = setOf(Stage.INTAKE, Stage.FDG_ALLOW, Stage.AUTHORITY_ALLOW,
        Stage.EXEC_ATTEMPT, Stage.BUY_CONFIRMED, Stage.BUY_FAILED)

    fun render(): String = buildString {
        append("===== LIVE PIPELINE TRACE (V5.0.6431 §H) =====\n")
        append("  Scope: supplemental event counts; repeated assessments and attempts are separate populations.\n")
        for (s in Stage.entries) {
            append("  ").append("%-20s".format(s.name)).append(" = ")
            if (s in instrumented) append(counters[s]?.get() ?: 0L)
            else append("UNINSTRUMENTED")
            append('\n')
        }
        append("  Candidate-loss diagnosis: unavailable from these aggregate counts; use candidate/attempt causal terminals.\n")
        val top = topBlockReasons.entries.sortedByDescending { it.value.get() }.take(5)
            .joinToString(",") { "${it.key}=${it.value.get()}" }
        append("  topReasons=[").append(top).append("]\n")
    }

    fun statusLine(): String = Stage.entries.joinToString(",") {
        "${it.name}=${if (it in instrumented) counters[it]?.get().toString() else "UNINSTRUMENTED"}"
    }

    internal fun resetForTest() {
        counters.values.forEach { it.set(0L) }
        topBlockReasons.clear()
    }
}

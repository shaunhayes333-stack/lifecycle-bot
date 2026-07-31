package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6402 §A — BOT-LOOP STAGE TIMING.
 *
 * OPERATOR DIRECTIVE
 * ───────────────────
 * Add monotonic timing around every top-level stage. Emit
 * BOT_LOOP_STAGE_START, BOT_LOOP_STAGE_DONE, BOT_LOOP_STAGE_TIMEOUT
 * and BOT_LOOP_STAGE_EXCEPTION with cycleId + stage + elapsedMs.
 *
 * Every BOT_LOOP_TICK must emit BOT_LOOP_DONE inside a finally
 * block. This module owns the emission surface so callers just wrap
 * their stage bodies with [time].
 *
 * DESIGN
 * ──────
 *   BotLoopStageTiming6402.time(cycleId, Stage.SCANNER_DRAIN) {
 *       …stage body…
 *   }
 * emits START, runs body, emits DONE (finally) with elapsed ms, and
 * routes any exception to BOT_LOOP_STAGE_EXCEPTION without swallowing
 * the throw.
 */
object BotLoopStageTiming6402 {

    /** Canonical stage list from V5.0.6402 §A. */
    enum class Stage {
        SCANNER_DRAIN,
        INTAKE_NORMALIZATION,
        CANONICAL_MINT_DEDUPE,
        LANE_EVALUATION,
        V3_EVALUATION,
        FINAL_DECISION_GATE,
        EXECUTION_DRAIN,
        EXIT_SWEEP,
        UNIVERSAL_STOP_LOSS,
        POSITION_RECONCILIATION,
        JOURNAL_FLUSH,
        TELEMETRY_SNAPSHOT,
    }

    private val nextCycleId = AtomicLong(0L)

    /** Assign a fresh cycle id — one per BOT_LOOP_TICK. */
    fun newCycleId(): Long = nextCycleId.incrementAndGet()

    /** Per-cycle p50/p95 accumulators (populated on DONE). */
    private data class Bucket(
        var count: Long = 0L,
        var totalMs: Long = 0L,
        var maxMs: Long = 0L,
    )
    private val perStage = ConcurrentHashMap<Stage, Bucket>().apply {
        Stage.entries.forEach { put(it, Bucket()) }
    }

    /**
     * Wrap a stage body. START → body → finally { DONE + counters }.
     * Exceptions are re-thrown so callers see the failure; a
     * BOT_LOOP_STAGE_EXCEPTION lifecycle row is emitted first.
     */
    inline fun <T> time(
        cycleId: Long,
        stage: Stage,
        crossinline body: () -> T,
    ): T {
        val startMono = System.nanoTime()
        emitStart(cycleId, stage)
        var thrown: Throwable? = null
        return try {
            body()
        } catch (t: Throwable) {
            thrown = t
            throw t
        } finally {
            val elapsedMs = (System.nanoTime() - startMono) / 1_000_000L
            emitDone(cycleId, stage, elapsedMs, thrown)
        }
    }

    fun emitStart(cycleId: Long, stage: Stage) {
        try {
            PipelineHealthCollector.labelInc("BOT_LOOP_STAGE_START_${stage.name}_6402")
            ForensicLogger.lifecycle(
                "BOT_LOOP_STAGE_START_6402",
                "cycleId=$cycleId stage=${stage.name} startedMonoMs=${System.currentTimeMillis()}",
            )
        } catch (_: Throwable) {}
    }

    fun emitDone(cycleId: Long, stage: Stage, elapsedMs: Long, thrown: Throwable?) {
        val b = perStage.getValue(stage)
        b.count++
        b.totalMs += elapsedMs
        if (elapsedMs > b.maxMs) b.maxMs = elapsedMs
        try {
            if (thrown != null) {
                PipelineHealthCollector.labelInc("BOT_LOOP_STAGE_EXCEPTION_${stage.name}_6402")
                ForensicLogger.lifecycle(
                    "BOT_LOOP_STAGE_EXCEPTION_6402",
                    "cycleId=$cycleId stage=${stage.name} elapsedMs=$elapsedMs err=${thrown.javaClass.simpleName}:${thrown.message?.take(120)}",
                )
            } else {
                PipelineHealthCollector.labelInc("BOT_LOOP_STAGE_DONE_${stage.name}_6402")
                ForensicLogger.lifecycle(
                    "BOT_LOOP_STAGE_DONE_6402",
                    "cycleId=$cycleId stage=${stage.name} elapsedMs=$elapsedMs",
                )
            }
        } catch (_: Throwable) {}
    }

    /** Emitted at BOT_LOOP_TICK boundary in caller's finally block. */
    fun emitCycleDone(cycleId: Long, activeElapsedMs: Long, longestStage: Stage?, longestStageMs: Long) {
        try {
            PipelineHealthCollector.labelInc("BOT_LOOP_DONE_6402")
            ForensicLogger.lifecycle(
                "BOT_LOOP_DONE_6402",
                "cycleId=$cycleId activeElapsedMs=$activeElapsedMs longestStage=${longestStage?.name ?: "NONE"} longestStageMs=$longestStageMs",
            )
        } catch (_: Throwable) {}
    }

    data class StageStats(val count: Long, val avgMs: Long, val maxMs: Long)
    fun stats(stage: Stage): StageStats {
        val b = perStage.getValue(stage)
        val avg = if (b.count == 0L) 0L else b.totalMs / b.count
        return StageStats(b.count, avg, b.maxMs)
    }

    internal fun clearAllForTest() {
        Stage.entries.forEach { perStage[it] = Bucket() }
        nextCycleId.set(0L)
    }
}

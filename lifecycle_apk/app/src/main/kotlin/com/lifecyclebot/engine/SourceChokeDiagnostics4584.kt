package com.lifecyclebot.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.4584 — source-level choke diagnostics.
 *
 * In-memory only. No IO, no network, no trade authority. This stitches together
 * the blind spots exposed by the operational report: lane evals disappearing
 * before FDG, SELL_OK not reconciling to journal categories, stop-loss overrun
 * latency, and pct-only/shadow learning pollution.
 */
object SourceChokeDiagnostics4584 {
    private data class StopTrigger(val lane: String, val reason: String, val pnlPct: Double, val atMs: Long)

    private val preFdgStages = ConcurrentHashMap<String, AtomicLong>()
    private val preFdgReasons = ConcurrentHashMap<String, AtomicLong>()
    private val sellJournalReasons = ConcurrentHashMap<String, AtomicLong>()
    private val stopTriggers = ConcurrentHashMap<String, StopTrigger>()
    private val stopFinalityReasons = ConcurrentHashMap<String, AtomicLong>()
    private val learningQuarantineReasons = ConcurrentHashMap<String, AtomicLong>()
    private val stopLatencyMsTotal = AtomicLong(0L)
    private val stopLatencyN = AtomicLong(0L)
    private val stopOverrunN = AtomicLong(0L)

    private fun bump(map: ConcurrentHashMap<String, AtomicLong>, key: String?) {
        val clean = key?.trim()?.take(80)?.ifBlank { "UNKNOWN" } ?: "UNKNOWN"
        map.getOrPut(clean) { AtomicLong(0L) }.incrementAndGet()
    }

    private fun top(map: ConcurrentHashMap<String, AtomicLong>, limit: Int = 5): String =
        map.entries.sortedByDescending { it.value.get() }
            .take(limit)
            .joinToString(" · ") { "${it.key}=${it.value.get()}" }
            .ifBlank { "none" }

    fun preFdg(stage: String, lane: String, source: String, decision: String, reason: String) {
        bump(preFdgStages, "${stage.uppercase()}|${lane.uppercase()}")
        if (stage.contains("reject", true) || decision.contains("BLOCK", true) || decision.contains("NO", true)) {
            bump(preFdgReasons, "${lane.uppercase()}|${reason.ifBlank { decision }}")
        }
        if (source.isNotBlank()) bump(preFdgStages, "SRC|${source.uppercase().take(40)}")
    }

    fun sellJournal(kind: String, lane: String, reason: String) {
        bump(sellJournalReasons, "${kind.uppercase()}|${lane.uppercase().ifBlank { "UNKNOWN" }}|${reason.take(48).ifBlank { "NO_REASON" }}")
    }

    fun stopTriggered(mint: String, lane: String, reason: String, pnlPct: Double) {
        val r = reason.uppercase()
        if (!r.contains("STOP") && !r.contains("CATASTROPHIC") && !r.contains("HARD_FLOOR")) return
        stopTriggers[mint] = StopTrigger(lane.uppercase().ifBlank { "UNKNOWN" }, reason.take(72), pnlPct, System.currentTimeMillis())
        bump(stopFinalityReasons, "TRIGGER|${lane.uppercase().ifBlank { "UNKNOWN" }}|${reason.take(48)}")
    }

    fun stopFinalized(mint: String, lane: String, reason: String, pnlPct: Double) {
        val r = reason.uppercase()
        if (!r.contains("STOP") && !r.contains("CATASTROPHIC") && !r.contains("HARD_FLOOR")) return
        val trig = stopTriggers.remove(mint)
        if (trig != null) {
            val dt = (System.currentTimeMillis() - trig.atMs).coerceAtLeast(0L)
            stopLatencyMsTotal.addAndGet(dt)
            stopLatencyN.incrementAndGet()
            if (pnlPct + 15.0 < trig.pnlPct) stopOverrunN.incrementAndGet()
            bump(stopFinalityReasons, "FINAL|${lane.uppercase().ifBlank { trig.lane }}|${reason.take(48)}|dt=${(dt / 1000L)}s")
        } else {
            bump(stopFinalityReasons, "FINAL_NO_TRIGGER|${lane.uppercase().ifBlank { "UNKNOWN" }}|${reason.take(48)}")
        }
    }

    fun learningQuarantined(reason: String, context: String = "") {
        bump(learningQuarantineReasons, reason.uppercase().take(80))
        try { PipelineHealthCollector.labelInc("SOURCE_CHOKE_LEARNING_QUARANTINE_${reason.uppercase().take(40)}") } catch (_: Throwable) {}
        if (context.isNotBlank()) try { ForensicLogger.lifecycle("SOURCE_CHOKE_LEARNING_QUARANTINE_4584", "reason=$reason context=${context.take(120)}") } catch (_: Throwable) {}
    }

    fun summary(): String {
        val n = stopLatencyN.get()
        val avgStop = if (n > 0) stopLatencyMsTotal.get() / n else 0L
        return "SourceChoke4584 preFdg=[${top(preFdgStages)}] preFdgReject=[${top(preFdgReasons)}] sellJournal=[${top(sellJournalReasons)}] stop=[${top(stopFinalityReasons)} avgStopMs=$avgStop overrun=${stopOverrunN.get()}/$n] learningQuarantine=[${top(learningQuarantineReasons)}]"
    }
}

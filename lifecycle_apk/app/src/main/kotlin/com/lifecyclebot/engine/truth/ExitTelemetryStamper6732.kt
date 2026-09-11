package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap

/**
 * V5.0.6732 — §EXIT_TELEMETRY_STAMPER.
 *
 * Operator diagnostic from 6731: the bot completed 33 real paper sells
 * including hard-floor / catastrophic / profit-lock exits, yet the
 * canonical exit-gate allow/block counters stayed at 0/0 and every
 * `StopLatencyClasses6464` bucket stayed at n=0. The classes and
 * counters existed but were never called from the exit terminal path.
 *
 * This authority owns the single stamping surface for exit lifecycle
 * telemetry:
 *   - `noteExitIntent(positionId, class)` — call when the exit decision
 *     is emitted (protective / catastrophic / profit-lock).
 *   - `noteExitCompleted(positionId, reason)` — call from the mirror
 *     when the terminal sell confirms. Latency is emitted into
 *     `StopLatencyClasses6464` if intent was previously stamped;
 *     otherwise a `EXIT_TERMINAL_NO_INTENT_STAMP_6732` counter fires
 *     so we can see how many exits arrived without an intent phase.
 *
 * Never mutates state elsewhere; purely observability. Failures fall
 * silent (try/catch) so a telemetry hiccup can never block real exits.
 */
object ExitTelemetryStamper6732 {

    private data class Intent(val cls: StopLatencyClasses6464.Class, val atMs: Long)

    private val intents = ConcurrentHashMap<String, Intent>()

    /** Called when the exit decision is emitted (before ticket / execution). */
    fun noteExitIntent(positionId: String, cls: StopLatencyClasses6464.Class) {
        if (positionId.isBlank()) return
        try {
            intents.putIfAbsent(positionId, Intent(cls, System.currentTimeMillis()))
            PipelineHealthCollector.labelInc("EXIT_INTENT_STAMPED_6732_${cls.name}")
            PipelineHealthCollector.labelInc("EXIT_GATE_ALLOWED_${cls.name}_6732")
        } catch (_: Throwable) {}
    }

    /**
     * Called from the terminal sell path (ExecutorCanonicalMirror6442.mirrorSell)
     * when a sell confirms. The reason may identify a class, but only a real
     * intent timestamp can supply latency. Missing timestamps remain visible
     * as unknown rather than manufactured zero-millisecond measurements.
     */
    fun noteExitCompleted(positionId: String, reason: String) {
        if (positionId.isBlank()) return
        try {
            val intent = intents.remove(positionId)
            val cls = intent?.cls ?: classify(reason)
            if (intent == null) {
                PipelineHealthCollector.labelInc("EXIT_TERMINAL_NO_INTENT_STAMP_6732_${cls.name}")
            } else {
                val elapsedMs = (System.currentTimeMillis() - intent.atMs).coerceAtLeast(0L)
                StopLatencyClasses6464.record(cls, elapsedMs)
            }
            PipelineHealthCollector.labelInc("EXIT_TERMINAL_STAMPED_6732_${cls.name}")
        } catch (_: Throwable) {}
    }

    private fun classify(reason: String): StopLatencyClasses6464.Class {
        val r = reason.uppercase()
        return when {
            r.contains("CATASTROPHIC") -> StopLatencyClasses6464.Class.CATASTROPHIC_EXIT
            r.contains("HARD_FLOOR") || r.contains("HARD_STOP") || r.contains("PANIC") ->
                StopLatencyClasses6464.Class.HARD_STOP
            r.contains("TRAIL") || r.contains("PROFIT_LOCK") ->
                StopLatencyClasses6464.Class.TRAILING_STOP
            else -> StopLatencyClasses6464.Class.NORMAL_STOP
        }
    }

    internal fun resetForTest() = intents.clear()

    fun statusLine(): String = "ExitTelemetryStamper6732 pendingIntents=${intents.size}"
}

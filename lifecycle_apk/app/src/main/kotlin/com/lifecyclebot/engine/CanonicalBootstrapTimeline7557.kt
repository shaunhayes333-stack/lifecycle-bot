package com.lifecyclebot.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * V5.0.7557 — durable bootstrap phase timeline, immune to forensic-log churn.
 *
 * Operator: "its not starting from the stop start button. it either never
 * starts or take a looooomg time."
 *
 * Both startup gates already timestamp their own phases:
 *   - canonical-bootstrap-6515 (BotService.markCanonical7438) -> android.util.Log
 *     under the AATE_BOOTSTRAP tag only. Never reaches ForensicLogger, so it
 *     never reaches the in-app Pipeline Health Snapshot the operator can
 *     actually capture and paste back.
 *   - service-bootstrap-6516 / traderStep7447 (bootstrapPhase6516) DOES reach
 *     ForensicLogger.lifecycle("SERVICE_BOOTSTRAP_PHASE_6516", ...).
 *
 * The 6516 phases still don't survive to a pasted snapshot in practice: the
 * "Recent events" section the report prints is a fixed ~150-row ring, and
 * the first render pass after a 100+ held-position restart fires that many
 * OPEN_POSITION_UI_BASIS_WAIT / STALE_PRICE_QUARANTINED rows in the same
 * second, evicting the entire bootstrap timeline before the operator can
 * capture it. The labelled counter (e.g. SERVICE_BOOTSTRAP_PHASE_6516)
 * survives, but a bare count says nothing about which phase or how long.
 *
 * This is a small, bounded, always-retained record of the LAST bootstrap
 * attempt spanning BOTH gates (6516 depends on and runs after 6515, so one
 * continuous timeline is the correct shape), with wall-clock elapsed time
 * per named phase, independent of the forensic ring buffer and of how much
 * unrelated telemetry fires afterward. Observation only — it does not gate,
 * retry, restart, or alter any bootstrap behavior.
 */
object CanonicalBootstrapTimeline7557 {

    /** Phases whose arrival means the combined bootstrap has stopped moving. */
    private val TERMINAL_PHASES = setOf(
        "AUXILIARY_FEEDS_READY",
        "CANONICAL_BOOTSTRAP_FAILED",
        "SERVICE_BOOTSTRAP_FAILED",
    )
    private val FAILURE_PHASES = setOf(
        "CANONICAL_BOOTSTRAP_FAILED",
        "SERVICE_BOOTSTRAP_FAILED",
    )

    data class Phase(val name: String, val atElapsedMs: Long)

    @Volatile private var runStartedAtMs: Long = 0L
    @Volatile private var phases: List<Phase> = emptyList()
    private val attemptsStarted = AtomicInteger(0)

    /** Call once, at the very start of the canonical-bootstrap-6515 job. */
    @Synchronized
    fun beginAttempt() {
        runStartedAtMs = System.currentTimeMillis()
        phases = emptyList()
        attemptsStarted.incrementAndGet()
    }

    /** Call from any bootstrap phase marker, 6515 or 6516/7447 alike. */
    @Synchronized
    fun mark(phase: String) {
        if (runStartedAtMs == 0L) runStartedAtMs = System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - runStartedAtMs
        val next = phases + Phase(phase, elapsed)
        // Bounded: a full bootstrap has on the order of 10-20 named phases
        // (a handful of canonical stages plus one STARTING/READY pair per
        // trader). 64 comfortably covers that with headroom.
        phases = if (next.size > 64) next.takeLast(64) else next
    }

    fun statusLine(): String {
        val started = attemptsStarted.get()
        val last = phases.lastOrNull()
        val nowMs = System.currentTimeMillis()
        val finished = last != null && last.name in TERMINAL_PHASES
        val outcome = when {
            last == null -> "never_run_this_process"
            last.name in FAILURE_PHASES -> "FAILED_at_${last.name}"
            finished -> "READY"
            else -> "IN_PROGRESS"
        }
        val sinceLastPhaseSec = if (last != null) (nowMs - (runStartedAtMs + last.atElapsedMs)) / 1000 else -1L
        val stuckNote = if (!finished && last != null && sinceLastPhaseSec >= 15)
            " ⚠ no new phase for ${sinceLastPhaseSec}s — likely wedged at ${last.name}" else ""
        val totalMs = if (finished) last!!.atElapsedMs else -1L
        val timeline = phases.joinToString(" -> ") { "${it.name}=+${it.atElapsedMs}ms" }
        return "attempts=$started outcome=$outcome" +
            (if (totalMs >= 0) " totalMs=$totalMs" else "") +
            stuckNote +
            " | timeline=[${timeline.ifBlank { "none_yet" }}]"
    }

    internal fun resetForTest() {
        runStartedAtMs = 0L
        phases = emptyList()
        attemptsStarted.set(0)
    }
}

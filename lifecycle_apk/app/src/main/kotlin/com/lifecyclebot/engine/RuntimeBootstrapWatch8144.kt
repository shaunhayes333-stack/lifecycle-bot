package com.lifecyclebot.engine

/**
 * V5.0.8144 — phase timing and stall witness for the service bootstrap. Pure: no Android.
 *
 * The smoke's only view of a wedged bootstrap was `lastServicePhase=CORE_STORES_READY`:
 * the last phase that COMPLETED, never the call that was running when the clock ran
 * out, and nothing at all about how long each phase took. Between CORE_STORES_READY and
 * the next marker sit ~25 store inits; "stuck after core stores" named none of them.
 *
 * [Tracker8144] receives every bootstrap phase marker (CanonicalBootstrapTimeline7557.mark
 * is the single funnel for canonical-6515, service-6516 and trader-7447 phases), timestamps
 * the step that just ended, and answers when a stall report is due: first after
 * [STALL_FIRST_MS_8144] without a new phase, then at every doubling (20s, 40s, 80s …), so a
 * six-minute wedge yields a handful of reports, not a flood. The Android side
 * (RuntimeBootstrapWatchdog8144) prints the step lines and, on a stall, the live stack of
 * the bootstrap thread — which names the exact blocking call, not just the phase.
 *
 * Observation only: nothing here gates, retries or reorders the bootstrap.
 */
object RuntimeBootstrapWatch8144 {

    /** First stall report after this long in one phase; later reports at each doubling. */
    private const val STALL_FIRST_MS_8144 = 20_000L

    /** Bounded step history: a full bootstrap is ~10-40 phases. */
    const val MAX_STEPS_8144 = 64

    /** Phases after which the combined bootstrap has stopped moving (same set as the 7557 timeline). */
    private val TERMINAL_8144: Set<String> = setOf(
        "AUXILIARY_FEEDS_READY",
        "CANONICAL_BOOTSTRAP_FAILED",
        "SERVICE_BOOTSTRAP_FAILED",
    )

    data class Step8144(val phase: String, val previous: String, val stepMs: Long)

    class Tracker8144 {
        @Volatile var phase: String = ""
            private set
        @Volatile var phaseAtMs: Long = 0L
            private set
        /** The last phase that was not a terminal marker — where a failure actually happened. */
        @Volatile var workingPhase: String = ""
            private set
        @Volatile var workingPhaseAtMs: Long = 0L
            private set
        @Volatile var terminal: Boolean = false
            private set
        private var reportedLevel = 0
        private val steps = ArrayDeque<Step8144>()

        /** Record [newPhase] at [nowMs]; returns the step that just ended (previous phase → this one). */
        @Synchronized
        fun onPhase(newPhase: String, nowMs: Long): Step8144 {
            val prev = phase
            val stepMs = if (prev.isEmpty()) 0L else (nowMs - phaseAtMs).coerceAtLeast(0L)
            val step = Step8144(newPhase, prev, stepMs)
            steps.addLast(step)
            while (steps.size > RuntimeBootstrapWatch8144.MAX_STEPS_8144) steps.removeFirst()
            phase = newPhase
            phaseAtMs = nowMs
            terminal = newPhase in RuntimeBootstrapWatch8144.TERMINAL_8144
            if (!terminal) {
                workingPhase = newPhase
                workingPhaseAtMs = nowMs
            }
            reportedLevel = 0
            return step
        }

        /**
         * Milliseconds stalled when a stall report is due at [nowMs], else null.
         * Due at firstMs, 2·firstMs, 4·firstMs … in the current phase; never once terminal.
         */
        @Synchronized
        fun stallDue(nowMs: Long, firstMs: Long = RuntimeBootstrapWatch8144.STALL_FIRST_MS_8144): Long? {
            if (terminal || phase.isEmpty() || firstMs <= 0L) return null
            val stalled = nowMs - phaseAtMs
            val due = firstMs shl reportedLevel.coerceAtMost(20)
            if (stalled < due) return null
            reportedLevel++
            return stalled
        }

        @Synchronized
        fun steps(): List<Step8144> = steps.toList()

        /** The [n] slowest recorded steps, slowest first. */
        @Synchronized
        fun slowest(n: Int): List<Step8144> = steps.sortedByDescending { it.stepMs }.take(n.coerceAtLeast(0))
    }

    /** App frames first (they name the step); falls back to the raw top of stack. */
    fun appFrames8144(frames: List<String>, max: Int = 8): List<String> {
        val app = frames.filter { it.startsWith("com.lifecyclebot.") }
        return (if (app.isNotEmpty()) app else frames).take(max.coerceAtLeast(0))
    }

    /** The class ART refused, from a VerifyError message ("Verifier rejected class X: ..."), else "". */
    fun rejectedClass8144(message: String?): String {
        if (message.isNullOrBlank()) return ""
        val marker = "rejected class "
        val i = message.indexOf(marker)
        if (i >= 0) {
            val rest = message.substring(i + marker.length)
            return rest.takeWhile { !it.isWhitespace() && it != ':' && it != ';' }
        }
        // ART also throws VerifyError whose message is only the class descriptor on a re-touch.
        val bare = message.trim()
        return if (bare.startsWith("com.lifecyclebot.") && bare.none { it.isWhitespace() }) bare else ""
    }

    fun stepLine8144(step: Step8144, thread: String): String =
        "SERVICE_BOOTSTRAP_STEP_8144 phase=${step.phase} after=${step.previous.ifEmpty { "START" }} " +
            "stepMs=${step.stepMs} thread=${clean8144(thread)}"

    fun stallLine8144(phase: String, stalledMs: Long, thread: String, state: String, frames: List<String>): String =
        "SERVICE_BOOTSTRAP_STALL_8144 phase=$phase stalledMs=$stalledMs thread=${clean8144(thread)} " +
            "state=${state.ifEmpty { "UNKNOWN" }} at=${appFrames8144(frames).joinToString(" <- ").ifEmpty { "none" }}"

    fun failureLine8144(phase: String, sincePhaseMs: Long, type: String, message: String?, frames: List<String>): String =
        "SERVICE_BOOTSTRAP_FAILED_STEP_8144 phase=${phase.ifEmpty { "NONE" }} sincePhaseMs=$sincePhaseMs " +
            "type=${type.ifEmpty { "Throwable" }} rejected=${rejectedClass8144(message).ifEmpty { "none" }} " +
            "at=${appFrames8144(frames, 4).joinToString(" <- ").ifEmpty { "none" }}"

    /** Slowest-steps summary for one log line once the bootstrap is terminal. */
    fun summaryLine8144(tracker: Tracker8144, n: Int = 5): String =
        "SERVICE_BOOTSTRAP_SLOWEST_8144 " + tracker.slowest(n).joinToString(" ") { "${it.previous.ifEmpty { "START" }}->${it.phase}=${it.stepMs}ms" }

    private fun clean8144(s: String): String = s.replace(' ', '_').ifEmpty { "unknown" }
}

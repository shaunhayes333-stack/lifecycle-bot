package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6469 §P0 — BACKGROUND TRADING RUNTIME AUTHORITY.
 *
 * OPERATOR MANDATE (verbatim, 6468 evidence):
 *
 *   "MAIN_UI_SCREEN_OFF_INACTIVATED_6300=173
 *    BOTLOOP_CANCELLED=26 RESCUE_RELAUNCHED_SERVICE_SCOPE=27
 *    LIFECYCLE_START_REQUESTED=306 RUNTIME_JOB_ALREADY_EXISTS=305
 *    NO trading coroutine/job may be a child of Activity.lifecycleScope,
 *    Fragment lifecycle, repeatOnLifecycle, MainActivity scope,
 *    UI-visible state, or screenInteractive/display state."
 *
 * This is the AUTHORITY that answers "is trading allowed to run right
 * now?" and REJECTS any UI-scoped attempt to mutate that state.
 *
 * SEMANTICS
 * ─────────
 *   runtimeActive        — service-owned trading authority (this class).
 *   uiVisible            — Activity rendering authority (MainActivity).
 *   screenInteractive    — display telemetry only (MainActivity).
 *
 * Trading paths depend on `runtimeActive` ONLY. UI paths may read
 * `runtimeActive` for display but must never write it.
 *
 * ENFORCEMENT
 * ───────────
 * Every mutation call site must specify a `caller` string. Calls where
 * `caller` looks like a UI-lifecycle path (`MainActivity`, `Fragment`,
 * `onStop`, `onPause`, `onDestroy`, `lifecycleScope`) are REJECTED
 * with a forensic trace + `UI_LIFECYCLE_RUNTIME_MUTATION_REJECTED`
 * counter increment. The current `runtimeActive` value is preserved.
 *
 * Service call sites (`BotService.startLoop`, `BotService.stopLoop`,
 * `operatorRequestedStop`, `HeartbeatRescue`) pass through.
 *
 * FORENSIC COUNTERS (mandated by the acceptance list)
 * ───────────────────────────────────────────────────
 *   BACKGROUND_RUNTIME_SCREEN_OFF_TICKS
 *   BACKGROUND_RUNTIME_UI_ABSENT_TICKS
 *   UI_LIFECYCLE_RUNTIME_MUTATION_REJECTED
 *   RUNTIME_JOB_REPLACEMENTS
 */
object BackgroundTradingAuthority6469 {

    /** Service-owned trading authority. Only the service may write this. */
    private val runtimeActive = AtomicBoolean(false)
    /** Monotonically increasing job identity. `registerJob` bumps this. */
    private val runtimeJobId = AtomicLong(0L)
    /** Monotonically increasing runtime epoch. Only service.start* writes it. */
    private val runtimeEpoch = AtomicLong(0L)
    /** Last-write caller for forensic. */
    private val lastMutator = AtomicReference<String?>(null)

    private val screenOffTicks = AtomicLong(0L)
    private val uiAbsentTicks = AtomicLong(0L)
    private val jobReplacements = AtomicLong(0L)
    private val uiRejections = AtomicLong(0L)
    private val serviceMutations = AtomicLong(0L)

    /**
     * Set of caller string fragments that indicate a UI-lifecycle mutation.
     * Any of these substrings (case-insensitive) triggers a rejection.
     */
    private val uiCallerBlacklist = listOf(
        "mainactivity",
        "fragment",
        "lifecyclescope",
        "onstop",
        "onpause",
        "ondestroy",
        "activity_onresume",
        "activity_lifecycle",
        "repeatonlifecycle",
    )

    private fun isUiCaller(caller: String): Boolean {
        val c = caller.lowercase()
        return uiCallerBlacklist.any { it in c }
    }

    /**
     * Service requests trading START. Only the service-owned code path
     * (BotService) should call this. Returns true when the mutation was
     * accepted; false when rejected (UI caller).
     */
    fun setRuntimeActive(active: Boolean, caller: String): Boolean {
        if (isUiCaller(caller)) {
            uiRejections.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "UI_LIFECYCLE_RUNTIME_MUTATION_REJECTED",
                    "caller=$caller attemptedActive=$active preservedActive=${runtimeActive.get()}",
                )
                PipelineHealthCollector.labelInc("UI_LIFECYCLE_RUNTIME_MUTATION_REJECTED")
            } catch (_: Throwable) {}
            return false
        }
        serviceMutations.incrementAndGet()
        val prev = runtimeActive.getAndSet(active)
        if (prev != active) {
            lastMutator.set(caller)
            try {
                ForensicLogger.lifecycle(
                    "BACKGROUND_RUNTIME_ACTIVE_CHANGED_6469",
                    "prev=$prev now=$active caller=$caller epoch=${runtimeEpoch.get()} jobId=${runtimeJobId.get()}",
                )
            } catch (_: Throwable) {}
        }
        return true
    }

    /** Service registers a fresh runtime job. Bumps runtimeJobId. */
    fun registerRuntimeJob(caller: String): Long {
        if (isUiCaller(caller)) {
            uiRejections.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "UI_LIFECYCLE_RUNTIME_MUTATION_REJECTED",
                    "caller=$caller op=registerRuntimeJob preservedJobId=${runtimeJobId.get()}",
                )
                PipelineHealthCollector.labelInc("UI_LIFECYCLE_RUNTIME_MUTATION_REJECTED")
            } catch (_: Throwable) {}
            return runtimeJobId.get()
        }
        val prev = runtimeJobId.get()
        val newId = runtimeJobId.incrementAndGet()
        runtimeEpoch.incrementAndGet()
        if (prev > 0L) {
            jobReplacements.incrementAndGet()
            try { PipelineHealthCollector.labelInc("RUNTIME_JOB_REPLACEMENTS") } catch (_: Throwable) {}
            try {
                ForensicLogger.lifecycle(
                    "RUNTIME_JOB_REPLACEMENTS",
                    "prev=$prev new=$newId caller=$caller",
                )
            } catch (_: Throwable) {}
        }
        return newId
    }

    /** Called by service tick when screen is off (BotService receiver). */
    fun onScreenOffTick() {
        screenOffTicks.incrementAndGet()
        try { PipelineHealthCollector.labelInc("BACKGROUND_RUNTIME_SCREEN_OFF_TICKS") } catch (_: Throwable) {}
    }

    /** Called by service tick when the UI is absent (no started Activity). */
    fun onUiAbsentTick() {
        uiAbsentTicks.incrementAndGet()
        try { PipelineHealthCollector.labelInc("BACKGROUND_RUNTIME_UI_ABSENT_TICKS") } catch (_: Throwable) {}
    }

    fun isRuntimeActive(): Boolean = runtimeActive.get()
    fun currentJobId(): Long = runtimeJobId.get()
    fun currentEpoch(): Long = runtimeEpoch.get()
    fun screenOffTicks(): Long = screenOffTicks.get()
    fun uiAbsentTicks(): Long = uiAbsentTicks.get()
    fun jobReplacements(): Long = jobReplacements.get()

    fun statusLine(): String =
        "active=${runtimeActive.get()} jobId=${runtimeJobId.get()} epoch=${runtimeEpoch.get()} " +
            "screenOff=${screenOffTicks.get()} uiAbsent=${uiAbsentTicks.get()} " +
            "jobReplacements=${jobReplacements.get()} uiRejects=${uiRejections.get()}"

    internal fun resetForTest() {
        runtimeActive.set(false); runtimeJobId.set(0L); runtimeEpoch.set(0L)
        lastMutator.set(null)
        screenOffTicks.set(0L); uiAbsentTicks.set(0L)
        jobReplacements.set(0L); uiRejections.set(0L); serviceMutations.set(0L)
    }
}

package com.lifecyclebot.engine

/**
 * V5.0.6362 — SUPERVISOR EMERGENCY THROTTLE RE-ARM (pure helper).
 *
 * OPERATOR DIRECTIVE (verbatim excerpts):
 *   "Update the supervisor logic so that SUPERVISOR_EMERGENCY_THROTTLE_OBSERVED_DISARMED
 *    is switched back to ACTIVE mode. 300+ worker timeouts per 10 mins must
 *    actually shed load instead of just observing."
 *
 * WHY THIS FILE EXISTS
 *   [BotService.supervisorEffectiveCap] and [BotService.supervisorArmEmergencyThrottle]
 *   have historically been unit-testable only through the full Android runtime.
 *   V5.9.1332 disarmed the throttle by making `supervisorArmEmergencyThrottle` a
 *   no-op. V5.0.6362 re-arms it — the arm writes an `until` timestamp, and this
 *   pure helper is consulted from `supervisorEffectiveCap` so the two paths agree
 *   and can be tested without spinning up BotService.
 *
 * ENFORCEMENT MODE
 *   Emergency throttle clamps the concurrent worker cap to
 *   [SUPERVISOR_EMERGENCY_MAX_WORKERS] (=16) for as long as the arm window is
 *   active. Cooling (already-armed via V5.9.1470) STILL runs on top: if cooling
 *   floor is lower it wins. Exit dispatcher is NEVER affected — this only trims
 *   the intake worker pool so wedged IO stops compounding.
 */
object SupervisorEmergencyThrottle6362 {

    /** Pure computation of the effective worker cap. All inputs come from the caller
     *  so nothing here depends on wall clock, Android runtime, or BotService state. */
    fun effectiveCap(
        base: Int,
        emergency: Int,
        nowMonoMs: Long,
        coolingUntilMs: Long,
        emergencyThrottleUntilMs: Long,
        timeouts: Int,
    ): Int {
        val cooling = nowMonoMs < coolingUntilMs
        val emergency_ = nowMonoMs < emergencyThrottleUntilMs
        // V5.0.6362 — emergency throttle wins over cooling only when it's stricter.
        // Both floors survive: pool never drops below max(8, emergency) here, and
        // exits are unaffected because they run on a dedicated dispatcher.
        return when {
            emergency_        -> maxOf(8, emergency)
            cooling           -> maxOf(8, base / 3)   // 90s cooling floor
            timeouts >= 150   -> maxOf(8, base / 3)   // heavy: drain debt first
            timeouts >= 30    -> maxOf(12, base / 2)  // moderate: report 3717 had 50 and was parked
            else              -> base                 // healthy: full pool
        }
    }

    /**
     * Returns the *new* `emergencyThrottleUntilMs` after arming. Callers persist
     * this and forward it to [effectiveCap]. Never shortens an existing arm — the
     * throttle only extends. Default window is 5min for cycle-time trips, 5min for
     * worker-timeout floods (matches original V5.9.1319 design).
     */
    fun armUntil(nowMonoMs: Long, existingUntilMs: Long, windowMs: Long = 5L * 60_000L): Long =
        maxOf(existingUntilMs, nowMonoMs + windowMs)
}

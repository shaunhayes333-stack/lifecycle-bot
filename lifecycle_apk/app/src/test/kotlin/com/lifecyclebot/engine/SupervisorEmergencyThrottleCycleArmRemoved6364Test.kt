package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6364 — supervisor emergency-throttle cycle-time arm REMOVAL invariants.
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "the fixes need to be at the source of creation not a bandaid patch"
 *
 * BACKGROUND:
 *   V5.0.6362 re-armed the emergency throttle and added a cycle-time trip
 *   at `cycleMs > 15_000L`. Under real load, ANY cycle above 15s armed a
 *   5-minute clamp of the worker pool to 16. The V5.0.6363 emergency
 *   snapshot showed 2099 arms in 96min (~one every 3s) with cycles at
 *   87s-171s and rolling 50 WR crashing 80% → 32%.
 *
 * WHY THE CYCLE-TIME ARM WAS THE WRONG DESIGN:
 *   Cycle time is a MAIN-THREAD symptom (e.g. probation zero-liq churn,
 *   heavy synchronous work). The emergency throttle is a WORKER-POOL cap.
 *   Clamping workers can never help a main-thread bottleneck — it only
 *   starves the exit path, which was co-processed inside the same tiny
 *   16-worker window. Positive feedback loop:
 *     slow cycle -> arm -> clamp -> exits starve -> slower cycle -> arm...
 *
 * FIX (V5.0.6364):
 *   Removed the `if (cycleMs > 15_000L) supervisorArmEmergencyThrottle(...)`
 *   line from supervisorNoteCycleElapsedForThrottle. Worker-timeout arm
 *   path (supervisorNoteWorkerTimeoutForThrottle) is UNCHANGED and still
 *   arms on 30+ real worker timeouts / 10min. Cooling latch below the
 *   removed line is retained because it applies its own tighter window,
 *   not the emergency clamp.
 */
class SupervisorEmergencyThrottleCycleArmRemoved6364Test {

    private val botServiceSrc: String by lazy {
        java.io.File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
    }

    @Test
    fun cycle_time_arm_is_gone() {
        assertFalse(
            "V5.0.6364: cycle-time arm trigger was the source of the self-reinforcing loop. " +
                "The literal 'if (cycleMs > 15_000L) supervisorArmEmergencyThrottle' must NOT be in BotService.kt.",
            botServiceSrc.contains("if (cycleMs > 15_000L) supervisorArmEmergencyThrottle"),
        )
    }

    @Test
    fun worker_timeout_arm_path_is_preserved() {
        // Real worker-timeout floods (30+/10min) MUST still arm the clamp —
        // that's the throttle's original purpose (V5.9.1319).
        assertTrue(
            "Worker-timeout arm path must remain intact.",
            botServiceSrc.contains("supervisorNoteWorkerTimeoutForThrottle") &&
                botServiceSrc.contains("supervisorTimeoutWindowCount >= 30") &&
                botServiceSrc.contains("supervisorArmEmergencyThrottle(\"worker_timeouts\""),
        )
    }

    @Test
    fun v6362_rearm_helper_is_preserved() {
        // The pure helper introduced in V5.0.6362 is still consulted by
        // supervisorEffectiveCap. Removing the cycle-time trigger doesn't
        // remove the clamp — it just narrows the arm conditions.
        assertTrue(
            "SupervisorEmergencyThrottle6362 helper must still be wired in.",
            botServiceSrc.contains("SupervisorEmergencyThrottle6362.effectiveCap") &&
                botServiceSrc.contains("SupervisorEmergencyThrottle6362.armUntil"),
        )
    }

    @Test
    fun cooling_latch_is_preserved() {
        // Cooling is DISTINCT from the emergency throttle and continues to
        // arm on cycle overrun. It applies a narrower cap floor (base/3)
        // rather than the emergency clamp (SUPERVISOR_EMERGENCY_MAX_WORKERS).
        assertTrue(
            "Cooling latch (V5.9.1470/V5.0.6308) must still arm on cycle overrun.",
            botServiceSrc.contains("SUPERVISOR_COOLING_ARMED_6308") &&
                botServiceSrc.contains("reason=cycle_over_30s") &&
                botServiceSrc.contains("reason=cycle_over_90s"),
        )
    }
}

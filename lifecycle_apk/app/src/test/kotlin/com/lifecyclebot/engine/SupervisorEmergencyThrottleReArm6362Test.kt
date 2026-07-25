package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6362 — SUPERVISOR EMERGENCY THROTTLE RE-ARM invariants.
 *
 * Operator directive: "300+ worker timeouts per 10 mins must actually shed
 * load instead of just observing." This test proves the re-armed clamp
 * actually drops the effective worker cap while active, and that it
 * cooperates with cooling (already-armed under V5.9.1470) without letting
 * either subsystem overrule the stricter floor.
 */
class SupervisorEmergencyThrottleReArm6362Test {

    private val BASE = 48
    private val EMERG = 16

    @Test
    fun `healthy path returns full base cap`() {
        val cap = SupervisorEmergencyThrottle6362.effectiveCap(
            base = BASE,
            emergency = EMERG,
            nowMonoMs = 10_000L,
            coolingUntilMs = 0L,
            emergencyThrottleUntilMs = 0L,
            timeouts = 0,
        )
        assertEquals(BASE, cap)
    }

    @Test
    fun `emergency arm actually clamps to emergency cap`() {
        // Was V5.9.1332 no-op: cap stayed at BASE even with the throttle "armed".
        // V5.0.6362: while the arm window is active the cap MUST drop to EMERG.
        val cap = SupervisorEmergencyThrottle6362.effectiveCap(
            base = BASE,
            emergency = EMERG,
            nowMonoMs = 10_000L,
            coolingUntilMs = 0L,
            emergencyThrottleUntilMs = 10_000L + 60_000L, // 60s from "now"
            timeouts = 0,
        )
        assertEquals(EMERG, cap)
    }

    @Test
    fun `emergency arm expires and cap returns to healthy`() {
        val cap = SupervisorEmergencyThrottle6362.effectiveCap(
            base = BASE,
            emergency = EMERG,
            nowMonoMs = 10_000_000L,       // long after arm window
            coolingUntilMs = 0L,
            emergencyThrottleUntilMs = 100_000L,
            timeouts = 0,
        )
        assertEquals(BASE, cap)
    }

    @Test
    fun `armUntil never shortens an existing window`() {
        val existing = 500_000L
        val next = SupervisorEmergencyThrottle6362.armUntil(
            nowMonoMs = 100_000L,
            existingUntilMs = existing,
            windowMs = 60_000L, // would set until=160000 which is < existing
        )
        assertEquals(existing, next)
    }

    @Test
    fun `armUntil extends when window pushes past existing`() {
        val next = SupervisorEmergencyThrottle6362.armUntil(
            nowMonoMs = 100_000L,
            existingUntilMs = 120_000L,
            windowMs = 60_000L,
        )
        assertEquals(160_000L, next)
    }

    @Test
    fun `emergency floor is never below 8 even if operator misconfigures`() {
        val cap = SupervisorEmergencyThrottle6362.effectiveCap(
            base = BASE,
            emergency = 2, // absurd config
            nowMonoMs = 10_000L,
            coolingUntilMs = 0L,
            emergencyThrottleUntilMs = 20_000L,
            timeouts = 0,
        )
        assertEquals(8, cap)
    }

    @Test
    fun `emergency arm wins over healthy path even when cooling is also active`() {
        // Cooling floor = base/3 = 16 (same as EMERG). Emergency should ALSO clamp to 16.
        val cap = SupervisorEmergencyThrottle6362.effectiveCap(
            base = BASE,
            emergency = EMERG,
            nowMonoMs = 10_000L,
            coolingUntilMs = 20_000L,
            emergencyThrottleUntilMs = 20_000L,
            timeouts = 0,
        )
        assertEquals(EMERG, cap)
    }

    @Test
    fun `moderate timeout tier still respected when nothing is armed`() {
        val cap = SupervisorEmergencyThrottle6362.effectiveCap(
            base = BASE,
            emergency = EMERG,
            nowMonoMs = 10_000L,
            coolingUntilMs = 0L,
            emergencyThrottleUntilMs = 0L,
            timeouts = 30,
        )
        // maxOf(12, 48/2) = 24
        assertEquals(24, cap)
    }

    @Test
    fun `heavy timeout tier drops to base_3 floor even without emergency arm`() {
        val cap = SupervisorEmergencyThrottle6362.effectiveCap(
            base = BASE,
            emergency = EMERG,
            nowMonoMs = 10_000L,
            coolingUntilMs = 0L,
            emergencyThrottleUntilMs = 0L,
            timeouts = 200,
        )
        // maxOf(8, 48/3) = 16
        assertEquals(16, cap)
    }

    @Test
    fun `cap always positive`() {
        for (t in intArrayOf(0, 10, 30, 100, 150, 500)) {
            val cap = SupervisorEmergencyThrottle6362.effectiveCap(
                base = BASE,
                emergency = EMERG,
                nowMonoMs = 10_000L,
                coolingUntilMs = 0L,
                emergencyThrottleUntilMs = 0L,
                timeouts = t,
            )
            assertTrue("cap must be positive, was $cap for timeouts=$t", cap > 0)
        }
    }
}

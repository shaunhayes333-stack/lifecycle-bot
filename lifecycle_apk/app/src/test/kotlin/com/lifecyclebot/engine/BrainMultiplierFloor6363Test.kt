package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * V5.0.6363 — Brain multiplier floor invariants.
 *
 * Operator directive: WR 80% → 32% because `brain=0.262` component was
 * crushing STANDARD lane entries to product=0.144 (14.4% of base). The floor
 * lifts sub-0.50 brain multipliers so no per-context tuple can dust-crush
 * size, while hard-veto callers (TOXIC/CATASTROPHIC verdicts) bypass the
 * floor and preserve the crush.
 */
class BrainMultiplierFloor6363Test {

    @Before
    fun setUp() {
        BrainMultiplierFloor6363.reset()
    }

    @Test
    fun crushing_brain_mult_is_lifted_to_floor() {
        assertEquals(BrainMultiplierFloor6363.FLOOR,
            BrainMultiplierFloor6363.apply(0.262, hardVeto = false),
            1e-12)
    }

    @Test
    fun healthy_brain_mult_passes_through_unchanged() {
        assertEquals(1.0, BrainMultiplierFloor6363.apply(1.0), 1e-12)
        assertEquals(1.25, BrainMultiplierFloor6363.apply(1.25), 1e-12)
        assertEquals(0.75, BrainMultiplierFloor6363.apply(0.75), 1e-12)
    }

    @Test
    fun exactly_at_floor_passes_through() {
        assertEquals(BrainMultiplierFloor6363.FLOOR,
            BrainMultiplierFloor6363.apply(BrainMultiplierFloor6363.FLOOR),
            1e-12)
    }

    @Test
    fun hard_veto_bypasses_floor_so_toxic_verdicts_still_crush() {
        // Operator invariant: TOXIC/CATASTROPHIC verdicts must still be able to
        // shrink size — the floor is only for the noisy-tuple case.
        assertEquals(0.10, BrainMultiplierFloor6363.apply(0.10, hardVeto = true), 1e-12)
        assertEquals(0.0, BrainMultiplierFloor6363.apply(0.0, hardVeto = true), 1e-12)
    }

    @Test
    fun lift_counter_only_ticks_on_actual_lift() {
        BrainMultiplierFloor6363.apply(0.9)         // no lift
        BrainMultiplierFloor6363.apply(0.3)         // lift
        BrainMultiplierFloor6363.apply(0.2)         // lift
        BrainMultiplierFloor6363.apply(0.5)         // no lift (exactly floor)
        BrainMultiplierFloor6363.apply(0.1, true)   // bypass
        assertEquals(2L, BrainMultiplierFloor6363.liftCount())
        assertEquals(1L, BrainMultiplierFloor6363.bypassCount())
    }

    @Test
    fun floor_value_is_operator_calibrated_half() {
        // Operator-visible constant. If this changes, sizing behavior changes
        // for every lane, so keep it locked in the test contract.
        assertEquals(0.50, BrainMultiplierFloor6363.FLOOR, 1e-12)
    }

    @Test
    fun floor_never_amplifies_upward() {
        // Sanity: the floor only LIFTS, never DAMPENS.
        for (v in doubleArrayOf(0.6, 0.75, 1.0, 1.3, 2.5)) {
            assertTrue(
                "floor must never dampen mult=$v",
                BrainMultiplierFloor6363.apply(v) >= v,
            )
        }
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.sell.ProtectiveExitClass7807
import com.lifecyclebot.engine.sell.ProtectiveExitClass7807.Priority
import com.lifecyclebot.engine.truth.MissingMarkExitVeto6835
import com.lifecyclebot.engine.truth.ProtectiveExitScheduler6450
import com.lifecyclebot.engine.truth.ProtectiveExitScheduler6450.TriggerKind
import com.lifecyclebot.v3.scoring.SellOptimizationAI
import com.lifecyclebot.v3.scoring.SellOptimizationAI.ExitStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7884 — Cortex Phase 0 ("make the data true") regressions. */
class Aate7884CortexPhase0Test {

    @Test fun realLossExitsAreNoLongerClassNone() {
        for (r in listOf(
            "SWEEP_FLUID_FLOOR_-25", "RUNNER_EARLY_CUT_MOONSHOT_-21PCT_7277", "STALE_PRICE_FORCED_EXIT_AGE_95s",
            "PARTIAL_LADDER_COLLAPSED_RISK_EXIT_-30", "circuit_breaker_force_exit",
        )) assertEquals(r, Priority.HARD_SL, ProtectiveExitClass7807.of(r))
        // Profit protection is checked first and stays out of the bypass class.
        assertEquals(Priority.PROFIT_PROTECTION, ProtectiveExitClass7807.of("TRAIL_STOP_PEAK_40"))
        assertEquals(Priority.NONE, ProtectiveExitClass7807.of("PLAN_TIME_EXIT"))
    }

    @Test fun priceBasedSiblingSellsRespectTheStaleMarkVeto() {
        // A mark with no timestamp is stale: a price-based floor sell is deferred.
        assertFalse(MissingMarkExitVeto6835.evaluate("Mint7884FluidA", 1.0, 0L, "SWEEP_FLUID_FLOOR_-25").allow)
        assertFalse(MissingMarkExitVeto6835.evaluate("Mint7884CutA", 1.0, 0L, "RUNNER_EARLY_CUT_SHITCOIN_-22PCT_7277").allow)
        // The dark-feed exit is deliberately exempt: its trigger is the missing feed itself.
        assertTrue(MissingMarkExitVeto6835.evaluate("Mint7884DarkA", 1.0, 0L, "STALE_PRICE_FORCED_EXIT_AGE_95s").allow)
    }

    @Test fun anEscalatedLatchDispatchesWithoutWaitingOutTheFirstLatchWindow() {
        val id = "pos-7884-escalation"
        ProtectiveExitScheduler6450.latchTrigger(id, TriggerKind.STOP_LOSS, 1.0)
        // The stop latch seeded the clock: its own path already dispatched.
        assertFalse(ProtectiveExitScheduler6450.shouldDispatch7176(id))
        ProtectiveExitScheduler6450.latchTrigger(id, TriggerKind.CATASTROPHE, 0.4)
        assertTrue(ProtectiveExitScheduler6450.shouldDispatch7176(id))
    }

    @Test fun exitStrategyFollowsTheCloseReason() {
        assertEquals(ExitStrategy.STOP_LOSS, SellOptimizationAI.strategyForReason7884("HARD_STOP_-12"))
        assertEquals(ExitStrategy.TRAILING_LOCK, SellOptimizationAI.strategyForReason7884("profit_lock_2x"))
        assertEquals(ExitStrategy.TIME_DECAY_EXIT, SellOptimizationAI.strategyForReason7884("maxhold_reached"))
        assertEquals(ExitStrategy.FULL_EXIT, SellOptimizationAI.strategyForReason7884("MANUAL"))
    }
}

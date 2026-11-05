package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.EntryClock8535
import com.lifecyclebot.engine.cortex.EntryClock8535.Clock
import com.lifecyclebot.engine.truth.CandidateAdmission8136 as Admission
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.StrategyMove8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8535 — audit P0-03 (a generic NO_RECLAIM clock overruled setup-specific early pullbacks), P0-04 (the launch window
 * exempted every fresh coin from the spent-move read), P1-07 (FALLING_HOUR_UNPROVEN suppressed rebound strategies).
 */
class Aate8535StrategyClockTest {

    // chg1h +25 %, chg5m −3 %, dip 20 %, buyers back: MOMENTUM_PULLBACK_5M fired; the generic stage read is DUMPING / NO_RECLAIM
    private fun pullback(setup: String = "MOMENTUM_PULLBACK_5M", lane: String = "SHITCOIN") = Admission.Candidate8136(
        mint = "clock8535-$lane-$setup", lane = lane, selectedSetup = setup,
        live = true, candidateVersion = 8535L, stage = "DUMPING", entryWait8506 = "NO_RECLAIM",
        priceAgeMs = 10L, expectedGrossPct = 12.0, costPct = 4.0, genericScore = 70.0, genericFloor = 40.0,
        nativeQualified8213 = true, nativeSetup8213 = setup, firedSetups8528 = setOf(setup),
    )

    @Test fun every_setup_has_one_entry_clock() {
        assertEquals(Clock.REBOUND_RECLAIM, EntryClock8535.of("MOMENTUM_PULLBACK_5M"))
        assertEquals(Clock.REBOUND_RECLAIM, EntryClock8535.of("FIRST_DIP_BOUGHT"))
        assertEquals(Clock.REBOUND_RECLAIM, EntryClock8535.of("TREND_DIP_8383"))
        assertEquals(Clock.REBOUND_RECLAIM, EntryClock8535.of("RECLAIM_AFTER_WEAKNESS"))
        assertEquals(Clock.EARLY_BASE, EntryClock8535.of("PRE_IGNITION_BASE"))
        assertEquals(Clock.IMPULSE_CONTINUATION, EntryClock8535.of("NEW_HIGH_PUSH"))
        assertEquals(Clock.IMPULSE_CONTINUATION, EntryClock8535.of("BREAKOUT_RUNNER"))
        assertEquals(Clock.QUALITY_ONLY, EntryClock8535.of("SWEET_SPOT_8383"))
        assertEquals(Clock.IMPULSE_CONTINUATION, EntryClock8535.of(null))
        assertTrue(EntryClock8535.ownsReclaim("PEAK_RECLAIM_8383"))
        assertFalse(EntryClock8535.ownsReclaim("IGNITION_5M"))
    }

    @Test fun a_fired_pullback_setup_is_judged_on_its_own_clock_not_the_generic_reclaim_wait() {
        for (setup in listOf("MOMENTUM_PULLBACK_5M", "FIRST_DIP_BOUGHT", "TREND_DIP_8383", "FIRST_PULLBACK_AFTER_RUN", "PEAK_RECLAIM_8383")) {
            val c = pullback(setup)
            assertTrue(setup, Admission.strategyClockWitness8535(c))
            // superseded: `Admission.decide8136(c)` admitted LIVE on the fired setup alone — V5.0.8536 (owner): no live buy on the
            // lane opinion alone; V5.0.8538: it admits when an 8069–8114 live edge carries it.
            assertEquals(setup, Admission.LIVE_NATIVE_OPINION_UNPROVEN_8536, Admission.decide8136(c).reason)
            val d = Admission.decide8136(c.copy(liveEdge8114 = "CHART_BUY"))
            assertEquals(setup, Admission.Terminal8136.ADMIT, d.terminal)
            assertFalse("a fired setup is not statistical proof", d.specific)
            assertTrue(d.strategyClockWitness8535)
            assertTrue(d.trace.any { it.startsWith("strategyClock8535=$setup") })
            // the same DUMPING misfit prior is settled downstream; a PROVEN-losing stage or a cost refusal is not
            assertTrue(LiveEdgeGate7877.stagePriorAlreadyResolved(d, c.lane, "STAGE_LANE_MISFIT_7928_${c.lane}_DUMPING"))
            assertFalse(LiveEdgeGate7877.stagePriorAlreadyResolved(d, c.lane, "STAGE_PROVEN_LOSING_7928_${c.lane}_DUMPING"))
            assertFalse(LiveEdgeGate7877.stagePriorAlreadyResolved(d, c.lane, "STAGE_LANE_MISFIT_7928_${c.lane}_NO_SETUP"))
        }
    }

    @Test fun the_momentum_breakout_strategy_still_waits_and_a_falling_knife_is_still_refused() {
        // a breakout setup has the momentum clock: NO_RECLAIM is its own wait
        val breakout = pullback("NEW_HIGH_PUSH")
        assertFalse(Admission.strategyClockWitness8535(breakout))
        assertEquals("ENTRY_NO_RECLAIM_8506", Admission.decide8136(breakout).reason)
        // an early-base setup on a dumping chart is not a reclaim either
        assertFalse(Admission.decide8136(pullback("PRE_IGNITION_BASE")).admitted)
        // the pullback setup named but NOT fired (no witness) does not answer the wait
        assertFalse(Admission.strategyClockWitness8535(pullback().copy(firedSetups8528 = emptySet())))
        assertFalse(Admission.decide8136(pullback().copy(firedSetups8528 = emptySet())).admitted)
        // the lane's cheat sheet AVOID on that setup, a stale price, bad economics or a losing record still bind
        assertFalse(Admission.strategyClockWitness8535(pullback().copy(sheet8323 = mapOf("MOMENTUM_PULLBACK_5M" to Admission.SHEET_AVOID_8323))))
        assertFalse(Admission.decide8136(pullback().copy(priceAgeMs = Long.MAX_VALUE)).admitted)
        assertFalse(Admission.decide8136(pullback().copy(costPct = 10.0)).admitted)
        val losing = Admission.Evidence8136(Admission.KIND_SETUP, "MOMENTUM_PULLBACK_5M", 60, -12.0, 1.0)
        assertFalse(Admission.decide8136(pullback().copy(setups = listOf(losing))).admitted)
        assertEquals("SAFETY_REFUSED_8136", Admission.decide8136(pullback().copy(safetyRefusal = "MINT_AUTH")).reason)
    }

    // ── P1-07 ──

    @Test fun a_falling_hour_is_adverse_for_the_momentum_strategy_not_for_a_reclaim_on_the_same_lane() {
        assertTrue(StrategyMove8153.momentumEntry8535("SHITCOIN", "NEW_HIGH_PUSH"))
        assertTrue(StrategyMove8153.momentumEntry8535("MOONSHOT", null))
        assertFalse(StrategyMove8153.momentumEntry8535("SHITCOIN", "RECLAIM_AFTER_WEAKNESS"))
        assertFalse(StrategyMove8153.momentumEntry8535("MOONSHOT", "POST_EVENT_RECLAIM"))
        assertFalse("a non-momentum lane never was", StrategyMove8153.momentumEntry8535("DIP_HUNTER", "NEW_HIGH_PUSH"))
        val momentum = StrategyMove8153.expected8153(45.0, 36.0, true, null, 0, Double.NaN, Double.NaN, 4.0, false,
            signedHourPct = -31.0, momentumEntry = StrategyMove8153.momentumEntry8535("SHITCOIN", "IGNITION_5M"))
        assertEquals("FALLING_HOUR_UNPROVEN", momentum.basis)
        val reclaim = StrategyMove8153.expected8153(45.0, 36.0, true, null, 0, Double.NaN, Double.NaN, 4.0, false,
            signedHourPct = -31.0, momentumEntry = StrategyMove8153.momentumEntry8535("SHITCOIN", "RECLAIM_AFTER_WEAKNESS"))
        assertTrue("the reclaim is costed on the hour's range, not refused on its sign", reclaim.basis != "FALLING_HOUR_UNPROVEN" && reclaim.expectedGrossPct > 0.0)
    }

    // ── P0-04 ──

    @Test fun a_launch_that_already_ran_is_costed_on_what_is_left_and_a_first_block_base_keeps_its_read() {
        fun launch(runup: Double?, room: Double?) = StrategyMove8153.expected8153(45.0, 36.0, true, null, 0, Double.NaN, Double.NaN, 4.0,
            freshLaunch = true, signedHourPct = 30.0, momentumEntry = true, inLaunchWindow8418 = true,
            runupFromHourLowPct8529 = runup, roomToHourHighPct8529 = room)
        val base = launch(2.0, 1.0)
        val ran = launch(70.0, 5.0)
        assertEquals("RANGE", base.basis); assertEquals("RANGE", ran.basis)   // a dense 36 % range is the hour read
        assertTrue("a +70 % in-window run is consumed move", ran.expectedGrossPct < base.expectedGrossPct)
        assertEquals(StrategyMove8153.remaining8529(base.expectedGrossPct, 70.0, 5.0)!!, ran.expectedGrossPct, 1e-9)
        assertEquals("below the documented-run bar the first move is not a spent hour", base.expectedGrossPct, launch(20.0, 3.0).expectedGrossPct, 1e-9)
        assertEquals("unknown position: the read stands", base.expectedGrossPct, launch(null, null).expectedGrossPct, 1e-9)
        assertTrue(StrategyMove8153.launchRunDocumented8535(StrategyMove8153.LAUNCH_SPENT_MIN_PCT_8535))
        assertEquals(25.0, StrategyMove8153.LAUNCH_SPENT_MIN_PCT_8535, 0.0)
        assertFalse(StrategyMove8153.launchRunDocumented8535(24.9)); assertFalse(StrategyMove8153.launchRunDocumented8535(Double.NaN))
    }
}

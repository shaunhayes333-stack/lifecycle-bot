package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.cortex.StopAuthority7887
import com.lifecyclebot.engine.truth.FieldManual7715
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8418 — one binding value per exit threshold (lane before global) and CYCLIC's scalp contract. */
class Aate8418ExitThresholdsTest {
    private fun live(mint: String, lane: String, price: Double = 0.95) =
        TokenState(mint = mint, position = Position(qtyToken = 1.0, entryPrice = 1.0, entryTime = 1L, tradingMode = lane,
            isPaperPosition = false, entryPolicySnapshot = "style=QUICK_FLIP;entryTactic=BREAKOUT")).also {
            it.lastPrice = price; it.lastPriceUpdate = System.currentTimeMillis()
        }

    @Test fun laneStopOwnsTheDoorTheGlobalBindsOnlyWhenTheLaneIsUnknown() {
        val lane = ExitThresholds8418.ordinaryStopOf8418(15.0, "LANE", 10.0)
        assertEquals(15.0, lane.value, 0.0)
        assertTrue(lane.source, lane.source.startsWith("LANE_"))
        // a tighter lane stop binds too: the lane owns it in both directions
        assertEquals(6.0, ExitThresholds8418.ordinaryStopOf8418(6.0, "LANE", 10.0).value, 0.0)
        val global = ExitThresholds8418.ordinaryStopOf8418(Double.NaN, "", -10.0)
        assertEquals(10.0, global.value, 0.0)
        assertEquals("GLOBAL", global.source)
        val unknown = ExitThresholds8418.ordinaryStopOf8418(Double.NaN, "", Double.NaN)
        assertTrue(unknown.value.isNaN())
        assertEquals("UNKNOWN", unknown.source)
    }

    @Test fun aRunnerLanePositionIsNotCutAtTheGlobalTenPercent() {
        val ts = live("shit8418", "SHITCOIN")
        val b = StopAuthority7887.stopFor8418(ts)
        assertEquals(b.value, StopAuthority7887.stopMagFor(ts), 0.0)
        assertTrue("runner floor: ${b.value}", b.value >= 15.0)
        val door = ExitThresholds8418.ordinaryStop8418(ts, 10.0, "BACKUP_STOP")
        assertEquals(b.value, door.value, 0.0)
        assertFalse(door.source, door.source == "GLOBAL")
        assertEquals(b.value, ExitThresholds8418.ordinaryStopMag8418(ts, 10.0, "BACKUP_STOP"), 0.0)
        assertTrue(ExitThresholds8418.statusLine8418().contains("BACKUP_STOP:LANE="))
    }

    @Test fun theWinnerDipStopKeepsTheRunnerFloorAndTheLaneDoctrine() {
        // runner lane: a 6 % learned dip stop never goes under the 15 % runner invariant
        assertEquals(15.0, StopAuthority7887.winnerDipFloored8418(6.0, "SHITCOIN", noPlan = true, runnerLane = true), 0.0)
        // no plan: never inside the lane's Field Manual invalidation (widened to its round trip)
        val doctrine = FieldManual7715.laneStopPct8392("QUALITY")
        val q = StopAuthority7887.winnerDipFloored8418(5.0, "QUALITY", noPlan = true, runnerLane = false)
        assertTrue("$q >= $doctrine", q >= minOf(doctrine, 25.0) - 1e-9)
        assertTrue(q > 5.0)
        // a planned trade's own stop is not floored by the doctrine
        assertEquals(5.0, StopAuthority7887.winnerDipFloored8418(5.0, "QUALITY", noPlan = false, runnerLane = false), 0.0)
        // a wider learned stop is untouched
        assertEquals(22.0, StopAuthority7887.winnerDipFloored8418(22.0, "QUALITY", noPlan = true, runnerLane = false), 0.0)
    }

    @Test fun catastropheIsTwentyFiveAndNeverAboveAHeldLifecycleFloor() {
        val base = ExitThresholds8418.catastropheOf8418(Double.NaN)
        assertEquals(25.0, base.value, 0.0)
        assertEquals("BASE", base.source)
        val held = ExitThresholds8418.catastropheOf8418(LifecycleHold8065.FLOOR_PCT)
        assertEquals(LifecycleHold8065.FLOOR_PCT, held.value, 0.0)
        assertEquals("LIFECYCLE_FLOOR", held.source)
        // a position that is not held keeps the 25 % line
        assertEquals(25.0, ExitThresholds8418.catastropheMag8418(live("s8418", "SHITCOIN")), 0.0)
    }

    @Test fun cyclicIsAScalpLaneEverywhere() {
        // not a lifecycle lane, not a runner lane, not a runner-from-entry lane: its labels grade the scalp family
        assertFalse(LifecycleHold8065.lifecycleLane8065("CYCLIC"))
        assertFalse(RunnerExitProfile7277.isRunnerLane("CYCLIC"))
        assertFalse(TradeExitContract8077.runnerFromEntryLane8104("CYCLIC", false))
        // the hold ends at the label's final read, the same as HoldingLogicLayer's CYCLIC hold
        assertEquals(HoldingLogicLayer.getHoldParams("CYCLIC").maxHoldTimeMs, CyclicContract8418.HOLD_MS_8418)
        assertFalse(CyclicContract8418.holdEnded8418(59L * 60_000L))
        assertTrue(CyclicContract8418.holdEnded8418(60L * 60_000L))
        // the take is the first rung of the ladder its labels are credited on
        assertEquals(SpikeCapture7943.TIERS.first().first, CyclicContract8418.takePct8418(), 0.0)
    }

    @Test fun cyclicStopIsTheStopItsLabelsAreGradedOnNotTheRoutedMode() {
        val c = CyclicContract8418.contractOf8418(12.0, 8.0)
        assertEquals(12.0, c.stopPct, 0.0)
        assertEquals("LANE", c.stopSource)
        assertEquals(CyclicContract8418.HOLD_MS_8418, c.holdMs)
        val fb = CyclicContract8418.contractOf8418(Double.NaN, -8.0)
        assertEquals(8.0, fb.stopPct, 0.0)
        assertEquals("FALLBACK", fb.stopSource)
        // no position: the lane's label stop (StopAuthority7887.laneStopMag8029), whatever mode the token was routed as
        val label = StopAuthority7887.laneStopMag8029("CYCLIC")
        val entry = CyclicContract8418.contractFor8418(null, 120.0)
        if (label.isFinite() && label > 0.0) assertEquals(label, entry.stopPct, 1e-9)
        // open position: the same authority every generic exit applies to it
        val ts = live("cyc8418", "CYCLIC")
        assertEquals(StopAuthority7887.stopMagFor(ts), CyclicContract8418.contractFor8418(ts, 8.0).stopPct, 1e-9)
        assertTrue(CyclicContract8418.statusLine8418().contains("scalp"))
    }
}

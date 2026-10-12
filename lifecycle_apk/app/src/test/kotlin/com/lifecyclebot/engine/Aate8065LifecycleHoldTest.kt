package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8065 — lifecycle lanes hold for the coin's lifecycle; no buying the top of a spike. */
class Aate8065LifecycleHoldTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private fun bar(l: Double, c: Double) = doubleArrayOf(0.0, c, maxOf(l, c), l, c)

    @Test fun ordinaryExitsAreHeldIntegrityPasses() {
        assertFalse(LifecycleHold8065.passes8065("RAPID_FLUID_STOP"))
        assertFalse(LifecycleHold8065.passes8065("RAPID_CATASTROPHE_STOP"))
        assertFalse(LifecycleHold8065.passes8065("FALLBACK_SHITCOIN_TIME_EXIT"))
        assertFalse(LifecycleHold8065.passes8065("SPIKE_CAPTURE_7943_T1_45PCT"))
        assertTrue(LifecycleHold8065.passes8065("RUG_DETECTED"))
        assertTrue(LifecycleHold8065.passes8065("LIQUIDITY_PULLED"))
        assertTrue(LifecycleHold8065.passes8065("bot_shutdown"))
        assertTrue(LifecycleHold8065.passes8065("LIFECYCLE_STOP_FLOOR_8065_-41PCT"))
    }

    @Test fun takesAndBreaks() {
        assertNull(LifecycleHold8065.takeDue8065(80.0, 0))
        assertEquals(0 to 0.5, LifecycleHold8065.takeDue8065(105.0, 0))
        assertNull(LifecycleHold8065.takeDue8065(300.0, 1))
        assertEquals(1 to 0.3, LifecycleHold8065.takeDue8065(420.0, 1))
        val base = List(16) { bar(1.0, 1.2) }
        assertFalse(LifecycleHold8065.rangeBroken8065(base + bar(1.05, 1.1), 16))
        assertTrue(LifecycleHold8065.rangeBroken8065(base + bar(0.8, 0.9), 16))
        assertTrue(LifecycleHold8065.peakGiveback8065(400.0, 120.0))
        assertFalse(LifecycleHold8065.peakGiveback8065(400.0, 200.0))
        assertTrue(LifecycleHold8065.deadMoney8065(80L * 3_600_000L, 3.0, 50L * 3_600_000L))
        assertFalse(LifecycleHold8065.deadMoney8065(80L * 3_600_000L, 60.0, 50L * 3_600_000L))
    }

    @Test fun wired() {
        assertTrue(src("engine/Executor.kt").contains("LifecycleHold8065.defer8065(ts, reason)"))
        assertTrue(src("engine/Executor.kt").contains("LifecycleHold8065.partialHeld8065(ts, reason)"))
        assertTrue(src("engine/SpikeCapture7943.kt").contains("LifecycleHold8065.onMark8065(ts, px, nowMs, sell)"))
        assertTrue(src("engine/cortex/StopAuthority7887.kt").contains("LifecycleHold8065.lifecycleLane8065(l)"))
        assertTrue(LifecycleHold8065.lifecycleLane8065("moonshot"))
        assertFalse(LifecycleHold8065.lifecycleLane8065("SHITCOIN"))
    }

    @Test fun noChasing() {
        assertTrue(LiveEdgeGate7877.chasing8065(120.0, 0.95, 10.0))
        assertTrue(LiveEdgeGate7877.chasing8065(10.0, 0.5, 150.0))
        assertFalse(LiveEdgeGate7877.chasing8065(120.0, 0.6, 10.0))   // pulled back off the high
        assertFalse(LiveEdgeGate7877.chasing8065(30.0, 0.99, 20.0))
    }

    @Test fun onePrintCrossingMultipleTiersCombinesPartialWithoutSellingRunnerRemainder() {
        val twoTiers = LifecycleHold8065.cumulativeTakeDue8079(781.0, 0)
        assertEquals(1, twoTiers?.first)
        assertEquals(0.65, twoTiers!!.second, 1e-9)
        val afterFirst = LifecycleHold8065.cumulativeTakeDue8079(950.0, 1)
        assertEquals(2, afterFirst?.first)
        assertEquals(0.51, afterFirst!!.second, 1e-9)
        assertNull(LifecycleHold8065.cumulativeTakeDue8079(70.0, 0))
        assertEquals(0.964, SpikeCapture7943.crossedTierFraction8079(SpikeCapture7943.TIERS, 0, 3), 1e-9)
        assertEquals(0.70, SpikeCapture7943.crossedTierFraction8079(SpikeCapture7943.TIERS, 2, 3), 1e-9)
        assertEquals(0.0, SpikeCapture7943.crossedTierFraction8079(SpikeCapture7943.TIERS, 3, 3), 1e-9)
    }

    @Test fun lateHistoricalBuyCannotUndoPendingOrFinalizedSell() {
        val t = HostWalletTokenTracker
        assertTrue(t.historicalBuyBlocked8079(t.PositionStatus.CLOSED, "sellTx"))
        assertTrue(t.historicalBuyBlocked8079(t.PositionStatus.SOLD_CONFIRMED, "sellTx"))
        assertTrue(t.historicalBuyBlocked8079(t.PositionStatus.SELL_VERIFYING, "sellTx"))
        assertFalse(t.historicalBuyBlocked8079(t.PositionStatus.OPEN_TRACKING, null))
    }
}

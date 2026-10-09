package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.chart.StructureTracker7962.Bar7962
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.truth.CostLedger7962
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7962 — market structure (swings, HL_RECLAIM, learned structure-break exit) and the measured all-in cost. */
class Aate7962StructureAndCostTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    /** Bars through [closes]; each bar's high/low is its close +/- 0.5, with the given buy share of 10 SOL. */
    private fun bars(closes: List<Double>, buyShare: Double = 0.7): List<Bar7962> =
        closes.mapIndexed { i, c -> Bar7962(i * 15_000L, c, c + 0.5, c - 0.5, c, 10.0 * buyShare, 10.0 * (1.0 - buyShare)) }

    // Up-leg to H1=12, low L1=8, higher high H2=16, higher low L2=12, then a reclaim above 16.
    private val staircase = listOf(8.0, 10.0, 12.0, 10.0, 8.0, 10.0, 12.0, 14.0, 16.0, 14.0, 12.0, 14.0, 15.0, 17.0)

    @Test fun swingsAlternateAndFindPivots() {
        val sw = StructureTracker7962.swings7962(bars(staircase))
        val highs = sw.filter { it.high }.map { it.px }
        val lows = sw.filter { !it.high }.map { it.px }
        assertEquals(listOf(12.5, 16.5), highs)
        assertEquals(listOf(7.5, 11.5), lows)
        // Strictly alternating.
        for (i in 1 until sw.size) assertTrue(sw[i].high != sw[i - 1].high)
    }

    @Test fun hlReclaimNeedsHigherHighHigherLowReclaimAndBuyers() {
        val r = StructureTracker7962.read7962(bars(staircase), 17.0, 20)
        assertNotNull(r)
        r!!
        assertTrue(r.higherHigh && r.higherLow && r.refilled && r.reclaimFresh)
        assertTrue(r.hhHl >= 2)
        assertEquals(11.5, r.lastLow, 1e-9)
        assertTrue(StructureTracker7962.hlReclaimFires7962(r))
        // Sellers dominate: no setup.
        assertFalse(StructureTracker7962.hlReclaimFires7962(StructureTracker7962.read7962(bars(staircase, 0.3), 17.0, 20)))
        // Not reclaimed yet (price still under the prior high): no setup.
        val noReclaim = staircase.dropLast(1) + 15.5
        assertFalse(StructureTracker7962.hlReclaimFires7962(StructureTracker7962.read7962(bars(noReclaim), 15.5, 20)))
        // Too short to read.
        assertNull(StructureTracker7962.read7962(bars(listOf(1.0, 2.0, 3.0)), 3.0, 20))
    }

    @Test fun firstLowerLowAfterARunIsABreak() {
        val run = StructureTracker7962.read7962(bars(staircase + listOf(18.0, 19.0)), 19.0, 20)!!
        assertFalse(run.brokeStructure)
        // Close under the last higher low (11.5) after the run.
        val broke = StructureTracker7962.read7962(bars(staircase + listOf(14.0, 11.0)), 11.0, 20)!!
        assertTrue(broke.brokeStructure)
        // A falling chart never had a run: a lower low is not a "break".
        val down = listOf(20.0, 18.0, 16.0, 17.0, 18.0, 16.0, 14.0, 12.0, 13.0, 14.0, 12.0, 10.0, 8.0)
        assertFalse(StructureTracker7962.read7962(bars(down), 8.0, 20)!!.brokeStructure)
    }

    @Test fun breakExitIsLearnedNotFixed() {
        assertEquals(10.0, StructureTracker7962.savedPct7962(100.0, 90.0), 1e-9)
        assertEquals(-20.0, StructureTracker7962.savedPct7962(100.0, 120.0), 1e-9)
        assertTrue(StructureTracker7962.savedPct7962(0.0, 1.0).isNaN())
        fun stat(xs: List<Double>) = CortexLedger7885.Stat().also { s -> xs.forEach { s.add(it, false) } }
        assertFalse(StructureTracker7962.breakActive7962(null, 1.5))
        // 19 strong samples: not enough evidence yet (shadow).
        assertFalse(StructureTracker7962.breakActive7962(stat(List(19) { 8.0 + it % 3 }), 1.5))
        // 20+ samples saving more than the cost: acts.
        assertTrue(StructureTracker7962.breakActive7962(stat(List(24) { 6.0 + it % 4 }), 1.5))
        // Saving, but under the round-trip cost: shadow.
        assertFalse(StructureTracker7962.breakActive7962(stat(List(30) { 1.0 + (it % 2) * 0.2 }), 1.5))
        // Exiting lost (holding was better): shadow.
        assertFalse(StructureTracker7962.breakActive7962(stat(List(40) { -3.0 + it % 2 }), 1.5))
    }

    @Test fun roundTripCostIsMarkReturnMinusRealised() {
        // Spent 1.03 SOL (incl. fees) for 1000 raw tokens; market flat (mark 1.0 -> 1.0); got 0.95 back: ~8% all-in.
        val flat = CostLedger7962.roundTripCostPct7962(1.03, 1000.0, 1.0, listOf(CostLedger7962.Leg7962(1000.0, 0.95, 1.0)))!!
        assertEquals((1.0 - 0.95 / 1.03) * 100.0, flat, 1e-9)
        // Market +50%, two legs; realised 1.40 on 1.0 spent: cost = 50 - 40 = 10.
        val legs = listOf(CostLedger7962.Leg7962(600.0, 0.84, 1.5), CostLedger7962.Leg7962(400.0, 0.56, 1.5))
        assertEquals(10.0, CostLedger7962.roundTripCostPct7962(1.0, 1000.0, 1.0, legs)!!, 1e-9)
        // Unit mix-ups / impossible inputs are not measurements.
        assertNull(CostLedger7962.roundTripCostPct7962(1.0, 1000.0, 1.0, listOf(CostLedger7962.Leg7962(1000.0, 0.01, 150.0))))
        assertNull(CostLedger7962.roundTripCostPct7962(0.0, 1000.0, 1.0, legs))
        assertNull(CostLedger7962.roundTripCostPct7962(1.0, 1000.0, 1.0, emptyList()))
    }

    @Test fun medianPerLaneAndBandAndTheRefusalRule() {
        assertEquals(3.0, CostLedger7962.median7962(listOf(5.0, 1.0, 3.0)), 1e-9)
        assertEquals(2.5, CostLedger7962.median7962(listOf(1.0, 2.0, 3.0, 4.0, Double.NaN)), 1e-9)
        assertTrue(CostLedger7962.median7962(emptyList()).isNaN())
        assertEquals("0.02-0.05", CostLedger7962.sizeBand7962(0.027))
        assertEquals("UNK", CostLedger7962.sizeBand7962(Double.NaN))
        val few = List(CostLedger7962.MIN_LIVE_N - 1) { doubleArrayOf(9.0, 0.027, 2.0) }
        assertNull(CostLedger7962.measuredFrom7962(few, 0.027))
        // 8 small tickets at ~9%, 8 bigger at ~4%: each band reads its own median.
        val rows = List(8) { doubleArrayOf(9.0 + it * 0.1, 0.027, 2.0) } + List(8) { doubleArrayOf(4.0, 0.2, 1.0) }
        assertEquals(9.35, CostLedger7962.measuredFrom7962(rows, 0.03)!!, 1e-9)
        assertEquals(4.0, CostLedger7962.measuredFrom7962(rows, 0.15)!!, 1e-9)
        // An unmeasured band falls back to the lane median.
        assertEquals(6.5, CostLedger7962.measuredFrom7962(rows, 1.0)!!, 1e-9)
        // Refuses only once measured, and only when the gross move is below the cost.
        assertFalse(CostLedger7962.refuses7962(3.0, null))
        assertTrue(CostLedger7962.refuses7962(3.0, 9.0))
        assertFalse(CostLedger7962.refuses7962(12.0, 9.0))
        // Nothing measured in a plain JVM: labels/cards keep the estimate.
        assertNull(CostLedger7962.measuredCostPct7962("SHITCOIN", 0.027))
        assertEquals(2.5, CostLedger7962.costOr7962("SHITCOIN", 0.027, 2.5), 1e-9)
    }

    @Test fun wiring() {
        assertTrue(src("engine/chart/ChartReader7950.kt").contains("StructureTracker7962.onPrice7962(mint, priceUsd, atMs)"))
        assertTrue(src("engine/chart/ChartReader7950.kt").contains("StructureTracker7962.onTrade7962(mint, solAmount, isBuy, atMs)"))
        assertTrue(src("engine/SpikeCapture7943.kt").contains("StructureTracker7962.heldExit7962(ts, px, nowMs, sell)"))
        assertTrue(src("engine/SpikeCapture7943.kt").contains("CostLedger7962.statusLine7962()"))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.contains("Setup(\"HL_RECLAIM\", STRUCT)") && pb.contains("StructureTracker7962.hlReclaim7962(ts.mint, nowMs)"))
        assertTrue(src("engine/truth/LiveEdgeGate7877.kt").contains("CostLedger7962.liveRefusal7962(ts, l)"))
        assertTrue(src("engine/truth/EntryStrategySnapshot6450.kt").contains("CostLedger7962.onEntry7962(snap.positionId"))
        // Labels: measured cost replaces the estimate; the chase is still added once.
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("CostLedger7962.costOr7962(l, Double.NaN,") && lab.contains("EntryChase7961.lanePenaltyPct7961(l)"))
        assertTrue(src("engine/truth/FieldManual7715.kt").contains("CostLedger7962.costOr7962(lane, size, allInCostPct(sizeUsd, liq))"))
    }
}

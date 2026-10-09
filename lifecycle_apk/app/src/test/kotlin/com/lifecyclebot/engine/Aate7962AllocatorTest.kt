package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/** V5.0.7962 — slots, size and priority go to the best decision cells (Thompson bandit with shrinkage). */
class Aate7962AllocatorTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun stat(n: Int, vararg values: Double): CellAllocator7962.Stat7962 {
        val s = CellAllocator7962.Stat7962()
        for (i in 0 until n) s.add(values[i % values.size], 1.0)
        return s
    }

    @Test fun posteriorUpdateIsNormalInverseGamma() {
        val p = CellAllocator7962.posterior7962(stat(10, 10.0), 0.0, 10.0, 900.0)
        assertEquals(5.0, p.mean, 1e-9)          // (10*0 + 10*10) / 20
        assertEquals(20.0, p.kappa, 1e-9)
        assertEquals(7.0, p.alpha, 1e-9)         // 2 + 10/2
        assertEquals(1150.0, p.beta, 1e-9)       // 900 + 0 + 10*10*100/(2*20)
        assertEquals(10.0, p.nEff, 1e-9)
        // No evidence = the prior, nEff 0.
        val prior = CellAllocator7962.posterior7962(null, 3.0, 10.0, 900.0)
        assertEquals(3.0, prior.mean, 1e-9)
        assertEquals(0.0, prior.nEff, 1e-9)
        // Winsorized: one +1000% print counts as the ceiling.
        assertEquals(CellAllocator7962.WIN_HI_PCT_7962, CellAllocator7962.winsorize7962(1000.0), 1e-9)
        assertEquals(CellAllocator7962.WIN_LO_PCT_7962, CellAllocator7962.winsorize7962(-500.0), 1e-9)
        val w = stat(1, 1000.0)
        assertEquals(300.0, w.sx, 1e-9)
        // Live outcomes weigh more than one label.
        assertTrue(CellAllocator7962.LIVE_WEIGHT_7962 > 1.0)
    }

    @Test fun thompsonDrawIsDeterministicWithSeededRng() {
        val p = CellAllocator7962.hierarchy7962(listOf(stat(200, 30.0, -10.0)))
        val a = CellAllocator7962.thompsonDraw7962(p, Random(7))
        val b = CellAllocator7962.thompsonDraw7962(p, Random(7))
        assertEquals(a, b, 0.0)
        assertNotEquals(a, CellAllocator7962.thompsonDraw7962(p, Random(8)), 0.0)
        val rng = Random(42)
        val mean = (0 until 4000).map { CellAllocator7962.thompsonDraw7962(p, rng) }.average()
        assertEquals(p.mean, mean, 0.5)
        // An unproven posterior draws wide (exploration), a deep one narrow.
        val wide = CellAllocator7962.posterior7962(null, 0.0, 2.0, 900.0)
        val r2 = Random(1)
        val spreadWide = (0 until 500).map { CellAllocator7962.thompsonDraw7962(wide, r2) }.let { d -> d.maxOrNull()!! - d.minOrNull()!! }
        val spreadDeep = (0 until 500).map { CellAllocator7962.thompsonDraw7962(p, r2) }.let { d -> d.maxOrNull()!! - d.minOrNull()!! }
        assertTrue(spreadWide > spreadDeep * 3)
    }

    @Test fun sparseCellsBorrowFromTheirParents() {
        val global = stat(1000, 20.0, -20.0)
        val lane = stat(200, 30.0, -10.0)
        val sparse = CellAllocator7962.hierarchy7962(listOf(global, lane, stat(3, 100.0)))
        val laneOnly = CellAllocator7962.hierarchy7962(listOf(global, lane))
        assertTrue(laneOnly.mean > 8.0 && laneOnly.mean < 10.0)
        assertTrue(sparse.mean > laneOnly.mean && sparse.mean < 50.0)
        assertEquals(3.0, sparse.nEff, 1e-9)
        val deep = CellAllocator7962.hierarchy7962(listOf(global, lane, stat(300, 120.0, 80.0)))
        assertTrue(deep.mean > 90.0)
        // A cell with no data is its parent.
        val empty = CellAllocator7962.hierarchy7962(listOf(global, lane, null))
        assertEquals(laneOnly.mean, empty.mean, 1e-9)
        assertEquals(0.0, empty.nEff, 1e-9)
    }

    @Test fun kellyMultiplierIsCappedAndOnlyProvenCellsMove() {
        val base = 0.05
        val strong = CellAllocator7962.hierarchy7962(listOf(stat(200, 30.0, -10.0)))
        assertTrue(strong.pPos > 0.99)
        assertEquals(CellAllocator7962.MAX_MULT_7962, CellAllocator7962.kellySizeMult7962(strong, base), 1e-9)
        // Unproven: base size.
        val thin = CellAllocator7962.hierarchy7962(listOf(stat(10, 30.0, -10.0)))
        assertTrue(thin.nEff < CellAllocator7962.PROVEN_N_7962)
        assertEquals(1.0, CellAllocator7962.kellySizeMult7962(thin, base), 1e-9)
        // Uncertain sign: base size.
        val unsure = CellAllocator7962.hierarchy7962(listOf(stat(30, 22.0, -18.0)))
        assertTrue(unsure.pPos < CellAllocator7962.PROVEN_P_7962)
        assertEquals(1.0, CellAllocator7962.kellySizeMult7962(unsure, base), 1e-9)
        // Proven negative: shrinks, never below the floor.
        val bad = CellAllocator7962.hierarchy7962(listOf(stat(200, -30.0, 10.0)))
        assertEquals(CellAllocator7962.MIN_MULT_7962, CellAllocator7962.kellySizeMult7962(bad, base), 1e-9)
        // Combined with Cortex conviction: one 2.5x ceiling, 0.5x floor.
        assertEquals(2.5, CellAllocator7962.combinedMult7962(2.5, 2.5), 1e-9)
        assertEquals(2.0, CellAllocator7962.combinedMult7962(1.0, 2.0), 1e-9)
        assertEquals(0.5, CellAllocator7962.combinedMult7962(1.0, 0.1), 1e-9)
        assertEquals(1.0, CellAllocator7962.combinedMult7962(Double.NaN, 1.0), 1e-9)
        assertTrue(src("engine/cortex/Cortex7885.kt").contains("CONVICTION_MAX_MULT = 2.5"))
        assertEquals(2.5, CellAllocator7962.MAX_MULT_7962, 0.0)
    }

    @Test fun rankingIsEvPerHoldMinute() {
        assertEquals(2.0, CellAllocator7962.score7962(10.0, 5.0), 1e-9)
        assertEquals(5.0, CellAllocator7962.score7962(10.0, 0.5), 1e-9)       // hold floored at 2 min
        assertEquals(10.0 / 120.0, CellAllocator7962.score7962(10.0, 1e6), 1e-9)
        assertTrue(CellAllocator7962.score7962(Double.NaN, 5.0).isNaN())
        assertEquals(listOf("b", "d", "a"), CellAllocator7962.rank7962(mapOf("a" to 1.0, "b" to 3.0, "c" to Double.NaN, "d" to 2.0)))
        // Last slot: only when exactly one is free and a positive benchmark beats the candidate.
        assertTrue(CellAllocator7962.lastSlotRefusal7962(1.0, 3.0, null, 1))
        assertTrue(CellAllocator7962.lastSlotRefusal7962(1.0, null, 2.0, 1))
        assertFalse(CellAllocator7962.lastSlotRefusal7962(1.0, 3.0, null, 2))
        assertFalse(CellAllocator7962.lastSlotRefusal7962(1.0, -1.0, -2.0, 1))
        assertFalse(CellAllocator7962.lastSlotRefusal7962(2.95, 3.0, null, 1))
        assertFalse(CellAllocator7962.lastSlotRefusal7962(1.0, null, null, 1))
        // Rotation: a top cell waiting may rotate a flat/negative-EV hold after 10 minutes.
        val old = CellAllocator7962.MIN_ROTATE_AGE_MS_7962 + 60_000L
        assertTrue(CellAllocator7962.earlyRotation7962(-1.0, -0.2, 5.0, old))
        assertFalse(CellAllocator7962.earlyRotation7962(-1.0, -0.2, 5.0, 5L * 60_000L))
        assertFalse(CellAllocator7962.earlyRotation7962(10.0, 2.0, 5.0, old))
        assertTrue(CellAllocator7962.earlyRotation7962(10.0, 1.0, 5.0, old))
        assertFalse(CellAllocator7962.earlyRotation7962(-1.0, -0.2, 0.0, old))
    }

    @Test fun labelsReachTheBookAndTheDiag() {
        val cell = "TEST_SRC_7962|MOONSHOT|MC_10K_100K|AGE_LT15M"
        repeat(30) { i -> CellAllocator7962.onLabel7962(cell, "MOONSHOT", "LAUNCH_CONTINUATION", if (i % 2 == 0) 40.0 else -5.0) }
        CellAllocator7962.onLabel7962(cell, "PLANWAIT_NO_IMPULSE", "", 500.0)   // plan pseudo-lanes are not decisions
        val line = CellAllocator7962.statusLine()
        assertTrue(line, line.contains(cell))
        assertTrue(line.contains("slotRefusals=") && line.contains("rotations=") && line.contains("sizeMult[min/med/max]="))
    }

    @Test fun allocatorIsWiredWhereItChangesMoney() {
        val lab = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(lab.contains("CellAllocator7962.onLabel7962(o.cell, o.lane, o.setup7955, net)"))
        assertTrue(lab.contains("CellAllocator7962.attach(context)"))
        assertTrue(src("engine/truth/CanonicalFinalizedTradeBus6464.kt").contains("CellAllocator7962.onClose7962(env)"))
        assertTrue(src("engine/truth/TraderSizingBridge6444.kt").contains("CellAllocator7962.combinedSizeMult7962("))
        assertTrue(src("engine/ExecutableOpenGate.kt").contains("CellAllocator7962.throughput7962(modeUpper, lane, mint)"))
        assertTrue(src("engine/BotService.kt").contains("priority += CellAllocator7962.watchPriority7962(ts, nowMs)"))
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("CellAllocator7962.rotationAgeMs7962(ts, lane, posAgeMs,"))
        assertTrue(ex.contains("CellAllocator7962.noteRotation7962()"))
        assertTrue(src("engine/SpikeCapture7943.kt").contains("CellAllocator7962.statusLine()"))
    }
}

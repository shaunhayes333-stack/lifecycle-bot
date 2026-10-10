package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import com.lifecyclebot.engine.truth.TailHunter7996
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7997ProgressiveGradingTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun revisionReplacesNeverDoubleCounts() {
        val s = CortexLedger7885.Stat()
        s.add(-9.0, false); s.add(4.0, false)
        s.revise(-9.0, 250.0, false, true)                 // the -9% launch went on to run
        assertEquals(2.0, s.n, 1e-12)
        assertEquals(254.0, s.sum, 1e-9)
        assertEquals(1.0, s.runners, 1e-12)
        assertEquals(2.0, s.wins, 1e-12)
        val m = SpecialistMiner7972.Stat()
        m.add(-9.0, -9.0); m.revise(-9.0, 120.0, -9.0, 400.0)
        assertEquals(1, m.n); assertEquals(120.0, m.sum, 1e-9); assertEquals(1, m.runners); assertEquals(1, m.wins)
    }

    @Test fun checkpointsAreWiredIntoEveryBook() {
        val f = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(f.contains("val stop7997 = if (o.done60) checkpoint7997(o, net, gross, age, nowMs) else false"))
        assertTrue(f.contains("LanePlaybook7907.reviseLabel7997(o.mint, o.lane, oldNet, newNet, oldGross, newGross)"))
        assertTrue(f.contains("SpecialistMiner7972.reviseLabel7997(o.lane, o.feats7972, oldNet, newNet, oldGross, newGross)"))
        assertTrue(f.contains("CellAllocator7962.reviseLabel7997(o.cell, o.lane, o.setup7955, oldNet, newNet)"))
    }

    @Test fun cryptoRidesTheSwingLadder() {
        val l = TailHunter7996.Ladder7996(100.0, crypto = true)
        for (p in listOf(105.0, 115.0, 140.0, 150.0, 119.0)) l.onPrice(p)
        assertTrue(l.done)   // out 20% below the 1.5x peak
        // 0.5x1.15 + 0.175x1.40 + 0.325x1.19 = 1.20375 -> +20.4% - 3% cost
        assertEquals(17.375, l.resultPct(), 1e-6)
    }
}

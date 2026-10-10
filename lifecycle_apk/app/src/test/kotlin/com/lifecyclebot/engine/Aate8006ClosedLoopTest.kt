package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8006ClosedLoopTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
    private val flag = doubleArrayOf(0.5)

    @Test fun aRevisedLabelLandsWhereADirectOneWould() {
        val ids = listOf("V", "W")
        val edges = listOf(flag, flag)
        val revised = CortexLedger7885()
        val direct = CortexLedger7885()
        repeat(30) { i ->
            val raws = doubleArrayOf((i % 2).toDouble(), 1.0)
            revised.grade("SHITCOIN", ids, edges, raws, -3.0, -1.0)
            direct.grade("SHITCOIN", ids, edges, raws, -3.0, -1.0)
        }
        val raws = doubleArrayOf(1.0, 1.0)
        val trace = revised.grade("SHITCOIN", ids, edges, raws, -9.0, -6.0, "NORMAL")
        assertNotNull(trace)
        revised.revise8006("SHITCOIN", ids, edges, raws, trace!!, -9.0, 140.0, -6.0, 160.0, "NORMAL")
        direct.grade("SHITCOIN", ids, edges, raws, 140.0, 160.0, "NORMAL")
        for (k in listOf("V|SHITCOIN", "W|SHITCOIN", "V|*")) {
            val a = revised.seats[k]!!.bins[1]; val b = direct.seats[k]!!.bins[1]
            assertEquals(k, b.sum, a.sum, 1e-9); assertEquals(k, b.n, a.n, 1e-9)
            assertEquals(k, b.runners, a.runners, 1e-9); assertEquals(k, b.wins, a.wins, 1e-9)
        }
        assertEquals(direct.lanes["SHITCOIN"]!!.sum, revised.lanes["SHITCOIN"]!!.sum, 1e-9)
        assertEquals(direct.lanes["SHITCOIN"]!!.runners, revised.lanes["SHITCOIN"]!!.runners, 1e-9)
        assertEquals(direct.lanes["*"]!!.sum, revised.lanes["*"]!!.sum, 1e-9)
        assertEquals(direct.lanes["SHITCOIN@NORMAL"]!!.sum, revised.lanes["SHITCOIN@NORMAL"]!!.sum, 1e-9)
    }

    @Test fun vetoAuditRetiresOnlyProvenWinners() {
        val win = CortexLedger7885.Stat().also { s -> repeat(50) { s.add(if (it % 5 == 0) -4.0 else 9.0, false) } }
        val mixed = CortexLedger7885.Stat().also { s -> repeat(50) { s.add(if (it % 2 == 0) -6.0 else 6.0, false) } }
        val thin = CortexLedger7885.Stat().also { s -> repeat(20) { s.add(20.0, true) } }
        assertTrue(Cortex7885.vetoProvenWinners8006(win))
        assertFalse(Cortex7885.vetoProvenWinners8006(mixed))
        assertFalse(Cortex7885.vetoProvenWinners8006(thin))
    }

    @Test fun theLoopsAreWired() {
        val frl = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(frl.contains("Cortex7885.reviseLabel8006(o.mint, o.lane, oldNet, newNet, oldGross, newGross)"))
        assertTrue(frl.contains("if (deferExit8006) o.exitDeferred8006 = true else exitSample8006(o, gross0)"))
        assertTrue(frl.contains("endRun8006(o, gross)"))
        val cx = src("engine/cortex/Cortex7885.kt")
        assertTrue(cx.contains("if (trace8006 != null) retain8006(key, p, trace8006, netPct, grossPct)"))
        assertTrue(cx.contains("vetoBook.getOrPut(vetoLaneKey8006(r, p.a.lane))"))
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("Cortex7885.vetoRefusesWinners8006(why, l)"))
        assertTrue(gate.contains("Cortex7885.vetoRefusesWinners8006(prior, l)"))
    }
}

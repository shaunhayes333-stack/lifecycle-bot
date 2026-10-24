package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.Cortex7885
import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexRunOdds8325
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexTailEv8324
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8325 §RUN_ODDS — on a runner lane the Cortex orders (and STRONGs) coins by p(run) read from the voters' bins on the
 * run / no-run outcome; on a scalp lane by the voters' deviation around the lane's all-regime prior; a lane whose own recent
 * order is measured INVERTED buckets NEUTRAL; STRONG sits in the lane's own top fifth. Checked on a synthetic pond where a
 * high-skill voter identifies the runners and a noise voter does not, for every runner lane.
 */
class Aate8325RunOddsTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private val runnerLanes = listOf("SHITCOIN", "MOONSHOT", "EXPRESS", "PROJECT_SNIPER", "MANIPULATED", "DIP_HUNTER", "CORE")
    private val edges = doubleArrayOf(0.5)        // two bins: raw 0 / raw 1
    private val ids = listOf("SWEEP_VOLUME_ACCEL", "NOISE")

    private class Coin(val signal: Double, val noise: Double, val runner: Boolean) {
        val net: Double get() = if (runner) 600.0 else -20.0
        val gross: Double get() = if (runner) 620.0 else -18.0
    }

    /** Deterministic pond: a coin the signal voter flags runs 25% of the time, an unflagged one 1%; the noise voter is random. */
    private fun pond(seed: Long, n: Int): List<Coin> {
        val rnd = java.util.Random(seed)
        return List(n) {
            val sig = if (rnd.nextDouble() < 0.3) 1.0 else 0.0
            val runOdds = if (sig > 0.0) 0.25 else 0.01
            val run = rnd.nextDouble() < runOdds
            Coin(sig, if (rnd.nextDouble() < 0.5) 1.0 else 0.0, run)
        }
    }

    private fun votes(c: Coin) = listOf(
        CortexLedger7885.Vote(ids[0], edges, c.signal, setOf("sweep")),
        CortexLedger7885.Vote(ids[1], edges, c.noise, setOf("noise")),
    )

    private fun trained(lane: String): CortexLedger7885 {
        val led = CortexLedger7885()
        led.runnerLane8324 = { it == lane }
        for (c in pond(11L, 1_200)) led.grade(lane, ids, listOf(edges, edges), doubleArrayOf(c.signal, c.noise), c.net, c.gross)
        return led
    }

    @Test fun aHighSkillVoterOrdersTheRunnersOnEveryRunnerLane() {
        for (lane in runnerLanes) {
            val led = trained(lane)
            val sig = led.seats["${ids[0]}|$lane"]!!; val noi = led.seats["${ids[1]}|$lane"]!!
            val lr = led.lanes[lane]!!.runnerRate()
            val sSkill = CortexRunOdds8325.runSkill8325(DoubleArray(sig.bins.size) { sig.bins[it].n }, DoubleArray(sig.bins.size) { sig.bins[it].runners }, lr)
            val nSkill = CortexRunOdds8325.runSkill8325(DoubleArray(noi.bins.size) { noi.bins[it].n }, DoubleArray(noi.bins.size) { noi.bins[it].runners }, lr)
            assertTrue("$lane signal skill $sSkill", sSkill > 0.02)
            assertTrue("$lane noise skill $nSkill below the signal's", nSkill < sSkill / 4.0)
            // held-out coins: p(run) ranks the runners first
            val test = pond(97L, 600)
            val p = test.map { c -> led.fuseWithSnapshot8324(lane, votes(c)).first.pRun8325 }
            assertTrue(lane, p.all { it.isFinite() })
            val ic = CortexTailEv8324.rankIc8324(p, test.map { it.net })
            assertTrue("$lane rank IC $ic", ic > 0.2)
            val q = CortexTailEv8324.quintiles8324(p, test.map { it.net })
            assertTrue("$lane top fifth ${q[0]} above bottom ${q[1]}", q[0] > q[1])
            assertEquals("RANKS", CortexTailEv8324.verdict8324(ic, q[0], q[1]))
            // a flagged coin reads several times the lane's run odds; an unflagged one below them
            val flagged = led.runOdds8325(lane, votes(Coin(1.0, 0.0, false)))!!.first
            val plain = led.runOdds8325(lane, votes(Coin(0.0, 0.0, false)))!!.first
            assertTrue("$lane $flagged vs $lr", flagged > 2.0 * lr && plain < lr)
        }
    }

    @Test fun aScalpLaneLedgerReadsNoRunOdds() {
        val led = trained("SHITCOIN")
        led.runnerLane8324 = { false }
        assertTrue(led.fuseWithSnapshot8324("SHITCOIN", votes(Coin(1.0, 0.0, false))).first.pRun8325.isNaN())
    }

    @Test fun theTailEvFollowsPRunAndStrongIsTheTopFifth() {
        val xs = List(95) { -20.0 } + List(5) { 600.0 }
        val pond = CortexTailEv8324.pond8324(xs, List(xs.size) { Double.POSITIVE_INFINITY })!!
        val lo = CortexRunOdds8325.ev8325(pond, 0.01); val hi = CortexRunOdds8325.ev8325(pond, 0.20)
        assertTrue(hi > lo)
        assertEquals(0.2 * 600.0 + 0.8 * -20.0, hi, 1e-9)
        assertTrue(CortexRunOdds8325.ev8325(pond, Double.NaN).isNaN())
        // the 8324 STRONG bar is the lane's own top fifth once 50 EVs are known; a scalp STRONG must be there too
        val recent = (1..100).map { it.toDouble() }
        assertTrue(CortexRunOdds8325.strongInTopFifth8325(90.0, recent))
        assertFalse(CortexRunOdds8325.strongInTopFifth8325(50.0, recent))
        assertTrue("thin window", CortexRunOdds8325.strongInTopFifth8325(1.0, recent.take(10)))
        // an inverted lane neither STRONGs nor REFUSEs
        val strong = CortexScoreboard7885.Bucket.STRONG
        assertEquals(CortexScoreboard7885.Bucket.NEUTRAL, CortexRunOdds8325.guardedBucket8325(strong, "INVERTED", 120))
        assertEquals(CortexScoreboard7885.Bucket.NEUTRAL, CortexRunOdds8325.guardedBucket8325(CortexScoreboard7885.Bucket.REFUSE, "INVERTED", 60))
        assertEquals(strong, CortexRunOdds8325.guardedBucket8325(strong, "INVERTED", 59))
        assertEquals(strong, CortexRunOdds8325.guardedBucket8325(strong, "RANKS", 500))
    }

    @Test fun aScalpLaneIsOrderedByTheVotersNotByTheRegimesPastMean() {
        // two coins with the same voter deviation in two regimes whose priors differ: one edge after the regime-neutral read
        val a = CortexRunOdds8325.regimeNeutral8325(rawEdge = 5.0, laneMeanRegime = 3.0, laneMeanAll = -1.0, slope = 0.8)
        val b = CortexRunOdds8325.regimeNeutral8325(rawEdge = -4.0, laneMeanRegime = -6.0, laneMeanAll = -1.0, slope = 0.8)
        assertEquals(a, b, 1e-12)
        assertEquals(-1.0 + 0.8 * 2.0, a, 1e-12)
        // runner lanes keep the 7901 read; scalp lanes read regime-neutral when the all-regime prior is known
        val f = CortexLedger7885.Fused(5.0, 0.0, 3.0, 100.0, 0.0, 0.0, 0.0, emptyList(), laneMeanAll8325 = -1.0)
        assertEquals(3.0 + 0.8 * 2.0, Cortex7885.calibratedRead8325(true, f, 0.8), 1e-12)
        assertEquals(-1.0 + 0.8 * 2.0, Cortex7885.calibratedRead8325(false, f, 0.8), 1e-12)
        assertEquals(3.0 + 0.8 * 2.0, Cortex7885.calibratedRead8325(false, f.copy(laneMeanAll8325 = Double.NaN), 0.8), 1e-12)
        val cx = src("engine/cortex/Cortex7885.kt")
        // 8372 uses the arithmetic fit at the same frozen token score; an unmeasured fit retains the token read.
        assertTrue(cx.contains("val calibrated = calibratedRead8372(runner, fused, locked8325.slope, locked8325.net8372, executableOffset8187(lane))"))
        assertFalse(cx.contains("calibratedRead8372(runner, fused, locked8325.slope, locked8325.net8372) - executableOffset8187(lane)"))
        assertTrue(cx.contains("return calibratedRead8325(runner, fused, slope)"))
        assertTrue(cx.contains("CortexRunOdds8325.ev8325(pond, fused.pRun8325)"))
        // The displayed token forecast, not a separate tail/rank diagnostic, assigns its frozen verdict.
        assertTrue(cx.contains("val bucket = CortexScoreboard7885.bucketOf(calibrated, fused.runnerRate, runner)"))
        assertFalse(cx.contains("val bucket8325 = uncalibratedNeutral8366("))
    }
}

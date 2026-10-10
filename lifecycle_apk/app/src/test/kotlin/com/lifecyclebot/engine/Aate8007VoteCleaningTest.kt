package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate8007VoteCleaningTest {
    private val flag = doubleArrayOf(0.5)

    @Test fun theNoiseBarRisesWithTheNumberOfSeats() {
        assertEquals(1.0, CortexLedger7885.fdrFactor8007(0), 1e-12)
        assertEquals(1.0, CortexLedger7885.fdrFactor8007(1_500), 1e-12)
        assertTrue(CortexLedger7885.fdrFactor8007(5_000) in 1.5..1.7)
    }

    @Test fun aFlatVoterAndAnInvertedVoterAreFiltered() {
        val flat = CortexLedger7885.Seat(2)
        repeat(99) { flat.bins[0].add(1.0, false) }
        flat.bins[1].add(1.0, false)
        assertFalse(CortexLedger7885.discriminates8007(flat))
        val inverted = CortexLedger7885.Seat(2)
        repeat(30) { inverted.above.add(-6.0 + (it % 3), false); inverted.below.add(6.0 + (it % 3), false) }
        assertEquals(0.0, CortexLedger7885.direction8007(inverted), 1e-12)
        val right = CortexLedger7885.Seat(2)
        repeat(30) { right.above.add(6.0 + (it % 3), false); right.below.add(-6.0 + (it % 3), false) }
        assertEquals(1.0, CortexLedger7885.direction8007(right), 1e-12)
    }

    @Test fun onePullCannotSwingAFusion() {
        assertEquals(10.0, CortexLedger7885.clampPull8007(80.0, 2.0), 1e-12)
        assertEquals(-30.0, CortexLedger7885.clampPull8007(-80.0, 10.0), 1e-12)
        assertEquals(4.0, CortexLedger7885.clampPull8007(4.0, 2.0), 1e-12)
    }

    @Test fun amongManyNoiseVotersOnlyTheInformedOneIsSeated() {
        val noiseIds = (0 until 150).map { "N$it" }
        val ids = listOf("INFORMED") + noiseIds
        val edges = ids.map { flag }
        val led = CortexLedger7885()
        val rnd = java.util.Random(8007)
        repeat(400) {
            val good = rnd.nextBoolean()
            val y = (if (good) 5.0 else -5.0) + rnd.nextGaussian() * 10.0
            val raws = DoubleArray(ids.size) { i -> if (i == 0) (if (good) 1.0 else 0.0) else (if (rnd.nextBoolean()) 1.0 else 0.0) }
            led.grade("SHITCOIN", ids, edges, raws, y, y)
        }
        assertTrue(led.seats["INFORMED|SHITCOIN"]!!.authority() > 0.5)
        val seatedNoise = noiseIds.count { (led.seats["$it|SHITCOIN"]?.authority() ?: 0.0) > 0.0 }
        assertTrue("noise seated $seatedNoise", seatedNoise <= 2)
    }

    @Test fun cleaningIsInTheFusionPathAndTheDiag() {
        val l = File("src/main/kotlin/com/lifecyclebot/engine/cortex/CortexLedger7885.kt").readText()
        assertTrue(l.contains("fun authority(): Double = cleanAuthority8007(this, 0)"))
        assertTrue(l.contains("st?.let { cleanAuthority8007(it, seats.size) }"))
        assertTrue(l.contains("clampPull8007(p.netPct - m, laneSd8007)"))
        assertTrue(File("src/main/kotlin/com/lifecyclebot/engine/cortex/Cortex7885.kt").readText().contains("ledger.cleanLine8007()"))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket
import com.lifecyclebot.engine.cortex.CortexVoters7885
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7885 — the Cortex earns trust only from out-of-sample skill. */
class Aate7885CortexTest {
    private val flag = doubleArrayOf(0.5)
    private val ids = listOf("INFORMED", "NOISE", "INFORMED_COPY")
    private val edges = listOf(flag, flag, flag)

    /** INFORMED (and its copy) know the sign of the outcome; NOISE does not. */
    private fun trained(n: Int = 400): CortexLedger7885 {
        val led = CortexLedger7885()
        val rnd = java.util.Random(7885)
        repeat(n) {
            val good = rnd.nextBoolean()
            val noise = if (rnd.nextBoolean()) 1.0 else 0.0
            val y = (if (good) 5.0 else -5.0) + rnd.nextGaussian() * 10.0
            val sig = if (good) 1.0 else 0.0
            led.grade("SHITCOIN", ids, edges, doubleArrayOf(sig, noise, sig), y, if (y > 50) 60.0 else y)
        }
        return led
    }

    @Test fun aFreshInstallHasNoAuthorityAndFusesToTheLanePrior() {
        val led = CortexLedger7885()
        val f = led.fuse("MOONSHOT", listOf(CortexLedger7885.Vote("INFORMED", flag, 1.0, setOf("x"))))
        assertEquals(0.0, f.edgePct, 1e-9)
        assertEquals(0.0, f.totalWeight, 1e-9)
    }

    @Test fun informationEarnsASeatAndNoiseDoesNot() {
        val led = trained()
        val informed = led.seats["INFORMED|SHITCOIN"]!!
        val noise = led.seats["NOISE|SHITCOIN"]!!
        assertTrue("informed skill ${informed.skill()}", informed.skill() > 0.05)
        assertTrue(informed.authority() > 0.5)
        assertEquals(0.0, noise.authority(), 1e-12)
    }

    @Test fun fusionFollowsSeatedVotersAndDoesNotCountOneEvidenceTwice() {
        val led = trained()
        val up = led.fuse("SHITCOIN", listOf(CortexLedger7885.Vote("INFORMED", flag, 1.0, setOf("plan"))))
        val down = led.fuse("SHITCOIN", listOf(CortexLedger7885.Vote("INFORMED", flag, 0.0, setOf("plan"))))
        assertTrue(up.edgePct > up.laneMean + 1.0)
        assertTrue(down.edgePct < down.laneMean - 1.0)
        val twice = led.fuse("SHITCOIN", listOf(
            CortexLedger7885.Vote("INFORMED", flag, 1.0, setOf("plan")),
            CortexLedger7885.Vote("INFORMED_COPY", flag, 1.0, setOf("plan")),
        ))
        // The copy shares evidence and errors: two votes must not double the pull.
        assertTrue((twice.edgePct - twice.laneMean) < 1.3 * (up.edgePct - up.laneMean))
    }

    @Test fun refusalAuthorityNeedsAProvenRecordAndRunnerLanesKeepTheirTail() {
        val loser = CortexLedger7885.Stat().also { s -> repeat(45) { s.add(if (it % 2 == 0) -9.0 else -5.0, false) } }
        val rest = CortexLedger7885.Stat().also { s -> repeat(80) { s.add(if (it % 2 == 0) 3.0 else -1.0, false) } }
        assertTrue(CortexScoreboard7885.refuseProven(loser, rest, runnerLane = false, minN = 40))
        assertFalse(CortexScoreboard7885.refuseProven(loser, rest, runnerLane = false, minN = 60))
        val tail = CortexLedger7885.Stat().also { s -> repeat(45) { s.add(if (it % 2 == 0) -9.0 else -5.0, it < 6) } }
        assertFalse(CortexScoreboard7885.refuseProven(tail, rest, runnerLane = true, minN = 40))
        assertEquals(Bucket.NEUTRAL, CortexScoreboard7885.bucketOf(-6.0, 0.15, runnerLane = true))
        assertEquals(Bucket.REFUSE, CortexScoreboard7885.bucketOf(-6.0, 0.15, runnerLane = false))
        assertEquals(Bucket.STRONG, CortexScoreboard7885.bucketOf(4.0, 0.0, runnerLane = false))
    }

    @Test fun overruleNeedsAProvenStrongRecord() {
        val strong = CortexLedger7885.Stat().also { s -> repeat(50) { s.add(if (it % 2 == 0) 9.0 else 3.0, false) } }
        assertTrue(CortexScoreboard7885.overruleProven(strong))
        val thin = CortexLedger7885.Stat().also { s -> repeat(20) { s.add(9.0, false) } }
        assertFalse(CortexScoreboard7885.overruleProven(thin))
    }

    @Test fun ledgerSurvivesPersistence() {
        val led = trained(200)
        val back = CortexLedger7885().also { it.decode(org.json.JSONObject(led.encode().toString())) }
        assertEquals(led.seats["INFORMED|SHITCOIN"]!!.skill(), back.seats["INFORMED|SHITCOIN"]!!.skill(), 1e-9)
        assertEquals(led.lanes["SHITCOIN"]!!.mean(), back.lanes["SHITCOIN"]!!.mean(), 1e-9)
    }

    @Test fun vetoReasonsMapToStableRuleIds() {
        assertEquals("EDGE_NO_PREDICTED_EDGE", com.lifecyclebot.engine.cortex.Cortex7885.vetoRuleOf("EDGE_7877_NO_PREDICTED_EDGE_SHITCOIN"))
        assertEquals("UNNAMED", com.lifecyclebot.engine.cortex.Cortex7885.vetoRuleOf("lowercase reason"))
    }

    @Test fun aPaidFeatureIsBoughtWhileItIsBeingLearned() {
        assertTrue(com.lifecyclebot.engine.cortex.Cortex7885.enrichmentWorth("VOTER_NEVER_GRADED_7885"))
    }

    @Test fun theSymbolicMoodLayerIsObservationOnly() {
        SymbolicContext.emotionalState = "PANIC"
        assertEquals(1.0, SymbolicContext.getSizeAdjustment(), 1e-12)
        assertEquals(1.0, SymbolicContext.getEntryAdjustment(), 1e-12)
        assertEquals(1.0, SymbolicContext.getHoldPatience(), 1e-12)
        assertEquals(0.5, SymbolicContext.getEntryGreenLight(), 1e-12)
        SymbolicContext.emotionalState = "NEUTRAL"
    }

    @Test fun everyVoterHasAUniqueIdAndAscendingEdges() {
        assertEquals(CortexVoters7885.IDS.size, CortexVoters7885.IDS.toSet().size)
        for (v in CortexVoters7885.ALL) for (i in 1 until v.edges.size) assertTrue(v.id, v.edges[i] > v.edges[i - 1])
    }
}

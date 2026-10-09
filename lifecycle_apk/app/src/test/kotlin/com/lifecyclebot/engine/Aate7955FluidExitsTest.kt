package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.7955 — ExitProfile7955: learned, lane- and setup-fluid exits. */
class Aate7955FluidExitsTest {

    private fun s(peak: Double, ttpMin: Double, read: Double, read5: Double = read) =
        doubleArrayOf(peak, ttpMin, ExitProfile7955.giveback7955(peak, read), ExitProfile7955.giveback7955(peak, read5))

    /** Pops to +25..+55% in two minutes and is back near entry by the read. */
    private val popFade = (0 until 40).map { i -> s(25.0 + (i % 7) * 5.0, 2.0, 1.0 + (i % 3), 3.0) }

    /** Half the launches run past +100%, they hold most of it. */
    private val runner = (0 until 40).map { i ->
        if (i % 2 == 0) s(150.0 + i * 20.0, 25.0, (150.0 + i * 20.0) * 0.75) else s(30.0, 10.0, 20.0)
    }

    @Test fun givebackIsShareOfPeak() {
        assertEquals(0.9, ExitProfile7955.giveback7955(100.0, 10.0), 1e-9)
        assertTrue(ExitProfile7955.giveback7955(5.0, 0.0).isNaN())   // no real peak
        assertEquals(2.0, ExitProfile7955.giveback7955(20.0, -100.0), 1e-9)
    }

    @Test fun popFadeKeySellsEarlyAndHeavy() {
        val p = ExitProfile7955.profileOf7955(popFade)
        assertNotNull(p)
        assertEquals(40, p!!.n)
        assertTrue(p.medGiveback >= 0.7)
        assertEquals(0.0, p.runnerRate, 1e-9)
        val plan = ExitProfile7955.planFrom7955(p, "MEME|SPIKE", "KEY")
        val prior = SpikeCapture7943.TIERS
        assertTrue(plan.popFade)
        assertFalse(plan.runner)
        assertTrue("first tier earlier than +40", plan.tiers[0].first < prior[0].first)
        assertTrue("first tier sells more than 60%", plan.tiers[0].second > prior[0].second)
        assertTrue("trail tighter than prior", plan.trailFrac < 0.35)
        assertTrue("short hold", plan.maxHoldMs < 60L * 60_000L)
        // Tiers stay ordered.
        assertTrue(plan.tiers[1].first > plan.tiers[0].first && plan.tiers[2].first > plan.tiers[1].first)
    }

    @Test fun runnerKeySellsLateAndLightOnAWideTrail() {
        val p = ExitProfile7955.profileOf7955(runner)!!
        assertTrue(p.runnerRate >= 0.25)
        val plan = ExitProfile7955.planFrom7955(p, "MOONSHOT|LAUNCH", "KEY")
        val prior = SpikeCapture7943.TIERS
        assertTrue(plan.runner)
        assertFalse(plan.popFade)
        assertTrue("first tier later than +40", plan.tiers[0].first > prior[0].first)
        assertTrue("light first tier", plan.tiers[0].second < prior[0].second)
        assertTrue("wide trail", plan.trailFrac > 0.35)
    }

    @Test fun thinDataStaysNearThePrior() {
        val prior = SpikeCapture7943.TIERS
        val none = ExitProfile7955.planFrom7955(null)
        assertEquals("PRIOR", none.source)
        assertEquals(prior, none.tiers)
        assertEquals(0.35, none.trailFrac, 1e-9)
        val thin = ExitProfile7955.planFrom7955(ExitProfile7955.profileOf7955(popFade.take(3)), "MEME|SPIKE", "KEY")
        assertFalse(thin.popFade)
        assertFalse(thin.runner)
        for (i in prior.indices) {
            assertEquals(prior[i].second, thin.tiers[i].second, 0.06)
            assertTrue(kotlin.math.abs(thin.tiers[i].first - prior[i].first) / prior[i].first < 0.35)
        }
        assertEquals(0.35, thin.trailFrac, 0.05)
    }

    @Test fun learnedTiersCreditTheLabel() {
        val plan = ExitProfile7955.planFrom7955(ExitProfile7955.profileOf7955(popFade), "MEME|SPIKE", "KEY")
        // A +38% pop back to 0 at the read: the prior ladder (+40) banks nothing, the learned one (~+36) banks it.
        assertEquals(0.0, SpikeCapture7943.realisableGrossPct(38.0, 0.0), 1e-9)
        assertTrue(SpikeCapture7943.realisableGrossPct(38.0, 0.0, plan.tiers) > 10.0)
        assertTrue(SpikeCapture7943.tierReached(38.0, plan.tiers) >= 1)
        assertEquals(0, SpikeCapture7943.tierReached(38.0))
    }

    @Test fun trailAndDeferralFollowThePlan() {
        val pop = ExitProfile7955.planFrom7955(ExitProfile7955.profileOf7955(popFade), "MEME|SPIKE", "KEY")
        val run = ExitProfile7955.planFrom7955(ExitProfile7955.profileOf7955(runner), "MOONSHOT|LAUNCH", "KEY")
        // Pop-fade: a +40% peak with a lane lock at +10 is raised toward the peak.
        assertTrue(ExitProfile7955.applyTrail7955(pop, 10.0, 40.0, 4.0) > 10.0)
        // Runner: a +200% peak with a lock at +170 is widened, never under net break-even.
        val w = ExitProfile7955.applyTrail7955(run, 170.0, 200.0, 4.0)
        assertTrue(w < 170.0 && w >= 4.5)
        // Prior plan changes nothing.
        assertEquals(10.0, ExitProfile7955.applyTrail7955(ExitProfile7955.planFrom7955(null), 10.0, 40.0, 4.0), 1e-9)
        // Deferral: runner holds under +50 in any lane, pop-fade never waits.
        assertTrue(ExitProfile7955.deferGiveBack7955(run, laneDeferred = false, peakPct = 30.0))
        assertFalse(ExitProfile7955.deferGiveBack7955(pop, laneDeferred = true, peakPct = 30.0))
        assertTrue(ExitProfile7955.deferGiveBack7955(null, laneDeferred = true, peakPct = 30.0))
        assertTrue(ExitProfile7955.runnerExits7955(run, laneRunner = false))
        assertFalse(ExitProfile7955.runnerExits7955(pop, laneRunner = true))
        // Max hold: pop-fade past its hold and net green exits; red or early does not.
        assertNotNull(ExitProfile7955.maxHoldExit7955(pop, pop.maxHoldMs + 1, 12.0, 4.0))
        assertNull(ExitProfile7955.maxHoldExit7955(pop, pop.maxHoldMs + 1, 2.0, 4.0))
        assertNull(ExitProfile7955.maxHoldExit7955(pop, pop.maxHoldMs - 1, 12.0, 4.0))
        assertNull(ExitProfile7955.maxHoldExit7955(run, run.maxHoldMs + 1, 12.0, 4.0))
    }
}

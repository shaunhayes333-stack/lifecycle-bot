package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.cortex.LanePlaybook7907.F
import com.lifecyclebot.engine.cortex.LanePlaybook7907.Obs8084
import com.lifecyclebot.engine.cortex.LanePlaybook7907.Outcome8084
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8383 — the evidence-built setup list: tradable envelope, its own cohort, retired losers, appended setups. */
class Aate8383EvidenceSetupListTest {
    private val now = 1_800_000_000_000L
    private val allCore = LanePlaybook7907.CORE_8084.associateWith { Obs8084.OBSERVED }
    private fun f(mcap: Double = 30_000.0, liq: Double = 6_000.0, bp: Double = 60.0, chg5m: Double = 1.0, chg1h: Double = 40.0,
                  dd: Double = 20.0, pxPeak: Double = 0.8, runup: Double = 90.0, age: Double = 30.0) =
        F(age, runup, pxPeak, dd, bp, liq, mcap, 20.0, chg5m, chg1h, Double.NaN, Double.NaN, "", "").also { it.obs8084 = allCore }

    @Test fun envelopeBandsAreTheNetworkEvidence() {
        val all = LanePlaybook7907.envelopeBreaks8383(f())
        assertTrue(all.toString(), all.isEmpty())
        assertEquals(listOf("MCAP_LT_10K", "LIQ_LT_3K"), LanePlaybook7907.envelopeBreaks8383(f(mcap = 6_000.0, liq = 1_000.0)))
        assertNull(LanePlaybook7907.envelope8383("SHITCOIN", f()))
        // V5.0.8389 — SHITCOIN's own mandate starts at a few hundred dollars: the 10k / 3k floors never cut its launches
        assertNull(LanePlaybook7907.envelope8383("SHITCOIN", f(mcap = 6_000.0)))
        assertEquals("MCAP_LT_10K", LanePlaybook7907.envelope8383("TREASURY", f(mcap = 6_000.0)))
        assertEquals("MCAP_GT_5M", LanePlaybook7907.envelope8383("SHITCOIN", f(mcap = 9_000_000.0, liq = 200_000.0)))
        assertNull(LanePlaybook7907.envelope8383("SHITCOIN", f(liq = 1_500.0)))
        assertEquals("LIQ_GT_250K", LanePlaybook7907.envelope8383("SHITCOIN", f(mcap = 2_000_000.0, liq = 400_000.0)))
        assertEquals("MCAP_LIQ_GT_25X", LanePlaybook7907.envelope8383("SHITCOIN", f(mcap = 900_000.0, liq = 20_000.0)))
        assertEquals("BP_LT_45", LanePlaybook7907.envelope8383("SHITCOIN", f(bp = 40.0)))
        // unknown values carry no verdict (missing data is DATA_INCOMPLETE's job, not a band breach)
        assertNull(LanePlaybook7907.envelope8383("SHITCOIN", f(mcap = Double.NaN, liq = Double.NaN, bp = Double.NaN)))
    }

    @Test fun eachSpecialistKeepsItsOwnIdentityBands() {
        // BLUECHIP's large caps / deep liquidity are not cut (its own bins never proved them dead)
        assertNull(LanePlaybook7907.envelope8383("BLUECHIP", f(mcap = 9_000_000.0, liq = 400_000.0)))
        // CASHGEN keeps sub-10k coins (its own bins: 18% positive, its average) but not > 5M caps (3%)
        assertNull(LanePlaybook7907.envelope8383("CASHGEN", f(mcap = 6_000.0, liq = 3_000.0)))
        assertEquals("MCAP_GT_5M", LanePlaybook7907.envelope8383("CASHGEN", f(mcap = 9_000_000.0, liq = 400_000.0)))
        // CYCLIC only cuts thin liquidity for its cap
        assertNull(LanePlaybook7907.envelope8383("CYCLIC", f(mcap = 6_000.0, liq = 1_000.0)))
        assertEquals("MCAP_LIQ_GT_25X", LanePlaybook7907.envelope8383("CYCLIC", f(mcap = 900_000.0, liq = 20_000.0)))
        // unknown lanes carry no envelope
        assertNull(LanePlaybook7907.envelope8383("NOT_A_LANE", f(mcap = 6_000.0)))
        // every playbook lane has an evidence row
        assertEquals(LanePlaybook7907.menuLanes8383(), LanePlaybook7907.ENVELOPE_CUTS_8383.keys)
    }

    @Test fun outsideTheEnvelopeIsItsOwnCohortNotNoTrigger() {
        val c = LanePlaybook7907.evaluate8084("TREASURY", f(mcap = 6_000.0), 1, now)
        assertEquals(Outcome8084.NO_MATCH, c.outcome)
        assertEquals("MCAP_LT_10K", c.envelope8383)
        assertEquals(LanePlaybook7907.OUTSIDE_ENVELOPE_8383, c.tag)
        assertEquals(LanePlaybook7907.NO_TRIGGER, c.consumerTag8087)
        assertFalse(c.noMatchCohort)
        assertTrue(c.matched.isEmpty())
    }

    @Test fun retiredLosersNeverFireAsTheLaneStrategy() {
        // a non-early lane's retired setup never fires as its strategy
        assertTrue(LanePlaybook7907.retired8383("BLUECHIP", "QUIET_ACCUMULATION"))
        assertTrue(LanePlaybook7907.retired8383("SHITCOIN", "BUYERS_DOMINATING"))
        // V5.0.8389 — an early-band lane keeps its launch / early-accumulation family (its own 7907 book refuses real losers)
        val quiet = f(runup = 10.0, dd = 5.0, bp = 60.0, chg5m = 0.5, chg1h = 2.0, pxPeak = 0.95)
        assertTrue(LanePlaybook7907.matches("SHITCOIN", quiet)!!.any { it.id == "QUIET_ACCUMULATION" })
        assertFalse(LanePlaybook7907.retired8383("SHITCOIN", "QUIET_ACCUMULATION"))
        assertTrue("QUIET_ACCUMULATION" in LanePlaybook7907.evaluate8084("SHITCOIN", quiet, 2, now).matched)
    }

    @Test fun theNewSetupsFireOnTheWinningShape() {
        val ids = LanePlaybook7907.matches("MOONSHOT", f())!!.map { it.id }
        assertTrue(ids.toString(), "TREND_PULLBACK_8383" in ids)
        assertTrue(ids.toString(), "SWEET_SPOT_8383" in ids)
        assertTrue(ids.toString(), "RUNUP_HOLD_8383" in ids)
        assertTrue(ids.toString(), "PEAK_RECLAIM_8383" in ids)
        // buying the top is not a pullback
        assertFalse("TREND_PULLBACK_8383" in LanePlaybook7907.matches("MOONSHOT", f(dd = 2.0, pxPeak = 0.99))!!.map { it.id })
        assertTrue("TREND_DIP_8383" in LanePlaybook7907.matches("DIP_HUNTER", f(chg1h = 50.0))!!.map { it.id })
        assertTrue("YOUNG_LIQUID_8383" in LanePlaybook7907.matches("CASHGEN", f(age = 20.0))!!.map { it.id })
        assertTrue("DEEP_RATIO_8383" in LanePlaybook7907.matches("CYCLIC", f(mcap = 30_000.0, liq = 15_000.0))!!.map { it.id })
        assertTrue("STRONG_TREND_8383" in LanePlaybook7907.matches("TREASURY", f(chg1h = 150.0))!!.map { it.id })
    }

    @Test fun appendedSetupsKeepEveryEarlierVoterBin() {
        for (lane in listOf("MOONSHOT", "SHITCOIN", "QUALITY", "TREASURY", "CYCLIC", "DIP_HUNTER", "BLUECHIP", "CORE")) {
            val ids = LanePlaybook7907.menuIds(lane)
            val noTrigger = LanePlaybook7907.setupIndexFor8076(lane, LanePlaybook7907.NO_TRIGGER)
            val added = ids.filter { it.endsWith("_8383") }
            assertTrue(lane, added.isNotEmpty())
            for (id in added) assertTrue("$lane $id", LanePlaybook7907.setupIndexFor8076(lane, id) > noTrigger)
            val bins = (ids + LanePlaybook7907.NO_TRIGGER).map { LanePlaybook7907.setupIndexFor8076(lane, it) }
            assertEquals(lane, bins.size, bins.distinct().size)
        }
    }

    @Test fun treasuryHuntsTheBandThatPays() {
        assertEquals(250_000.0, TreasuryScannerFeed.MIN_TREASURY_MCAP, 0.0)
        assertTrue(LanePlaybook7907.ENV_MCAP_MAX_8383 > TreasuryScannerFeed.MIN_TREASURY_MCAP)
    }
}

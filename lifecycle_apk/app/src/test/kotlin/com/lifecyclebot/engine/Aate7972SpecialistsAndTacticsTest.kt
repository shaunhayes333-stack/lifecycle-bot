package com.lifecyclebot.engine

import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.cortex.LanePlaybook7907
import com.lifecyclebot.engine.truth.SpecialistMiner7972
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7972SpecialistsAndTacticsTest {

    // ── specialist miner ──

    @Test fun promotionNeedsTwoStandardErrorsAboveFifteen() {
        assertTrue(SpecialistMiner7972.promotes7972(25, 40.0, 10.0, 10))
        assertFalse(SpecialistMiner7972.promotes7972(25, 34.0, 10.0, 10))     // +14 after 2 SE
        assertFalse(SpecialistMiner7972.promotes7972(24, 80.0, 5.0, 20))      // too few
        assertFalse(SpecialistMiner7972.promotes7972(40, 60.0, 5.0, 8))       // one fat outlier, 20% wins
        assertTrue(SpecialistMiner7972.demotes7972(10, -20.0, 10.0))
        assertFalse(SpecialistMiner7972.demotes7972(9, -50.0, 5.0))
        assertFalse(SpecialistMiner7972.demotes7972(12, -5.0, 10.0))
    }

    @Test fun combosArePairsPlusCoreTriplesInOneOrder() {
        val a = SpecialistMiner7972.combos7972("MOONSHOT", listOf("mc=10_30K", "bp=GE70", "rg=BULL", "dev=WIN"))
        val b = SpecialistMiner7972.combos7972("MOONSHOT", listOf("dev=WIN", "rg=BULL", "bp=GE70", "mc=10_30K"))
        assertEquals(a.toSet(), b.toSet())
        // 4 facts -> 6 pairs; core = bp, dev, mc -> 1 triple
        assertEquals(7, a.size)
        assertTrue(a.contains("MOONSHOT|bp=GE70|dev=WIN|mc=10_30K"))
        assertTrue(a.all { it.startsWith("MOONSHOT|") })
    }

    @Test fun devAndHolderFacts() {
        assertEquals("NEW", SpecialistMiner7972.devBin7972(0, 0.0))
        assertEquals("ONE", SpecialistMiner7972.devBin7972(1, 90.0))
        assertEquals("WIN", SpecialistMiner7972.devBin7972(4, 30.0))
        assertEquals("LOSE", SpecialistMiner7972.devBin7972(4, -40.0))
        assertEquals("MIXED", SpecialistMiner7972.devBin7972(4, 2.0))
        assertEquals(4.0, SpecialistMiner7972.holderVelocity7972(140, 120, 5.0), 1e-12)
        assertTrue(SpecialistMiner7972.holderVelocity7972(0, 120, 5.0).isNaN())
    }

    @Test fun aCombinationThatPaysIsPromotedThenDemotedOutOfSample() {
        SpecialistMiner7972.resetForTest7972()
        val facts = listOf("mc=10_30K", "bp=GE70", "hv=1_5", "st=HL2")
        repeat(30) { i -> SpecialistMiner7972.onLabel7972("SHITCOIN", "M$i", facts, if (i % 3 == 0) 10.0 else 60.0, 70.0, 1_000L) }
        assertTrue(SpecialistMiner7972.statusLine7972().contains("specialists="))
        assertFalse(SpecialistMiner7972.statusLine7972().contains("specialists=0 "))
        // After promotion, the out-of-sample record goes bad: demoted.
        val later = System.currentTimeMillis() + 60_000L
        repeat(12) { i -> SpecialistMiner7972.onLabel7972("SHITCOIN", "N$i", facts, -40.0, -40.0, later) }
        assertTrue(SpecialistMiner7972.statusLine7972().contains("demoted="))
        assertFalse(SpecialistMiner7972.statusLine7972().contains("demoted=0 "))
        SpecialistMiner7972.resetForTest7972()
    }

    // ── time stop ──

    private fun prof(n: Int, medPeak: Double, ttp: Double) =
        ExitProfile7955.Profile7955(n, n / 2, medPeak, medPeak * 2, medPeak * 3, ttp, 0.5, 0.4, 0.1)

    @Test fun noProgressStopOnlyForNonStartersUnderWater() {
        val p = prof(30, 40.0, 6.0)                                   // clock 12 min, bar max(5, 12, cost+3)
        assertNull(ExitProfile7955.noProgressExit7973(p, 11 * 60_000L, -8.0, 2.0, 4.0))   // before the clock
        assertEquals("NO_PROGRESS_TIME_STOP_7973_12M", ExitProfile7955.noProgressExit7973(p, 13 * 60_000L, -8.0, 2.0, 4.0))
        assertNull(ExitProfile7955.noProgressExit7973(p, 13 * 60_000L, -8.0, 15.0, 4.0))  // it got going once
        assertNull(ExitProfile7955.noProgressExit7973(p, 13 * 60_000L, 1.0, 2.0, 4.0))    // not under water
        assertNull(ExitProfile7955.noProgressExit7973(prof(10, 40.0, 6.0), 60 * 60_000L, -8.0, 0.0, 4.0))  // unlearned key
    }

    // ── scale-in ──

    private fun read(hhHl: Int, hl: Boolean, broke: Boolean, share: Double = 0.7) =
        StructureTracker7962.Read7962(30, hhHl, 1.0, 1.2, true, hl, false, false, share, 1.1, broke)

    @Test fun addsOnlyOnNewConfirmedSwingsInProfit() {
        assertTrue(RunnerGrab7967.addConfirmed7973(read(3, true, false), 2, 0, 12.0))
        assertFalse(RunnerGrab7967.addConfirmed7973(read(2, true, false), 2, 0, 12.0))     // no new swing
        assertFalse(RunnerGrab7967.addConfirmed7973(read(3, true, false), 2, 0, -1.0))     // never average down
        assertFalse(RunnerGrab7967.addConfirmed7973(read(3, true, true), 2, 0, 12.0))      // broken
        assertFalse(RunnerGrab7967.addConfirmed7973(read(3, true, false, 0.4), 2, 0, 12.0)) // sellers
        assertFalse(RunnerGrab7967.addConfirmed7973(read(5, true, false), 2, 2, 40.0))     // two adds already
    }

    // ── new setups ──

    @Test fun secondWaveWindow() {
        val now = 10_000_000L
        assertTrue(LanePlaybook7907.secondWave7974(now - 10 * 60_000L, 1.0, 0.8, now, false))
        assertFalse(LanePlaybook7907.secondWave7974(now - 60_000L, 1.0, 0.8, now, false))      // too soon
        assertFalse(LanePlaybook7907.secondWave7974(now - 200 * 60_000L, 1.0, 0.8, now, false)) // too late
        assertFalse(LanePlaybook7907.secondWave7974(now - 10 * 60_000L, 1.0, 0.4, now, false))  // collapsed
        assertFalse(LanePlaybook7907.secondWave7974(now - 10 * 60_000L, 1.0, 0.8, now, true))   // still held
    }

    @Test fun rangeSupportSwingFiresOnEstablishedMemes() {
        fun f(mcap: Double, dd: Double, chg5m: Double) = LanePlaybook7907.F(
            age = 2_000.0, runup = 50.0, pxPeak = 0.5, dd = dd, bp = 60.0, liq = 900_000.0, mcap = mcap, top = 10.0,
            chg5m = chg5m, chg1h = -3.0, holderGrowth = 1.0, volAccel = 1.0, planSetup = "", launchPhase = "",
        )
        assertTrue(LanePlaybook7907.matches("QUALITY", f(7_000_000.0, 40.0, 1.5))!!.any { it.id == "RANGE_SUPPORT_SWING" })
        assertFalse(LanePlaybook7907.matches("QUALITY", f(300_000.0, 40.0, 1.5))!!.any { it.id == "RANGE_SUPPORT_SWING" })  // not established
        assertFalse(LanePlaybook7907.matches("QUALITY", f(7_000_000.0, 10.0, 1.5))!!.any { it.id == "RANGE_SUPPORT_SWING" }) // top of range
        assertFalse(LanePlaybook7907.matches("QUALITY", f(7_000_000.0, 40.0, -2.0))!!.any { it.id == "RANGE_SUPPORT_SWING" }) // still falling
        assertTrue(LanePlaybook7907.menuIds("MOONSHOT").contains("SECOND_WAVE"))
    }
}

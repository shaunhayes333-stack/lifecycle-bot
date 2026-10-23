package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.engine.truth.ExploreShadow8347
import com.lifecyclebot.engine.truth.ScalpExit8351
import org.junit.Assert.*
import org.junit.Test

/** 8351 — cohort niches, inverted-filter detection, live scalp exit. */
class Aate8351TuneLanesTest {
    @Test fun cohortNeedsBothHalvesPositiveAndTwoSe() {
        val good = List(60) { if (it % 4 == 0) -8.0 else 15.0 }
        assertTrue(ExploreShadow8347.cohortProof8351(good).proven)
        // a hot first half and a cold second half is luck, not a niche
        val burst = List(30) { 40.0 } + List(30) { -5.0 }
        assertFalse(ExploreShadow8347.cohortProof8351(burst).proven)
        assertFalse(ExploreShadow8347.cohortProof8351(List(39) { 20.0 }).proven)
    }

    @Test fun nicheMatchesOnlyItsOwnSetupOrBand() {
        val p = EvidenceHold8336.proof8336(listOf(1.0), listOf(0.01))
        val bySetup = ExploreShadow8347.Choice8351(ExploreShadow8347.Entry8347.PULLBACK, ExploreShadow8347.Exit8347.SCALP, "S", "IGNITION_5M", p)
        assertTrue(ExploreShadow8347.inNiche8351(bySetup, "ignition_5m", "MC_LT10K"))
        assertFalse(ExploreShadow8347.inNiche8351(bySetup, "BUYERS_DOMINATING", "MC_LT10K"))
        val whole = bySetup.copy(dim = null, value = null)
        assertTrue(ExploreShadow8347.inNiche8351(whole, null, null))
    }

    @Test fun invertedFilterWhenSampledNosBeatYesesClearly() {
        assertTrue(ExploreShadow8347.filterInverted8351(List(25) { -6.0 }, List(25) { 2.0 }))
        assertFalse(ExploreShadow8347.filterInverted8351(List(25) { -6.0 }, List(10) { 20.0 }))   // too few rejects
        assertFalse(ExploreShadow8347.filterInverted8351(List(25) { -1.0 }, List(25) { 2.0 }))    // gap under 5 pts
    }

    @Test fun scalpExitLadderStopFloorAndClock() {
        assertEquals("SCALP_HARD_STOP_8351", ScalpExit8351.decide8351(-7.0, 0.0, 1_000, 0, 10.0, 6.0)?.second)   // stop 6 %
        assertEquals(0.5 to "SCALP_TAKE_PROFIT_8351_T1", ScalpExit8351.decide8351(16.0, 16.0, 1_000, 0, 10.0, 6.0))
        assertEquals(1.0 to "SCALP_TAKE_PROFIT_8351_T2", ScalpExit8351.decide8351(31.0, 31.0, 1_000, 1, 10.0, 6.0))
        assertEquals("SCALP_PROFIT_LOCK_8351", ScalpExit8351.decide8351(6.0, 14.0, 1_000, 0, 10.0, 6.0)?.second)  // floor 7
        assertEquals("SCALP_TIME_8351", ScalpExit8351.decide8351(2.0, 4.0, 11 * 60_000L, 0, 10.0, 6.0)?.second)
        assertNull(ScalpExit8351.decide8351(2.0, 4.0, 60_000L, 0, 10.0, 6.0))
    }
}

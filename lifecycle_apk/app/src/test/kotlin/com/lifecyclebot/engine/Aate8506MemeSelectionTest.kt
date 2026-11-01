package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.TokenMetricStageRouter.Stage
import com.lifecyclebot.engine.chart.StructureTracker7962
import com.lifecyclebot.engine.market.EntryFlow8506
import com.lifecyclebot.engine.market.LaneHunter7297
import com.lifecyclebot.engine.market.MarketSweep7297
import com.lifecyclebot.engine.market.OpportunityRoute8293
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.PairFlow8169
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.*
import org.junit.Test

class Aate8506MemeSelectionTest {
    private val bought = EntryFlow8506(100L, 9, 3, 400.0, 4.0)
    private fun row(mint: String) = MarketSweep7297.Row(mint, "R", "Runner", 1.0, 12_000.0,
        3_500.0, 800.0, 3_000.0, 8.0, 20, 0, 0.0, false, 0.02, setOf("JUP_RECENT"))

    @Test fun rotatingLaunchObservationKeepsAnySubscriptionStillNeededByAnotherReader() {
        val ws = com.lifecyclebot.network.PumpFunWS
        assertTrue(ws.releaseLifecycleSub8506(false, false))
        assertFalse(ws.releaseLifecycleSub8506(false, true))
        assertFalse(ws.releaseLifecycleSub8506(true, false))
        assertFalse(ws.releaseLifecycleSub8506(true, true))
    }

    @Test fun capEnrichmentKeepsProofOnItsOwnMintOnly() {
        val young = "A".repeat(44)
        val old = "B".repeat(44)
        val undated = "C".repeat(44)
        val first = MarketSweep7297.merge(listOf(row(young), row(old), row(undated))) {
            when (it) { young -> 0.02; old -> 48.0; else -> null }
        }
        val after = MarketSweep7297.merge(first + row(undated).copy(observedAtMs8169 = 100L))
        val eligible = after.filter { LaneHunter7297.sniperAgeFits8419(it.ageHours, it.birthProven8419) }
        assertEquals(listOf(young), eligible.map { it.mint })
        assertEquals(48.0, after.single { it.mint == old }.ageHours, 0.0)
        assertFalse(after.single { it.mint == undated }.birthProven8419)
    }

    @Test fun everyMemeDeskNeedsAResolvedStageAndActiveBuyVolume() {
        val t = TokenMetricStageRouter
        for (p in LaneHunter7297.profiles) {
            val fit = TokenMetricStageRouter.LaneFit(true, p.lane, Stage.BASE_START, "")
            assertEquals(p.lane, "FLOW_UNOBSERVED", t.entryWait8506(fit, null))
            assertEquals(p.lane, "NO_BUY_VOLUME", t.entryWait8506(fit, bought.copy(volume = 0.0)))
            assertEquals(p.lane, "NO_BUY_VOLUME", t.entryWait8506(fit, bought.copy(buys = 0)))
            assertEquals(p.lane, "STAGE_UNRESOLVED", t.entryWait8506(fit.copy(stage = Stage.UNKNOWN), bought))
            assertNull(p.lane, t.entryWait8506(fit, bought))
        }
        val runner = TokenMetricStageRouter.LaneFit(true, "MOONSHOT", Stage.BASE_START, "")
        assertEquals("NO_IGNITION", t.entryWait8506(runner, bought.copy(move = -2.0, buys = 2)))
        assertEquals("NO_RECLAIM", t.entryWait8506(runner.copy(stage = Stage.DUMPING, allowed = false), bought))
        assertNull(t.entryWait8506(runner.copy(lane = "CASHGEN"), bought.copy(move = 0.0)))
    }

    @Test fun activeProviderCanStageAnEarlyBreakoutBeforeLocalBarHistoryBuilds() {
        val now = System.currentTimeMillis()
        val ts = TokenState(mint = "8506Breakout".padEnd(44, 'A'), symbol = "BRK").apply {
            lastPrice = 1.0; lastPriceUpdate = now; lastMcap = 50_000.0; lastLiquidityUsd = 8_000.0
        }
        val rising = bought.copy(atMs = now, move = 20.0)
        val stage = TokenMetricStageRouter.snapshot(ts, now, rising)
        assertEquals(Stage.CONTROLLED_MARKUP, stage.stage)
        assertNotEquals(Stage.CONTROLLED_MARKUP, TokenMetricStageRouter.snapshot(ts, now, rising.copy(volume = 0.0)).stage)
    }

    @Test fun freshTradesReplaceAnOlderProviderObservationBothWays() {
        val mint = "8506TradeTape".padEnd(44, 'A')
        val now = System.currentTimeMillis()
        PairFlow8169.note8169(mint, PairFlow8169.Flow8169(10, 2, 32, 5, 10.0, 20.0, 10_000.0,
            now - 45_000L, volumeM5 = 800.0))
        repeat(4) { i -> TradeTape8100.note8100(mint, 1.0 - i * 0.05, 0.3, false, now - (4 - i) * 1000L) }
        val falling = EntryFlow8506.forMint(mint, now)!!
        assertEquals(0, falling.buys); assertTrue(falling.move < 0)
        assertNull(EntryFlow8506.forMint(mint, now + 400_000L))
        PairFlow8169.note8169(mint, PairFlow8169.Flow8169(12, 3, 35, 9, 8.0, 20.0, 10_000.0,
            now, volumeM5 = 900.0))
        assertEquals(8.0, EntryFlow8506.forMint(mint, now)!!.move, 0.0)
    }

    @Test fun aFlatVolumeFreeStructureCannotQualifyAsAnUnlearnedRunner() {
        val r = StructureTracker7962.Read7962(20, 0, 1.0, 1.0, false, false, false, false, Double.NaN, 1.0, false)
        assertFalse(RunnerGrab7967.formingStructure8270(null, r))
        assertFalse(RunnerGrab7967.formingStructure8270(null, r.copy(buyShare = 0.9)))
        assertTrue(RunnerGrab7967.formingStructure8270(null, r.copy(hhHl = 2, higherLow = true, buyShare = 0.7, lastClose = 1.1)))
    }

    @Test fun entriesWithMissingEvidenceUseExistingReassessmentFeatures() {
        val c = CandidateAdmission8136.Candidate8136("m", "MOONSHOT", "IGNITION", live = true,
            nativeQualified8213 = true, nativeSetup8213 = "IGNITION", entryWait8506 = "FLOW_UNOBSERVED",
            expectedGrossPct = 20.0, costPct = 1.0, genericScore = 80.0, genericFloor = 15.0)
        val wait = CandidateAdmission8136.decide8136(c) { false }
        assertEquals(CandidateAdmission8136.Terminal8136.DEFER_DATA, wait.terminal)
        assertTrue("swaps" in CandidateAdmission8136.missingOf8144(wait))
        assertTrue(CandidateAdmission8136.decide8136(c.copy(entryWait8506 = null)).admitted)
    }

    @Test fun aConfirmedRecoveryLeadsTheDipDeskAndNotTheSniper() {
        assertTrue(OpportunityRoute8293.leadsLane8506("DIP_HUNTER", "DIP_RECOVERY"))
        assertFalse(OpportunityRoute8293.leadsLane8506("PROJECT_SNIPER", "DIP_RECOVERY"))
        assertFalse(OpportunityRoute8293.leadsLane8506("DIP_HUNTER", "BREAKOUT_EXPANSION"))
        assertTrue(OpportunityRoute8293.leadsLane8506("EXPRESS", "BREAKOUT_EXPANSION"))
    }
}

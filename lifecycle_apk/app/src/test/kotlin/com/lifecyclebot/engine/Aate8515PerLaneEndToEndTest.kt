package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.LaneCheatSheet8323
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Candidate8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.NetEdge8030
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.8515 — per-lane behavioural fixtures through the real decision code (no string checks):
 * admission (winner / danger / stale-defer / uneconomic / chase), lane token-type bands, stop anchor vs round trip,
 * and winner-only high-water-mark profit fees.
 */
class Aate8515PerLaneEndToEndTest {
    private val lanes = listOf("QUALITY","BLUECHIP","SHITCOIN","CYCLIC","EXPRESS","CORE","MOONSHOT","PROJECT_SNIPER","DIP_HUNTER","MANIPULATED","TREASURY","CASHGEN")
    private val setup = "LANE_SETUP_8515"
    private fun winner(lane: String, live: Boolean = false) = Candidate8136(
        mint = "Mint8515$lane", lane = lane, selectedSetup = setup, priceAgeMs = 2_000L,
        expectedGrossPct = 24.0, costPct = 4.5, costSizeSol = 0.03, downsidePct = 8.0,
        expectedGrossBySetup = mapOf(setup to 24.0),
        setups = listOf(Evidence8136("SETUP", setup, 60, 11.0, 1.4, 0.2, true)),
        timing = Evidence8136("TIMING", setup, 45, 9.0, 1.6, 0.2, true),
        nativeQualified8213 = true, nativeSetup8213 = setup, live = live)

    private fun decide(c: Candidate8136) = CandidateAdmission8136.decide8136(c)

    @Test fun everyLaneAdmitsAProvenSafeExecutableWinner() {
        lanes.forEach { lane ->
            val d = decide(winner(lane))
            assertEquals("$lane: ${d.reason} ${d.trace}", Terminal8136.ADMIT, d.terminal)
            assertTrue("$lane admitted without positive expected net: ${d.expectedNetPct}", !d.expectedNetPct.isFinite() || d.expectedNetPct > 0.0)
        }
    }

    @Test fun everyLaneRefusesHardDangerEvenWithAProvenSetup() {
        lanes.forEach { lane ->
            assertEquals(lane, Terminal8136.REFUSE, decide(winner(lane).copy(safetyRefusal = "RUG_FREEZE_AUTHORITY")).terminal)
            assertEquals(lane, Terminal8136.REFUSE, decide(winner(lane).copy(structuralRisk = "LP_UNLOCKED_TOP_HOLDER_80")).terminal)
        }
    }

    @Test fun everyLaneDefersStaleOrNeverPricedCoinsInsteadOfBuyingOrForgetting() {
        lanes.forEach { lane ->
            assertEquals(lane, Terminal8136.DEFER_DATA, decide(winner(lane).copy(priceAgeMs = Long.MAX_VALUE)).terminal)
            assertEquals(lane, Terminal8136.DEFER_DATA, decide(winner(lane).copy(priceAgeMs = 6 * 60_000L)).terminal)
            // a hot launch needs a price fresher than a minute; a normal coin tolerates it
            assertEquals(lane, Terminal8136.DEFER_DATA, decide(winner(lane).copy(hotLaunch = true, priceAgeMs = 90_000L)).terminal)
            assertNotEquals(lane, Terminal8136.DEFER_DATA, decide(winner(lane).copy(priceAgeMs = 90_000L)).terminal)
        }
    }

    @Test fun everyLaneRefusesAMoveThatDoesNotPayTheRoundTrip() {
        lanes.forEach { lane ->
            // expected 5% against a 4.5% round trip: positive gross, negative after the 1.5x cost margin
            val c = winner(lane).copy(expectedGrossPct = 5.0, expectedGrossBySetup = mapOf(setup to 5.0),
                setups = listOf(Evidence8136("SETUP", setup, 60, 0.4, 1.4, 0.0, true)),
                timing = Evidence8136("TIMING", setup, 45, 0.3, 1.6, 0.0, true))
            assertNotEquals("$lane bought a move that cannot pay its costs", Terminal8136.ADMIT, decide(c).terminal)
        }
        assertFalse(NetEdge8030.entryClears8030(5.0, 4.5))
        assertTrue(NetEdge8030.entryClears8030(7.0, 4.5))
    }

    @Test fun everyLaneRefusesASetupWhoseOwnRecordLoses() {
        lanes.forEach { lane ->
            val c = winner(lane).copy(setups = listOf(Evidence8136("SETUP", setup, 60, -6.0, 1.2, 0.05, true)),
                timing = Evidence8136("TIMING", setup, 45, -5.0, 1.3, 0.05, true))
            assertNotEquals("$lane admitted a setup proven to lose", Terminal8136.ADMIT, decide(c).terminal)
        }
    }

    @Test fun laneTokenTypeBandsAreCoherent() {
        lanes.forEach { lane ->
            val lo = LaneCheatSheet8323.mcapMin8323(lane); val hi = LaneCheatSheet8323.mcapMax8323(lane)
            assertTrue("$lane mcap band $lo..$hi", lo >= 0.0 && hi > lo)
            val (aLo, aHi) = LaneCheatSheet8323.ageBandMin8323(lane)
            assertTrue("$lane age band $aLo..$aHi", aLo >= 0.0 && aHi > aLo)
            assertTrue("$lane liquidity floor", LaneCheatSheet8323.liqMin8389(lane) >= 0.0)
        }
        // common sense: a blue chip is a bigger, older coin than a fresh shitcoin / sniper launch
        assertTrue(LaneCheatSheet8323.mcapMin8323("BLUECHIP") > LaneCheatSheet8323.mcapMin8323("SHITCOIN"))
        assertTrue(LaneCheatSheet8323.mcapMin8323("BLUECHIP") > LaneCheatSheet8323.mcapMin8323("PROJECT_SNIPER"))
        assertTrue(LaneCheatSheet8323.ageBandMin8323("BLUECHIP").second > LaneCheatSheet8323.ageBandMin8323("PROJECT_SNIPER").first)
    }

    @Test fun everyStopSitsAtLeastOneAndAHalfRoundTripsAway() {
        listOf(2.0, 4.5, 8.0, 15.0).forEach { rt ->
            listOf(1.0, 5.0, 12.0).forEach { base ->
                val stop = ExitDoctrine8169.floorStopMag8169(base, Double.NaN, Double.NaN, rt)
                assertTrue("stop $stop for round trip $rt", stop >= 1.5 * rt - 1e-9)
            }
        }
    }

    @Test fun profitFeeChargesWinnersOnlyAboveTheHighWaterMark() {
        val f = PerformanceFees8391
        var s = PerformanceFees8391.State()
        val win = f.assess(s, 1_000_000_000L); assertTrue(win.fee > 0); s = win.state
        assertEquals((1_000_000_000L * f.RATE).toLong(), win.fee)
        val loss = f.assess(s, -400_000_000L); assertEquals(0L, loss.fee); s = loss.state
        val recover = f.assess(s, 400_000_000L); assertEquals("recovering a loss is not new profit", 0L, recover.fee); s = recover.state
        val newHigh = f.assess(s, 500_000_000L); assertEquals((500_000_000L * f.RATE).toLong(), newHigh.fee)
    }

    @Test fun compoundingAfterCostsAndFeesStaysPositiveOnlyWithRealEdge() {
        // whole-trade arithmetic the admission margin protects: 20 trades at the fixture economics
        fun grow(gross: Double, cost: Double, wr: Double, loss: Double): Double {
            var w = 1.0
            repeat(20) { i -> val r = if (i < (20 * wr).toInt()) (gross - cost) * (1 - PerformanceFees8391.RATE) else -(loss + cost); w *= 1 + r / 100.0 * 0.25 }
            return w
        }
        // costs are paid on losers too: +24/-8 gross at a 4.5% round trip only compounds near a 50% win rate
        assertTrue(grow(24.0, 4.5, 0.5, 8.0) > 1.0)
        assertTrue("40% WR at this payoff is ~break-even after costs and fees", grow(24.0, 4.5, 0.4, 8.0) in 0.98..1.02)
        assertTrue(grow(24.0, 4.5, 0.35, 8.0) < 1.0)
        assertTrue("a sub-cost edge must bleed, which is why admission refuses it", grow(5.0, 4.5, 0.5, 8.0) < 1.0)
    }

    @Test fun earlyRunStandDownNeverBuysTheTickPinnedAtItsHigh() {
        assertFalse("at the high", LiveEdgeGate7877.offTheHigh8516(1.0))
        assertFalse("within 8 % of the high", LiveEdgeGate7877.offTheHigh8516(0.95))
        assertFalse("unknown peak claims nothing", LiveEdgeGate7877.offTheHigh8516(Double.NaN))
        assertTrue("a developing run on its pullback", LiveEdgeGate7877.offTheHigh8516(0.88))
        assertTrue(LiveEdgeGate7877.grabLaneEarly8418(true, 95.0, 20.0))  // the early-run bound itself is unchanged
    }

    @Test fun matureSwingLanesTradeWithoutALaunchPhaseButLaunchDesksStillNeedOne() {
        val r = TokenMetricStageRouter
        fun snap(stage: TokenMetricStageRouter.Stage, liq: Double = 40_000.0, buy: Double = 58.0, ml: Double = 20.0) =
            TokenMetricStageRouter.Snapshot(stage, 600.0, 0.85, 15.0, 30.0, buy, 100.0 - buy, liq, liq * ml, ml, 8.0, "fixture")
        for (lane in listOf("QUALITY", "BLUECHIP", "TREASURY", "CORE")) {
            assertTrue(lane, r.matureSwingFit8517(lane, snap(TokenMetricStageRouter.Stage.NO_SETUP)))
            assertFalse("$lane thin pool", r.matureSwingFit8517(lane, snap(TokenMetricStageRouter.Stage.NO_SETUP, liq = 5_000.0)))
            assertFalse("$lane sellers in charge", r.matureSwingFit8517(lane, snap(TokenMetricStageRouter.Stage.NO_SETUP, buy = 45.0)))
            assertFalse("$lane bloated mcap/liq", r.matureSwingFit8517(lane, snap(TokenMetricStageRouter.Stage.NO_SETUP, ml = 120.0)))
        }
        for (lane in listOf("SHITCOIN", "EXPRESS", "MOONSHOT", "PROJECT_SNIPER", "MANIPULATED"))
            assertFalse("$lane launch desk needs a phase", r.matureSwingFit8517(lane, snap(TokenMetricStageRouter.Stage.NO_SETUP)))
    }
}

package com.lifecyclebot.engine

import com.lifecyclebot.engine.cortex.CortexLedger7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885
import com.lifecyclebot.engine.cortex.CortexScoreboard7885.Bucket
import com.lifecyclebot.engine.cortex.LaneEvidence8077
import com.lifecyclebot.engine.learning.TacticSwitcher
import com.lifecyclebot.engine.learning.TacticSwitcher.Tactic
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate8077StrategyAlignmentTest {
    private fun stat(n: Int, mean: Double, sd: Double = 0.0) = CortexLedger7885.Stat().also { s ->
        repeat(n) { s.add(mean + if (it % 2 == 0) sd else -sd, mean >= 50.0) }
    }
    @Test fun lowWinRateDoesNotDiscardPositiveNetRunnerExpectancy() {
        val expectancy = (15 * 300.0 - 45 * 4.0) / 60.0
        assertFalse(LaneEvidence8077.lowWinRateNeedsPivot(60, 0.75, expectancy))
        assertFalse(LaneEvidence8077.lowWinRateNeedsPivot(60, 0.75, 0.001))
        assertTrue(LaneEvidence8077.lowWinRateNeedsPivot(60, 0.75, -0.1))
        assertTrue(LaneEvidence8077.lowWinRateNeedsPivot(60, 0.75, 0.0))
        assertFalse(LaneEvidence8077.lowWinRateNeedsPivot(49, 0.9, -8.0))
        assertFalse(LaneEvidence8077.lowWinRateNeedsPivot(60, 0.70, -8.0))
        assertFalse(LaneEvidence8077.lowWinRateNeedsPivot(60, 0.75, Double.NaN))
    }
    @Test fun tradeTacticMatchesTheActualSetupRatherThanTheFirstSubstring() {
        assertEquals(Tactic.BREAKOUT, TacticSwitcher.seedTactic8049("QUALITY", "PLAN_BASE_BREAKOUT"))
        assertEquals(Tactic.BREAKOUT, TacticSwitcher.seedTactic8049("SHITCOIN", "BASE_BREAKOUT_FLOW"))
        assertEquals(Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049("SHITCOIN", "PRE_IGNITION_BASE"))
        assertEquals(Tactic.PULLBACK, TacticSwitcher.seedTactic8049("QUALITY", "PLAN_PULLBACK_RECLAIM"))
        assertEquals(Tactic.BREAKOUT, TacticSwitcher.seedTactic8049("MOONSHOT", "SPECIALIST_EDGE"))
        assertEquals(Tactic.PULLBACK, TacticSwitcher.seedTactic8049("QUALITY", "SPECIALIST_EDGE"))
        assertEquals(Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049("SHITCOIN", "SPECIALIST_EDGE"))
        assertEquals(Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049("CASHGEN", "EXPERT_ENTRY"))
        assertEquals(Tactic.MOMENTUM, TacticSwitcher.seedTactic8049("EXPRESS", "ACCELERATING_TAPE"))
    }
    @Test fun thinOutlierCannotHideAnAlreadyFiredProvenSetup() {
        val proven = LaneEvidence8077.Setup("SPECIALIST_EDGE", 40.0, 240.0, 43.8, 240.0 * 16.8 * 16.8)
        val noisy = LaneEvidence8077.Setup("NOISY_NEW_HIGH", 500.0, 2.0, 5000.0, 20_000_000.0)
        assertEquals("SPECIALIST_EDGE", LaneEvidence8077.choose(listOf(noisy, proven))?.id)
        val runner = LaneEvidence8077.Setup("LAUNCH_LADDER_PROVEN", 270.1, 41.0, 401.3, 41.0 * 73.5 * 73.5)
        assertEquals("LAUNCH_LADDER_PROVEN", LaneEvidence8077.choose(listOf(proven, runner))?.id)
        assertEquals("NOISY_NEW_HIGH", LaneEvidence8077.choose(listOf(noisy))?.id)
        assertNull(LaneEvidence8077.choose(emptyList()))
        assertNull(LaneEvidence8077.choose(listOf(noisy.copy(expected = Double.NaN))))
    }
    @Test fun fullAbsoluteProofDoesNotSkipItsPositiveNeutralBenchmark() {
        val strong = stat(60, 6.0, 0.2)
        val neutral = stat(30, 5.0, 0.2)
        assertTrue(CortexScoreboard7885.overruleProven(strong))
        assertEquals(0.0, CortexScoreboard7885.authorityFraction7955(strong, neutral, false), 1e-12)
        assertEquals(1.0, CortexScoreboard7885.authorityFraction7955(strong, stat(30, -5.0), false), 1e-12)
        assertEquals(0.0, CortexScoreboard7885.graduatedFraction7955(50.0, Double.NaN, 0.0, 0.0, 2.0, 40, true), 0.0)
    }
    @Test fun paperLossesDoNotSuspendLiveLaneAuthority() {
        val b = CortexScoreboard7885()
        repeat(60) { b.record("MOONSHOT", Bucket.STRONG, true, 40.0, 40.0) }
        repeat(30) { b.recordRealized("PAPER", "MOONSHOT", Bucket.STRONG, -20.0) }
        repeat(20) { b.recordRealized("PAPER", "MOONSHOT", Bucket.REFUSE, 60.0) }
        assertTrue(b.inverted7948("MOONSHOT", "PAPER"))
        assertFalse(b.inverted7948("MOONSHOT", "LIVE"))
        assertTrue(b.fractionFor7955("MOONSHOT", "LIVE") > 0.0)
        assertEquals(0.0, b.fractionFor7955("MOONSHOT", "PAPER"), 0.0)
        repeat(30) { b.recordRealized("LIVE", "MOONSHOT", Bucket.STRONG, -20.0) }
        repeat(20) { b.recordRealized("LIVE", "MOONSHOT", Bucket.REFUSE, 60.0) }
        assertTrue(b.inverted7948("MOONSHOT", "LIVE"))
    }
    @Test fun invalidErrorMetricsNeverBecomeGiganticSkills() {
        val seat = CortexLedger7885.Seat(2).also { it.scored = 100 }
        seat.brierBase = 10.0; seat.brierModel = -5000.0
        seat.logLossBase = 10.0; seat.logLossModel = Double.NaN
        assertEquals(0.0, seat.brierSkill(), 0.0)
        assertEquals(0.0, seat.logLossSkill(), 0.0)
        seat.brierModel = 2.5
        assertEquals(0.75, seat.brierSkill(), 1e-12)
        assertTrue(LaneEvidence8077.coherent(2.0, 20_000.0, 200_000_000.0, 2.0, 2.0))
        assertFalse(LaneEvidence8077.coherent(2.0, 20.0, 100.0, 2.0, 0.0))
        val s = stat(1, 10.0)
        s.add(Double.MAX_VALUE, true)
        assertEquals(1.0, s.n, 0.0)
        assertEquals(10.0, s.mean(), 0.0)
    }
    @Test fun liveReadersConsumeAlignmentAndRevisionBindingsPersistWithTheirBook() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/cortex/LanePlaybook7907.kt").readText()
        assertTrue(p.contains("LaneEvidence8077.choose(m.map { setupEvidence8077(lane, it.id) })"))
        assertTrue(p.contains("c.observedAt == observedAt"))
        assertTrue(p.contains("c.revision == revision"))
        assertTrue(p.contains("_grades8077"))
        val t = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(t.contains("lowWinRateNeedsPivot(tradesIn, lossRate, meanPnl)"))
        assertTrue(t.contains("lowWinRateNeedsPivot(totalSamples, lossRate, st.meanPnl)"))
    }
    @Test fun laneLocalSetupOrdinalsNeverBorrowAnotherLanesDifferentSetup() {
        val l = CortexLedger7885()
        repeat(60) { l.lane("QUALITY").add(0.0, false); l.lane("MOONSHOT").add(0.0, false) }
        for (id in listOf("PLAYBOOK_SETUP_V3", "PRICE")) repeat(60) { l.seat(id, "QUALITY", 2).bins[0].add(100.0, true) }
        assertEquals(0.0, l.predict("PLAYBOOK_SETUP_V3", "MOONSHOT", doubleArrayOf(0.5), 0.0).netPct, 1e-12)
        assertTrue(l.predict("PRICE", "MOONSHOT", doubleArrayOf(0.5), 0.0).netPct > 0.0)
    }

    @Test fun aliasesAndUntrainedDefaultsRetainTheirLaneAndCompletedEvidence() {
        assertEquals(Tactic.PULLBACK, TacticSwitcher.seedTactic8049("BLUE_CHIP", "SPECIALIST_EDGE"))
        assertEquals(Tactic.PULLBACK, TacticSwitcher.seedTactic8049("BLUE CHIP", "NO_TRIGGER"))
        assertEquals(Tactic.BREAKOUT, TacticSwitcher.seedTactic8049("MOON_SHOT", "LAUNCH_LADDER_PROVEN"))
        assertEquals(Tactic.REACCUMULATION, TacticSwitcher.seedTactic8049("SHIT_COIN", "LAUNCH_LADDER_PROVEN"))
        assertTrue(LaneEvidence8077.refreshUntrainedSeed("initial", 0))
        assertFalse(LaneEvidence8077.refreshUntrainedSeed("initial", 5))
        assertTrue(LaneEvidence8077.refreshUntrainedSeed("seed8049:PLAN_BASE_BREAKOUT|8052", 0))
        assertFalse(LaneEvidence8077.refreshUntrainedSeed("seed8049:PLAN_BASE_BREAKOUT|8052", 1))
        assertFalse(LaneEvidence8077.refreshUntrainedSeed("seed8049:PLAN_BASE_BREAKOUT|8077", 0))
        assertFalse(LaneEvidence8077.refreshUntrainedSeed("bayes:loss", 0))
        val l = CortexLedger7885()
        repeat(60) { l.lane("QUALITY").add(0.0, false); l.lane("MOONSHOT").add(0.0, false) }
        repeat(60) { l.seat("X_PLAYBOOK_SETUP_V3__PRICE", "QUALITY", 2).bins[0].add(100.0, true) }
        assertEquals(0.0, l.predict("X_PLAYBOOK_SETUP_V3__PRICE", "MOONSHOT", doubleArrayOf(0.5), 0.0).netPct, 1e-12)
    }

}

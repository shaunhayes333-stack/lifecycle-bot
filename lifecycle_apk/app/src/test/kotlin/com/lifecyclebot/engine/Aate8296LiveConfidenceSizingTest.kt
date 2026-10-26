package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveConfidence8296
import com.lifecyclebot.engine.truth.LiveRiskPolicy7807
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8296 — confidence and size follow the lane's LAST verified live closes: fresh live losses shrink the expectancy
 * multiplier and revoke the compounding share; a statistically positive recent window may grow it; paper results never
 * size a live order up.
 */
class Aate8296LiveConfidenceSizingTest {

    private fun closes(vararg pctSol: Pair<Double, Double>) = pctSol.map { doubleArrayOf(it.first, it.second) }

    private fun inputs(n: Int, mean: Double, net: Double, wr: Double, recent: LiveConfidence8296.Recent8296?) =
        LiveRiskPolicy7807.Inputs(
            lane = "QUALITY", equitySol = 1.0, upstreamSol = 1.0, execMinSol = 0.025,
            planStopPct = 10.0, planFirstTargetPct = 30.0, planTargetPct = 40.0,
            solUsd = 200.0, liquidityUsd = 200_000.0, liveCloses = n, liveMeanNetPct = mean,
            liveTotalNetSol = net, liveWrPct = wr, drawdownFrac = 0.0, laneDailyLossSol = 0.0,
            governorLossMult = 1.0, partialProviderEvidence = false, oracleUnproven = false, laneOpenLive = 0,
            recentLive8296 = recent,
        )

    @Test fun recentRecordReadsNewestFirstWithLossStreakAndCappedOutliers() {
        val r = LiveConfidence8296.recentOf8296(closes(-10.0 to -0.003, -5.0 to -0.001, 20.0 to 0.006, 5_000.0 to 0.5))
        assertEquals(4, r.n)
        assertEquals(2, r.lossStreak)
        assertEquals((-10.0 - 5.0 + 20.0 + 1_000.0) / 4.0, r.meanPct, 1e-9)   // an outlier is read at the plausibility cap
        assertEquals(0.502, r.netSol, 1e-12)
        val empty = LiveConfidence8296.recentOf8296(emptyList())
        assertEquals(0, empty.n)
        assertFalse(LiveConfidence8296.losing8296(empty))
        // only the window is read
        val many = LiveConfidence8296.recentOf8296(List(30) { doubleArrayOf(1.0, 0.001) })
        assertEquals(LiveConfidence8296.WINDOW_8296, many.n)
    }

    @Test fun freshLiveLossesCapGrowthAndShrinkAProfitableEraMultiplier() {
        val losing = LiveConfidence8296.recentOf8296(closes(-33.8 to -0.0095, -16.7 to -0.0047, -10.6 to -0.003, -9.2 to -0.0026))
        assertTrue(LiveConfidence8296.losing8296(losing))
        val adjusted = LiveConfidence8296.recencyAdjusted8296(1.5, losing)
        assertTrue("profitable-era growth must not survive four fresh live losses: $adjusted", adjusted < 1.0)
        assertTrue(adjusted >= LiveConfidence8296.RECENT_FLOOR_8296)
        assertTrue(adjusted <= 0.80)   // a 3+ loss streak
        // a lane already shrunk is never raised by the recent rule
        assertEquals(0.35, LiveConfidence8296.recencyAdjusted8296(0.35, losing), 1e-12)
        // two closes are not a record: unchanged
        val thin = LiveConfidence8296.recentOf8296(closes(-30.0 to -0.01, -20.0 to -0.01))
        assertEquals(1.5, LiveConfidence8296.recencyAdjusted8296(1.5, thin), 1e-12)
        assertEquals(1.2, LiveConfidence8296.recencyAdjusted8296(1.2, null), 1e-12)
    }

    @Test fun aProvenRecentWindowLetsARecoveredLaneGrowAgainButOnlyModestly() {
        val window = LiveConfidence8296.recentOf8296(List(LiveConfidence8296.WINDOW_8296) { i ->
            if (i % 4 == 1) doubleArrayOf(-6.0, -0.002) else doubleArrayOf(18.0, 0.006)
        })
        assertTrue(LiveConfidence8296.provenRecent8296(window))
        val g = LiveConfidence8296.recencyAdjusted8296(0.85, window)    // old history negative
        assertTrue(g > 1.0 && g <= LiveConfidence8296.RECENT_GROWTH_MAX_8296)
        // a short window (even all wins) never grows
        val short = LiveConfidence8296.recentOf8296(List(6) { doubleArrayOf(20.0, 0.006) })
        assertFalse(LiveConfidence8296.provenRecent8296(short))
        assertEquals(0.85, LiveConfidence8296.recencyAdjusted8296(0.85, short), 1e-12)
    }

    @Test fun compoundingShareIsRevokedByLaneLossesOrALosingSetupAndNeverRaised() {
        val losing = LiveConfidence8296.recentOf8296(closes(-10.0 to -0.003, -8.0 to -0.002, 4.0 to 0.001))
        assertEquals(0.0, LiveConfidence8296.kellyGate8296(0.12, losing, 0.0, Double.NaN), 0.0)
        val fine = LiveConfidence8296.recentOf8296(closes(12.0 to 0.004, -3.0 to -0.001, 9.0 to 0.003))
        assertEquals(0.12, LiveConfidence8296.kellyGate8296(0.12, fine, 0.0, Double.NaN), 0.0)
        // 5.0.8186: LAUNCH_LADDER_PROVEN labels +103% while its own live closes were n2 -47.9%
        assertEquals(0.0, LiveConfidence8296.kellyGate8296(0.12, fine, 2.0, -47.9), 0.0)
        assertEquals(0.12, LiveConfidence8296.kellyGate8296(0.12, fine, 1.0, -47.9), 0.0)   // one close is not a record
        assertEquals(0.12, LiveConfidence8296.kellyGate8296(0.12, fine, 3.0, 15.0), 0.0)
        assertEquals(0.0, LiveConfidence8296.kellyGate8296(0.0, fine, 3.0, 15.0), 0.0)
        assertEquals(0.0, LiveConfidence8296.kellyGate8296(Double.NaN, null, 0.0, Double.NaN), 0.0)
    }

    @Test fun onlyVerifiedLiveClosesAreRecorded() {
        assertFalse(LiveConfidence8296.note8296("PAPER", "PAPER_SIMULATED", "p8296-a", "QUALITY", 50.0, 0.1, 1L))
        assertFalse(LiveConfidence8296.note8296("LIVE", "LIVE_UNVERIFIED", "p8296-b", "QUALITY", 50.0, 0.1, 2L))
        assertFalse(LiveConfidence8296.note8296("LIVE", "LIVE_FINALIZED", "p8296-c", "QUALITY", Double.NaN, 0.1, 3L))
        assertTrue(LiveConfidence8296.note8296("LIVE", "LIVE_FINALIZED", "p8296-d", "TEST_LANE_8296", -12.0, -0.003, 4L))
        assertFalse(LiveConfidence8296.note8296("LIVE", "LIVE_FINALIZED", "p8296-d", "TEST_LANE_8296", -12.0, -0.003, 4L))  // once per position
    }

    @Test fun decideShrinksAfterFreshLiveLossesAndNamesIt() {
        val era = inputs(n = 40, mean = 30.0, net = 2.0, wr = 60.0, recent = null)
        val losing = LiveConfidence8296.recentOf8296(closes(-20.0 to -0.006, -15.0 to -0.004, -9.0 to -0.003))
        val base = LiveRiskPolicy7807.decide(era)
        val after = LiveRiskPolicy7807.decide(era.copy(recentLive8296 = losing))
        assertTrue(base.open && after.open)
        assertTrue("${after.sizeSol} vs ${base.sizeSol}", after.sizeSol < base.sizeSol)
        assertTrue("RECENT_LIVE_LOSSES_SHRINK_8296" in after.labels)
        assertFalse("LIVE_EXPECTANCY_GROWTH_PROVEN_7807" in after.labels)
        assertTrue("LIVE_EXPECTANCY_GROWTH_PROVEN_7807" in base.labels)
    }

    @Test fun decideGrowsARecoveredLaneOnAProvenRecentWindow() {
        val stale = inputs(n = 40, mean = -20.0, net = -0.5, wr = 30.0, recent = null)
        val window = LiveConfidence8296.recentOf8296(List(LiveConfidence8296.WINDOW_8296) { i ->
            if (i % 4 == 1) doubleArrayOf(-6.0, -0.002) else doubleArrayOf(18.0, 0.006)
        })
        val base = LiveRiskPolicy7807.decide(stale)
        val recovered = LiveRiskPolicy7807.decide(stale.copy(recentLive8296 = window))
        assertTrue(base.open && recovered.open)
        assertTrue("${recovered.sizeSol} vs ${base.sizeSol}", recovered.sizeSol > base.sizeSol)
    }

    @Test fun paperResultsNeverSizeALiveOrderUp() {
        assertEquals(1.0, LiveConfidenceSizing8296.paperFallbackLiveMult8296(1.30), 0.0)
        assertEquals(1.0, LiveConfidenceSizing8296.paperFallbackLiveMult8296(1.15), 0.0)
        assertEquals(0.70, LiveConfidenceSizing8296.paperFallbackLiveMult8296(0.70), 0.0)
        assertEquals(1.0, LiveConfidenceSizing8296.paperFallbackLiveMult8296(Double.NaN), 0.0)
        assertTrue(LiveConfidenceSizing8296.paperSeedRefused8296(liveEmpty = true, paperAvailable = true))
        assertFalse(LiveConfidenceSizing8296.paperSeedRefused8296(liveEmpty = false, paperAvailable = true))
        val sizer = File("src/main/kotlin/com/lifecyclebot/engine/SmartSizer.kt").readText()
        // superseded by V5.0.8366 — paper never sizes live, not even down: under 10 live closes the multiplier is neutral (LivePerf8366)
        assertTrue(sizer.contains("if (LivePerf8366.thinLive8366(perf.totalTrades)) {") && sizer.contains("LivePerf8366.thinLiveMult8366(perf.totalTrades)"))
        assertFalse(sizer.contains("LiveConfidenceSizing8296.paperFallbackLiveMult8296(when {"))
        assertFalse(sizer.contains("recentTradesLive.addAll(recentTradesPaper)"))
        assertFalse(sizer.contains("winStreakLive = winStreakPaper"))
    }

    @Test fun theRiskPolicyIsWiredToTheVerifiedLiveCloses() {
        val p = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        assertTrue(p.contains("LiveConfidence8296.onLiveClose8296(e)"))
        assertTrue(p.contains("recentLive8296 = try { LiveConfidence8296.recentFor8296(effectiveLane7811) }"))
        // superseded by V5.0.8326 — the recency shrink applies through Entry8114Mode8326.sizeExpMult8326 unless the 8114 decision admitted it
        assertTrue(p.contains("val expMult = LiveConfidence8296.recencyAdjusted8296(expectancyMultiplier("))
        assertTrue(p.contains("recentKellyGate8296(ts, l, validated8296)"))
        assertTrue(p.contains("LiveConfidence8296.statusLine8296()"))
    }
}

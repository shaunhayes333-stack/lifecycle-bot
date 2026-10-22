package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.LiveAdmissionProof8290
import com.lifecyclebot.engine.truth.TapeStage8316
import com.lifecyclebot.engine.truth.TradeTape8100
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8316 P1 — the 8290 realised shortfall is one-way again. 5.0.8314: 48 ADMIT / 3,626 REFUSE; the shortfall (MOONSHOT
 * 55.9 pt, SHITCOIN 42.8 pt) was taken off every record at build, so the LOSS tests read a +20 % setup as -23 % and refused
 * it as SETUP_RECORD_LOSING (407) / TIMING_ADVERSE (118). Loss tests now read the raw records; the shifted twins feed only
 * the support tests. And the "liveThinLossBinds8290" refusal is withdrawn: 8257's clear-loss rule holds live.
 */
class Aate8316ShortfallOneWayTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun cand(setups: List<Evidence8136>, selected: String, live: Boolean = true, cellEv: Evidence8136? = null,
                     timing: Evidence8136? = null, shortfall: Double = 0.0) =
        CandidateAdmission8136.Candidate8136(
            mint = "mint8316", lane = "SHITCOIN", selectedSetup = selected, stage = "GROWTH", priceAgeMs = 2_000L,
            expectedGrossPct = 120.0, costPct = 9.0, setups = setups, cell = cellEv, timing = timing,
            genericScore = 70.0, genericFloor = 50.0, live = live, liveShortfall8290 = shortfall,
        )

    @Test fun aPositiveRecordWithAFortyPointShortfallIsNotLosingButMayFailSupport() {
        val plus20 = Evidence8136(CandidateAdmission8136.KIND_SETUP, "RS_LEADER", 40, 20.0, 5.0)
        val c = cand(listOf(plus20), "RS_LEADER", shortfall = 40.0)
        // raw: not losing on any loss test
        assertFalse(CandidateAdmission8136.losing8136(c.setups.single()))
        assertFalse(CandidateAdmission8136.clearlyLosing8136(c.setups.single()))
        // the shifted twin reads -20 ± 5: it supports nothing
        assertEquals(-20.0, c.setupsProof8290.single().meanPct, 1e-9)
        assertFalse(CandidateAdmission8136.supported8136(c.setupsProof8290.single(), 9.0))
        assertNull(CandidateAdmission8136.bestSetup8153(c))
        val d = CandidateAdmission8136.decide8136(c)
        assertTrue(d.compact, d.reason != "SETUP_RECORD_LOSING_8136")
        assertTrue(d.compact, d.trace.none { it.startsWith("setupLosing") })
        assertEquals(d.compact, Terminal8136.REFUSE, d.terminal)   // live: no supported record, the prior cannot admit
        assertEquals("LIVE_GENERIC_PRIOR_UNSUPPORTED_8238", d.reason)
        // without the shortfall the same record is the proven edge
        val clean = CandidateAdmission8136.decide8136(cand(listOf(plus20), "RS_LEADER"))
        assertEquals(clean.compact, "SPECIFIC_EDGE_PROVEN_8136", clean.reason)
        assertEquals("RS_LEADER", CandidateAdmission8136.bestSetup8153(cand(listOf(plus20), "RS_LEADER"))?.id)
        // the shortfall is traced once, on the decision
        assertTrue(d.trace.any { it.startsWith("liveShortfall8290=-40.0") })
    }

    @Test fun theShiftedTwinsCarryTheShortfallAndTheRawRecordsDoNot() {
        val setup = Evidence8136(CandidateAdmission8136.KIND_SETUP, "A", 60, 30.0, 3.0)
        val timing = Evidence8136(CandidateAdmission8136.KIND_TIMING, "A@EARLY", 20, 5.0, 2.0)
        val cell = Evidence8136(CandidateAdmission8136.KIND_CELL, "c", 60, 12.0, 2.0)
        val c = CandidateAdmission8136.Candidate8136(mint = "m", lane = "SHITCOIN", selectedSetup = "A", setups = listOf(setup),
            setupTimings = mapOf("A" to timing), timing = timing, cell = cell, cellLoss8169 = cell, liveShortfall8290 = 8.6)
        assertEquals(30.0, c.setups.single().meanPct, 0.0)
        assertEquals(21.4, c.setupsProof8290.single().meanPct, 1e-9)
        assertEquals(-3.6, c.setupTimingsProof8290.getValue("A").meanPct, 1e-9)
        assertEquals(-3.6, c.timingProof8290!!.meanPct, 1e-9)
        assertEquals(3.4, c.cellProof8290!!.meanPct, 1e-9)
        assertEquals(3.4, c.cellLossProof8290!!.meanPct, 1e-9)
        assertTrue(c.copy(liveShortfall8290 = 0.0).cellProof8290 === cell)
    }

    @Test fun aThinOneSeLiveLossNoLongerBindsAClearLossStillDoes() {
        val thin = Evidence8136(CandidateAdmission8136.KIND_SETUP, "BREAKOUT_RUNNER", 14, -3.0, 2.0)
        val cellOk = Evidence8136(CandidateAdmission8136.KIND_CELL, "cell", 60, 20.0, 2.0)
        val live = CandidateAdmission8136.decide8136(cand(listOf(thin), "BREAKOUT_RUNNER", cellEv = cellOk))
        assertTrue(live.compact, live.trace.any { it == "setupLosingThin=advisory" })
        assertFalse(live.trace.any { it == "liveThinLossBinds8290" })
        assertEquals(live.compact, "SPECIFIC_EDGE_PROVEN_8136", live.reason)   // the supported cell is the strategy
        // without a supported record the thin loser falls to the generic prior, which cannot admit live money
        val alone = CandidateAdmission8136.decide8136(cand(listOf(thin), "BREAKOUT_RUNNER"))
        assertEquals(alone.compact, "LIVE_GENERIC_PRIOR_UNSUPPORTED_8238", alone.reason)
        // a CLEAR thin loss (mean two SE below zero) still refuses live
        val clear = Evidence8136(CandidateAdmission8136.KIND_SETUP, "BREAKOUT_RUNNER", 15, -6.0, 2.0)
        assertEquals("SETUP_RECORD_LOSING_8136", CandidateAdmission8136.decide8136(cand(listOf(clear), "BREAKOUT_RUNNER", cellEv = cellOk)).reason)
        // timing: the same rule
        val good = Evidence8136(CandidateAdmission8136.KIND_SETUP, "S", 60, 30.0, 3.0)
        val thinTiming = Evidence8136(CandidateAdmission8136.KIND_TIMING, "S@LATE", 14, -3.0, 2.0)
        val t = CandidateAdmission8136.decide8136(cand(listOf(good), "S", timing = thinTiming))
        assertTrue(t.compact, t.trace.any { it == "timingThin=advisory" })
        assertFalse(t.trace.any { it == "liveThinLossBinds8290" })
        assertTrue(t.compact, t.reason != "TIMING_ADVERSE_8136")
        val clearTiming = Evidence8136(CandidateAdmission8136.KIND_TIMING, "S@LATE", 15, -6.0, 2.0)
        assertEquals("TIMING_ADVERSE_8136", CandidateAdmission8136.decide8136(cand(listOf(good), "S", timing = clearTiming)).reason)
    }

    @Test fun theLabelSideIsThePessimisticWinsorisedMeanBoundedByTheMediansSign() {
        // n100 +45 % ± 10, 60 % winners: the pessimistic winsorised mean sits just under the robust mean
        val side = LiveAdmissionProof8290.labelSide8316(100, 45.0, 10.0, 0.6)
        assertTrue("$side", side < 45.0 && side > 40.0)
        // fewer than half the labels positive: the median is not positive, the label side is at most 0
        assertEquals(0.0, LiveAdmissionProof8290.labelSide8316(100, 45.0, 10.0, 0.3), 0.0)
        assertTrue(LiveAdmissionProof8290.labelSide8316(0, 45.0, 10.0, 0.6).isNaN())
        // the shortfall the label side yields keeps its 60-pt cap and its shrink
        assertEquals(0.0, LiveAdmissionProof8290.unpairedShortfall8290(100, 0.0, 10, 2.0), 0.0)
        assertEquals(LiveAdmissionProof8290.MAX_SHORTFALL_PCT_8290, LiveAdmissionProof8290.unpairedShortfall8290(100, 215.0, 50, -10.4), 0.0)
        val m = src("engine/truth/LiveAdmissionProof8290.kt").substringAfter("private fun measure8290(")
        assertTrue(m.contains("labelSide8316(label.n60, label.meanNet60Pct, label.stderr60Pct, label.winRate60, RobustLabelRead8185.capForKey8185(label.key))"))
        assertTrue(m.contains("unpairedShortfall8290(label?.n60 ?: 0, labelSide, closes.size, realisedMean)"))
    }

    @Test fun theLiveAdapterBuildsRawRecordsAndTheDecisionShiftsOnlyAtSupport() {
        val a = src("engine/truth/CandidateAdmission8136.kt")
        val adapter = a.substringAfter("internal fun candidateFor8136(")
        assertFalse(adapter.contains("LiveAdmissionProof8290.shift8290(Evidence8136("))
        assertTrue(adapter.contains("liveShortfall8290 = extra8290"))
        val decide = a.substringAfter("fun decide8136(").substringBefore("internal fun bestSetup8153(")
        assertFalse(decide.contains("liveThinLossBinds8290"))
        assertTrue(decide.contains("val cellSupported = supported8136(liveCellProof8290, c.costPct)"))
        assertTrue(decide.contains("if (losing8136(selectedRec))"))
        assertTrue(decide.contains("bestSetup ?: chosenProof8316?.takeIf { supported8136(it, c.costPct) }, liveCellProof8290?.takeIf { cellSupported }"))
        assertTrue(a.substringAfter("internal fun bestSetup8153(").substringBefore("const val KIND_SETUP").contains("val setups8316 = c.setupsProof8290"))
    }

    // ── the stage a young coin's own prints name (owner: "are we hitting tokens early as they build, not dumping?") ──

    private fun print(atMs: Long, px: Double, sol: Double, buy: Boolean) = TradeTape8100.Print8128(atMs, px, sol, buy)

    @Test fun aSellDominatedTapeOffItsPeakIsADumpAndEarlyBuyingIsABuild() {
        val now = 10L * 60_000L
        // ran 1.0 -> 2.0, now 1.3 (35 % off the peak) with sells dominating the last three minutes
        val dump = listOf(print(now - 540_000L, 1.0, 1.0, true), print(now - 480_000L, 1.5, 1.0, true), print(now - 420_000L, 2.0, 1.0, true),
            print(now - 150_000L, 1.7, 0.2, true), print(now - 120_000L, 1.5, 1.5, false), print(now - 60_000L, 1.4, 1.2, false), print(now - 10_000L, 1.3, 1.0, false))
        val d: TapeStage8316.Read8316 = TapeStage8316.classify8316(dump, now, 9.0)!!
        assertEquals(TokenMetricStageRouter.Stage.DUMPING, d.stage)
        assertEquals(6, TapeStage8316.MIN_PRINTS_8316)
        assertEquals(20.0, TapeStage8316.MAX_AGE_MIN_8316, 0.0)
        assertTrue(d.compact, d.offPeakPct >= 25.0 && d.buySharePct < 45.0)
        // building: buy share >= 60 %, price +30 % over its first print, 5 % off its peak
        val build = listOf(print(now - 150_000L, 1.0, 0.5, true), print(now - 130_000L, 1.1, 0.4, true), print(now - 100_000L, 1.2, 0.3, false),
            print(now - 70_000L, 1.3, 0.6, true), print(now - 40_000L, 1.37, 0.5, true), print(now - 10_000L, 1.3, 0.4, true))
        assertEquals(TokenMetricStageRouter.Stage.FRESH_LAUNCH, TapeStage8316.classify8316(build, now, 2.5)!!.stage)
        assertEquals(TokenMetricStageRouter.Stage.BASE_START, TapeStage8316.classify8316(build, now, 6.0)!!.stage)
        // extended +100 % and turned sell-dominated 15 % off the peak: exhausted, not yet a dump
        assertEquals(TokenMetricStageRouter.Stage.PEAK_EXHAUSTION, TapeStage8316.stageOf8316(15.0, 100.0, 30.0, -1.0, 8.0))
        // no stage from: too few prints, a coin past 20 min, a flat tape with mixed flow
        assertNull(TapeStage8316.classify8316(build.take(5), now, 2.5))
        assertNull(TapeStage8316.classify8316(dump, now, 25.0))
        assertNull(TapeStage8316.stageOf8316(5.0, 10.0, 50.0, 0.1, 5.0))
        assertNull(TapeStage8316.classify8316(emptyList(), now, 1.0))
        // the launch tape's own features read the same way (whole-life flow, price from first / peak); too few trades = nothing
        fun feat(age: Double, net: Double, share: Double, fromFirst: Double, fromPeak: Double, trades: Int) =
            com.lifecyclebot.engine.market.LaunchTape7921.Feat(age, 5, net, 2.0, share, 20.0, 40.0, false, fromFirst, fromPeak, 1.0, trades, share)
        assertEquals(TokenMetricStageRouter.Stage.DUMPING, TapeStage8316.classifyLaunch8316(feat(6.0, -2.0, 30.0, 40.0, -40.0, 20))!!.stage)
        assertEquals(TokenMetricStageRouter.Stage.FRESH_LAUNCH, TapeStage8316.classifyLaunch8316(feat(1.5, 1.2, 70.0, 25.0, -5.0, 12))!!.stage)
        assertNull(TapeStage8316.classifyLaunch8316(feat(1.5, 1.2, 70.0, 25.0, -5.0, 4)))
        assertNull(TapeStage8316.classifyLaunch8316(null))
        val r = src("engine/TokenMetricStageRouter.kt")
        assertTrue(r.contains("else -> tape8316?.stage ?: Stage.UNKNOWN"))
        assertTrue(r.contains("else com.lifecyclebot.engine.truth.TapeStage8316.stageFromTape8316(ts.mint, ageMin, now)"))
        assertTrue(src("engine/truth/TapeStage8316.kt").contains("\"STAGE_FROM_TAPE_8316_\${read.stage.name}\""))
    }
}

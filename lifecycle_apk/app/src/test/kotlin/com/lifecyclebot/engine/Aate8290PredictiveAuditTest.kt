package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Evidence8136
import com.lifecyclebot.engine.truth.CandidateAdmission8136.Terminal8136
import com.lifecyclebot.engine.truth.CellProofLadder7731
import com.lifecyclebot.engine.truth.ForwardReturnLabeler7731
import com.lifecyclebot.engine.truth.LiveAdmissionProof8290
import com.lifecyclebot.engine.truth.LiveEdgeGate7877
import com.lifecyclebot.engine.truth.RobustLabelRead8185
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8290 — predictive / admission audit (AUDIT_PREDICTIVE_8290.md). Owner: "went from a near money printer to a bot
 * that just buys losers". 5.0.8286: 4/4 live losses (-9.2 .. -33.8 %), all UNPROVEN at the route minimum, while the
 * admission proofs read forward-label books that were OUTLIER_SUSPECT or never met execution (admitted60 +1.8 %, the
 * 8187 executable offset -3.5 pt because 657 of 701 graded decisions lost their paired label).
 */
class Aate8290PredictiveAuditTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    private fun cell(n: Int, mean: Double, se: Double, wr: Double = 0.5, run: Double = 0.0, suspect: Boolean = false) =
        ForwardReturnLabeler7731.CellStat("KIND|SHITCOIN|PUMPSWAP|MC_LT10K", n, mean, wr, run, se, 0, outlierSuspect8185 = suspect)

    // ── finding 1: an outlier-suspect record can still "prove" through the 8185 read ──

    @Test fun aSuspectRecordStillClearsMeanMinusSeUnderThe8185ReadButNeverSurvives8290() {
        // 20 labels of +2,000 % (basis errors) and 80 of -20 %: the raw mean +384 % is impossible under the 11x cap.
        val n = 100; val sum = 20 * 2_000.0 + 80 * -20.0; val sumSq = 20 * 2_000.0 * 2_000.0 + 80 * 400.0
        val r = RobustLabelRead8185.read8185("KIND|SHITCOIN|PUMPSWAP|MC_LT10K", n, sum, sumSq, 20)
        assertTrue(r.suspect)
        assertTrue("the winsorised lower bound is positive, so the 8060 proof passed", r.mean - r.stderr > 0.0)
        assertTrue(LiveEdgeGate7877.cellProven8060(n, r.mean, r.stderr))
        assertFalse(LiveAdmissionProof8290.survives8290(n.toDouble(), r.mean, r.stderr, r.suspect, 0.0))
    }

    @Test fun theRunnerTailAndCohortProofsNoLongerReadASuspectUpperBound() {
        // NARR|SHITCOIN|UNKNOWN n1110 +328.8 % OUTLIER_SUSPECT_8185: the tail test admitted it on its point estimate.
        val suspect = cell(1110, 328.8, 400.0, wr = 0.2, run = 0.3, suspect = true)
        val honest = suspect.copy(outlierSuspect8185 = false, meanNet60Pct = 30.0)
        assertEquals("CELL_OUTLIER_SUSPECT_8290", LiveEdgeGate7877.judgeRunner(suspect, emptyList(), false).why)
        assertFalse(LiveEdgeGate7877.judgeRunner(suspect, emptyList(), false).allow)
        assertFalse(LiveEdgeGate7877.judge(suspect, true).allow)
        assertTrue(LiveEdgeGate7877.judgeRunner(honest, emptyList(), false).allow)            // the honest tail still trades
        // a suspect pooled cohort is not a measurement: it neither admits nor counts as a losing cohort
        val v = LiveEdgeGate7877.judgeRunner(null, listOf(suspect), false)
        assertFalse(v.why.startsWith("RUNNER_TAIL"))
        assertFalse(LiveEdgeGate7877.cohortProvenPositive7948(suspect.copy(meanNet60Pct = 50.0, stderr60Pct = 5.0)))
        assertTrue(LiveEdgeGate7877.cohortProvenPositive7948(honest.copy(meanNet60Pct = 50.0, stderr60Pct = 5.0)))
        assertNull(LiveEdgeGate7877.outlierSuspect8290(honest))
    }

    @Test fun theCellProofLadderNeverTagsASuspectRecordPositive() {
        assertEquals(CellProofLadder7731.Tier.POSITIVE, CellProofLadder7731.tierFor(cell(200, 20.0, 5.0)))
        assertEquals(CellProofLadder7731.Tier.UNPROVEN, CellProofLadder7731.tierFor(cell(200, 20.0, 5.0, suspect = true)))
        assertEquals(CellProofLadder7731.Tier.NEGATIVE, CellProofLadder7731.tierFor(cell(200, -20.0, 5.0, wr = 0.2, suspect = true)))
    }

    // ── finding 2: the label-to-bank gap the 8187 offset could not measure ──

    @Test fun theUnpairedShortfallOnThe8286Book() {
        // admitted60 n17 +1.8 %; live closes -33.8, -16.7, -10.6, -9.2 (mean -17.575 %)
        val live = listOf(-33.8, -16.7, -10.6, -9.2).average()
        val sf = LiveAdmissionProof8290.unpairedShortfall8290(17, 1.8, 4, live)
        assertEquals((1.8 - live) * 4.0 / 9.0, sf, 1e-9)                    // 8.61 pt, shrunk by n / (n + 5)
        assertEquals(sf - 3.5, LiveAdmissionProof8290.extraBeyondOffset8290(sf, 3.5), 1e-9)   // the 8187 offset is not taken twice
        assertEquals(0.0, LiveAdmissionProof8290.extraBeyondOffset8290(2.0, 3.5), 0.0)
        // no proof of a gap, no extra
        assertEquals(0.0, LiveAdmissionProof8290.unpairedShortfall8290(17, 1.8, 1, -30.0), 0.0)    // one close is an anecdote
        assertEquals(0.0, LiveAdmissionProof8290.unpairedShortfall8290(0, Double.NaN, 4, live), 0.0)
        assertEquals(0.0, LiveAdmissionProof8290.unpairedShortfall8290(17, 1.8, 6, 12.0), 0.0)     // closes beat the labels
        assertEquals(LiveAdmissionProof8290.MAX_SHORTFALL_PCT_8290, LiveAdmissionProof8290.unpairedShortfall8290(500, 900.0, 1_000, -100.0), 0.0)
    }

    @Test fun aProofMustClearItsErrorAndTheRealisedShortfall() {
        assertTrue(LiveAdmissionProof8290.survives8290(60.0, 12.0, 4.0, false, 0.0))
        assertFalse(LiveAdmissionProof8290.survives8290(60.0, 12.0, 4.0, false, 8.6))   // +12 ± 4 does not pay a 8.6 pt shortfall
        assertFalse(LiveAdmissionProof8290.survives8290(29.0, 50.0, 1.0, false, 0.0))   // 30-label bar
        assertFalse(LiveAdmissionProof8290.survives8290(60.0, 50.0, Double.POSITIVE_INFINITY, false, 0.0))
        assertFalse(LiveAdmissionProof8290.survives8290(60.0, 50.0, 1.0, true, 0.0))    // suspect: never
        assertTrue(LiveAdmissionProof8290.survives8290(15.0, 20.0, 4.0, false, 0.0, minN = 15.0))
        val e = Evidence8136(CandidateAdmission8136.KIND_SETUP, "S", 60, 12.0, 4.0, lateN = 40, lateMeanPct = 9.0)
        val s = LiveAdmissionProof8290.shift8290(e, 8.6)!!
        assertEquals(3.4, s.meanPct, 1e-9); assertEquals(0.4, s.lateMeanPct, 1e-9); assertEquals(4.0, s.sePct, 0.0)
        assertTrue(LiveAdmissionProof8290.shift8290(e, 0.0) === e)
        assertNull(LiveAdmissionProof8290.shift8290(null, 5.0))
    }

    // ── the admission record ──

    private fun cand(setups: List<Evidence8136>, selected: String, live: Boolean = false, cellEv: Evidence8136? = null) =
        CandidateAdmission8136.Candidate8136(
            mint = "mint8290", lane = "SHITCOIN", selectedSetup = selected, stage = "GROWTH", priceAgeMs = 2_000L,
            expectedGrossPct = 120.0, costPct = 9.0, setups = setups, cell = cellEv, genericScore = 70.0, genericFloor = 50.0, live = live,
        )

    @Test fun aSuspectRecordNeverSupportsAnAdmission() {
        val proven = Evidence8136(CandidateAdmission8136.KIND_SETUP, "LAUNCH_LADDER_PROVEN", 824, 83.5, 9.0, 0.3)
        assertTrue(CandidateAdmission8136.supported8136(proven, 9.0))
        assertFalse(CandidateAdmission8136.supported8136(proven.copy(suspect8290 = true), 9.0))
        assertFalse(CandidateAdmission8136.grabSupported8245(Evidence8136(CandidateAdmission8136.KIND_RUNNER_GRAB_8245, "c", 40, 60.0, 5.0, suspect8290 = true)))
        val clean = CandidateAdmission8136.decide8136(cand(listOf(proven), "LAUNCH_LADDER_PROVEN"))
        assertEquals(clean.compact, "SPECIFIC_EDGE_PROVEN_8136", clean.reason)
        val sus = CandidateAdmission8136.decide8136(cand(listOf(proven.copy(suspect8290 = true)), "LAUNCH_LADDER_PROVEN", live = true))
        assertTrue(sus.compact, sus.reason != "SPECIFIC_EDGE_PROVEN_8136")
        assertFalse(sus.compact, sus.admitted)                                  // live: no supported record, the generic prior cannot admit
        assertNull(CandidateAdmission8136.bestSetup8153(cand(listOf(proven.copy(suspect8290 = true)), "LAUNCH_LADDER_PROVEN")))
    }

    @Test fun liveMoneyHonoursTheSelectedStrategysOwnLosingRecordPaperKeepsThe8257Advisory() {
        val thin = Evidence8136(CandidateAdmission8136.KIND_SETUP, "BREAKOUT_RUNNER", 14, -3.0, 2.0)
        val cellOk = Evidence8136(CandidateAdmission8136.KIND_CELL, "cell", 60, 20.0, 2.0)
        val paper = CandidateAdmission8136.decide8136(cand(listOf(thin), "BREAKOUT_RUNNER", live = false, cellEv = cellOk))
        assertTrue(paper.trace.any { it == "setupLosingThin=advisory" })
        // superseded by V5.0.8316: the live "liveThinLossBinds8290" refusal is withdrawn (8314: SETUP_RECORD_LOSING 407 on
        // records the shortfall had shifted below zero) — 8257's rule holds live too: only a CLEAR loss refuses, a thin
        // one-SE loser is advisory and the supported cell is the strategy.
        val live = CandidateAdmission8136.decide8136(cand(listOf(thin), "BREAKOUT_RUNNER", live = true, cellEv = cellOk))
        assertTrue(live.compact, live.reason != "SETUP_RECORD_LOSING_8136")
        assertTrue(live.trace.any { it == "setupLosingThin=advisory" })
        assertFalse(live.trace.any { it == "liveThinLossBinds8290" })
        // a supported alternative strategy still replaces the losing one live
        val alt = Evidence8136(CandidateAdmission8136.KIND_SETUP, "RS_LEADER", 60, 25.0, 3.0)
        assertEquals("RS_LEADER", CandidateAdmission8136.decide8136(cand(listOf(thin, alt), "BREAKOUT_RUNNER", live = true)).chosenSetup)
    }

    @Test fun theShortfallIsTracedOnTheDecision() {
        val proven = Evidence8136(CandidateAdmission8136.KIND_SETUP, "S", 60, 30.0, 3.0)
        val d = CandidateAdmission8136.decide8136(cand(listOf(proven), "S").copy(liveShortfall8290 = 8.6))
        assertTrue(d.trace.any { it.startsWith("liveShortfall8290=-8.6") })
    }

    // ── wiring contracts ──

    @Test fun theLiveAdapterReadsEveryLabelRecordPastTheShortfallAndFlagsSuspects() {
        val a = src("engine/truth/CandidateAdmission8136.kt")
        val adapter = a.substringAfter("internal fun candidateFor8136(")
        assertTrue(adapter.contains("LiveAdmissionProof8290.laneExtra8290(lane, nowMs)"))
        assertTrue(adapter.contains("suspect8290 = r.getOrNull(4) == 1.0"))
        assertTrue(adapter.contains("suspect8290 = it.outlierSuspect8185"))
        assertTrue(adapter.contains("ForwardReturnLabeler7731.executable8187(it, lane)"))   // the live fresh cell is read executable
        assertTrue(a.contains("LiveAdmissionProof8290.shift8290(c.cortexStrong8200, c.liveShortfall8290)"))
        assertTrue(adapter.contains("liveShortfall8290 = extra8290"))
        val pb = src("engine/cortex/LanePlaybook7907.kt")
        assertTrue(pb.substringAfter("fun provenSetup8056(").substringBefore("fun matchedSetups8083(").contains("!setupSurvives8290(lane, setup, st, extra8290)"))
        assertTrue(pb.substringAfter("fun firedSetupRecords8136(").substringBefore("fun dataIncomplete8136(").contains("suspect8187() == true) 1.0 else 0.0"))
        assertTrue(pb.substringAfter("fun coldStartSetup8080(").substringBefore("fun coldStartPositive8080(").contains("it.mean() - extra8290"))
    }

    @Test fun theLiveDoorsReadTheRobustExecutableProofAndThe8243And8256LooseningsAreReverted() {
        val g = src("engine/truth/LiveEdgeGate7877.kt")
        val cok = g.substringAfter("fun cellOrKindProven8094(").substringBefore("private fun kindProven8063(")
        assertTrue(cok.contains("LiveAdmissionProof8290.cellSurvives8290(st, lane, nowMs)"))
        assertTrue(g.substringAfter("private fun kindProven8063(").substringBefore("return ok").contains("LiveAdmissionProof8290.cellSurvives8290(it, lane)"))
        assertTrue(g.substringAfter("private fun measuredEdge8060(").substringBefore("kindProven8063(ts, lane)").contains("LiveAdmissionProof8290.cellSurvives8290(st, lane, nowMs)"))
        assertTrue(g.contains("if (st != null && !st.outlierSuspect8185 && cellEarnsLive7994(st.n60, watchMean8290))"))
        // 8243: a specific ADMIT no longer stands the spike-chase rule down — superseded by V5.0.8298: only a lane whose
        // realised live record earned it (RegressionRestore8298) stands it down; every other admit refuses the chase.
        val chaseCall = g.substringAfter("if (snap8065 != null) chaseRefusal8397(", "")
            .substringBefore("// Keep watch-first", "")
        assertTrue(chaseCall.contains("snap8065.currentVsPeak, nowMs, admittedJudged8201)?.let { reason ->"))
        assertTrue(chaseCall.contains("return reason"))
        val chase = g.substringAfter("internal fun chaseRefusal8397(", "")
            .substringBefore("private fun developingRunGrab8117(", "")
        assertTrue(chase.contains("if (!provenCell && admittedJudged) {"))
        assertTrue(chase.contains("return \"CHASING_SPIKE_8065\""))
        assertTrue(g.contains("val admittedJudged8201 = candidateSpecificAdmission8136 && RegressionRestore8298.specificStandDownEarned8298(l8059, nowMs)"))
        // 8256: the measured shape bin binds over a specific admission (the earned Cortex overrule stays the one exit)
        val shape = g.substringAfter("val shape = try { TradeShapeLearner7883.shapeRefusal(ts, l) }").substringBefore("val v = verdictFor(ts, l, nowMs)")
        // superseded by V5.0.8298: the shape yields only on the lane's earned realised record, else binds (counted 8290)
        assertTrue(shape.contains("RegressionRestore8298.specificStandDownEarned8298(l, nowMs)"))
        assertTrue(shape.contains("LIVE_EDGE_SHAPE_BINDS_OVER_ADMISSION_8290"))
        assertTrue(shape.indexOf("Cortex7885.overrulesEdgeRefusal(ts, l, shape)) return null") < shape.indexOf("return \"EDGE_7877_\$shape\""))
        // the 7955 graduated setup authority needs a record that survives
        assertTrue(g.contains("LanePlaybook7907.selectedSetupSurvives8290(ts, l, nowMs)"))
        // RunnerGrab's proven runner cell is never a suspect one
        val rg = src("engine/RunnerGrab7967.kt")
        assertTrue(rg.contains("!it.outlierSuspect8185 && provenCell7967(it.n60, it.meanNet60Pct, it.stderr60Pct) }?.key"))
        assertTrue(rg.substringAfter("fun grabEvidence8245(").contains("!it.outlierSuspect8185 && provenCell7967"))
    }

    @Test fun theShortfallIsZeroOutsideLiveSoPaperLearningIsUntouched() {
        // Outside LIVE no live-close book is read and no proof is shifted (the authority's default is PAPER).
        RuntimeModeAuthority.publishRuntimeStart(paperMode = true, autoTrade = false)
        assertEquals(0.0, LiveAdmissionProof8290.laneExtra8290("SHITCOIN"), 0.0)
        assertTrue(LiveAdmissionProof8290.statusLine8290().startsWith("proof8290["))
    }
}

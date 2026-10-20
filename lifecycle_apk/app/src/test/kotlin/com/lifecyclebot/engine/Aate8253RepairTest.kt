package com.lifecyclebot.engine

import com.lifecyclebot.engine.RunnerPlan8153.Evidence8153
import com.lifecyclebot.engine.RunnerPlan8153.Plan8153
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import com.lifecyclebot.engine.truth.OnChainDepth8038
import com.lifecyclebot.engine.truth.OnChainDepth8038.LaunchBuyers8252
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.8253 — behavioural: the explicit runner-entry intent survives a sealed scalp style; the launch-buyer verdict is
 * one three-state read; arrived evidence wakes the held defer that asked for it; the unexecuted ticket names its stop.
 */
class Aate8253RepairTest {
    private fun src(rel: String) = java.io.File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun aRunnerGrabAdmissionIsTheRunnerPlanWhateverTheSealedScalpStyle() {
        fun plan(style: String, admitted: Boolean, grabbed: Boolean = false) =
            RunnerPlan8153.plan8153(Evidence8153(true, style, "", grabbedAtEntry = grabbed, runnerEntryAdmitted = admitted))
        // 8153 behaviour kept: a known scalp style alone is a scalp; the grab map counts only with an unknown style.
        assertEquals(Plan8153.SCALP, plan("QUICK_FLIP", admitted = false, grabbed = true))
        assertEquals(Plan8153.RUNNER_SEALED, plan("", admitted = false, grabbed = true))
        // 8252: the admission on the runner-grab edge is the explicit RUNNER_ENTRY intent.
        assertEquals(Plan8153.RUNNER_SEALED, plan("QUICK_FLIP", admitted = true))
        assertEquals(Plan8153.RUNNER_SEALED, plan("MOMENTUM_SCALP", admitted = true))
        // never where the lane does not host runners
        assertEquals(Plan8153.SCALP, RunnerPlan8153.plan8153(Evidence8153(false, "QUICK_FLIP", "", runnerEntryAdmitted = true)))
        // the stamp binds to the entry it admitted: inside 5 minutes after, never before, never a later re-entry
        CandidateAdmission8136.stampRunnerEntryForTest8252("mintR", 1_000_000L)
        assertTrue(CandidateAdmission8136.runnerEntryAdmitted8252("mintR", 1_000_000L + 30_000L))
        assertFalse(CandidateAdmission8136.runnerEntryAdmitted8252("mintR", 1_000_000L - 1L))
        assertFalse(CandidateAdmission8136.runnerEntryAdmitted8252("mintR", 1_000_000L + 6L * 60_000L))
        assertFalse(CandidateAdmission8136.runnerEntryAdmitted8252("other", 1_000_000L + 30_000L))
        assertTrue(src("engine/RunnerLive8153.kt").contains("snap?.takeIf { it.mint == ts.mint }?.runnerEntry == true"))
        assertFalse(src("engine/RunnerLive8153.kt").contains("CandidateAdmission8136.runnerEntryAdmitted8252"))
    }

    @Test fun oneLaunchBuyerVerdictProvenRefusedOrUnknown() {
        assertEquals(LaunchBuyers8252.BUYERS_PROVEN, OnChainDepth8038.launchBuyers8252(3.2))
        assertEquals(LaunchBuyers8252.BUYERS_PROVEN, OnChainDepth8038.launchBuyers8252(7.4))
        assertEquals(LaunchBuyers8252.NO_BUYERS_PROVEN, OnChainDepth8038.launchBuyers8252(1.1))
        assertEquals(LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN, OnChainDepth8038.launchBuyers8252(null))
        assertEquals(LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN, OnChainDepth8038.launchBuyers8252(Double.NaN))
        assertTrue(OnChainDepth8038.curveReadable8252("AbCdEfpump"))
        val src = src("engine/truth/OnChainDepth8038.kt")
        val door = src.substringAfter("fun buyRefusal8038(").substringBefore("BUY_DOOR_CURVE_DEPTH_READ_8045")
        // the direct measurement runs first and stays binding; the proxy decides only on the verdict
        assertTrue(door.indexOf("return \"CURVE_NOBODY_BOUGHT_8046\"") < door.indexOf("launchBuyers8252(curveReal8046)"))
        assertTrue(door.contains("LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN -> {"))
        assertTrue(door.contains("return \"LAUNCH_BUYERS_UNKNOWN_DEFER_8252\""))
        assertTrue(door.contains("LaunchBuyers8252.NO_BUYERS_PROVEN -> {"))
        val early = src.substringAfter("fun earlyLaunchRefusal8048(").substringBefore("enum class LaunchBuyers8252")
        assertTrue(early.contains("launchBuyers8252(real)"))
        assertTrue(early.contains("LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN -> {"))
        assertTrue(early.contains("LaunchBuyers8252.NO_BUYERS_PROVEN -> \"AT_LAUNCH_CAP_8047\""))
    }

    @Test fun unansweredLaunchReadsNeverBecomeAProvenNegative() {
        repeat(100) {
            for (unobserved in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
                assertEquals(LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN,
                    OnChainDepth8038.launchBuyers8252(unobserved))
            }
        }
        assertEquals(LaunchBuyers8252.NO_BUYERS_PROVEN, OnChainDepth8038.launchBuyers8252(0.0))
        assertEquals(LaunchBuyers8252.BUYERS_PROVEN, OnChainDepth8038.launchBuyers8252(3.0))
        val door = src("engine/truth/OnChainDepth8038.kt").substringAfter("fun buyRefusal8038(")
            .substringBefore("fun earlyLaunchRefusal8048(")
        val unknown = door.substringAfter("LaunchBuyers8252.BUYER_EVIDENCE_UNKNOWN -> {")
            .substringBefore("LaunchBuyers8252.NO_BUYERS_PROVEN -> {")
        assertTrue(unknown.contains("needDepth8047(ts, nowMs)"))
        assertTrue(unknown.contains("return \"LAUNCH_BUYERS_UNKNOWN_DEFER_8252\""))
        assertFalse(unknown.contains("AT_LAUNCH_CAP_8047"))
    }

    @Test fun arrivedEvidenceWakesTheHeldDeferThatAskedForIt() {
        val now = 5_000_000L
        CandidateAdmission8136.holdDeferForTest8252("mintD", "MOONSHOT", listOf("tape:NO_PRINTS", "shape:INVALID"), now)
        // scheduled with the 5 s backoff: not due one second later
        assertFalse(CandidateAdmission8136.reevalDueForTest8252("mintD", "tape:NO_PRINTS", now + 1_000L))
        assertFalse(CandidateAdmission8136.reevalDueForTest8252("mintD", "shape:INVALID", now + 1_000L))
        // a pair answer carries bp/chg/liq — not what this defer waits for: nothing wakes
        CandidateAdmission8136.evidenceArrived8252("mintD", CandidateAdmission8136.PAIR_FEATURES_8252, now + 1_000L)
        assertFalse(CandidateAdmission8136.reevalDueForTest8252("mintD", "tape:NO_PRINTS", now + 1_000L))
        // a print arrives: the tape feature is due now, the unrelated shape feature keeps its schedule
        CandidateAdmission8136.evidenceArrived8252("mintD", CandidateAdmission8136.TAPE_FEATURES_8252, now + 1_000L)
        assertTrue(CandidateAdmission8136.reevalDueForTest8252("mintD", "tape:NO_PRINTS", now + 1_000L))
        assertFalse(CandidateAdmission8136.reevalDueForTest8252("mintD", "shape:INVALID", now + 1_000L))
        // another mint's defer is untouched
        CandidateAdmission8136.holdDeferForTest8252("mintE", "SHITCOIN", listOf("tape:NO_PRINTS"), now)
        CandidateAdmission8136.evidenceArrived8252("mintD", CandidateAdmission8136.TAPE_FEATURES_8252, now + 1_000L)
        assertFalse(CandidateAdmission8136.reevalDueForTest8252("mintE", "tape:NO_PRINTS", now + 1_000L))
        val orch = src("engine/DataOrchestrator.kt")
        assertTrue(orch.substringAfter("private fun onTapeTrade7773(").substringBefore("LaunchTape7921.onTrade").contains("evidenceArrived8252(mint, com.lifecyclebot.engine.truth.CandidateAdmission8136.TAPE_FEATURES_8252)"))
        assertTrue(orch.contains("if (pair != null) try {"))
    }

    @Test fun theUnexecutedTicketNamesTheExecutorStop() {
        val ex = src("engine/Executor.kt")
        assertTrue(ex.contains("unexecutedTicket7948(layerTag, preflight.attemptId, namedUnexecuted8252(ts.mint, \"LIVE_BUY_NOT_OPENED_7948\"))"))
        assertTrue(ex.substringAfter("private fun emitLiveBuyFail(").substringBefore("LivePipelineTrace6431.bumpStage").contains("lastLiveBuyStop8252[ts.mint] = reason"))
        assertTrue(ex.substringAfter("private fun liveBuyDeferred(").substringBefore("RejectTaxonomy.classify").contains("lastLiveBuyStop8252[ts.mint] = reason"))
        // NetEdge: the breakdown reads this thread's components, never another candidate's
        val ne = src("engine/truth/NetEdge8030.kt")
        assertTrue(ne.contains("val c = tlComponents8252.get() ?: lastComponents8169"))
        assertTrue(ne.contains("tlComponents8252.set(components)"))
    }
}

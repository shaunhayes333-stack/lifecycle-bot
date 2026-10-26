package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LiveSizingTruth8136.EdgeMaturity8136
import com.lifecyclebot.engine.truth.RegressionRestore8298
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8298 — profitability regression audit (lifecycle_apk/AUDIT_REGRESSION_8298.md). The 8114 doors (timing / picked
 * setup / tactic negatives, chase rule, lane shape bins, watch-first negative cell, cell proof ladder, fresh-launch
 * stops-first prior) and the 8114 loss backstops (9 % fresh meme, 15 % hard floor, 15 % MOONSHOT first 12 min) bind
 * again for a candidate-specific ADMIT until the lane's realised live closes are mature and positive.
 */
class Aate8298RegressionRestoreTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun standDownIsEarnedOnlyByAMaturePositiveRealisedRecord() {
        assertFalse(RegressionRestore8298.standDownEarned8298(null))
        // 4 live closes, all losses (5.0.8286): not earned
        assertFalse(RegressionRestore8298.standDownEarned8298(EdgeMaturity8136(4, -17.6, 5.5, -0.02)))
        // a big label book is irrelevant; 12 closes, mean - SE > 0, net SOL > 0: earned
        assertTrue(RegressionRestore8298.standDownEarned8298(EdgeMaturity8136(12, 9.0, 3.0, 0.05)))
        // positive mean but inside one SE: not earned
        assertFalse(RegressionRestore8298.standDownEarned8298(EdgeMaturity8136(12, 2.0, 3.0, 0.01)))
        // positive mean but SOL lost (small winners, one large loser): not earned
        assertFalse(RegressionRestore8298.standDownEarned8298(EdgeMaturity8136(12, 9.0, 3.0, -0.01)))
        // too few closes
        assertFalse(RegressionRestore8298.standDownEarned8298(EdgeMaturity8136(9, 30.0, 3.0, 0.2)))
    }

    @Test fun backstopsReturnTo8114UntilEarned() {
        assertEquals(15.0, RegressionRestore8298.backstopMag8298(30.0, 15.0, false), 1e-9)
        assertEquals(30.0, RegressionRestore8298.backstopMag8298(30.0, 15.0, true), 1e-9)
        assertEquals(9.0, RegressionRestore8298.backstopMag8298(25.0, RegressionRestore8298.FRESH_MEME_BACKSTOP_PCT_8298, false), 1e-9)
        // never wider than the 8169 value either (a tighter widened value stays)
        assertEquals(12.0, RegressionRestore8298.backstopMag8298(12.0, 15.0, false), 1e-9)
        // unknown widened value: the 8114 backstop
        assertEquals(15.0, RegressionRestore8298.backstopMag8298(Double.NaN, 15.0, true), 1e-9)
        assertEquals(9.0, RegressionRestore8298.FRESH_MEME_BACKSTOP_PCT_8298, 1e-9)
        assertEquals(15.0, RegressionRestore8298.BACKSTOP_PCT_8298, 1e-9)
    }

    @Test fun liveEdgeGateStandDownsAreGatedOnTheEarnedRecord() {
        val gate = src("engine/truth/LiveEdgeGate7877.kt")
        assertTrue(gate.contains("val admittedJudged8201 = candidateSpecificAdmission8136 && RegressionRestore8298.specificStandDownEarned8298(l8059, nowMs)"))
        // the chase rule stands down on the earned judgement only; an unearned admission is refused and its terminal named
        val chase = gate.substringAfter("internal fun chaseRefusal8397(", "")
            .substringBefore("private fun developingRunGrab8117(", "")
        val admittedStandDown = chase.substringAfter("if (!provenCell && admittedJudged) {", "")
            .substringBefore("chaseRefused8065.incrementAndGet()", "")
        assertTrue(admittedStandDown.contains("ADMITTED_SPECIFIC_DUPLICATE_VETO_STOOD_DOWN_8201_CHASE_"))
        assertTrue(admittedStandDown.contains("return null"))
        assertTrue(chase.contains("return \"CHASING_SPIKE_8065\""))
        val chaseCall = gate.substringAfter("if (snap8065 != null) chaseRefusal8397(", "")
            .substringBefore("// Keep watch-first", "")
        assertTrue(chaseCall.contains("snap8065.currentVsPeak, nowMs, admittedJudged8201)?.let { reason ->"))
        val terminal = chaseCall.substringAfter("if (candidateSpecificAdmission8136) {", "")
            .substringBefore("observeTail7996", "")
        assertTrue(terminal.contains("RegressionRestore8298.note8298(\"CHASE\", l8059)"))
        assertTrue(terminal.contains("admittedTerminal8201(ts, lane, reason, candidateVersion)"))
        assertTrue(chaseCall.contains("return reason"))
        assertTrue(gate.contains("if (shape != null && shapeYields8298) {"))
        val cell = gate.substringAfter("if (why == \"WATCH_FIRST_7994_CELL_NEGATIVE\") {").substringBefore("return \"SUPPORTED_SETUP_8256\"")
        assertTrue(cell.contains("!RegressionRestore8298.specificStandDownEarned8298(lane, nowMs)"))
        // the live-close setup refusal and hard safety still run before any admission judgement
        assertTrue(gate.indexOf("LiveTruth8064.setupRefusal8083(l8059, setup8083, nowMs)") < gate.indexOf("val admittedJudged8201"))
    }

    @Test fun fdgLearnedRefusalsStandDownOnlyOnTheEarnedRecordOutsideEvaluate() {
        val fdg = src("engine/FinalDecisionGate.kt")
        val cellBlock = fdg.substringAfter("private fun cellProofBlock7731(").substringBefore("private fun freshLaunchBlock7737(")
        assertTrue(cellBlock.contains("RegressionRestore8298.restoredDoorStandsDown8298(raw, lane)"))
        val fresh = fdg.substringAfter("private fun freshLaunchBlock7737(").substringBefore("if (reason != null)")
        assertTrue(fresh.contains("(raw != \"FRESH_LAUNCH_SETUP_STOPS_FIRST_7737\" || com.lifecyclebot.engine.truth.RegressionRestore8298.restoredDoorStandsDown8298(raw, lane))"))
        // only those two helpers (evaluate() is untouched)
        assertEquals(2, fdg.split("RegressionRestore8298").size - 1)
    }

    @Test fun exitBackstopsReadTheRestore() {
        val ed = src("engine/ExitDoctrine8169.kt")
        assertTrue(ed.contains("restored8298(ts, hardFloorMag8169(ord), HARD_FLOOR_BASE_PCT_8169, \"RAPID_HARD_FLOOR\")"))
        assertTrue(ed.contains("restored8298(ts, hardFloorMag8169(ord), HARD_FLOOR_BASE_PCT_8169, \"RISK_HARD_FLOOR\")"))
        assertTrue(ed.contains("RegressionRestore8298.FRESH_MEME_BACKSTOP_PCT_8298, \"FRESH_MEME_BACKSTOP\")"))
        assertTrue(ed.contains("RegressionRestore8298.BACKSTOP_PCT_8298, \"EARLY_RUNNER_STOP\")"))
        assertTrue(ed.contains("RegressionRestore8298.backstopFor8298(ts.position.tradingMode, widened, base8114, door)"))
        // the call sites are unchanged (one-line wiring stays in Executor / BotService / MoonshotTraderAI)
        assertTrue(src("engine/Executor.kt").contains("val HARD_FLOOR_STOP_PCT = ExitDoctrine8169.riskHardFloorMag8169(ts, isFreshMeme)"))
        // the realised record is read from the canonical LIVE bus only (no paper, no labels)
        val rr = src("engine/truth/RegressionRestore8298.kt")
        assertTrue(rr.contains("CanonicalFinalizedTradeBus6464.terminalEnvelopes7863(\"live\")"))
        assertTrue(rr.contains("it.learningEligible"))
    }
}

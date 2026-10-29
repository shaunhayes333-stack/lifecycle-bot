package com.lifecyclebot.engine

import com.lifecyclebot.data.BotConfig
import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.*
import org.junit.Test

/** 8210: behavioural native WAIT contract and an actual FDG hard-negative invocation. */
class Aate8210AdmissionContinuityTest {
    private fun candidate(raw: String = "WAIT", reason: String = "Signal is WAIT, not BUY", veto: Boolean = false) =
        CandidateDecision.blocked(reason).copy(entryScore = 75.0, signal = raw, finalSignal = "WAIT",
            edgeVeto = veto, shouldTrade = false, setupQuality = "A", edgeQuality = if (veto) "SKIP" else "B",
            finalQuality = "A", aiConfidence = 75.0, edgeConfidence = 75.0)
    private fun admission(why: String = "GENERIC_PRIOR_CLEARS_8136", net: Double = 8.0) =
        CandidateAdmission8136.Decision8136(CandidateAdmission8136.Terminal8136.ADMIT, why,
            why == "SPECIFIC_EDGE_PROVEN_8136", "IGNITION_5M", net, emptyList(),
            basis = "GENERIC_PRIOR:IGNITION_5M", costPct = 4.0, costSizeSol = 0.04)

    @Test fun nativeWaitNeedsPositiveEconomicsAndSelectedStrategy() {
        assertTrue(FinalDecisionGate.nativeGenericWait8210(candidate(), "MOONSHOT", admission()))
        assertFalse(FinalDecisionGate.nativeGenericWait8210(candidate(), null, admission()))
        assertFalse(FinalDecisionGate.nativeGenericWait8210(candidate(), "MOONSHOT", admission(net = -0.5)))
        assertFalse(FinalDecisionGate.nativeGenericWait8210(candidate(), "MOONSHOT", admission("OTHER_REASON")))
        assertFalse(FinalDecisionGate.nativeGenericWait8210(candidate(veto = true), "MOONSHOT", admission()))
        assertFalse(FinalDecisionGate.nativeGenericWait8210(candidate(raw = "SELL"), "MOONSHOT", admission()))
        assertFalse(FinalDecisionGate.nativeGenericWait8210(candidate(reason = "HARD_BLOCK:RUG"), "MOONSHOT", admission()))
        assertFalse(FinalDecisionGate.nativeGenericWait8210(candidate(), "MOONSHOT", null))
    }

    @Test fun hardNoBuyActuallyCallsFdGAndStaysTerminal() {
        val token = TokenState("HardNo8210" + System.nanoTime(), symbol = "TEST")
        val no = candidate(raw = "HARD_NO_BUY").copy(finalSignal = "HARD_NO_BUY")
        val verdict = FinalDecisionGate.evaluate(token, no, BotConfig(paperMode = false), 0.04,
            specialistLane = "MOONSHOT", laneScore = 90.0)
        assertFalse(verdict.shouldTrade)
        assertEquals("PREFDG_HARD_NO_BUY_TERMINAL_7433", verdict.blockReason)
    }

    @Test fun gradingEpochAndProvenanceNeverUsePreviousFreshRefusals() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/ForwardReturnLabeler7731.kt").readText()
        // superseded by V5.0.8418 — the cost-contract change (mark gap no longer charged) starts a new grading epoch
        assertTrue(src.contains("GRADING_VERSION_8169 = 8418"))
        assertTrue(src.contains("return -1L"))
        assertTrue(src.contains("restoreFresh8169(p, System.currentTimeMillis())"))
        val adm = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CandidateAdmission8136.kt").readText()
        assertTrue(adm.contains("val liveCell8210 = if (c.lossEpochAware8169) c.cellLoss8169 else c.cell"))
    }
}

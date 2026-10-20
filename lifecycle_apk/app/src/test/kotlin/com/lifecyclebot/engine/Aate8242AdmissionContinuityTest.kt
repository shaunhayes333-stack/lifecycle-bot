package com.lifecyclebot.engine

import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.StrategyMeta
import com.lifecyclebot.engine.truth.CandidateAdmission8136
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 8242: candidate-specific admission and multi-lane data-fetch budgets. */
@Suppress("DEPRECATION")
class Aate8242AdmissionContinuityTest {
    private fun waiting() = CandidateDecision(
        entryScore=18.0, exitScore=0.0, phase="IGNITION", signal="WAIT",
        setupQuality="B", edgeQuality="A", finalQuality="B",
        edgePhase="EXPANSION", edgeConfidence=80.0, isOptimalEntry=true,
        edgeVeto=false, shouldTrade=false, finalSignal="WAIT", blockReason="",
        qualityPenalty=1.0, aiConfidence=80.0, meta=StrategyMeta(),
    )
    private fun admitted() = CandidateAdmission8136.Decision8136(
        terminal=CandidateAdmission8136.Terminal8136.ADMIT,
        reason="SPECIFIC_EDGE_PROVEN_8136", specific=true,
        chosenSetup="LAUNCH_LADDER_PROVEN", expectedNetPct=15.0,
        trace=listOf("edge=SETUP:LAUNCH_LADDER_PROVEN"),
        basis="SETUP:LAUNCH_LADDER_PROVEN n100 +25%", costPct=6.0, costSizeSol=0.030,
    )
    @Test fun blankReasonWaitDoesNotEraseProvenAdmissionOrBypassNegative() {
        val c=waiting(); val d=admitted()
        assertTrue(FinalDecisionGate.specificNativeWait8242(c,"SHITCOIN",d))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c,"",d))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c.copy(edgeVeto=true),"SHITCOIN",d))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c.copy(signal="SELL"),"SHITCOIN",d))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c.copy(signal="HARD_NO_BUY"),"SHITCOIN",d))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c.copy(blockReason="SAFETY_FAILED"),"SHITCOIN",d))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c,"SHITCOIN",d.copy(specific=false)))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c,"SHITCOIN",d.copy(basis="")))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c,"SHITCOIN",d.copy(expectedNetPct=-1.0)))
        assertFalse(FinalDecisionGate.specificNativeWait8242(c,"SHITCOIN",d.copy(terminal=CandidateAdmission8136.Terminal8136.REFUSE)))
        val src=File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(src.contains("return null to (nativeWait8210 || specificWait8242)"))
        assertTrue(src.contains("blockReasonFinal = \"ADMISSION_COST_REQUOTE_FAILED_8209\""))
    }
    @Test fun finalSizingConsumesTheExactAdmissionGenerationNotANewerScan() {
        val risk=File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveRiskPolicy7807.kt").readText()
        val fdg=File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(risk.contains("candidateVersion8198: Long = 0L"))
        assertTrue(risk.contains("val version = candidateVersion8198.takeIf { it > 0L }"))
        assertTrue(risk.contains("AdmissionEdge8180.proof8198(\"LIVE\", mint, effectiveLane7811, version)"))
        assertTrue(fdg.contains("candidateVersion8198 = candidateVersion8198"))
    }
    @Test fun deferredMintBudgetIsIsolatedByNativeLane() {
        val a=CandidateAdmission8136; val mint="lane-8242-"+System.nanoTime(); val t=1_000_000L
        for(i in 0..39) assertTrue(a.deferBudget8136(mint,t+i,"MOONSHOT"))
        assertFalse(a.deferBudget8136(mint,t+40,"MOONSHOT"))
        assertTrue(a.deferBudget8136(mint,t+41,"SHITCOIN"))
        assertNotEquals(a.deferScope8242(mint,"MOONSHOT"),a.deferScope8242(mint,"SHITCOIN"))
        assertEquals(a.deferScope8242(mint,"BLUECHIP"),a.deferScope8242(mint,"BLUE_CHIP"))
        val mint2="firstmissing-8242-"+System.nanoTime()
        assertTrue(a.deferBudgetFor8144(mint2,listOf("shape:UNKNOWN"),t,"MOONSHOT"))
        assertTrue(a.deferBudgetFor8144(mint2,listOf("bp:UNKNOWN"),t,"SHITCOIN"))
        assertTrue(a.deferUnanswered8204(mint2,listOf("shape:UNKNOWN"),t+1,"MOONSHOT"))
        assertFalse(a.deferUnanswered8204(mint2,listOf("bp:UNKNOWN"),t+1,"MOONSHOT"))
        assertTrue(a.deferUnanswered8204(mint2,listOf("bp:UNKNOWN"),t+1,"SHITCOIN"))
    }
}

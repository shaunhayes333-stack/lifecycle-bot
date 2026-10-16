package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaneScoreAdmission7308
import com.lifecyclebot.engine.HandoffReceiverTasks8153.Block8153
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for the 8159 admission -> specialist evaluation -> executor authority boundaries. */
class Aate8161AdmissionContinuityTest {
    @Test fun executorScoreIsBoundToMintOwnerModeAndCandidateGeneration() {
        val mint = "aate8161-${System.nanoTime()}"
        LaneScoreAdmission7308.record(
            mint, "MOONSHOT", 72.0, exploration = false,
            candidateVersion = 41L, mode = "LIVE", nowMs = 10_000L,
        )

        assertEquals(72.0, LaneScoreAdmission7308.forExecution(mint, "MOONSHOT", 41L, "LIVE", 10_001L)!!.score, 0.0)
        assertNull(LaneScoreAdmission7308.forExecution(mint, "SHITCOIN", 41L, "LIVE", 10_001L))
        assertNull(LaneScoreAdmission7308.forExecution(mint, "MOONSHOT", 42L, "LIVE", 10_001L))
        assertNull(LaneScoreAdmission7308.forExecution(mint, "MOONSHOT", 41L, "PAPER", 10_001L))
        assertNull(LaneScoreAdmission7308.forExecution(mint, "MOONSHOT", 41L, "LIVE", 10_000L + 10 * 60_000L + 1L))
    }

    @Test fun liveAdmissionAndHandoffTaskCloseOnTheReceiverDecision() {
        val gate = File("src/main/kotlin/com/lifecyclebot/engine/truth/LiveEdgeGate7877.kt").readText()
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val tasks = File("src/main/kotlin/com/lifecyclebot/engine/HandoffReceiverTasks8153.kt").readText()
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val executor = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()

        assertTrue(gate.contains("val admission8136 = candidateAdmission ?: try { CandidateAdmission8136.assess8136(ts, l8059, nowMs, candidateVersion)"))
        assertTrue(gate.contains("candidateSpecificAdmission8136 = admission8136?.specific == true"))
        assertTrue(gate.contains("!measuredEdge8060(ts, l8059, nowMs) && !candidateSpecificAdmission8136"))
        assertTrue(gate.contains("if (candidateSpecificAdmission8136 && !provenNegativeRefusal8136(prior)"))
        assertTrue(gate.contains("if (candidateSpecificAdmission8136 && !v.allow && !provenNegativeRefusal8136(v.why))"))

        val wrapper = bot.substringAfter("private fun laneQualifiedBuyDecision(").substringBefore("private fun laneQualifiedBuyDecisionCore(")
        assertTrue(wrapper.indexOf("val result = laneQualifiedBuyDecisionCore") in 0 until wrapper.indexOf("onNativeCandidate8153"))
        assertTrue(tasks.contains("fun onNativeCandidate8153("))
        assertTrue(tasks.contains("if (shouldTrade) return // the same task will be completed by its actual FDG verdict"))
        assertTrue(tasks.contains("NATIVE_NO_BUY_"))
        assertTrue(tasks.contains("DEFERRED_NATIVE:"))
        assertTrue(tasks.contains("currentVersion == t.version"))
        assertTrue(fdg.contains("assess8136(ts, floorLane7266, candidateVersion = candidateVersion7623)"))
        assertTrue(fdg.contains("?: tradingModeTag?.name ?: \"CORE\""))
        assertTrue(fdg.contains("candidateVersion7623, floorLane7266, candidateAdmission8136"))
        assertTrue(fdg.contains("val authoritativeAdmissionRefusal8136 = candidateAdmission8136?.takeIf"))
        assertTrue(gate.contains("candidateVersion, admission8136)?.let"))
        assertTrue(fdg.contains("assess8136(ts, lane, candidateVersion = candidateVersion)"))
        assertTrue(fdg.contains("confirmExecution7772(ts.mint, canonicalPrimaryLane6658, candidateVersion7623)"))
        assertTrue(executor.contains("LaneScoreAdmission7308.forExecution("))
        assertEquals(Block8153.DATA, HandoffReceiverTasks8153.classify8153("DATA_INCOMPLETE_DEFER_8136", fit = false, binding = false))
        assertEquals(Block8153.DATA, HandoffReceiverTasks8153.classify8153("TOO_FEW_BARS", fit = false, binding = false))
    }
}

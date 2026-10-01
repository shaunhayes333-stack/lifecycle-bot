package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7567FinalCExitTailClassificationTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `final c exit tail is helper accessor or formatter shaped`() {
        assertTrue(src("network/BirdeyeApi.kt").contains("fun getHolderDistribution("))
        assertTrue(src("engine/BotBrain.kt").contains("fun resetThresholds()"))
        assertTrue(src("engine/BotRuntimeController.kt").contains("fun runtimeJobActiveButUiStopped("))
        assertTrue(src("engine/truth/CanonicalPositionAuthority6441.kt").contains("fun activeMintProjections6489(): List<ActiveMintProjection6489> = activeMintProjections6490()"))
        assertTrue(src("engine/truth/CounterParityLedger6399.kt").contains("fun recordSellExecutorInvocation()"))
        assertTrue(src("engine/truth/EarlyEntryAndPeakCapture6390.kt").contains("leak canary"))
        assertTrue(src("v3/scoring/FluidLearningAI.kt").contains("fun getBreakoutThreshold()"))
        assertTrue(src("engine/TelegramNotifier.kt").contains("fun partialMsg("))
        assertTrue(src("engine/TradeIdentity.kt").contains("fun auditTrail(): String"))
    }

    @Test
    fun `audit marks c exit ledger fully classified`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7567 — final C_EXIT tail classification"))
        assertTrue(audit.contains("NETWORK DATA HELPER / BACKGROUND ONLY"))
        assertTrue(audit.contains("EXPLICIT LEARNING RESET CONTROL"))
        assertTrue(audit.contains("COMPATIBILITY ALIAS"))
        assertTrue(audit.contains("With 7560–7567, every C_EXIT row has now been"))
    }
}

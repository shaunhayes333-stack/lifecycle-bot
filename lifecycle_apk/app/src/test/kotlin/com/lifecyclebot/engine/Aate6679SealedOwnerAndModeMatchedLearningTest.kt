package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot
import com.lifecyclebot.engine.truth.ExecutionDecisionSnapshot6510
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6679SealedOwnerAndModeMatchedLearningTest {

    @After
    fun tearDown6679() {
        try { ExecutionDecisionSnapshot6510.resetForTest() } catch (_: Throwable) {}
        try { LaneExecutionCoordinator.resetForTests() } catch (_: Throwable) {}
        try { BotRuntimeController.resetForTests() } catch (_: Throwable) {}
    }

    @Test
    fun `sealed fdg specialist owner beats generic wrapper caller order`() {
        BotRuntimeController.resetForTests()
        val generation = BotRuntimeController.beginStart(paperMode = true, enabledTraders = "MEME")
        LaneExecutionCoordinator.resetForTests()
        ExecutionDecisionSnapshot6510.resetForTest()

        val mint = "Mint6679SealedOwner"
        val version = 66790001L
        ExecutionDecisionSnapshot6510.record(
            ExecutionDecisionSnapshot(
                mint = mint,
                candidateVersion = version,
                verdict = "BUY",
                executionLane = "PROJECT_SNIPER",
                score = 42.0,
                generatedAtMs = System.currentTimeMillis(),
                runtimeGeneration = generation,
                mode = "PAPER",
                authoritativeSignal = "BUY",
                safetyVerdict = "CAUTION",
                resolvedSizeSol = 0.05,
            )
        )

        val genericFirst = LaneExecutionCoordinator.canRequestExecution(
            mint = mint,
            lane = "CORE",
            candidateVersion = version,
            runtimeGeneration = generation,
        )
        assertFalse("generic CORE wrapper must defer to sealed PROJECT_SNIPER", genericFirst.allowed)
        assertEquals("PROJECT_SNIPER", genericFirst.primaryLane)

        val specialist = LaneExecutionCoordinator.canRequestExecution(
            mint = mint,
            lane = "PROJECT_SNIPER",
            candidateVersion = version,
            runtimeGeneration = generation,
        )
        assertTrue("sealed PROJECT_SNIPER owner must retain execution authority", specialist.allowed)
        assertEquals("PROJECT_SNIPER", specialist.primaryLane)
    }

    @Test
    fun `lane damper selects clean terminal truth from current mode only`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/LaneExpectancyDamper.kt").readText()
        assertTrue(src.contains("RuntimeModeAuthority.isPaper()"))
        assertTrue(src.contains("computeCleanPaperTerminalLeaderboard()"))
        assertTrue(src.contains("computeCleanLiveTerminalLeaderboard()"))
        assertTrue(src.contains("cachedMode6679"))
        assertFalse(src.contains("StrategyTelemetry.computeLeaderboard("))
    }

    @Test
    fun `regime detector does not blend environments and its lane exemption is mode matched`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/RegimeDetector.kt").readText()
        assertTrue(src.contains("it.mode.equals(mode6679, ignoreCase = true)"))
        assertTrue(src.contains("computeCleanPaperTerminalLeaderboard(limit = 1_500)"))
        assertTrue(src.contains("computeCleanLiveTerminalLeaderboard(limit = 1_500)"))
        assertTrue(src.contains("cachedMode6679"))
    }

    @Test
    fun `chronic bleeder scout feeds llm reprove from same runtime mode`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ChronicBleederScout.kt").readText()
        assertTrue(src.contains("RuntimeModeAuthority.isPaper()"))
        assertTrue(src.contains("computeCleanPaperTerminalLeaderboard(limit = 1_500)"))
        assertTrue(src.contains("computeCleanLiveTerminalLeaderboard(limit = 1_500)"))
        assertTrue(src.contains("scoutKey6679 = \"\$env6679|\$laneU\""))
        assertTrue(src.contains("CHRONIC_BLEEDER_LAB_REPROVE_6679"))
    }
}

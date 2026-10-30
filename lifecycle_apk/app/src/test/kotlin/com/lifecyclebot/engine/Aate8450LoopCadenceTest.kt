package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8450 — the bot loop ran a steady 30-35 s a cycle on 8446 while the cycle body exceeded 5 s only 4 times: it was
 * sleeping ~30 s (the LLM tuner's old pollSeconds ceiling, persisted in the config). The sleep is clamped to 3..8 s and
 * the tuner's ceiling is 8 s; the per-cycle trade count takes no lock.
 */
class Aate8450LoopCadenceTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theLoopSleepsAtMostEightSeconds() {
        assertEquals(8, BotService.pollSecondsFor8450(30))
        assertEquals(5, BotService.pollSecondsFor8450(5))
        assertEquals(3, BotService.pollSecondsFor8450(0))
        val bs = src("engine/BotService.kt")
        assertFalse(bs.contains("delay(cfg.pollSeconds * 1000L)"))
        assertEquals(2, Regex("delay\\(pollMs8450\\(cfg\\)\\)").findAll(bs).count())
        val tuner = src("engine/LlmParameterTuner.kt")
        assertTrue(tuner.contains("min = 3.0, max = 8.0, maxStep = 5.0"))
        assertTrue(LlmParameterTuner.isAllowedKey("pollSeconds"))
    }

    @Test fun theShadowMapIsConcurrentAndTheTradeCountTakesNoLock() {
        // V5.0.8451 — the 8450 worker offload of checkShadowPositions was withdrawn after 8449/8450 runtime failures; the check
        // runs on the loop again. The concurrent map and the lock-free per-cycle count stay.
        val bs = src("engine/BotService.kt")
        assertTrue(bs.contains("executor.checkShadowPositions(tokenStatesCopy)"))
        assertTrue(src("engine/Executor.kt").contains("private val shadowPositions = java.util.concurrent.ConcurrentHashMap<String, ShadowPosition>()"))
        assertFalse(bs.contains("val tradeCount = synchronized(status.tokens)"))
    }
}

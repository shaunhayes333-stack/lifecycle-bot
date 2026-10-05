package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7814 — autonomous self-healing starts from trade one. */
class Aate7814AutonomousSelfHealingFromTradeOneTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test fun tunerHasNoFiftyTradeDeadZone() {
        val s = src("engine/LlmParameterTuner.kt")
        assertTrue(s.contains("TUNE_BOOTSTRAP_END = 1"))
        assertTrue(s.contains("requires first settled trade"))
        assertFalse(s.contains("TUNE_BOOTSTRAP_END = 50"))
        assertFalse(s.contains("cannot self-adjust until learning phase"))
    }

    @Test fun freeRangeTunerStrengthStartsAtFirstTrade() {
        val s = src("engine/FreeRangeMode.kt")
        assertTrue(s.contains("TUNER_RAMP_START = 1"))
        assertTrue(s.contains("trade 1 begins at 5% authority"))
    }

    @Test fun autonomousAdvisorAppliesInLiveWithoutOperatorApproval() {
        val advisor = src("engine/truth/AutoPipelineAdvisor6462.kt")
        val self = src("engine/SelfHealingAdvisor.kt")
        val cfg = src("data/BotConfig.kt")
        assertTrue(advisor.contains("default: autonomous in PAPER and LIVE"))
        assertTrue(advisor.contains("cfg?.autoPipelineAdvisorEnabled ?: true"))
        assertTrue(advisor.contains("applyOne(ctx, c)"))
        assertTrue(self.contains("autoApplySuggestions(ctx, parsed)"))
        assertTrue(self.contains("No operator approval is required"))
        assertTrue(cfg.contains("autoPipelineAdvisorEnabled: Boolean = true"))
    }

    @Test fun safetyBoundsAndRollbackRemainIntact() {
        val tuner = src("engine/LlmParameterTuner.kt")
        val advisor = src("engine/truth/AutoPipelineAdvisor6462.kt")
        assertTrue(tuner.contains("ALLOWED_SPECS"))
        assertTrue(tuner.contains("coerceIn(-spec.maxStep * stepCapMultiplier"))
        assertTrue(tuner.contains("coerceIn(spec.min, spec.max)"))
        assertTrue(advisor.contains("AUTO_APPLY_MIN_AGREEMENT"))
        assertTrue(advisor.contains("PER_KEY_COOLDOWN_MS"))
        assertTrue(advisor.contains("AdvisorRegressionMonitor6463.registerApply"))
    }
}

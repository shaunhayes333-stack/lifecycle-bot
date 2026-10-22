package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8321 — owner's exact L1–L3 / A1–A3 (directive 8316): LLM tuner authority
 * from the first settled trade, LLM asked with an empty rules base,
 * direction-aware brain votes, self-heal on the advisor cadence and revertible.
 * Every guardrail (allowlist min/max/maxStep, 3 per call, 2 per tick, 10-min
 * cooldown, auto-revert, trade 0 locked) must survive.
 */
class Aate8321AdvisorTunerAuthorityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test fun keysOutsideAllowlistAreRejected() {
        for (k in listOf("paperMode", "perPositionSizePct", "killSwitch", "EXIT_SAFE_HARD_FLOOR",
            "maxDrawdownPct", "routeMinimumSol", "requireFreezeAuthorityRevoked", "")) {
            assertFalse("$k must not be tunable", LlmParameterTuner.isAllowedKey(k))
            assertFalse("$k must not be offered to the LLM", LlmParameterTuner.allowedKeys().contains(k))
        }
        val tuner = src("engine/LlmParameterTuner.kt")
        assertTrue(tuner.contains("rejected.add(\"\$key: not in allowlist\")"))
        assertTrue(src("engine/SelfHealingAdvisor.kt").contains("!LlmParameterTuner.isAllowedKey(key)) continue"))
        assertTrue(src("engine/truth/AutoPipelineAdvisor6462.kt").contains("!LlmParameterTuner.isAllowedKey(key)) continue"))
    }

    @Test fun perPositionSizePctRemovedFromTunerAndPrompt() {
        assertFalse(LlmParameterTuner.isAllowedKey("perPositionSizePct"))
        val tuner = src("engine/LlmParameterTuner.kt")
        val gemini = src("engine/GeminiCopilot.kt")
        assertFalse(tuner.contains("\"perPositionSizePct\" to ParamSpec"))
        assertFalse(gemini.contains("slippageBps, perPositionSizePct"))
    }

    @Test fun authorityFromFirstSettledTradeWithGuardrails() {
        val fr = src("engine/FreeRangeMode.kt")
        val tuner = src("engine/LlmParameterTuner.kt")
        assertTrue(fr.contains("private const val TUNER_RAMP_END   = 1 "))
        assertFalse(fr.contains("TUNER_RAMP_END   = 1000"))
        assertTrue(tuner.contains("private const val TUNE_LEARNING_END  = 1 "))
        assertFalse(tuner.contains("TUNE_LEARNING_END  = 3000"))
        assertTrue(tuner.contains("TUNE_BOOTSTRAP_END = 1"))
        assertTrue(tuner.contains("MAX_ADJUSTMENTS_PER_CALL = 3"))
        assertTrue(tuner.contains("coerceIn(-spec.maxStep * stepCapMultiplier"))
        assertTrue(tuner.contains("coerceIn(spec.min, spec.max)"))
        // ramp branch checks >= TUNER_RAMP_END before dividing by the span
        val ramp = fr.substringAfter("fun adjustmentStrength(")
        assertTrue(ramp.indexOf("trades >= TUNER_RAMP_END") < ramp.indexOf("val span"))
    }

    @Test fun copilotPromptStatesRealAuthority() {
        val g = src("engine/GeminiCopilot.kt")
        assertTrue(g.contains("AUTHORITY: TUNE is LIVE from the first settled trade."))
        assertTrue(g.contains("Trade 0 (no settled trade yet) is the only locked state."))
        assertFalse(g.contains("Bootstrap (0-999 trades): TUNE is LOCKED"))
    }

    @Test fun advisorAsksLlmWithEmptyBaseAndVotesByDirection() {
        val a = src("engine/truth/AutoPipelineAdvisor6462.kt")
        val enrich = a.substringAfter("private fun enrichWithLlm(").substringBefore("private data class LlmCandidate")
        assertFalse(enrich.contains("if (base.isEmpty()) return base"))
        assertTrue(enrich.contains("(none — the rules engine found nothing; propose up to 3 adjustments from the report yourself)"))
        assertTrue(a.contains("TIGHTEN_ON_INCREASE_8319 = setOf(\"minLiquidityUsd\", \"minDiscoveryScore\", \"walletReserveSol\", \"entryCooldownSec\")"))
        assertTrue(a.contains("val loosening = if (c.key in TIGHTEN_ON_INCREASE_8319) c.delta < 0.0 else c.delta > 0.0"))
        assertTrue(a.contains("AUTO_APPLY_MIN_AGREEMENT = 0.55"))
        assertTrue(a.contains("MAX_APPLY_PER_TICK = 2"))
        assertTrue(a.contains("PER_KEY_COOLDOWN_MS = 10L * 60_000L"))
    }

    @Test fun selfHealRunsOnAdvisorCadenceAndIsRevertible() {
        val bs = src("engine/BotService.kt")
        val block = bs.substringAfter("V5.0.6462 — AUTONOMOUS PIPELINE ADVISOR.").substringBefore("ADVISOR REGRESSION MONITOR")
        assertTrue(block.contains("AutoPipelineAdvisor6462.maybeTick(applicationContext)"))
        assertTrue(block.contains("SelfHealingAdvisor.maybeAutoAdvise(applicationContext, \"loop8319\")"))
        val self = src("engine/SelfHealingAdvisor.kt")
        assertTrue(self.contains("AdvisorRegressionMonitor6463.registerApply("))
        assertTrue(self.contains("reason = \"selfheal8319:"))
        assertTrue(self.contains("MIN_INTERVAL_MS = 5L * 60_000L"))
    }
}

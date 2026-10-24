package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 8365: a self-healing action must affect a live consumer and be receipt-confirmed. */
class Aate8365AdvisorEffectiveTuningTest {
    private fun source(name: String) =
        File("src/main/kotlin/com/lifecyclebot/engine/" + name).readText()

    @Test fun disconnectedDiscoveryThresholdCannotProduceFalseTuningSuccess() {
        assertFalse(LlmParameterTuner.isAllowedKey("minDiscoveryScore"))
        assertFalse(LlmParameterTuner.allowedKeys().contains("minDiscoveryScore"))
        assertFalse(source("GeminiCopilot.kt").contains("scanIntervalSecs, minDiscoveryScore."))
        val tuner = source("LlmParameterTuner.kt")
        assertFalse(tuner.contains("\"minDiscoveryScore\" to ParamSpec"))
        assertTrue(tuner.contains("minDiscoveryScore deliberately excluded"))
        assertFalse(source("SentienceHooks.kt").substringAfter("val qualityKeys = listOf(")
            .substringBefore(").filter").contains("\"minDiscoveryScore\""))
    }

    @Test fun rejectedSuggestionRemainsPendingRatherThanMarkedAsApplied() {
        val self = source("SelfHealingAdvisor.kt")
        val path = self.substringAfter("fun applySuggestion(ctx: Context, suggestion: Suggestion)")
            .substringBefore("private const val SYSTEM_PROMPT_TEMPLATE")
        val accepted = path.indexOf("return if (res.changes.isNotEmpty())")
        val marked = path.indexOf("AdvisorInbox.markApplied(suggestion.id)")
        val rejected = path.indexOf("else if (res.rejected.isNotEmpty())")
        assertTrue(accepted >= 0 && marked > accepted && rejected > marked)
        assertFalse(path.substring(0, accepted).contains("AdvisorInbox.markApplied(suggestion.id)"))
    }
}

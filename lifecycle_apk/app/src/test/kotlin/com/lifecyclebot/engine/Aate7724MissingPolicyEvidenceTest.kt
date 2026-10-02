package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AateStrategyContext6512
import com.lifecyclebot.engine.truth.PolicySynthesizer6512
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7724MissingPolicyEvidenceTest {
    @Test fun absent_brain_outcomes_remain_absent_on_the_decision_envelope() {
        val envelope = PolicySynthesizer6512.synthesize(
            context = AateStrategyContext6512(
                candidateId = "mint:1", runtimeGeneration = 1L, mode = "LIVE",
                mint = "mint", symbol = "TEST", candidateVersion = 1L,
                primaryStrategy = "QUALITY", source = "TEST", regime = "NORMAL",
            ),
            proposedAction = "BUY", scoreBase = 10.0, scoreFinal = 10.0,
            sizeBase = 0.01, sizeFinal = 0.01, tactic = "TEST",
            hardSafety = emptyList(), contributors = emptyList(), learningState = "bootstrap",
        )

        assertNull(envelope.pWin)
        assertNull(envelope.expectedPnlPct)
        assertNull(envelope.moonshotP)
        assertNull(envelope.rugP)
    }

    @Test fun recovered_missing_fields_bind_neutral_model_features() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt").readText()
        val fallback = source.substringAfter("fun bindDecisionFallback6713(")
            .substringBefore("fun ", missingDelimiterValue = "")
        assertTrue(fallback.contains("expectedPnlPct?.let") && fallback.contains("?: 0.5"))
        assertTrue(fallback.contains("pWin?.coerceIn(0.0, 1.0) ?: 0.5"))
        assertTrue(fallback.contains("rugP?.let") && fallback.contains("?: 0.5"))
    }
}

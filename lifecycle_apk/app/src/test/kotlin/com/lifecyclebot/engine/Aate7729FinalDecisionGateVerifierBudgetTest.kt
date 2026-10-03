package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7729FinalDecisionGateVerifierBudgetTest {
    private fun source() = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()

    @Test fun `forward outcome work is extracted from ART constrained evaluate`() {
        val source = source()
        val evaluate = source.substringAfter("fun evaluate(\n        ts: TokenState,")
        val helper = source.substringAfter("private fun forwardForecastAndStamp7729(").substringBefore("fun evaluate(")

        assertTrue(evaluate.contains("forwardForecastAndStamp7729("))
        assertFalse(evaluate.contains("ForwardOutcomeModel.stampDecision("))
        assertFalse(evaluate.contains("ForwardOutcomeModel.forecast("))
        assertTrue(helper.contains("candidateVersion = candidateVersion"))
        assertTrue(helper.contains("ForwardOutcomeModel.stampDecision("))
        assertTrue(helper.contains("ForwardOutcomeModel.forecast("))
        assertTrue(helper.contains("SignalQualityTracker.stamp("))
    }
}

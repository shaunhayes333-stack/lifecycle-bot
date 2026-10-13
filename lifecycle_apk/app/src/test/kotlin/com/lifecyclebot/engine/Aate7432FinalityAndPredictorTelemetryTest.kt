package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7432FinalityAndPredictorTelemetryTest {

    @Test fun historicalFinalityGapIsNotMislabelledAsCurrentBusFailure() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()
        assertTrue(s.contains("HISTORICAL_NO_DURABLE_FINALITY"))
        assertTrue(s.contains("fullTerminalByPosition7432"))
        // V5.0.8085 — the invariant moved (7544/8082): full terminal sells come from the cached durable-economics view,
        // and BUS_PUBLISH_FAILED requires a repairable full terminal sell for the same canonical positionId.
        assertTrue(s.contains("EconomicEventSchema6464.fullTerminalSellsByPosition7500()"))
        assertTrue(s.contains("val terminal7544 = fullTerminalSells7544[p.positionId]"))
        assertTrue(s.contains("val repairable7544 = terminal7544 != null && hasUsableBasis7544"))
        assertTrue(s.contains("repairable7544 -> Reason.BUS_PUBLISH_FAILED"))
        val e = File("src/main/kotlin/com/lifecyclebot/engine/truth/EconomicEventSchema6464.kt").readText()
        val full = e.substringAfter("fun fullTerminalSellsByPosition7500()").take(900)
        assertTrue(full.contains(".filterIsInstance<Sell>()"))
        assertTrue(full.contains("!it.partial && it.positionId.isNotBlank()"))
    }

    @Test fun predictorCountersSeparateConsultedFromContributed() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(s.contains("TRADING_MEMORY_PATTERN_CONSULTED_7432"))
        assertTrue(s.contains("TRADING_MEMORY_PATTERN_NEUTRAL_7432"))
        assertTrue(s.contains("TRADING_COPILOT_PREDICTIVE_CONSULTED_7432"))
        assertTrue(s.contains("TRADING_COPILOT_PREDICTIVE_NEUTRAL_7432"))
    }

    @Test fun exactStrategyPriorReportsWhyItIsNeutral() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        assertTrue(s.contains("EXACT_STRATEGY_EV_PRIOR_CONSULTED_7432"))
        assertTrue(s.contains("EXACT_STRATEGY_EV_PRIOR_NO_EVIDENCE_7432"))
        assertTrue(s.contains("EXACT_STRATEGY_EV_PRIOR_IMMATURE_7432"))
        assertTrue(s.contains("EXACT_STRATEGY_EV_PRIOR_NEUTRAL_7432"))
    }
}

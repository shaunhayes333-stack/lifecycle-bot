package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7432FinalityAndPredictorTelemetryTest {

    @Test fun historicalFinalityGapIsNotMislabelledAsCurrentBusFailure() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedLearningReconciler7423.kt").readText()
        assertTrue(s.contains("HISTORICAL_NO_DURABLE_FINALITY"))
        assertTrue(s.contains("fullTerminalByPosition7432"))
        assertTrue(s.contains("filterIsInstance<EconomicEventSchema6464.Sell>()"))
        assertTrue(s.contains("!it.partial"))
        assertTrue(s.contains("p.positionId in fullTerminalByPosition7432 -> Reason.BUS_PUBLISH_FAILED"))
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

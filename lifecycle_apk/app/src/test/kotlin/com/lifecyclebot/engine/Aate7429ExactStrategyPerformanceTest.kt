package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7429ExactStrategyPerformanceTest {
    @Test fun exactStrategyLedgerConsumesSealedIdentity() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExactStrategyPerformance7429.kt").readText()
        listOf(
            "entryTradeType", "entrySetup", "entryStyle",
            "entryTactic", "entryStrategyVariantId",
        ).forEach { assertTrue("missing exact strategy field $it", src.contains("env.$it")) }
        assertTrue(src.contains("EXACT_STRATEGY_OUTCOME_7429"))
        assertTrue(src.contains("EXACT_STRATEGY_ATTRIBUTION_COMPLETE_7429"))
    }

    @Test fun canonicalBusRegistersExactStrategyConsumer() {
        val bus = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalFinalizedTradeBus6464.kt").readText()
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(bus.contains(""ExactStrategyPerformance7429""))
        assertTrue(bridge.contains(""ExactStrategyPerformance7429" -> deliverToExactStrategyPerformance7429(env)"))
        assertTrue(bridge.contains("ExactStrategyPerformance7429.record(env)"))
    }

    @Test fun hypothesisTerminalCreditIsPositionBound() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val start = bridge.indexOf("private fun deliverToStrategyHypothesis")
        val end = bridge.indexOf("private fun deliverToExactStrategyPerformance7429", start)
        val block = bridge.substring(start, end)
        assertTrue(block.contains("recordOutcomeForPosition7428"))
        assertTrue(block.contains("env.positionId"))
        assertTrue(!block.contains("recordOutcome(env.mint"))
    }

    @Test fun pipelinePrintsExactStrategyScoreboard() {
        val health = File("src/main/kotlin/com/lifecyclebot/engine/PipelineHealthCollector.kt").readText()
        assertTrue(health.contains("Exact strategy (§7429)"))
        assertTrue(health.contains("ExactStrategyPerformance7429.statusLine()"))
    }
}

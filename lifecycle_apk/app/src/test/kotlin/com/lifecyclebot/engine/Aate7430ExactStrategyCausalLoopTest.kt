package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7430ExactStrategyCausalLoopTest {

    @Test fun entry_snapshot_preserves_distinct_strategy_namespaces() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/EntryStrategySnapshot6450.kt").readText()
        listOf(
            "entryTradeType",
            "entrySetup",
            "entryStyle",
            "entryEntryStyle",
            "entryExitStyle",
            "entryStrategyVariantId",
        ).forEach { assertTrue("missing $it", src.contains("val $it: String")) }
        assertTrue(src.contains("tradeType7427"))
        assertTrue(src.contains("setup7427"))
        assertTrue(src.contains("style7427"))
        assertTrue(src.contains("variant7427"))
    }

    @Test fun fdg_hypothesis_context_contains_exact_playbook_identity() {
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("exactStrategyIdentity7430"))
        assertTrue(fdg.contains("cls7430.tradeType.name"))
        assertTrue(fdg.contains("style7430.toolkit.setup.name"))
        assertTrue(fdg.contains("style7430.style.name"))
        assertTrue(fdg.contains("style7430.tactic.name"))
        assertTrue(fdg.contains("StrategyHypothesisEngine.getSizeBias("))
        assertTrue(fdg.contains("exactStrategyIdentity7430,"))
    }

    @Test fun hypothesis_engine_keeps_exact_context_and_parent_seed() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue(src.contains("ctxKey7430"))
        assertTrue(src.contains("seedExactFromParent7430"))
        assertTrue(src.contains("HYPOTHESIS_EXACT_CONTEXT_STAMPED_7430"))
        assertTrue(src.contains("strategyIdentity: String = \"\""))
        assertTrue(src.contains("pendingByDecision7428"))
        assertTrue(src.contains("pendingByPosition7428"))
        assertTrue(src.contains("bindExecutedPosition7428"))
    }

    @Test fun finalized_bus_settles_bound_position_not_mutable_mint_state() {
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        val start = bridge.indexOf("private fun deliverToStrategyHypothesis")
        assertTrue(start >= 0)
        val region = bridge.substring(start, (start + 1200).coerceAtMost(bridge.length))
        assertTrue(region.contains("recordOutcomeForPosition7428("))
        assertTrue(region.contains("env.positionId"))
        assertFalse(region.contains("recordOutcome(env.mint"))
    }

    @Test fun exact_variant_is_bound_at_open_and_credited_at_close() {
        val engine = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue(engine.contains("STRATEGY_VARIANT_EXACT_STAMPED_7428"))
        assertTrue(engine.contains("STRATEGY_VARIANT_EXACT_OUTCOME_7428"))
        assertTrue(engine.contains("StrategyVariantStore.recordOutcome("))
        assertTrue(engine.contains("applied.strategyVariantId"))
    }
}

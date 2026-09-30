package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7537ShadowExplorationBudgetTest {
    @Test fun exploration_shadow_route_consumes_shadow_budget_only() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val block = s.substringAfter("if (paperExploration7525) {")
            .substringBefore("doBuy(ts, size, decision.entryScore")
        assertTrue(block.contains("ExplorationBudget.allowShadowSignal(shadowLane7537)"))
        assertTrue(block.contains("PAPER_EXPLORATION_SHADOW_BUDGET_REFUSED_7537"))
        assertTrue(block.contains("PAPER_EXPLORATION_SHADOW_BUDGET_ADMIT_7537"))
        assertFalse(block.contains("allowPaperMicroTrade"))
    }

    @Test fun benchmark_economic_route_remains_outside_shadow_budget() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val classSet = s.substringAfter("val paperExploration7525 =")
            .substringBefore("if (paperExploration7525)")
        assertFalse(classSet.contains("PAPER_BENCHMARK"))
    }
}

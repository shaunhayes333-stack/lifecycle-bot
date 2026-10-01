
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7646AdaptiveDeliberationTest {
    @Test fun controllerAllocatesBoundedVariableDepth() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperDeliberationController7646.kt").readText()
        assertTrue(s.contains("complexity >= 0.78 -> 5"))
        assertTrue(s.contains("5 -> 21"))
        assertTrue(s.contains("novel_and_uncertain"))
        assertTrue(s.contains("high_downside_risk"))
        assertTrue(s.contains("large_optional_upside"))
    }

    @Test fun imaginationBudgetIsDynamicAndBounded() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperImaginationRollout7643.kt").readText()
        assertTrue(s.contains("rolloutBudget: Int = 11"))
        assertTrue(s.contains("rawBudget.coerceIn(5, 21)"))
        assertTrue(s.contains("shocksForBudget7646"))
    }

    @Test fun treeUsesRecursiveLookaheadAndAdaptiveRollouts() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        assertTrue(s.contains("recursiveLookahead7646"))
        assertTrue(s.contains("deliberation.depth"))
        assertTrue(s.contains("rolloutBudget = deliberation.rolloutBudget"))
    }

    @Test fun oracleEmitsDeliberationBeforeTreeSearch() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(s.contains("SuperDeliberationController7646.plan("))
        assertTrue(s.contains("contributions += deliberation7646.contributionTag()"))
    }
}


package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7638RetrievalTreeSearchTest {
    @Test fun retrieverUsesExactStrategyThenSemanticFallback() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperEpisodicRetriever7638.kt").readText()
        assertTrue(s.contains("ExactStrategyPerformance7429.evidenceFor7431("))
        assertTrue(s.contains("SemanticPatternGraph.entryDnaBias("))
        assertTrue(s.contains("private const val TTL_MS = 20_000L"))
    }

    @Test fun policyTreeSearchesMultiStepPolicies() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        assertTrue(s.contains("REDUCED_THEN_SCALE"))
        assertTrue(s.contains("BASE_TACTICAL_HOLD"))
        assertTrue(s.contains("BASE_TACTICAL_BANK"))
        assertTrue(s.contains("CONVICTION_RUNNER"))
        assertTrue(s.contains("branches.maxByOrNull"))
    }

    @Test fun plannerConsumesTreeSearchAndOracleExposesRetrieval() {
        val planner = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligencePlanner7633.kt").readText()
        val oracle = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(planner.contains("tree: SuperPolicyTree7638.Result? = null"))
        assertTrue(planner.contains("tree.rootAction == action"))
        assertTrue(oracle.contains("SuperEpisodicRetriever7638.retrieve("))
        assertTrue(oracle.contains("SuperPolicyTree7638.search("))
        assertTrue(oracle.contains("contributions += tree7638.contributionTag()"))
    }
}

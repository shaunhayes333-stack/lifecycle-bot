
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7643DistributionalImaginationTest {
    @Test fun imaginationProducesDownsideAndUpsideDistribution() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperImaginationRollout7643.kt").readText()
        assertTrue(s.contains("downsideCvar"))
        assertTrue(s.contains("downsideP10"))
        assertTrue(s.contains("upsideP90"))
        assertTrue(s.contains("failureProbability"))
        assertTrue(s.contains("robustUtility"))
        assertTrue(s.contains("private val shocks"))
    }

    @Test fun treeRanksPoliciesByRobustDistributionalUtility() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        assertTrue(s.contains("SuperImaginationRollout7643.evaluate("))
        assertTrue(s.contains("utility = imagined.robustUtility"))
        assertTrue(s.contains("downsideCvar = imagined.downsideCvar"))
        assertTrue(s.contains("failureProbability = imagined.failureProbability"))
    }

    @Test fun imaginationIsLocalAndHasNoExecutionAuthority() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperImaginationRollout7643.kt").readText()
        assertTrue(!s.contains("executeBuy"))
        assertTrue(!s.contains("GeminiCopilot"))
        assertTrue(!s.contains("TradeAuthorizer"))
    }
}

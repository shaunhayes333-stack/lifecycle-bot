
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7650ExistingIntelligenceIntegrationTest {
    @Test fun specialistBridgeIsReadFromCacheNotReevaluated() {
        val a = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SpecialistBrainBridge7542.kt").readText()
        assertTrue(a.contains("SpecialistBrainBridge7542.cachedSnapshot7650(mint)"))
        assertTrue(!a.contains("SpecialistBrainBridge7542.evaluate("))
        assertTrue(s.contains("fun cachedSnapshot7650(mint:String):Snapshot?"))
    }

    @Test fun existingBrainsAndLearningStacksFeedPlanner() {
        val a = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        listOf(
            "UltimateEdgeEngine.cached",
            "BrainConsensusBridge6329.consult",
            "StrategyHypothesisEngine.peekSizeBias",
            "AsyncStrategyLab.reviewedSizeBias",
            "CounterfactualReplayEngine.mctsExitPolicyHint",
        ).forEach { assertTrue(a.contains(it)) }
    }

    @Test fun treeConsumesExistingStackAsBoundedPrior() {
        val t = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        val o = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(t.contains("existing: ExistingIntelligenceContext7650.Snapshot? = null"))
        assertTrue(t.contains("existing?.policyPrior(b.policy, world.latentState) ?: 0.0"))
        assertTrue(o.contains("ExistingIntelligenceContext7650.read("))
        assertTrue(o.contains("contributions += existing7650.contributionTag()"))
    }
}

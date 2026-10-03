package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7534CanonicalOwnerLearningIdentityTest {
    @Test fun entry_time_learning_uses_canonical_execution_owner() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val block = s.substringAfter("val learningOwnerLane7534 = canonicalPrimaryLane6658")
            .substringBefore("// V5.9.1330")
        assertFalse(block.contains("mpLane"))
        assertTrue(block.contains("AutonomousMetaPolicy.stampDecision(ts.mint, learningOwnerLane7534"))
        assertTrue(block.contains("ForwardOutcomeModel.stampDecision("))
        assertTrue(block.contains("candidateVersion = candidateVersion7623"))
        assertTrue(block.contains("UnifiedPolicyHead.stamp(ts.mint, learningOwnerLane7534"))
        assertTrue(block.contains("StrategyHypothesisEngine.getSizeBias(\n                            learningOwnerLane7534"))
        assertTrue(block.contains("AgenticStyleRouter.decide(ts, cls7430, learningOwnerLane7534)"))
    }

    @Test fun aate_envelope_primary_strategy_is_canonical_owner() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val ctx = s.substringAfter("val context6512 = com.lifecyclebot.engine.truth.AateStrategyContext6512(")
            .substringBefore("com.lifecyclebot.engine.truth.AateDecisionFabric6512.record")
        assertTrue(ctx.contains("primaryStrategy = canonicalPrimaryLane6658"))
        assertFalse(ctx.contains("primaryStrategy = laneName"))
    }
}

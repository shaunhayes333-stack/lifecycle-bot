package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7532MathEdgeReadOnlyLearningTest {
    @Test fun mathematical_edge_sizing_readback_does_not_stamp_canonical_learning_identity() {
        val mee = File("src/main/kotlin/com/lifecyclebot/engine/MathematicalEdgeEngine.kt").readText()
        val block = mee.substringAfter(""SIZING" -> {").substringBefore("if (e.score >= 75.0")
        assertFalse(block.contains("ForwardOutcomeModel.stamp("))
        assertFalse(block.contains("UnifiedPolicyHead.stamp("))
        assertFalse(block.contains("StrategyHypothesisEngine.getSizeBias("))
        assertFalse(block.contains("StrategyHypothesisEngine.getStopBias("))
        assertTrue(block.contains("StrategyHypothesisEngine.peekSizeBias("))
        assertTrue(block.contains("StrategyHypothesisEngine.peekStopBias7532("))
        assertTrue(block.contains("UnifiedPolicyHead.predictWinProb("))
    }

    @Test fun stop_bias_readback_is_non_mutating() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        val fn = s.substringAfter("fun peekStopBias7532(").substringBefore("fun getStopBias(")
        assertTrue(fn.contains("active[ctx]"))
        assertFalse(fn.contains("pending["))
        assertFalse(fn.contains("active.getOrPut"))
    }
}

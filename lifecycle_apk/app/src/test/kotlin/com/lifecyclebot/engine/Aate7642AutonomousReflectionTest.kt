
package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7642AutonomousReflectionTest {
    @Test fun repeatedReasoningFailuresFeedBackgroundCriticReviewedHypotheses() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperReflectionLoop7642.kt").readText()
        assertTrue(s.contains("private const val MIN_REPEAT = 3L"))
        assertTrue(s.contains("ChokeReliefBus.launch(\"SUPER_REFLECTION_7642\""))
        assertTrue(s.contains("MultiAgentCriticStack.reviewAndSubmit("))
        assertTrue(s.contains("sourceTag = \"BACKGROUND_SUPER_REFLECTION_7642\""))
        assertTrue(s.contains("AsyncStrategyLab").not())
    }

    @Test fun reflectionNeverDirectlyMutatesExecutionAuthority() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperReflectionLoop7642.kt").readText()
        assertTrue(!s.contains("executeBuy"))
        assertTrue(!s.contains("TradeAuthorizer.authorize"))
        assertTrue(!s.contains("FinalDecisionGate.evaluate"))
        assertTrue(!s.contains("return 0.0"))
    }

    @Test fun terminalCalibrationInvokesReflectionAfterFailureClassification() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(s.contains("SuperReflectionLoop7642.observe("))
        assertTrue(s.contains("failureMode = failureMode7640"))
    }
}

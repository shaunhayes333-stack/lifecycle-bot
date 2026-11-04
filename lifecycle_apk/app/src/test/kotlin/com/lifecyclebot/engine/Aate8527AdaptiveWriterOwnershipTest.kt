package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Ownership integration tests complement the executable feature/direction/dedup tests. */
class Aate8527AdaptiveWriterOwnershipTest {
    private fun source(path: String): String =
        File("src/main/kotlin/com/lifecyclebot/" + path).readText()

    @Test fun OnlyCanonicalAdapterOwnsAdaptiveSettledLearning() {
        for (path in listOf(
            "engine/Executor.kt",
            "v3/scoring/EducationSubLayerAI.kt",
            "v3/scoring/MoonshotTraderAI.kt",
            "v3/scoring/ShitCoinTraderAI.kt",
        )) {
            val text = source(path)
            assertFalse(path + " must not duplicate the canonical learner",
                text.contains("AdaptiveLearningEngine.learnFromTrade("))
            assertFalse(path + " must not manufacture proxy entry features on close",
                text.contains("AdaptiveLearningEngine.captureFeatures("))
        }
        val subscribers = source("engine/CanonicalSubscribers.kt")
        assertFalse("raw sell legs must not retrain whole-position ALE",
            subscribers.contains("AdaptiveLearningEngine.onCanonicalOutcome("))
        assertFalse(subscribers.contains("\"AdaptiveLearningEngine\""))
        val bus = source("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        assertTrue(bus.contains("\"AdaptiveLearningEngine8527\""))
        val bridge = source("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertEquals(1, Regex("AdaptiveLearningEngine\\.onCanonicalTerminal8527\\(env\\)")
            .findAll(bridge).count())
        val adaptive = source("engine/AdaptiveLearningEngine.kt")
        assertFalse(adaptive.contains("fun onCanonicalOutcome("))
        val owner = adaptive.indexOf("fun onCanonicalTerminal8527(")
        assertTrue("whole-position terminal owner is wired", owner >= 0)
        assertTrue(adaptive.substring(owner).contains("learnFromTrade("))
    }

    @Test fun EducationRetainsLayerMetricsWithoutReplayingTheOutcomeAsAnotherLesson() {
        val education = source("v3/scoring/EducationSubLayerAI.kt")
        assertTrue(education.contains("markLayerOutcome(\"AdaptiveLearningEngine\""))
        assertTrue(education.contains("adaptiveEnvironment7803 != com.lifecyclebot.engine.TradeEnvironment.LIVE"))
        assertFalse(education.contains("entryMcapUsd = outcome.entryMcapUsd,\n                tokenAgeMinutes = outcome.tokenAgeMinutes,"))
    }
}

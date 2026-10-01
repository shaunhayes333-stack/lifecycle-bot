package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7659EstateOverlapGuardTest {
    @Test fun collectiveHiveIsAlreadyRepresentedInOracle() {
        val o = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(o.contains("CollectiveIntelligenceAI"))
        assertTrue(o.contains("getSourceReliability(sourceFamily)"))
    }

    @Test fun sentienceLlmVoteIsAlreadyRepresentedUpstream() {
        val n = File("src/main/kotlin/com/lifecyclebot/v3/scoring/MemeNarrativeAI.kt").readText()
        assertTrue(n.contains("SentienceHooks.entryQualityScoreBias6678(symbol)"))
    }

    @Test fun metaCognitionIsAlreadyInsideLegacyConsensusAggregate() {
        val b = File("src/main/kotlin/com/lifecyclebot/engine/BrainConsensusBridge6329.kt").readText()
        assertTrue(b.contains("MetaCognitionExecutorBridge"))
    }

    @Test fun provenanceRegistryMarksThoseAsRepresentedRatherThanDirectNewVotes() {
        val r = File("src/main/kotlin/com/lifecyclebot/engine/SuperEstateCoverageRegistry7655.kt").readText()
        assertTrue(r.contains("Collective/hive intelligence"))
        assertTrue(r.contains("Sentience LLM trade vote"))
        assertTrue(r.contains("MetaCognition executor bridge"))
        assertTrue(r.contains("REPRESENTED_UPSTREAM"))
    }
}

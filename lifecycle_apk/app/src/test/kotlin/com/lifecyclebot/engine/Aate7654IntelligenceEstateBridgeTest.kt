package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7654IntelligenceEstateBridgeTest {
    @Test fun estateReadsAreCacheOnlyAndDoNotInvokeProvidersOrScorers() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceEstate7654.kt").readText()
        val c = File("src/main/kotlin/com/lifecyclebot/engine/AICrossTalk.kt").readText()
        val l = File("src/main/kotlin/com/lifecyclebot/engine/AsyncGeminiNarrativeCache6478.kt").readText()
        assertTrue(e.contains("AICrossTalk.cachedSignal7654("))
        assertTrue(e.contains("AsyncGeminiNarrativeCache6478.peekBySymbol7654("))
        assertTrue(e.contains("ArbScannerAI.cachedOpportunity("))
        assertTrue(!e.contains("GeminiCopilot."))
        assertTrue(!e.contains("analyzeCrossTalk("))
        assertTrue(c.contains("fun cachedSignal7654("))
        assertTrue(l.contains("fun peekBySymbol7654("))
    }

    @Test fun broadLayerBrainEstateIsVisibleButNotAFreeDirectionalVote() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceEstate7654.kt").readText()
        val l = File("src/main/kotlin/com/lifecyclebot/engine/LayerBrain.kt").readText()
        assertTrue(l.contains("data class EstateSnapshot7654"))
        assertTrue(l.contains("fun estateSnapshot7654()"))
        assertTrue(e.contains("fun breadthConfidence()"))
        assertTrue(!e.contains("Family.LAYER_BRAIN"))
    }

    @Test fun missingFamiliesJoinCorrelationAwareTopology() {
        val t = File("src/main/kotlin/com/lifecyclebot/engine/SuperEvidenceTopology7651.kt").readText()
        val e = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        listOf("AI_CROSSTALK", "LLM_COUNCIL", "SCANNER_ENSEMBLE").forEach { assertTrue(t.contains(it)) }
        assertTrue(e.contains("estate7654.crossTalkUtility(policy)"))
        assertTrue(e.contains("estate7654.llmUtility(policy)"))
        assertTrue(e.contains("estate7654.scannerUtility(policy)"))
        assertTrue(e.contains("estate7654.tag()"))
    }
}

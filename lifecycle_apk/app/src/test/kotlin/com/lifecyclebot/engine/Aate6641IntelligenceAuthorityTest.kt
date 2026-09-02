package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate6641IntelligenceAuthorityTest {

    @Test fun llm_personality_is_diagnostic_not_execution_evidence() {
        val sentience = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SentienceOrchestrator.kt").readText()
        val consensus = java.io.File("src/main/kotlin/com/lifecyclebot/engine/BrainConsensusBridge6329.kt").readText()
        assertFalse(sentience.contains("CrossTalkFusionEngine.publish("))
        assertTrue(sentience.contains("SENTIENCE_REFLECTION_DIAGNOSTIC_ONLY_6641"))
        assertTrue(sentience.contains("SENTIENCE_MUTATION_DIAGNOSTIC_ONLY_6642"))
        val proposalFn = sentience.substringAfter("private fun recordProposedMutations")
            .substringBefore("private val MOOD_VOCAB")
        assertFalse(proposalFn.contains("PersonalityMemoryStore.nudgeTrait"))
        assertFalse(proposalFn.contains("SymbolicContext.save"))
        assertFalse(proposalFn.contains("SymbolicContext.overallRisk"))
        assertTrue(consensus.contains("val sentienceMult = 1.0"))
        assertFalse(consensus.contains("recentReflections(5)"))
    }

    @Test fun crosstalk_rejects_stale_snapshots_and_bounds_each_source() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/v4/meta/CrossTalkFusionEngine.kt").readText()
        assertTrue(src.contains("System.currentTimeMillis() - snapshot.timestamp >= SIGNAL_TTL_MS"))
        assertTrue(src.contains("sameSource.size >= MAX_SIGNALS_PER_SOURCE"))
        assertTrue(src.contains("val snapshot = getSnapshot() ?: return GatedScore("))
    }

    @Test fun metacognition_freezes_entry_roster_and_maps_outer_brains() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/MetaCognitionAI.kt").readText()
        assertTrue(src.contains("alreadyOpen && pendingPredictions.containsKey(mint)"))
        assertTrue(src.contains("\"executioncostpredictorai\""))
        assertTrue(src.contains("\"tokendnaclusteringai\""))
        assertTrue(src.indexOf("recentAccuracyEwma <= 32.0") < src.indexOf("recentAccuracyEwma <= 40.0"))
    }

    @Test fun education_dedupe_happens_after_validation_and_trainability() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/v3/scoring/EducationSubLayerAI.kt").readText()
        val fn = src.substringAfter("fun recordTradeOutcomeAcrossAllLayers")
        assertTrue(fn.indexOf("if (!shouldProcessOutcomeOnce(outcome)) return") >
            fn.indexOf("ExecutionStatusRegistry.shouldTrainStrategy"))
    }

    @Test fun random_personality_copy_declares_non_telemetry_provenance() {
        val src = java.io.File("src/main/kotlin/com/lifecyclebot/engine/SentientPersonality.kt").readText()
        assertTrue(src.contains("Creative musing (not live telemetry):"))
    }

    @Test fun paper_pattern_learning_waits_for_durable_economic_settlement() {
        val executor = java.io.File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val classifier = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PatternClassifier.kt").readText()
        assertTrue(executor.contains("CanonicalEconomicEvent6635.afterCommitted(eventId, patternEntry6643)"))
        assertTrue(executor.contains("CanonicalEconomicEvent6635.afterCommitted(eventId, patternExit6643)"))
        assertTrue(classifier.contains("pending.putIfAbsent(positionKey, features.copyOf())"))
        assertFalse(classifier.contains("val paperGraduated = totalSamples >= 200"))
    }

    @Test fun paper_ui_renders_only_unified_account_snapshot() {
        val uiRoot = java.io.File("src/main/kotlin/com/lifecyclebot/ui")
        val files = listOf("MainActivity.kt", "CryptoAltActivity.kt", "MultiAssetActivity.kt")
        files.forEach { name ->
            val src = java.io.File(uiRoot, name).readText()
            assertTrue("$name must consume unified account authority", src.contains("UnifiedAccountSnapshot6635"))
        }
        val behavior = java.io.File(uiRoot, "BehaviorActivity.kt").readText()
        val reset = behavior.substringAfter("USER_BEHAVIOR_UI_RESET_6618").substringBefore("setNegativeButton")
        assertFalse(reset.contains("paperWalletSol ="))
        assertFalse(reset.contains("forceSetBalance"))
        assertFalse(reset.contains("paper_wallet_sol"))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.7813 — one expert-trader vocabulary, one causal learning loop. */
class Aate7813ExpertTraderCoreTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test fun expertCoreIsSharedBoundedAndNotASecondExecutionAuthority() {
        val s = src("engine/ExpertTraderKnowledge7813.kt")
        listOf(
            "IGNITION", "BREAKOUT_EXPANSION", "RECLAIM", "CONTINUATION",
            "RELATIVE_STRENGTH", "LIQUIDITY_EXPANSION", "PARTICIPATION_EXPANSION",
            "BUY_PRESSURE", "MULTI_PROVIDER_AGREEMENT", "EXITABILITY", "ROUTE_QUALITY",
            "HOLDER_QUALITY", "REGIME_FIT", "CHASE_RISK", "DISTRIBUTION", "EXHAUSTION",
        ).forEach { assertTrue("missing feature $it", s.contains(it)) }
        assertTrue(s.contains("COUNTERFACTUAL_WEIGHT = 0.20"))
        assertTrue(s.contains("PAPER_TO_LIVE_WEIGHT = 0.20"))
        assertTrue(s.contains("private fun polarity(value: Double)"))
        assertTrue(s.contains("captureDecision7813"))
        assertTrue(s.contains("bindPosition7813"))
        assertTrue(s.contains("recordForwardOutcome7813"))
        assertTrue(s.contains("authority=ordering+bounded_prior no_safety_or_execution_authority=true"))
        assertFalse(s.contains("ExecutableOpenGate.canOpenExecutablePosition"))
        assertFalse(s.contains("FinalExecutionPermit"))
    }

    @Test fun hunterOracleAndSuperPlannerReadSameDoctrineInsteadOfCopies() {
        val hunter = src("engine/market/LaneHunter7297.kt")
        val inputs = src("engine/truth/LearnedAdmissionInputs6909.kt")
        val oracle = src("engine/truth/PredictiveEntryOracle6915.kt")
        val topology = src("engine/SuperEvidenceTopology7651.kt")
        val existing = src("engine/ExistingIntelligenceContext7650.kt")
        assertTrue(hunter.contains("ExpertTraderKnowledge7813.marketRankMultiplier7813"))
        assertTrue(inputs.contains("ExpertTraderKnowledge7813.captureDecision7813"))
        assertTrue(inputs.contains("expertFeaturePrior7813 = expertPrior7813"))
        assertTrue(oracle.contains("expertFeaturePrior7813"))
        assertTrue(oracle.contains("EXPERT_TRADER_PRIOR_READ_7813"))
        assertTrue(topology.contains("EXPERT_DOCTRINE"))
        assertTrue(existing.contains("ExpertTraderKnowledge7813.peekPrior7813"))
        assertTrue(existing.contains("SuperEvidenceTopology7651.Family.EXPERT_DOCTRINE"))
    }

    @Test fun exactDecisionFeaturesArePositionBoundAndLearnFromBothTakenAndRejected() {
        val open = src("engine/truth/CanonicalPositionAuthority6441.kt")
        val labels = src("engine/truth/ForwardReturnLabeler7731.kt")
        assertTrue(open.contains("ExpertTraderKnowledge7813.bindPosition7813(positionId, mint, lane, canonicalMode6490)"))
        assertTrue(labels.contains("ExpertTraderKnowledge7813.recordForwardOutcome7813(o.mint, o.lane, net, o.admitted, o.atMs)"))
    }

    @Test fun statePersistsResetsAndIsVisibleInRuntimeDoctor() {
        val lp = src("engine/LearningPersistence.kt")
        val sent = src("engine/AiStatePersistenceSentinel.kt")
        val health = src("engine/PipelineHealthCollector.kt")
        assertTrue(lp.contains("putBlob(\"EXPERT_TRADER_KNOWLEDGE_7813\""))
        assertTrue(lp.contains("getBlob(\"EXPERT_TRADER_KNOWLEDGE_7813\""))
                assertTrue(lp.contains("ExpertTraderKnowledge7813.reset()"))
        assertTrue(sent.contains("ExpectedState(\"EXPERT_TRADER_KNOWLEDGE_7813\", \"ExpertTraderKnowledge7813\")"))
        assertTrue(health.contains("Expert trader core (§7813)"))
    }

    @Test fun cryptoAltUsesTheSamePriorOnlyWhenCanonicalTokenStateExists() {
        val s = src("engine/truth/CanonicalAssetEntryContract6551.kt")
        assertTrue(s.contains("BotService.status.tokens[candidate.assetId]"))
        assertTrue(s.contains("ExpertTraderKnowledge7813.captureDecision7813"))
        assertTrue(s.contains("expertFeaturePrior7813 = expertPrior7813"))
        assertTrue(s.contains("ExpertTraderKnowledge7813.Prior.NEUTRAL"))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7431ExactStrategyEvAdmissionTest {

    @Test fun exactStrategyLedgerExposesSampleGatedEvidence() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExactStrategyPerformance7429.kt").readText()
        assertTrue(src.contains("data class Evidence7431"))
        assertTrue(src.contains("fun evidenceFor7431("))
        assertTrue(src.contains("live.n >= 3L"))
        assertTrue(src.contains("paper.n >= 5L"))
        assertTrue(src.contains("PAPER_SEED"))
    }

    @Test fun predictiveOracleUsesExactStrategyAsShrinkageEvidenceNotStandaloneGate() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(src.contains("ExactStrategyPerformance7429.evidenceFor7431("))
        assertTrue(src.contains("exactStrategyLevel7431"))
        assertTrue(src.contains("listOfNotNull(exactStrategyLevel7431, cell, lane1, globalLevel)"))
        assertTrue(src.contains("PREDICTIVE_EXACT_STRATEGY_EV_READ_7431"))
        assertFalse(src.contains("EXACT_STRATEGY_EV_HARD_BLOCK_7431"))
    }

    @Test fun learnedAdmissionCarriesExactTaxonomyToOracle() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LearnedAdmissionInputs6909.kt").readText()
        listOf("tradeTypeHint", "setupHint", "styleHint", "tacticHint").forEach {
            assertTrue("missing $it", src.contains(it))
        }
        assertTrue(src.contains("tradeType = tradeTypeHint"))
        assertTrue(src.contains("setup = setupHint"))
        assertTrue(src.contains("style = styleHint"))
        assertTrue(src.contains("tactic = tacticHint"))
    }

    @Test fun executableOpenUsesCanonicalRouterIdentityWithoutProviderIo() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(src.contains("val exactClass7431"))
        assertTrue(src.contains("ModeRouter.classify(it)"))
        assertTrue(src.contains("val exactStyle7431"))
        assertTrue(src.contains("AgenticStyleRouter.decide(it, cls, lane)"))
        assertTrue(src.contains("tradeTypeHint = exactClass7431?.tradeType?.name.orEmpty()"))
        assertTrue(src.contains("setupHint = exactStyle7431?.toolkit?.setup?.name.orEmpty()"))
        assertTrue(src.contains("styleHint = exactStyle7431?.style?.name.orEmpty()"))
        assertTrue(src.contains("tacticHint = exactStyle7431?.tactic?.name.orEmpty()"))
    }

    @Test fun sourceTimingRemainsSingleVote() {
        val oracle = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        val score = File("src/main/kotlin/com/lifecyclebot/v3/scoring/ScoreCard.kt").readText()
        assertTrue(score.contains("SourceTimingRegistry.getSourceTimingPenalty"))
        assertFalse(oracle.contains("SourceTimingRegistry.isLateSignal(mint)"))
    }
}

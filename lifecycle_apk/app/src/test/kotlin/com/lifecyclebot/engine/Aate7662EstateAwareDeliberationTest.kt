package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7662EstateAwareDeliberationTest {
    private fun base(depth: Int = 2, risk: Double = 0.3) =
        SuperDeliberationController7646.Plan(
            depth = depth,
            rolloutBudget = if (depth == 1) 7 else 9,
            novelty = 0.2,
            uncertainty = 0.2,
            valueAtRisk = risk,
            opportunity = 0.4,
            reason = "base",
        )

    private fun estate() = SuperIntelligenceEstate7654.Snapshot(
        crossTalk = null,
        llm = null,
        arbScore = null,
        arbConfidence = null,
        arbExpectedMovePct = null,
        arbType = null,
        sourceLearning = null,
        layerEstate = LayerBrain.EstateSnapshot7654(10, 0, 0, 5, 5, 500L, 8, 0),
        smartSystemsTotal = 20,
        smartSystemsActive = 10,
        smartSystemsInterfaceUsed = 5,
        coverageFamilies = 20,
        coverageDirectional = 10,
    )

    @Test fun conflictDeepensReasoning() {
        val t = SuperEvidenceTopology7651.Result(
            rawUtility = 2.0,
            familyUtility = mapOf(
                SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST to 2.0,
                SuperEvidenceTopology7651.Family.AI_CROSSTALK to -1.5,
            ),
            decorrelatedUtility = 0.3,
            activeSignals = 2,
            independentFamilies = 2,
            agreement = 0.15,
            redundancyRatio = 0.0,
            contradictionPenalty = 0.62,
        )
        val r = SuperEstateDeliberation7662.refine(base(), t, estate())
        assertTrue(r.depth > 2)
        assertTrue(r.rolloutBudget > 9)
    }

    @Test fun matureBroadConsensusCanUseFastPath() {
        val t = SuperEvidenceTopology7651.Result(
            rawUtility = 4.0,
            familyUtility = mapOf(
                SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST to 1.0,
                SuperEvidenceTopology7651.Family.AI_CROSSTALK to 1.0,
                SuperEvidenceTopology7651.Family.SCANNER_ENSEMBLE to 1.0,
                SuperEvidenceTopology7651.Family.STRATEGY_LEARNING to 1.0,
            ),
            decorrelatedUtility = 4.0,
            activeSignals = 4,
            independentFamilies = 4,
            agreement = 1.0,
            redundancyRatio = 0.0,
            contradictionPenalty = 1.0,
        )
        val r = SuperEstateDeliberation7662.refine(base(depth = 3), t, estate())
        assertEquals(2, r.depth)
        assertEquals(9, r.rolloutBudget)
    }

    @Test fun oracleReadsEstateBeforeFinalDeliberation() {
        val o = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        assertTrue(o.contains("SuperEstateDeliberation7662.refine("))
        assertTrue(o.contains("baseDeliberation7646"))
        assertTrue(o.indexOf("ExistingIntelligenceContext7650.read(") <
            o.indexOf("SuperEstateDeliberation7662.refine("))
    }
}

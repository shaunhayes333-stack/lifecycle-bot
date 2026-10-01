package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7663CounterfactualAblationTest {
    @Test fun onlyMaterialFamiliesReceiveMarginalCredit() {
        val r = SuperEvidenceTopology7651.Result(
            rawUtility = 3.0,
            familyUtility = mapOf(
                SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST to 2.0,
                SuperEvidenceTopology7651.Family.AI_CROSSTALK to 1.0,
            ),
            decorrelatedUtility = 3.0,
            activeSignals = 2,
            independentFamilies = 2,
            agreement = 1.0,
            redundancyRatio = 0.0,
            contradictionPenalty = 1.0,
        )
        val m = SuperEvidenceAblation7663.marginals(r)
        assertTrue((m[SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST] ?: 0.0) >
            (m[SuperEvidenceTopology7651.Family.AI_CROSSTALK] ?: 0.0))
    }

    @Test fun marginalTrustLearnsFromActualDecisionContribution() {
        SuperFamilyMarginalTrust7663.resetForTest()
        repeat(50) {
            SuperFamilyMarginalTrust7663.recordOutcome(
                "MOONSHOT",
                mapOf(
                    SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST to 1.5,
                    SuperEvidenceTopology7651.Family.AI_CROSSTALK to -1.0,
                ),
                20.0,
            )
        }
        assertTrue(
            SuperFamilyMarginalTrust7663.trust(
                "MOONSHOT", SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST
            ) > 1.0
        )
        assertTrue(
            SuperFamilyMarginalTrust7663.trust(
                "MOONSHOT", SuperEvidenceTopology7651.Family.AI_CROSSTALK
            ) < 1.0
        )
    }
}

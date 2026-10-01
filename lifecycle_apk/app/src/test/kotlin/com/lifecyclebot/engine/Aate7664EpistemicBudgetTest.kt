package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7664EpistemicBudgetTest {
    @Test fun addingMoreFamiliesCannotExceedFixedBudget() {
        val fam = SuperEvidenceTopology7651.Family.entries.take(6)
            .associateWith { 2.0 }
        val t = SuperEvidenceTopology7651.Result(
            rawUtility = 12.0,
            familyUtility = fam,
            decorrelatedUtility = 6.0,
            activeSignals = 6,
            independentFamilies = 6,
            agreement = 1.0,
            redundancyRatio = 0.0,
            contradictionPenalty = 1.0,
        )
        val r = SuperEpistemicBudget7664.apply(t)
        assertTrue(r.l1After <= 6.0001)
        assertTrue(kotlin.math.abs(r.utility) <= 6.0001)
    }

    @Test fun oneFamilyCannotDominateBroadEstate() {
        val t = SuperEvidenceTopology7651.Result(
            rawUtility = 6.0,
            familyUtility = mapOf(
                SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST to 5.0,
                SuperEvidenceTopology7651.Family.AI_CROSSTALK to 0.5,
                SuperEvidenceTopology7651.Family.LLM_COUNCIL to 0.5,
            ),
            decorrelatedUtility = 5.0,
            activeSignals = 3,
            independentFamilies = 3,
            agreement = 1.0,
            redundancyRatio = 0.0,
            contradictionPenalty = 1.0,
        )
        val r = SuperEpistemicBudget7664.apply(t)
        assertTrue(r.maxFamilyShare <= 0.5501)
    }

    @Test fun budgetingNeverAmplifiesExistingDecorrelatedOpinion() {
        val t = SuperEvidenceTopology7651.Result(
            rawUtility = 1.5,
            familyUtility = mapOf(
                SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST to 1.0,
                SuperEvidenceTopology7651.Family.AI_CROSSTALK to 0.5,
            ),
            decorrelatedUtility = 1.2,
            activeSignals = 2,
            independentFamilies = 2,
            agreement = 1.0,
            redundancyRatio = 0.0,
            contradictionPenalty = 1.0,
        )
        val r = SuperEpistemicBudget7664.apply(t)
        assertTrue(kotlin.math.abs(r.utility) <= 1.2001)
    }
}

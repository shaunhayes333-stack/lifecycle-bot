package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7651EvidenceTopologyTest {
    @Test fun duplicateEvidenceCollapsesInsideSharedAncestryFamily() {
        val r = SuperEvidenceTopology7651.fuse(listOf(
            SuperEvidenceTopology7651.Observation("hyp", SuperEvidenceTopology7651.Family.STRATEGY_LEARNING, 2.0),
            SuperEvidenceTopology7651.Observation("lab", SuperEvidenceTopology7651.Family.STRATEGY_LEARNING, 2.0),
        ))
        assertEquals(1, r.independentFamilies)
        assertEquals(2, r.activeSignals)
        assertEquals(2.0, r.decorrelatedUtility, 0.0001)
        assertTrue(r.decorrelatedUtility < r.rawUtility)
        assertTrue(r.redundancyRatio > 0.0)
    }

    @Test fun independentFamiliesCanCorroborateWithoutDuplicateAmplification() {
        val r = SuperEvidenceTopology7651.fuse(listOf(
            SuperEvidenceTopology7651.Observation("specialist", SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST, 1.5),
            SuperEvidenceTopology7651.Observation("mcts", SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY, 1.0),
        ))
        assertEquals(2, r.independentFamilies)
        assertEquals(1.0, r.agreement, 0.0001)
        assertEquals(2.5, r.decorrelatedUtility, 0.0001)
    }

    @Test fun contradictoryFamiliesReduceConviction() {
        val r = SuperEvidenceTopology7651.fuse(listOf(
            SuperEvidenceTopology7651.Observation("specialist", SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST, 2.0),
            SuperEvidenceTopology7651.Observation("mcts", SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY, -1.0),
        ))
        assertTrue(r.agreement < 1.0)
        assertTrue(r.contradictionPenalty < 1.0)
        assertTrue(r.decorrelatedUtility in 0.0..1.0)
    }

    @Test fun existingIntelligenceRoutesThroughTopology() {
        val a = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        assertTrue(a.contains("fun evidenceTopology7651("))
        assertTrue(a.contains("SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK"))
        assertTrue(a.contains("SuperEvidenceTopology7651.Family.STRATEGY_LEARNING"))
        assertTrue(a.contains("evidenceTopology7651(policy).decorrelatedUtility"))
    }
}

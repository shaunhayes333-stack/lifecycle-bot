package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7653EvidenceInteractionTest {
    @Test fun sparsePairsRemainNeutral() {
        SuperEvidenceInteraction7653.resetForTest()
        val fam = mapOf(
            SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST to 1.0,
            SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY to 1.0,
        )
        repeat(11) { SuperEvidenceInteraction7653.recordOutcome("MOONSHOT", fam, 20.0) }
        assertEquals(0.0, SuperEvidenceInteraction7653.adjustment("MOONSHOT", fam), 0.0001)
    }

    @Test fun complementaryAgreementEarnsPositivePairCredit() {
        SuperEvidenceInteraction7653.resetForTest()
        val fam = mapOf(
            SuperEvidenceTopology7651.Family.NATIVE_SPECIALIST to 1.5,
            SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY to 1.2,
        )
        repeat(60) { SuperEvidenceInteraction7653.recordOutcome("MOONSHOT", fam, 25.0) }
        assertTrue(SuperEvidenceInteraction7653.adjustment("MOONSHOT", fam) > 0.0)
        assertEquals(0.0, SuperEvidenceInteraction7653.adjustment("CORE", fam), 0.0001)
    }

    @Test fun repeatedlyWrongPairGetsNegativeInteractionCredit() {
        SuperEvidenceInteraction7653.resetForTest()
        val fam = mapOf(
            SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK to 1.5,
            SuperEvidenceTopology7651.Family.STRATEGY_LEARNING to 1.3,
        )
        repeat(60) { SuperEvidenceInteraction7653.recordOutcome("CORE", fam, -20.0) }
        assertTrue(SuperEvidenceInteraction7653.adjustment("CORE", fam) < 0.0)
    }

    @Test fun plannerAndCalibrationUseTheInteractionGraph() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        val c = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(e.contains("SuperEvidenceInteraction7653.adjustment(lane, topology.familyUtility)"))
        assertTrue(c.contains("SuperEvidenceInteraction7653.recordOutcome("))
        assertTrue(c.contains("evidenceInteractions7653"))
    }
}

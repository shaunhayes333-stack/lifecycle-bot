package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7652EvidenceReliabilityTest {
    @Test fun trustIsNeutralWhileEvidenceIsSparse() {
        SuperEvidenceReliability7652.resetForTest()
        repeat(7) {
            SuperEvidenceReliability7652.recordOutcome(
                "MOONSHOT",
                mapOf(SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY to 1.0),
                12.0,
            )
        }
        assertEquals(
            1.0,
            SuperEvidenceReliability7652.trust(
                "MOONSHOT", SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY
            ),
            0.0001,
        )
    }

    @Test fun exactWinningOutcomesIncreaseOnlyTheirLaneFamilyTrust() {
        SuperEvidenceReliability7652.resetForTest()
        repeat(40) {
            SuperEvidenceReliability7652.recordOutcome(
                "MOONSHOT",
                mapOf(SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY to 1.5),
                20.0,
            )
        }
        val moon = SuperEvidenceReliability7652.trust(
            "MOONSHOT", SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY
        )
        val core = SuperEvidenceReliability7652.trust(
            "CORE", SuperEvidenceTopology7651.Family.COUNTERFACTUAL_REPLAY
        )
        assertTrue(moon > 1.0)
        assertEquals(1.0, core, 0.0001)
    }

    @Test fun losingSupportReducesTrustButNeverCreatesAVeto() {
        SuperEvidenceReliability7652.resetForTest()
        repeat(40) {
            SuperEvidenceReliability7652.recordOutcome(
                "CORE",
                mapOf(SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK to 1.5),
                -20.0,
            )
        }
        val trust = SuperEvidenceReliability7652.trust(
            "CORE", SuperEvidenceTopology7651.Family.AGGREGATE_CROSSCHECK
        )
        assertTrue(trust in 0.65..1.0)
    }

    @Test fun canonicalCalibrationCarriesAndPersistsFamilyEvidence() {
        val c = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        val o = File("src/main/kotlin/com/lifecyclebot/engine/truth/PredictiveEntryOracle6915.kt").readText()
        val e = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        assertTrue(c.contains("evidenceFamilyUtility7652"))
        assertTrue(c.contains("SuperEvidenceReliability7652.recordOutcome("))
        assertTrue(c.contains("SuperEvidenceReliability7652.exportJson()"))
        assertTrue(c.contains("SuperEvidenceReliability7652.importJson("))
        assertTrue(o.contains("existing7650.evidenceTopology7651(tree7638.bestPolicy, world7634.latentState)"))
        assertTrue(e.contains("SuperEvidenceReliability7652.trust(lane, family)"))
    }
}

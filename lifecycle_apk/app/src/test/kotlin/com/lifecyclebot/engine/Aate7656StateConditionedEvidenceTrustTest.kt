package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7656StateConditionedEvidenceTrustTest {
    @Test fun sparseContextStaysNeutral() {
        SuperEvidenceContextTrust7656.resetForTest()
        repeat(5) {
            SuperEvidenceContextTrust7656.recordOutcome(
                "MOONSHOT",
                SuperWorldModel7634.LatentState.ACCELERATING,
                mapOf(SuperEvidenceTopology7651.Family.AI_CROSSTALK to 1.0),
                20.0,
            )
        }
        assertEquals(
            1.0,
            SuperEvidenceContextTrust7656.trust(
                "MOONSHOT",
                SuperWorldModel7634.LatentState.ACCELERATING,
                SuperEvidenceTopology7651.Family.AI_CROSSTALK,
            ),
            0.0001,
        )
    }

    @Test fun sameFamilyCanEarnOppositeTrustByState() {
        SuperEvidenceContextTrust7656.resetForTest()
        repeat(50) {
            SuperEvidenceContextTrust7656.recordOutcome(
                "MOONSHOT",
                SuperWorldModel7634.LatentState.ACCELERATING,
                mapOf(SuperEvidenceTopology7651.Family.LLM_COUNCIL to 1.2),
                25.0,
            )
            SuperEvidenceContextTrust7656.recordOutcome(
                "MOONSHOT",
                SuperWorldModel7634.LatentState.DISTRIBUTING,
                mapOf(SuperEvidenceTopology7651.Family.LLM_COUNCIL to 1.2),
                -20.0,
            )
        }
        val accel = SuperEvidenceContextTrust7656.trust(
            "MOONSHOT", SuperWorldModel7634.LatentState.ACCELERATING,
            SuperEvidenceTopology7651.Family.LLM_COUNCIL,
        )
        val distributing = SuperEvidenceContextTrust7656.trust(
            "MOONSHOT", SuperWorldModel7634.LatentState.DISTRIBUTING,
            SuperEvidenceTopology7651.Family.LLM_COUNCIL,
        )
        assertTrue(accel > 1.0)
        assertTrue(distributing < 1.0)
    }

    @Test fun plannerUsesCurrentLatentStateAndCalibrationPersistsContextTrust() {
        val e = File("src/main/kotlin/com/lifecyclebot/engine/ExistingIntelligenceContext7650.kt").readText()
        val t = File("src/main/kotlin/com/lifecyclebot/engine/SuperPolicyTree7638.kt").readText()
        val c = File("src/main/kotlin/com/lifecyclebot/engine/SuperIntelligenceCalibration7636.kt").readText()
        assertTrue(e.contains("SuperEvidenceContextTrust7656.trust(lane, state, family)"))
        assertTrue(t.contains("existing?.policyPrior(b.policy, world.latentState)"))
        assertTrue(c.contains("SuperEvidenceContextTrust7656.recordOutcome("))
        assertTrue(c.contains("evidenceContextTrust7656"))
    }
}

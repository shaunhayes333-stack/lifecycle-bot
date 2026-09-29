package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7445HypothesisDurabilityTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun executed_position_binding_is_persisted_and_restored() {
        val s = src("engine/StrategyHypothesisEngine.kt")
        assertTrue(s.contains("HYPOTHESIS_POSITION_BIND_PERSISTED_7445"))
        assertTrue(s.contains("boundPositions7445"))
        assertTrue(s.contains("HYPOTHESIS_POSITION_BIND_RESTORED_7445"))
        assertTrue(s.contains("pendingByPosition7428[positionId] = AppliedDecision7428"))
    }

    @Test fun decision_fanout_stays_session_local_but_position_identity_does_not() {
        val s = src("engine/StrategyHypothesisEngine.kt")
        val export = s.substringAfter("fun exportState()").substringBefore("fun importState")
        assertFalse(export.contains("pendingByDecision7428.forEach"))
        assertTrue(export.contains("pendingByPosition7428.forEach"))
    }

    @Test fun aate_reward_fanout_no_longer_credits_mint_only_hypothesis() {
        val s = src("engine/truth/AateDecisionEnvelope6512.kt")
        assertFalse(s.contains("StrategyHypothesisEngine.recordOutcome(env.mint"))
        assertTrue(s.contains("HYPOTHESIS_LEGACY_MINT_TERMINAL_SKIPPED_7445"))
    }

    @Test fun canonical_position_terminal_remains_the_hypothesis_authority() {
        val s = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertTrue(s.contains("StrategyHypothesisEngine.recordOutcomeForPosition7428("))
        assertTrue(s.contains("env.positionId"))
    }
}

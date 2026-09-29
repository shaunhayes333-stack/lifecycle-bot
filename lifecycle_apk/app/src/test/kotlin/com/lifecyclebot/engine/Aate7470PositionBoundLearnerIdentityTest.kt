package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7470PositionBoundLearnerIdentityTest {
    @Test fun hypothesis_bind_is_wired_at_canonical_open_attribution() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        val fn = s.substringAfter("fun attachPosition(").substringBefore("fun onFinalized(")
        assertTrue(fn.contains("UnifiedPolicyHead.bindPosition6681(positionId, mint, lane)"))
        assertTrue(fn.contains("StrategyHypothesisEngine.bindExecutedPosition7428("))
        assertTrue(fn.contains("candidateVersion = e.context.candidateVersion"))
        assertTrue(fn.contains("lane = e.context.primaryStrategy"))
    }

    @Test fun canonical_position_open_remains_the_single_attach_boundary() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPositionAuthority6441.kt").readText()
        assertTrue(s.contains("AateDecisionFabric6512.attachPosition(positionId, canonicalMode6490, mint, lane)"))
    }

    @Test fun terminal_hypothesis_credit_remains_position_bound() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/FinalizedBusConsumerBridge6465.kt").readText()
        assertTrue(s.contains("StrategyHypothesisEngine.recordOutcomeForPosition7428("))
        assertFalse(s.contains("StrategyHypothesisEngine.recordOutcome(env.mint"))
    }
}

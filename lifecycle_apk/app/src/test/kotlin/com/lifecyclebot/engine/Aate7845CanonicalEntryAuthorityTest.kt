package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7845CanonicalEntryAuthorityTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test
    fun specialist_owner_is_not_rewritten_by_generic_wait_guard() {
        val fdg = src("engine/FinalDecisionGate.kt")
        assertTrue(fdg.contains("specialistLane.isNullOrBlank() && candidate.blockReason.startsWith"))
        assertFalse(fdg.contains("if (candidate.blockReason.startsWith(\"Signal is \") && candidate.blockReason.endsWith(\", not BUY\"))"))
    }

    @Test
    fun generic_strategy_layers_are_advisory_after_specialist_ownership() {
        val fdg = src("engine/FinalDecisionGate.kt")
        assertTrue(fdg.contains("private fun specialistAdvisoryBlock7845("))
        assertTrue(fdg.contains("FDG_SPECIALIST_OWNER_VERDICT_PRESERVED_7845"))
        assertTrue(fdg.contains("FDG_ADVISORY_FIELD_MANUAL_7845"))
        assertTrue(fdg.contains("FDG_ADVISORY_TRADE_PLAN_7845"))
        assertTrue(fdg.contains("FDG_ADVISORY_COUNCIL_7845"))
        assertTrue(fdg.contains("cellProofBlock7731(ts, candidate"))
        assertTrue(fdg.contains("freshLaunchBlock7737(ts, candidate"))
    }

    @Test
    fun exact_intent_contract_remains_authorizer_boundary() {
        val auth = src("engine/TradeAuthorizer.kt")
        assertTrue(auth.contains("SpecialistPreauthSeal7834.ensure("))
        assertTrue(auth.contains("val finalityAttemptId = sealedIntent7812.attemptId"))
        assertTrue(auth.contains("preResolvedSizeSol6490 = sealedIntent7812.resolvedSize"))
    }
}

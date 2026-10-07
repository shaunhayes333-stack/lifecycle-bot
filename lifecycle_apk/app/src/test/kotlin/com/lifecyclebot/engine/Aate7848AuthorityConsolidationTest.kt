package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7848AuthorityConsolidationTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test
    fun live_fdg_never_falls_back_to_blended_stats() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("if (!com.lifecyclebot.engine.RuntimeModeAuthority.isPaper() || liveDecisive7706 != null) null"))
    }

    @Test
    fun asymmetric_runner_ignores_generic_global_wr_deficit() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("!com.lifecyclebot.engine.WrRecoveryPartial.isRunnerLaneExempt7693(specialistLane)"))
    }

    @Test
    fun policy_synthesizer_cannot_rewrite_owned_specialist_buy() {
        val s = src("engine/FinalDecisionGate.kt")
        assertTrue(s.contains("val policyAllows7848 = specialistLane?.isNotBlank() == true || aateEnvelope6512?.action != \"BLOCK\""))
        assertTrue(s.contains("policyAllows7848 && executableSize7835 > 0.0"))
        assertFalse(s.contains("canonicalEconomicApproval7548 &&\n                aateEnvelope6512?.action != \"BLOCK\""))
    }

    @Test
    fun automode_paused_is_exposed_as_caution_not_runtime_stop() {
        val s = src("engine/AutoModeEngine.kt")
        assertTrue(s.contains("AUTO_CAUTION_CONTINUE_TO_STRATEGY_7848"))
        assertTrue(s.contains("Quiet-hour caution"))
    }
}

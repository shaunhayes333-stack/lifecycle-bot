package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7849ExecutorSealConsumptionTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun live_executor_does_not_redecide_entry_after_seal() {
        val live = src("engine/Executor.kt").substringAfter("private fun liveBuy(")
        assertTrue(live.contains("LIVE_ENTRY_AUTH_CONSUMED_SEALED_7849"))
        val pre = live.substringBefore("// V5.0.7310 — no new live entries")
        assertFalse(pre.contains("ExecutableEntryAuthority6450.gate("))
        assertFalse(pre.contains("commonSenseRiskRewardPreTicketRefused7807(ts"))
    }

    @Test fun live_executor_never_mutates_sealed_notional() {
        val risk = src("engine/Executor.kt").substringAfter("private fun liveRiskPolicyFinalSize7807(").substringBefore("private fun emitLiveBuyFail")
        assertTrue(risk.contains("LIVE_RISK_SIZE_CHANGED_AFTER_SEAL_7849"))
        assertTrue(risk.contains("kotlin.math.abs(d.sizeSol - sol) > 1e-9"))
        assertTrue(risk.contains("fresh_decision_required"))
    }

    @Test fun common_sense_non_hard_is_advisory_after_seal() {
        val s = src("engine/Executor.kt")
        assertTrue(s.contains("COMMON_SENSE_POST_SEAL_ADVISORY_7849"))
        assertTrue(s.contains("if (!commonSenseHard6026)"))
    }
}

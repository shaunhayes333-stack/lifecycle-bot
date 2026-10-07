package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7850SingleExecutionAuthorityTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun paper_executor_consumes_sealed_entry_authority() {
        val s = src("engine/Executor.kt")
        val paper = s.substringAfter("fun paperBuy(").substringBefore("private fun liveBuy(")
        assertTrue(paper.contains("PAPER_ENTRY_AUTH_CONSUMED_SEALED_7850"))
        val afterSeal = paper.substringAfter("val sealedIntent7835")
        assertFalse(afterSeal.contains("ExecutableEntryAuthority6450.gate("))
    }

    @Test fun live_executor_does_not_rerun_risk_policy_after_seal() {
        val s = src("engine/Executor.kt")
        val live = s.substringAfter("private fun liveBuy(")
        val pre = live.substringBefore("// V5.0.7310 — no new live entries")
        assertTrue(pre.contains("LIVE_RISK_POLICY_CONSUMED_SEALED_7850"))
        assertFalse(pre.contains("liveRiskPolicyPreTicketRefused7807(ts"))
    }

    @Test fun final_live_size_is_exact_sealed_notional() {
        val s = src("engine/Executor.kt")
        val fn = s.substringAfter("private fun liveRiskPolicyFinalSize7807(").substringBefore("private fun emitLiveBuyFail")
        assertTrue(fn.contains("LIVE_FINAL_SIZE_CONSUMED_SEALED_7850"))
        assertTrue(fn.contains("return sol"))
        assertFalse(fn.contains("LiveRiskPolicy7807.decide("))
    }
}

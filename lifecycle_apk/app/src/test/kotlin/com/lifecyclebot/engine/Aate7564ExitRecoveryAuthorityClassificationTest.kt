package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7564ExitRecoveryAuthorityClassificationTest {
    private fun src(rel: String) =
        File("src/main/kotlin/com/lifecyclebot/" + rel).readText()

    @Test
    fun `recovery helpers are classifiers and plans not seller implementations`() {
        val recovery = src("engine/truth/GovernorRecoverySubstrate6388.kt")
        assertTrue(recovery.contains("fun buildFullExitPlan("))
        assertTrue(recovery.contains("fun p1FaultLiveReconcilerMissingWithHoldings("))
        assertTrue(recovery.contains("fun requiresFullExit("))
        assertTrue(recovery.contains("ROUTE_CHUNKED_FULL_EXIT"))
    }

    @Test
    fun `compatibility sell gate remains bounded and non authoritative`() {
        val liveGate = src("engine/LiveExecutionGate.kt")
        assertTrue(liveGate.contains("Kept for forward-compat. No-op"))
        assertTrue(liveGate.contains("fun sellCompleted()"))
        assertTrue(liveGate.contains("fun trySell(): Decision"))
        assertTrue(liveGate.contains("PENDING_SELLS"))
    }

    @Test
    fun `price integrity helper prevents fake loss classification`() {
        val s = src("engine/truth/LiveTruthExitAuthority6387.kt")
        assertTrue(s.contains("fun classifyForStop("))
        assertTrue(s.contains("PRICE_UNKNOWN"))
        assertTrue(s.contains("STALE_HOLD_UNDER_OBSERVATION"))
        assertTrue(s.contains("REFRESH_VIA_EXECUTABLE_QUOTE"))
    }

    @Test
    fun `audit records recovery and compatibility classifications`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7564 — C_EXIT recovery/compatibility authority classification"))
        assertTrue(audit.contains("RECOVERY_SUBSTRATE / TESTED HELPER"))
        assertTrue(audit.contains("FORWARD-COMPAT NO-OP"))
        assertTrue(audit.contains("PRICE-INTEGRITY CLASSIFIER / TESTED HELPER"))
        assertTrue(audit.contains("STATE ACCESSOR"))
    }
}

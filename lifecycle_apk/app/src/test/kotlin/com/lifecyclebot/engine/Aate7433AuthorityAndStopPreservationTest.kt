package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7433AuthorityAndStopPreservationTest {

    @Test
    fun `paper stop preserves economic positions instead of manufacturing shutdown sells`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // V5.0.7928 — liquidation is folded into liquidateOnStop, which a PAPER runtime never sets.
        assertTrue(src.contains("val paperRuntime7433 = try {"))
        assertTrue(src.contains("val liquidateOnStop = explicitLiquidationStop7433 && !paperRuntime7433"))
        assertTrue(src.contains("val softStopPreservePositions = !liquidateOnStop"))
        assertTrue(src.contains("if (liquidateOnStop) {"))
        assertTrue(src.contains("if (liquidateOnStop) try {"))
    }

    @Test
    fun `v3 execute does not pre-seal fdg buy before real final decision gate`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val region = src.substringAfter("ExecutableOpenGate.clearExecutableApproval(ts.mint, ts.symbol, \"EXECUTE\")")
            .substringBefore("V5.9.1323 — V3 Verdict Reconciliation")
        assertTrue(region.contains("FinalExecutionPermit.registerApproval("))
        assertFalse(region.contains("ExecutableOpenGate.recordFdg("))
        assertTrue(region.contains("V3_CANONICAL_HANDOFF_PENDING_6533"))
        assertTrue(region.contains("action=real_fdg_then_one_immutable_intent"))
    }

    @Test
    fun `hard no outranks and revokes same-version sealed buy`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        val precedence = src.substringAfter("V5.0.7433 — HARD_NO is factual impossibility")
            .substringBefore("V5.0.6910 §LANE_OWNERSHIP_IS_NOT_THE_VERDICT")
        assertTrue(precedence.contains("\"HARD_NO_BUY\" -> 4"))
        assertTrue(precedence.contains("!finalVerdict.equals(\"HARD_NO_BUY\", true)"))
        val record = src.substringAfter("V5.0.7433 — the post-record canonical state")
            .substringBefore("if (!canExecute) return null")
        assertTrue(record.contains("activeExecutionIntents6519.remove"))
        assertTrue(record.contains("executionTickets.remove"))
        assertTrue(record.contains("HARD_NO_REVOKED_SEALED_BUY_7433"))
        assertTrue(record.contains("return null"))
    }
}

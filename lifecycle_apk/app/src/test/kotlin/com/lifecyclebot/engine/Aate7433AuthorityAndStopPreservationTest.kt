package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7433AuthorityAndStopPreservationTest {

    @Test
    fun `config restart preserves positions while deliberate paper stop uses canonical closes`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        // 8521: LIVE liquidation stays separate; explicit PAPER Stop has an exact-lot close path.
        assertTrue(src.contains("val paperRuntime7433 = try {"))
        assertTrue(src.contains("val liquidateOnStop = explicitLiquidationStop7433 && !paperRuntime7433"))
        assertTrue(src.contains("val softStopPreservePositions = !liquidateOnStop && !paperLiquidationStop8521"))
        assertTrue(src.contains("val paperLiquidationStop8521 = paperRuntime7433 && operatorStop7928"))
        assertTrue(src.contains("closePaperStopIntent8521(requestCurrent = true, source = source)"))
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
            .substringBefore("val effectiveVerdict = if (keepOld)")  // V5.0.8169 — keepOld moved below the 6910 lane ranks
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

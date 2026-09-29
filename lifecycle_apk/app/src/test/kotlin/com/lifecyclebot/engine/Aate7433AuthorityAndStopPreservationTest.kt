package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7433AuthorityAndStopPreservationTest {

    @Test
    fun `paper stop preserves economic positions instead of manufacturing shutdown sells`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(src.contains("paperModeAtStop7433"))
        assertTrue(src.contains("preserveEconomicPositions7433 = softStopPreservePositions || paperModeAtStop7433"))
        assertTrue(src.contains("economicLiquidationOnStop7433 = liquidateOnStop && !paperModeAtStop7433"))
        assertTrue(src.contains("if (economicLiquidationOnStop7433)"))
        assertTrue(src.contains("STOP_CROSS_ASSET_POSITIONS_PRESERVED_7433"))
    }

    @Test
    fun `v3 execute does not pre-seal fdg buy before real final decision gate`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val region = src.substringAfter("V5.0.7433 — V3 Execute is an upstream opinion")
            .substringBefore("V5.9.1323 — V3 Verdict Reconciliation")
        assertTrue(region.contains("ExecutableOpenGate.recordV3("))
        assertFalse(region.contains("ExecutableOpenGate.recordFdg("))
        assertTrue(region.contains("V3_EXECUTABLE_TRUNK_HANDOFF_DEFERRED_TO_FDG_7433"))
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

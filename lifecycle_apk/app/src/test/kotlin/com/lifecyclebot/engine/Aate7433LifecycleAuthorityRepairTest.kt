package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7433LifecycleAuthorityRepairTest {

    @Test
    fun `paper runtime stop preserves positions and never enters shutdown liquidation block`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val stop = src.substringAfter("fun stopBot(source: String = \"direct_call_unknown\")")
        assertTrue(stop.contains("paperRuntime7433"))
        assertTrue(stop.contains("val liquidateOnStop = explicitLiquidationStop7433 && !paperRuntime7433"))
        assertTrue(stop.contains("if (liquidateOnStop)"))
        assertTrue(stop.contains("STOP_SOFT_PRESERVE_POSITIONS"))
    }

    @Test
    fun `pre fdg hard no cannot become canonical buy authority`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val base = src.indexOf("val baseEntrySignal7243")
        val guard = src.indexOf("PREFDG_HARD_NO_BUY_TERMINAL_7433", base)
        val laneRescue = src.indexOf("val laneScoreClears7307", base)
        assertTrue(base >= 0)
        assertTrue(guard > base)
        assertTrue(laneRescue > guard)
        val block = src.substring(guard, laneRescue)
        assertTrue(block.contains("shouldTrade = false"))
        assertTrue(block.contains("BlockLevel.HARD"))
        assertTrue(block.contains("no_fdg_allow_no_exec_intent"))
        assertFalse(block.contains("shouldTrade = true"))
    }
}

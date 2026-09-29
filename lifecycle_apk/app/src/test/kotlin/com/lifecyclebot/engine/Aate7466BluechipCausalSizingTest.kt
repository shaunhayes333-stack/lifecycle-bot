package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7466BluechipCausalSizingTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun bluechip_reuses_the_active_sealed_fdg_attempt() {
        val s = src("engine/BotService.kt")
        val block = s.substringAfter("val blueChipCandidateVersion7466").substringBefore("val canExecute = blueChipAuth6494.isExecutable()")
        assertTrue(block.contains("ExecutableOpenGate.activeExecutionIntent6519("))
        assertTrue(block.contains("attemptId = blueChipSealedIntent7466?.attemptId.orEmpty()"))
        assertTrue(block.contains("BLUECHIP_SEALED_INTENT_REUSED_FOR_AUTH_7466"))
    }

    @Test fun bluechip_size_stages_are_written_only_after_authorizer_executes() {
        val s = src("engine/BotService.kt")
        val block = s.substringAfter("val blueChipAuth6494 = TradeAuthorizer.authorize(")
            .substringBefore("val canExecute = blueChipAuth6494.isExecutable()")
        val guard = block.indexOf("if (blueChipAuth6494.isExecutable()")
        val size = block.indexOf("recordDeskStage(\"BLUECHIP\", \"SIZED_EXECUTABLE\"")
        val ticket = block.indexOf("recordDeskStage(\"BLUECHIP\", \"TICKET\"")
        assertTrue(guard >= 0)
        assertTrue(size > guard)
        assertTrue(ticket > guard)
    }

    @Test fun bluechip_p0_3_does_not_retune_strategy_or_size() {
        val s = src("engine/BotService.kt")
        val block = s.substringAfter("val blueChipCandidateVersion7466").substringBefore("val canExecute = blueChipAuth6494.isExecutable()")
        assertFalse(block.contains("bcSize7389 ="))
        assertFalse(block.contains("minScore"))
        assertFalse(block.contains("confidenceFloor ="))
    }
}

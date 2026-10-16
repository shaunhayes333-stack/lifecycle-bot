package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7466BluechipCausalSizingTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun bluechip_reuses_the_active_sealed_fdg_attempt() {
        val s = src("engine/BotService.kt")
        // V5.0.7835 / V5.0.7871 — BLUECHIP hands its FDG decision to the authorizer,
        // which reuses the live sealed intent via SpecialistPreauthSeal7834.
        val block = s.substringAfter("val blueChipAuth6494 = TradeAuthorizer.authorize(").substringBefore("val canExecute = blueChipAuth6494.isExecutable()")
        assertTrue(block.contains("fdgDecision7835 = blueChipFdg, tokenState7835 = ts"))
        assertTrue(src("engine/TradeAuthorizer.kt").contains("SpecialistPreauthSeal7834.ensure(tokenState7835, fdgDecision7835!!, requestedBook.name, preResolvedSizeSol)"))
        assertTrue(src("engine/SpecialistPreauthSeal7834.kt").contains("ExecutableOpenGate.reuseSealedIntent7871("))
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
        val block = s.substringAfter("val blueChipAuth6494 = TradeAuthorizer.authorize(").substringBefore("val canExecute = blueChipAuth6494.isExecutable()")
        assertFalse(block.contains("bcSize7389 ="))
        assertFalse(block.contains("minScore"))
        assertFalse(block.contains("confidenceFloor ="))
    }
}

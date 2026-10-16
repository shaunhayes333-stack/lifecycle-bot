package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7469PostFdgAttemptContinuityTest {
    private fun bot() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
    private fun auth() = File("src/main/kotlin/com/lifecyclebot/engine/TradeAuthorizer.kt").readText()

    // V5.0.7812 — TradeAuthorizer rebinds every lane to the FDG-sealed intent's
    // attempt id; specialist call sites consume authResult.attemptId and never
    // mint a fresh fallback attempt after FDG.
    @Test fun express_reuses_sealed_attempt_before_fresh_fallback() {
        val s = bot()
        val block = s.substringAfter("requestedBook = TradeAuthorizer.ExecutionBook.EXPRESS,")
            .substringBefore("ToolkitSignalSheet.recordDeskStage(\"EXPRESS\", \"POOL\"")
        assertTrue(block.contains("val expressAttemptId = authResult.attemptId"))
        assertFalse(block.contains("nextAttemptId("))
        assertTrue(auth().contains("val finalityAttemptId = sealedIntent7812.attemptId"))
    }

    @Test fun shitcoin_reuses_sealed_attempt_before_fresh_fallback() {
        val s = bot()
        val block = s.substringAfter("requestedBook = TradeAuthorizer.ExecutionBook.SHITCOIN,")
            .substringBefore("ToolkitSignalSheet.recordDeskStage(\"SHITCOIN\", \"POOL\"")
        assertTrue(block.contains("val shitcoinAttemptId = authResult.attemptId"))
        assertFalse(block.contains("nextAttemptId("))
        assertTrue(auth().contains("val finalityAttemptId = sealedIntent7812.attemptId"))
    }
}

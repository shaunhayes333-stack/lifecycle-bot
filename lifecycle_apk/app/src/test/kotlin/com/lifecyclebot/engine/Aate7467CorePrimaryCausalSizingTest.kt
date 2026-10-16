package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7467CorePrimaryCausalSizingTest {
    private fun bot() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    // V5.0.7858/7835 — the primary spine no longer re-resolves a sealed intent of
    // its own; TradeAuthorizer carries the exact FDG decision and returns the
    // same-attempt sealed ticket as executionIntent7835.
    @Test fun primary_spine_reuses_lane_matched_sealed_intent() {
        val s = bot()
        val block = s.substringAfter("val actualInitialSizeForAuth6649")
            .substringBefore("ErrorLogger.info(\"BotService\", \"MEME_SPINE AUTH")
        assertTrue(block.contains("requestedBook = executionBookForLane6494(fdgDecision.canonicalLane7835)"))
        assertTrue(block.contains("fdgDecision7835 = fdgDecision"))
        assertTrue(block.contains("val specialistIntent6614 = authResult.executionIntent7835"))
    }

    @Test fun post_fdg_stages_require_executable_same_attempt_proof() {
        val s = bot()
        val block = s.substringAfter("val specialistIntent6614 = authResult.executionIntent7835")
            .substringBefore("val actualInitialSize = specialistIntent6614.resolvedSize")
        assertTrue(block.contains("if (!authResult.isExecutable())"))
        assertTrue(block.contains("if (specialistIntent6614 == null)"))
        assertTrue(block.contains("ExecutableOpenGate.terminalizeAttempt6514(authResult.attemptId"))
    }

    @Test fun incomplete_proof_is_named_not_fabricated() {
        val s = bot()
        assertTrue(s.contains("AUTH_EXECUTABLE_INTENT_MISSING_7858"))
        val old = "if (ticketStampIntent6658 != null) try {\n            ToolkitSignalSheet.recordDeskStage(cyclePrimaryLane, \"POOL\""
        assertFalse(s.contains(old))
    }
}

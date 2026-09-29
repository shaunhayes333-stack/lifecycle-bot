package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7467CorePrimaryCausalSizingTest {
    private fun bot() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun primary_spine_reuses_lane_matched_sealed_intent() {
        val s = bot()
        val block = s.substringAfter("val primaryCandidateVersion7467")
            .substringBefore("ErrorLogger.info(\"BotService\", \"🧬 MEME_SPINE AUTH")
        assertTrue(block.contains("activeExecutionIntent6519("))
        assertTrue(block.contains("CanonicalLaneIdentity6506.canonical(intent.canonicalLane)"))
        assertTrue(block.contains("attemptId = primarySealedIntent7467?.attemptId.orEmpty()"))
    }

    @Test fun post_fdg_stages_require_executable_same_attempt_proof() {
        val s = bot()
        val block = s.substringAfter("val postAuthIntent7467")
            .substringBefore("val specialistFdgAllowed6614")
        assertTrue(block.contains("authResult.isExecutable()"))
        assertTrue(block.contains("it.attemptId == authResult.attemptId"))
        assertTrue(block.contains("it.resolvedSize.isFinite() && it.resolvedSize > 0.0"))
        assertTrue(block.contains("it.executableMarkTimestampMs6613 > 0L"))
        assertTrue(block.contains("recordDeskStage(cyclePrimaryLane, \"SIZED_EXECUTABLE\", postAuthIntent7467.attemptId)"))
    }

    @Test fun incomplete_proof_is_named_not_fabricated() {
        val s = bot()
        assertTrue(s.contains("PRIMARY_SPINE_POST_AUTH_PROOF_INCOMPLETE_7467"))
        val old = "if (ticketStampIntent6658 != null) try {\n            ToolkitSignalSheet.recordDeskStage(cyclePrimaryLane, \"POOL\""
        assertFalse(s.contains(old))
    }
}

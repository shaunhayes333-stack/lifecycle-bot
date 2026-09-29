package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7468GroupedSpecialistAttemptContinuityTest {
    private fun bot() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun shared_helper_requires_version_and_lane_match() {
        val s = bot()
        val fn = s.substringAfter("private fun sealedSpecialistAttempt7468(")
            .substringBefore("private fun executionBookForLane6494")
        assertTrue(fn.contains("LaneExecutionCoordinator.candidateVersionFor(mint)"))
        assertTrue(fn.contains("activeExecutionIntent6519("))
        assertTrue(fn.contains("sealedLane == canonicalLane"))
        assertTrue(fn.contains("SPECIALIST_SEALED_ATTEMPT_MISSING_OR_MISMATCH_7468_"))
    }

    @Test fun all_split_attempt_specialists_reuse_the_sealed_attempt() {
        val s = bot()
        listOf("TREASURY","QUALITY","MOONSHOT","MANIPULATED","DIP_HUNTER").forEach { lane ->
            assertTrue(
                "missing sealed attempt reuse for $lane",
                s.contains("attemptId = sealedSpecialistAttempt7468(ts.mint, \"$lane\", cfg.paperMode)")
            )
        }
    }

    @Test fun already_correct_explicit_attempt_lanes_are_not_rewritten() {
        val s = bot()
        assertTrue(s.contains("attemptId = expressAttemptId7389"))
        assertTrue(s.contains("attemptId = sniperAttemptId6842"))
        assertTrue(s.contains("attemptId = shitCoinAttemptId7389"))
    }
}

package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7525PaperRehearsalBoundaryTest {
    @Test fun execution_intent_carries_first_writer_approval_class() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(s.contains("val approvalClass7525: String = \"\""))
        assertTrue(s.contains("fun bindApprovalClass7525("))
        assertTrue(s.contains("EXEC_INTENT_APPROVAL_CLASS_CONFLICT_7525"))
        assertTrue(s.contains("action=keep_first_immutable"))
    }

    @Test fun paper_exploration_and_probe_route_to_shadow_before_doBuy() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val block = s.substringAfter("V5.0.7525 — PAPER is the live-rehearsal account")
            .substringBefore("doBuy(ts, size, decision.entryScore")
        assertTrue(block.contains("PAPER_EXPLORATION"))
        assertTrue(block.contains("PAPER_PROBE"))
        assertTrue(block.contains("runShadowPaperBuy("))
        assertTrue(block.contains("PAPER_EXPLORATION_ROUTED_SHADOW_7525"))
        assertTrue(block.contains("return"))
    }

    @Test fun benchmark_is_not_in_shadow_route_set() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val block = s.substringAfter("val paperExploration7525 =")
            .substringBefore("if (paperExploration7525)")
        assertFalse(block.contains("PAPER_BENCHMARK"))
    }
}

package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7504DecisionGenerationCleanupTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/ExecutionDecisionSnapshot6510.kt").readText()

    @Test fun old_generation_cleanup_runs_on_reads() {
        val s = src()
        assertTrue(s.contains("private fun cleanupGeneration7504"))
        assertTrue(s.contains("cleanupGeneration7504(generation7504)"))
        assertTrue(s.contains("EXEC_DECISION_OLD_GENERATION_PRUNED_7504"))
    }

    @Test fun cleanup_only_removes_older_runtime_generation() {
        val s = src()
        assertTrue(s.contains("it.value.runtimeGeneration < current"))
        assertFalse(s.contains("it.value.runtimeGeneration <= current"))
    }
}

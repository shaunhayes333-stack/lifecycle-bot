package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7576DeadExplicitTestHookTrancheTest {
    @Test
    fun `audit separates explicit test hooks from production wiring gaps`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7576 — F_DEAD explicit test-hook tranche (22 rows)"))
        assertTrue(audit.contains("TEST_HOOK_ONLY / NOT PRODUCTION-WIRING GAP"))
        assertTrue(audit.contains("62 / 1,458"))
        assertTrue(audit.contains("1,396"))
    }
}

package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7574PreTriagedTierDispositionTest {
    @Test
    fun `audit records machine triaged tiers as resolved dispositions`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7574 — wider-ledger pre-triaged disposition tiers (182 rows)"))
        assertTrue(audit.contains("INTERNAL_VERIFIED_7079"))
        assertTrue(audit.contains("RETIRED_*"))
        assertTrue(audit.contains("STARVED_LANE_IDLE_7095"))
        assertTrue(audit.contains("Remaining large raw tier for real triage: **F_DEAD = 1,458 declarations**"))
    }
}

package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7575DeadUiVoiceTrancheTest {
    @Test
    fun `audit separates dead ui voice surface from trading correctness`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7575 — F_DEAD safe tranche: UI + voice product surface (40 rows)"))
        assertTrue(audit.contains("PRODUCT_UI_VOICE_DEAD / NOT TRADING AUTHORITY"))
        assertTrue(audit.contains("40 / 1,458"))
        assertTrue(audit.contains("1,418"))
    }
}

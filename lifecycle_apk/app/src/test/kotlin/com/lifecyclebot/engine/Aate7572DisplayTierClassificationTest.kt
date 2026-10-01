package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7572DisplayTierClassificationTest {
    @Test
    fun `audit demotes display tier from trading correctness backlog`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7572 — wider-ledger D_DISPLAY demotion (151 rows)"))
        assertTrue(audit.contains("All 151 `D_DISPLAY` rows"))
        assertTrue(audit.contains("not counted as an unwired trading primitive"))
        assertTrue(audit.contains("original 113 complete + D_DISPLAY 151/151 classified"))
    }
}

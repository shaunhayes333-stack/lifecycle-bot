package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7573InFileHelperClassificationTest {
    @Test
    fun `audit classifies in-file helpers as locally consumed`() {
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(audit.contains("V5.0.7573 — wider-ledger E_INFILE local-helper classification (100 rows)"))
        assertTrue(audit.contains("All 100 `E_INFILE` rows are classified"))
        assertTrue(audit.contains("LOCAL_IMPLEMENTATION_HELPER / NOT EXTERNALLY UNWIRED"))
        assertTrue(audit.contains("D_DISPLAY 151/151 + E_INFILE 100/100 classified"))
    }
}

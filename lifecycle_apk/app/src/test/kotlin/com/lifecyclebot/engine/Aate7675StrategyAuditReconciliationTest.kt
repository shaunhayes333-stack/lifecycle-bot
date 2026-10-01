package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7675StrategyAuditReconciliationTest {
    @Test fun staleExactStrategyAndEdgeParityItemsAreClosed() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(a.contains("Exact hypothesis context expansion completed in 7430"))
        assertTrue(a.contains("Exact style/setup outcome consumption completed in 7431"))
        assertTrue(a.contains("FDG edge-evidence PAPER/LIVE parity completed by 7431/7549"))
        assertFalse(a.contains("- [ ] Next: expand hypothesis contexts from coarse"))
        assertFalse(a.contains("- [ ] PAPER/LIVE parity defect: FDG PAPER edge handling"))
    }

    @Test fun legacyFreshLaunchAuthoritiesStayRetired() {
        val a = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(a.contains("EarlyEntryScout6390.evaluate() is superseded/do-not-resurrect"))
        assertTrue(a.contains("ModeSpecificScanners.scanFreshLaunch() classified legacy/superseded"))
    }
}

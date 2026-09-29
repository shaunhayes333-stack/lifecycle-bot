package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7501ForensicPrecomputedReuseTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForensicReconciliation6635.kt").readText()

    @Test fun unchanged_guard_is_not_conditional_on_missing_precomputed_replay() {
        val s = src()
        val fn = s.substringAfter("fun reconcile6635").substringBefore("checks.incrementAndGet()")
        assertFalse(fn.contains("if (precomputedReplay6699 == null)"))
        assertTrue(fn.contains("TradeHistoryStore.journalRevision7343()"))
        assertTrue(fn.contains("CanonicalPositionAuthority6441.mutationCount7387()"))
        assertTrue(fn.contains("FORENSIC_PRECOMPUTED_RECONCILE_REUSED_7501"))
    }

    @Test fun actual_reconciliation_logic_is_preserved() {
        val s = src()
        assertTrue(s.contains("val replay6647 = precomputedReplay6699 ?:"))
        assertTrue(s.contains("FORENSIC_CASH_DELTA_6635"))
        assertTrue(s.contains("FORENSIC_REALIZED_DELTA_6635"))
        assertTrue(s.contains("FORENSIC_OPEN_COST_DELTA_6635"))
        assertTrue(s.contains("FORENSIC_QUANTITY_DELTA_6647"))
    }
}

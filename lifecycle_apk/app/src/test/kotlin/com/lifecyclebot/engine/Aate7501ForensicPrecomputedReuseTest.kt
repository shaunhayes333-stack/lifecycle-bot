package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7501ForensicPrecomputedReuseTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/ForensicReconciliation6635.kt").readText()

    @Test fun unchanged_guard_is_not_conditional_on_missing_precomputed_replay() {
        val s = src()
        val fn = s.substringAfter("fun reconcile6635").substringBefore("private fun reconcileClaimed8372")
        val stateKey = s.substringAfter("private fun stateKey8372()").substringBefore("private fun completedCurrent8372")
        assertFalse(fn.contains("if (precomputedReplay6699 == null)"))
        assertTrue(fn.contains("val key7387 = stateKey8372()"))
        assertTrue(stateKey.contains("TradeHistoryStore.journalRevision7343()"))
        assertTrue(stateKey.contains("CanonicalPositionAuthority6441.mutationCount7387()"))
        assertTrue(stateKey.contains("CanonicalEconomicEvent6635.receiptRevision8077()"))
        assertTrue(stateKey.contains("PaperCapitalAuthority6577.cashSol() + PaperCapitalAuthority6577.treasurySol7294()"))
        assertTrue(fn.contains("FORENSIC_PRECOMPUTED_RECONCILE_REUSED_7501"))
        val unchanged = fn.indexOf("key7387 == lastKey7387.get()")
        val claim = fn.indexOf("publication8372.claim(key7387)")
        val work = fn.indexOf("coherent8372 = reconcileClaimed8372(key7387, precomputedReplay6699)")
        val finish = fn.indexOf("publication8372.finish(lease8372, stateKey8372(), coherent8372)")
        assertTrue(unchanged >= 0 && claim > unchanged && work > claim && finish > work)
        assertFalse(fn.contains("lastKey7387.getAndSet"))
    }

    @Test fun actual_reconciliation_logic_is_preserved() {
        val s = src()
        // superseded by V5.0.8358 — the precomputed replay is still used first, then checked against the key's journal revision
        assertTrue(s.contains("val replayFirst8358 = precomputedReplay6699 ?:"))
        assertTrue(s.contains("val replay6647 = ReconLag8358.currentReplay8358(replayFirst8358,"))
        assertTrue(s.contains("FORENSIC_CASH_DELTA_6635"))
        assertTrue(s.contains("FORENSIC_REALIZED_DELTA_6635"))
        assertTrue(s.contains("FORENSIC_OPEN_COST_DELTA_6635"))
        assertTrue(s.contains("FORENSIC_QUANTITY_DELTA_6647"))
    }
}

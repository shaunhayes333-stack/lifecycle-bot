package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6378 — Golden-tape invariants for the emergency-response bundle
 * that de-loads the hot path after operator's V5.0.6308-format emergency
 * dump (full_builder_timeout_8s at cycles 156s, SCANNER_BATCH_BUDGET_EXCEEDED
 * 37s vs 8s budget, STRATEGY_CLEAN_TERMINAL_ROWS = 433,607 in 40 min).
 *
 * Three surgical de-loads (all one-line changes):
 *   • ForensicReconciler cadence 50 → 200 (~4× fewer passes)
 *   • ForensicReconciler snapshot depth 5000 → 1000 rows (~5× smaller sort)
 *   • StrategyTruthLedger cache TTL 3s → 10s (raises hit-rate above ~55%)
 *   • StrategyTruthLedger.auditLine default limit 2500 → 500 (dump-builder
 *     was pushed past 20s watchdog by the 2500-row sort)
 */
class Bundle6378InvariantsTest {

    @Test
    fun forensic_reconciler_cadence_bumped_to_200() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "V5.0.6378: reconciler cadence must be every 200 cycles (was 50) to reduce journal-snapshot hot-path load",
            txt.contains("(loopCount % 200) == 0")
        )
        assertTrue(
            "V5.0.6378: reconciler snapshot depth must be capped at 1000 rows (was 5000)",
            txt.contains("getAllValidTradesSnapshot(1_000)")
        )
    }

    @Test
    fun strategy_truth_ledger_ttl_bumped_to_10s() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/StrategyTruthLedger.kt").readText()
        assertTrue(
            "V5.0.6378: StrategyTruthLedger cache TTL must be 10_000L (was 3_000L)",
            txt.contains("private const val CLEAN_CACHE_TTL_MS: Long = 10_000L")
        )
    }

    @Test
    fun strategy_truth_ledger_cache_bucketed_v6379() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/StrategyTruthLedger.kt").readText()
        assertTrue(
            "V5.0.6379: cache key must be BUCKETED (size / 10) and (newestTs / 30_000) so back-to-back callers within same 10-row / 30s window hit the same cache slot instead of invalidating on every new SELL",
            txt.contains("val key = \"\${rawRows.size / 10}|\${newestTs / 30_000}|\$limit\"")
        )
    }

    @Test
    fun strategy_truth_ledger_audit_line_capped_at_500() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/StrategyTruthLedger.kt").readText()
        assertTrue(
            "V5.0.6378: auditLine default limit must be capped at 500 rows (was 2500) so the pipeline dump builder cannot exceed the 20s watchdog",
            txt.contains("fun auditLine(limit: Int = 500)")
        )
    }
}

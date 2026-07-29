package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.Trade

/**
 * V5.0.6387 — DIRECTIVE B P0 — HISTORICAL QUARANTINE FOR FALSE PROFIT ROWS +
 * DIRECTIVE A P0 — LEGACY_PRE_CANONICAL_6387 tagging (Journal migration
 * WITHOUT deleting forensics).
 */
object FalseProfitHistoricalQuarantine6387 {
    const val REASON: String = "FALSE_PROFIT_TRIGGER_PRICE_UNIT_CORRUPTION_6387"
    const val LEGACY_TAG: String = "LEGACY_PRE_CANONICAL_6387"

    fun evaluateReasons(t: Trade): List<String> {
        val reasons = mutableListOf<String>()
        val exit = t.reason.uppercase()
        val pnlSol = t.pnlSol
        val pnlPct = t.pnlPct

        // 10X with non-positive PnL.
        if (exit.contains("QUICK_RUNNER_10X") && pnlSol <= 0.0) reasons += "10X_NEG_PNL"
        // 10X with max gain (proxy: pnlPct or peakPnlPct if available) < 900%.
        if (exit.contains("QUICK_RUNNER_10X") && pnlPct < 900.0) reasons += "10X_MAX_GAIN_LT_900"
        // 6X with max gain < 500%.
        if (exit.contains("QUICK_RUNNER_6X") && pnlPct < 500.0) reasons += "6X_MAX_GAIN_LT_500"
        // Profit exit with zero/unknown basis.
        if (ExitReasonSemantics6387.isProfitExitReason(exit) && t.entryCostSol <= 0.0) reasons += "PROFIT_EXIT_UNKNOWN_BASIS"
        // Cross-unit ratio ~ SOL/USD (~180-200 in this era) — heuristic price-unit contamination.
        val cost = t.entryCostSol
        val proceeds = t.sol
        if (cost > 0.0 && proceeds > 0.0) {
            val ratio = proceeds / cost
            if (ratio in 100.0..300.0 && exit.contains("QUICK_RUNNER")) reasons += "CURRENCY_CONVERSION_RATIO_MATCH"
        }
        // Reason ↔ result contradiction.
        if (ExitReasonSemantics6387.isProfitExitReason(exit) && pnlSol < 0.0) reasons += "EXIT_REASON_RESULT_CONTRADICTION"
        return reasons
    }

    /**
     * One-shot at boot after TradeHistoryStore.init and after
     * TacticSwitcher.rederive. Tags rows without deleting. Downstream truth
     * consumers must ignore rows carrying LEGACY_TAG or REASON.
     */
    fun runOnce(): Pair<Int, Int> {
        val rows = try { com.lifecyclebot.engine.TradeHistoryStore.getAllTradesFromDb() } catch (_: Throwable) { return 0 to 0 }
        var priceTagged = 0
        var legacyTagged = 0
        for (t in rows) {
            if (!t.mode.equals("live", true)) continue
            val fp = evaluateReasons(t)
            if (fp.isNotEmpty()) {
                priceTagged++
                fp.forEach { r ->
                    try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("HISTORICAL_QUARANTINE_6387_${r}") } catch (_: Throwable) {}
                }
            }
            // Directive A P0: mark all existing rows LEGACY_PRE_CANONICAL_6387.
            legacyTagged++
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LEGACY_PRE_CANONICAL_6387_ROW_TAGGED") } catch (_: Throwable) {}
        }
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("FALSE_PROFIT_QUARANTINED_ROWS_${priceTagged}")
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc("LEGACY_PRE_CANONICAL_ROWS_${legacyTagged}")
        } catch (_: Throwable) {}
        return priceTagged to legacyTagged
    }
}

/**
 * V5.0.6387 — MODE NOTIFICATION DEDUPLICATION (Directive B P1).
 * Prevents 4× identical Range-mode notifications observed in the export.
 */
object ModeNotificationDedup6387 {
    private data class Key(val runtimeGen: Long, val scope: String, val subject: String, val prev: String, val next: String)
    @Volatile private var lastKey: Key? = null
    @Volatile private var lastEmittedAtMs: Long = 0L
    const val MIN_TTL_MS: Long = 60_000L

    /**
     * Returns true iff this notification should actually be emitted.
     * Requires prev != next (a REAL transition).
     */
    fun shouldEmit(runtimeGen: Long, scope: String, subject: String, prev: String, next: String): Boolean {
        if (prev == next) return false     // not a transition
        val k = Key(runtimeGen, scope, subject, prev, next)
        val now = System.currentTimeMillis()
        if (k == lastKey && (now - lastEmittedAtMs) < MIN_TTL_MS) return false
        lastKey = k
        lastEmittedAtMs = now
        return true
    }
    internal fun resetForTest() { lastKey = null; lastEmittedAtMs = 0L }
}

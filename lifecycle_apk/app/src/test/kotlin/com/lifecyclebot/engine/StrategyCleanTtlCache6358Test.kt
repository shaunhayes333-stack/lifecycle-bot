package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.*
import org.junit.Test

/**
 * V5.0.6358 — StrategyTruthLedger TTL cache + additional forensic rate-limits.
 *
 * Operator V5.0.6308 emergency dump showed STRATEGY_CLEAN_TERMINAL_ROWS =
 * 624,180 events / 3243s uptime (~192/sec), driving max cycle 227771ms and
 * avg 29923ms. Each caller resorted the same journal every time. The 3s
 * TTL cache short-circuits repeat callers within the window without changing
 * behaviour.
 */
class StrategyCleanTtlCache6358Test {

    private fun sellRow(mintTag: String, ts: Long) = Trade(
        side = "SELL", mode = "live",
        sol = 0.010, price = 0.00012, ts = ts,
        pnlSol = 0.001, pnlPct = 10.0, reason = "TP",
        entryPriceSnapshot = 0.0001, entryCostSol = 0.010,
        entryQtyToken = 100.0, soldQtyToken = 100.0,
        mint = mintTag, positionId = "POS_$mintTag",
    )

    @Test
    fun cache_hit_returns_same_result_instance_within_ttl() {
        val rows = (1..5).map { sellRow("M$it", ts = 1_000_000L + it) }
        val a = StrategyTruthLedger.clean(rows)
        val b = StrategyTruthLedger.clean(rows)
        assertSame("second call inside TTL must return the cached Result", a, b)
    }

    @Test
    fun cache_invalidates_when_row_count_changes() {
        val base = (1..3).map { sellRow("N$it", ts = 2_000_000L + it) }
        val a = StrategyTruthLedger.clean(base)
        val more = base + sellRow("N99", ts = 2_000_099L)
        val b = StrategyTruthLedger.clean(more)
        // Different size fingerprint → cache miss → new instance
        assertNotSame(a, b)
    }
}

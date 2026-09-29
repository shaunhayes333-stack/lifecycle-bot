package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7478StrategyHistoryRevisionCacheTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/StrategyTruthLedger.kt").readText()

    @Test fun clean_cache_uses_real_journal_revision() {
        val s = src()
        val region = s.substringAfter("V5.0.7478 — cache by the journal's real monotonic revision")
            .substringBefore("STRATEGY_CLEAN_CACHE_MISS_6358")
        assertTrue(region.contains("TradeHistoryStore.journalRevision7343()"))
        assertTrue(region.contains("rawRows.size"))
        assertTrue(region.contains("endpointIdentity7478"))
        assertFalse(region.contains("newestTs / 30_000"))
        assertFalse(region.contains("now - it.stampMs < CLEAN_CACHE_TTL_MS"))
    }

    @Test fun folded_partial_counter_is_once_per_terminal() {
        val s = src()
        val fn = s.substringAfter("private fun foldPartialLegs7333").substringBefore("private fun normalizedStrategyRow")
        assertTrue(fn.contains("seenTerminalKeysLifetime.add(\"FOLDED:\" + terminalKey(t))"))
        assertTrue(fn.contains("STRATEGY_TERMINAL_FOLDED_PARTIALS_7333"))
    }

    @Test fun bounded_cache_is_preserved() {
        assertTrue(src().contains("if (cleanCache7319.size > 16)"))
    }
}

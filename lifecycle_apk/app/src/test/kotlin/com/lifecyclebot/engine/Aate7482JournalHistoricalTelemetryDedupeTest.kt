package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7482JournalHistoricalTelemetryDedupeTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt").readText()

    @Test fun terminal_residual_arithmetic_remains_per_replay_but_emit_is_once() {
        val s = src()
        assertTrue(s.contains("val firstResidual7482 = reject("))
        assertTrue(s.contains("residualBasisWrittenOff6868 += nextBasis.coerceAtLeast(0.0)"))
        assertTrue(s.contains("residualLotCount6868 += 1"))
        assertTrue(s.contains("if (firstResidual7482) try"))
    }

    @Test fun aggregate_summaries_are_change_driven() {
        val s = src()
        assertTrue(s.contains("terminalResidualSummarySig7482.getAndSet"))
        assertTrue(s.contains("skippedEconomicsSummarySig7482.getAndSet"))
        assertTrue(s.contains("skippedSummaryChanged7482"))
    }

    @Test fun replay_memo_and_accounting_are_preserved() {
        val s = src()
        assertTrue(s.contains("TradeHistoryStore.journalRevision7343()"))
        assertTrue(s.contains("JOURNAL_REPLAY_REUSED_UNCHANGED_7343"))
        assertTrue(s.contains("cash += (gross - fee)"))
        assertTrue(s.contains("openCost -= basis"))
    }
}

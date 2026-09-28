package com.lifecyclebot.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Aate7407PaperAtomicityRegressionTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun journal_cannot_become_durable_before_ledger_witness() {
        val atomic = src("engine/truth/PaperEconomicAtomicCommit6632.kt")
        val history = src("engine/TradeHistoryStore.kt")
        assertTrue(atomic.contains("fun hasLedgerStamp7407"))
        val insert = history.substringAfter("private fun insertTradeAsync")
        assertTrue(insert.contains("PAPER_JOURNAL_BLOCKED_WITHOUT_LEDGER_7407"))
        assertTrue(insert.indexOf("hasLedgerStamp7407") < insert.indexOf("insertWithOnConflict"))
    }

    @Test fun canonical_raw_quantity_outranks_display_rounding_on_replay() {
        val replay = src("engine/truth/JournalEconomicReplay6619.kt")
        assertTrue(replay.contains("hasCanonicalRaw7407"))
        assertTrue(replay.contains("displayNegativeAuthoritative7407"))
        assertTrue(replay.contains("nextDisplayRaw7407.coerceAtLeast(0.0)"))
    }
}

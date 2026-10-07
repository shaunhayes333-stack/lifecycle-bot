package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.RootCauseClassifier6471
import org.junit.Assert.*
import org.junit.Test

class Aate7858ModeIsolationTest {
    @Test fun paper_lock_does_not_block_live_and_paper_release_cannot_release_live() {
        val mint = "Mode7858_lock_${System.nanoTime()}"
        val book = TradeAuthorizer.ExecutionBook.MOONSHOT
        try {
            TradeAuthorizer.forceOpenLockForTests(mint, book, live = false, ageMs = 0)
            assertTrue(TradeAuthorizer.hasOpenPosition(mint, isPaperMode = true))
            assertFalse(TradeAuthorizer.hasOpenPosition(mint, isPaperMode = false))
            TradeAuthorizer.forceOpenLockForTests(mint, book, live = true, ageMs = 0)
            assertTrue(TradeAuthorizer.hasOpenPosition(mint, isPaperMode = true))
            assertTrue(TradeAuthorizer.hasOpenPosition(mint, isPaperMode = false))
            TradeAuthorizer.releasePosition(mint, isPaperMode = true)
            assertFalse(TradeAuthorizer.hasOpenPosition(mint, isPaperMode = true))
            assertTrue(TradeAuthorizer.hasOpenPosition(mint, isPaperMode = false))
        } finally {
            TradeAuthorizer.releasePosition(mint, isPaperMode = true)
            TradeAuthorizer.releasePosition(mint, isPaperMode = false)
        }
    }

    @Test fun paper_close_and_reopen_cannot_change_live_close_state() {
        val mint = "Mode7858_close_${System.nanoTime()}"
        try {
            val paper = PositionCloseLedger.markClosed(mint, "TEST_TERMINAL", 1, mode = "PAPER")
            assertEquals(paper, PositionCloseLedger.closeIdOf(mint, "PAPER"))
            assertNull(PositionCloseLedger.closeIdOf(mint, "LIVE"))
            val live = PositionCloseLedger.markClosed(mint, "TEST_TERMINAL", 1, mode = "LIVE")
            assertNotEquals(paper, live)
            PositionCloseLedger.reopen(mint, "PAPER")
            assertNull(PositionCloseLedger.closeIdOf(mint, "PAPER"))
            assertEquals(live, PositionCloseLedger.closeIdOf(mint, "LIVE"))
        } finally {
            PositionCloseLedger.reopen(mint, "PAPER")
            PositionCloseLedger.reopen(mint, "LIVE")
        }
    }

    @Test fun paper_economics_are_not_classified_as_live_execution_faults() {
        for (label in listOf("LEDGER_VS_JOURNAL_DIVERGENCE_6502", "ECONOMIC_TRUTH_DIVERGENCE_6501",
                "PAPER_EQUITY_CONSERVATION_VIOLATION_6467")) {
            assertTrue(RootCauseClassifier6471.belongsToAccount7858(label, paper = true))
            assertFalse(RootCauseClassifier6471.belongsToAccount7858(label, paper = false))
        }
        assertTrue(RootCauseClassifier6471.belongsToAccount7858("EXEC_AUTHORITY_STATE_MISMATCH", paper = false))
    }
}

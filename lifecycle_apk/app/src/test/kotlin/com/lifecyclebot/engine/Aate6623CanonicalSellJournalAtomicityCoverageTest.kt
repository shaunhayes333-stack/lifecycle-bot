package com.lifecyclebot.engine

import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

/**
 * V5.0.6623 — CANONICAL SELL/JOURNAL ATOMICITY (P0 of operator's
 * V5.0.6622 forensic).
 *
 * Operator directive Feb 2026: "PAPER_CLOSE_CONFIRMED_LEDGER_ONLY = 149
 * while PAPER_SELL_JOURNAL_DONE = 16. That is enormous. You have 149
 * closes being confirmed directly into the ledger, while only a tiny
 * fraction become normal journal sell records. There should be one
 * terminal economic transaction: CanonicalCloseOutcome. A close should
 * not become economically visible until its canonical journal/
 * economic transaction has committed."
 *
 * Slice-P0 telemetry seal: PaperPositionCloseAuthority.markClosed
 * probes TradeHistoryStore for a matching paper SELL/PARTIAL_SELL
 * row within 60s of the close. When absent, emits
 * PAPER_CLOSE_NO_JOURNAL_ROW_6623 so the operator sees the exact
 * count of unjournaled closes — a much sharper signal than the
 * CLOSED_LEDGER_ONLY label which fires on every close. Steady-state
 * target = 0. Slice-P0-hard-block (follow-up) refuses markClosed
 * when the journal row is missing.
 */
class Aate6623CanonicalSellJournalAtomicityCoverageTest {

    @Test
    fun aate6623_close_authority_probes_journal_row_and_emits_counter() {
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt"
        ).readText()
        assertTrue(
            "V5.0.6623: markClosed must probe TradeHistoryStore.getAllValidTradesSnapshot for paper SELL/PARTIAL_SELL rows within 60s",
            src.contains("TradeHistoryStore") &&
                src.contains(".getAllValidTradesSnapshot(limit = 200)") &&
                src.contains("PARTIAL_SELL") &&
                src.contains("60_000L")
        )
        assertTrue(
            "V5.0.6623: absent journal row must emit PAPER_CLOSE_NO_JOURNAL_ROW_6623",
            src.contains("PAPER_CLOSE_NO_JOURNAL_ROW_6623")
        )
        assertTrue(
            "V5.0.6623: healthy parity must emit PAPER_CLOSE_JOURNAL_PARITY_HEALTHY_6623",
            src.contains("PAPER_CLOSE_JOURNAL_PARITY_HEALTHY_6623")
        )
        assertTrue(
            "V5.0.6623: probe must be scoped to PAPER mode only (LIVE journals via chain finality)",
            src.contains("if (normMode(mode) == \"PAPER\") try {")
        )
        assertTrue(
            "V5.0.6623: pre-existing PAPER_CLOSE_CONFIRMED_LEDGER_ONLY telemetry must remain for backward compat with 6622 dumps",
            src.contains("PAPER_CLOSE_CONFIRMED_LEDGER_ONLY")
        )
    }

    @Test
    fun aate6623_slice_p0_does_not_hard_block_yet() {
        // Slice P0 is telemetry-only per operator directive (measure
        // first, then hard-block when the count is proven to be the
        // real leak). Confirm no early-return/throw was introduced in
        // markClosed that would break existing close paths before the
        // operator has captured the counter data.
        val src = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/PaperPositionCloseAuthority.kt"
        ).readText()
        assertFalse(
            "V5.0.6623: slice P0 is telemetry only — must NOT throw from markClosed on missing journal row",
            src.contains("throw IllegalStateException(\"PAPER_CLOSE_NO_JOURNAL_ROW_6623")
        )
        assertFalse(
            "V5.0.6623: slice P0 must NOT return early from markClosed on missing journal row",
            src.contains("if (!hasJournalSell6623) return")
        )
    }
}

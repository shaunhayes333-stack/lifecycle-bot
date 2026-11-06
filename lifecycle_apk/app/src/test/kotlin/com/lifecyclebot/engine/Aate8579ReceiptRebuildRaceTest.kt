package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.JournalEconomicReplay6619
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8579 — the 7360 receipt rebuild raced the real closing row (the reconcile thread read the journal snapshot in the
 * milliseconds between a receipt and its row) and wrote a second SELL for the same receipt; every later replay skipped one
 * as DUPLICATE_FILL_INDEX and the accounting witness FAILED at zero deltas (8562 / 8564 / 8566 / 8577 smokes).
 */
class Aate8579ReceiptRebuildRaceTest {
    private fun sell(pid: String, raw: Long, gross: Double, basis: Double, reason: String = "x") = Trade(
        side = "SELL", mode = "paper", sol = gross, price = 1.0, ts = 1_000L, reason = reason, pnlSol = gross - basis, pnlPct = 0.0,
        mint = "M", positionId = pid, canonicalConsumedRaw = BigInteger.valueOf(raw), grossProceedsSol = gross, soldCostBasisSol = basis,
        economicEventId = "E:$pid:$reason",
    )

    @Test fun a_second_row_of_the_same_receipt_adds_no_economics_but_a_different_fill_does() {
        val real = sell("P1", 1_000_000L, 0.25, 0.10)
        assertTrue(JournalEconomicReplay6619.sameEconomics8579(real, sell("P1", 1_000_000L, 0.25, 0.10, "JOURNAL_REBUILT_FROM_RECEIPT_7360")))
        assertFalse(JournalEconomicReplay6619.sameEconomics8579(real, sell("P1", 999_999L, 0.25, 0.10)))
        assertFalse(JournalEconomicReplay6619.sameEconomics8579(real, sell("P1", 1_000_000L, 0.26, 0.10)))
        assertFalse(JournalEconomicReplay6619.sameEconomics8579(real, sell("P1", 1_000_000L, 0.25, 0.11)))
        assertFalse(JournalEconomicReplay6619.sameEconomics8579(real, sell("P2", 1_000_000L, 0.25, 0.10)))
    }

    @Test fun the_rebuild_waits_for_the_receipt_to_settle() {
        val now = 1_800_000_000_000L
        assertFalse(JournalEconomicReplay6619.receiptSettled8579(now - 1_000L, now))
        assertFalse(JournalEconomicReplay6619.receiptSettled8579(now - JournalEconomicReplay6619.REBUILD_RECEIPT_GRACE_MS_8579 + 1L, now))
        assertTrue(JournalEconomicReplay6619.receiptSettled8579(now - JournalEconomicReplay6619.REBUILD_RECEIPT_GRACE_MS_8579, now))
        assertFalse(JournalEconomicReplay6619.receiptSettled8579(0L, now))
        assertFalse(TradeHistoryStore.isEconomicEventWriteInFlight8579(""))
        assertFalse(TradeHistoryStore.isEconomicEventWriteInFlight8579("NO_SUCH_EVENT_8579"))
    }

    @Test fun the_runtime_rebuild_and_replay_carry_both_rules() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt").readText()
        val rebuild = src.substringAfter("private fun rebuildClosedLotFromReceipts7360(").substringBefore("PaperEconomicAtomicCommit6632.stampLedger(")
        assertTrue(rebuild.contains("if (!receiptSettled8579(terminal.atMs, System.currentTimeMillis())) return skip(\"RECEIPT_YOUNG_8579\")"))
        assertTrue(rebuild.contains("TradeHistoryStore.isEconomicEventWriteInFlight8579(it.idempotencyKey)"))
        assertTrue("the grace comes before the terminal-already-journaled skip is enough: both precede the write",
            rebuild.indexOf("RECEIPT_YOUNG_8579") < rebuild.indexOf("return skip(\"TERMINAL_ALREADY_JOURNALED\")"))
        val replay = src.substringAfter("val seenFills = HashMap<String, com.lifecyclebot.data.Trade>()").substringBefore("side == \"QTY_RECONCILE\" -> {")
        assertTrue(replay.contains("if (sameEconomics8579(owner8579, t)) { reject(t, eventId, \"DUPLICATE_FILL_INDEX_SAME_RECEIPT_8579\", skipped = false); continue }"))
        assertTrue("a different fill on the same index is still an abandoned event", replay.contains("reject(t, eventId, \"DUPLICATE_FILL_INDEX\"); continue"))
    }
}

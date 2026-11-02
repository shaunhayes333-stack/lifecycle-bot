package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EntryReceipt8500
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate8510ReceiptAndEpisodeTest {
    private fun r(at: Long) = EntryReceipt8500.Receipt8500("MOONSHOT", "DOOR", "S", "BASE_START", 10.0, 0.9, 1.0, 3.0, at)

    /** Re-entry into one mint: each close grades its own position's receipt, and a replayed old close is history. */
    @Test
    fun receipts_join_by_position_and_history_is_not_a_reconciliation_failure() {
        EntryReceipt8500.resetForTest8500()
        val ts = TokenState(mint = "m8510", symbol = "T")
        EntryReceipt8500.onLiveBuy8500(ts, r(1_000_000L), "LIVE:m8510:a")
        EntryReceipt8500.onLiveBuy8500(ts, r(2_000_000L), "LIVE:m8510:b")
        // second position closes first (out of order), then the first
        EntryReceipt8500.onLiveClose8500("m8510", "live", true, 5.0, 8.0, "LIVE:m8510:b", 2_100_000L, 100_000L)
        EntryReceipt8500.onLiveClose8500("m8510", "live", true, -3.0, 1.0, "LIVE:m8510:a", 2_200_000L, 1_200_000L)
        // a boot-replayed close of a position opened before any receipt existed
        EntryReceipt8500.onLiveClose8500("old", "live", true, -9.0, 0.0, "LIVE:old:z", 900_000L, 100_000L)
        val line = EntryReceipt8500.line8500()
        assertTrue(line, line.contains("open=0 unmatchedCurrent8510=0 historicalNoReceipt8510=1"))
        assertTrue(line, line.contains("MOONSHOT|DOOR{n2 w"))
        EntryReceipt8500.resetForTest8500()
    }
}

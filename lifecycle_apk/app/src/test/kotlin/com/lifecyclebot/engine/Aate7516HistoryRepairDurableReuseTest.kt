package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7516HistoryRepairDurableReuseTest {
    private fun src() =
        File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()

    @Test fun durable_check_precedes_event_reopen_and_store_stamps() {
        val fn = src().substringAfter("fun repairCryptoHistory6659()").substringBefore("fun open(")
        val check = fn.indexOf("TradeHistoryStore.isDurableEconomicEvent7371(eventId)")
        val open = fn.indexOf("CanonicalEconomicEvent6635.openEvent", check)
        val stamp = fn.indexOf("CanonicalEconomicEvent6635.markCommitted", check)
        assertTrue(check >= 0)
        assertTrue(open > check)
        assertTrue(stamp > check)
    }

    @Test fun non_durable_repair_remains_complete() {
        val fn = src().substringAfter("fun repairCryptoHistory6659()").substringBefore("fun open(")
        assertTrue(fn.contains("FillLotLedger6504.recordSellFill"))
        assertTrue(fn.contains("PaperEconomicAtomicCommit6632.stampLedger"))
        assertTrue(fn.contains("TradeHistoryStore.recordTrade"))
    }
}

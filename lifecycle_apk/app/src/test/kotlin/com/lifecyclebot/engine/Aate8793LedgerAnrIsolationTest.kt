package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 8792 Android ANR: the crypto screen waited on a ledger monitor which waited on full-journal validation. */
class Aate8793LedgerAnrIsolationTest {
    @Test fun allSellsCopiesUnderLockAndValidatesAfterReleasingIt() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val method = s.substringAfter("private fun computeAllSells(): List<Trade>")
            .substringBefore("private fun scheduleAllSellsRefresh()")
        assertTrue(method.contains("val copy = synchronized(lock) { ArrayList(trades) }"))
        assertTrue(method.contains("return copy.filter { isJournalSellLike(it.side) && isValidAccountingTrade(it) }"))
        assertFalse(method.contains("synchronized(lock) { trades.filter"))
    }

    @Test fun treasuryMutationsPublishCoalescedAfterTheirDurableWrite() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt").readText()
        for ((methodName, event) in listOf(
            "moveCashToTreasury7294" to "TREASURY_DEPOSIT_7294",
            "moveTreasuryToCash7294" to "TREASURY_BACKFUND_7294",
        )) {
            val body = s.substringAfter("fun $methodName(").substringBefore("\n    }\n")
            assertTrue(body.contains("persistCurrent6487()"))
            assertTrue(body.contains("notifyEconomicMutationAsync8077(\"$event\")"))
            assertFalse(body.contains("notifyEconomicMutation(\"$event\")"))
        }
    }
}

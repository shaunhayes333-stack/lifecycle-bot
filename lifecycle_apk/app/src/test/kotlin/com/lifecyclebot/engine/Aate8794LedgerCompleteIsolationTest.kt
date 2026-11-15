package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Ledger lock must never synchronously perform the complete journal projection. */
class Aate8794LedgerCompleteIsolationTest {
    @Test fun everySynchronizedEconomicMutationPublishesOffLock() {
        val ledger = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt").readText()
        val publisher = File("src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicAuthority6616.kt").readText()
        assertFalse(ledger.contains("JournalEconomicAuthority6616.notifyEconomicMutation(\""))
        for (event in listOf(
            "INITIALIZE", "RESET_6618", "BUY", "ROLLBACK_BUY", "PURGE",
            "SELL", "TREASURY_DEPOSIT_7294", "TREASURY_BACKFUND_7294",
        )) {
            assertTrue("Ledger notification must be asynchronous: $event",
                ledger.contains("notifyEconomicMutationAsync8077(\"$event\")"))
        }
        val async = publisher.substringAfter("fun notifyEconomicMutationAsync8077(")
            .substringBefore("fun refreshAfterDurableJournal()")
        assertTrue(async.contains("journalEconomicRevision.incrementAndGet()"))
        assertTrue(async.contains("snapshotWork8077.request(kind)"))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression: stats readers must never re-enter ledger/config via validation while locked. */
class Aate8794JournalReadLockIsolationTest {
    private val source = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()

    @Test fun allJournalReadersValidateOutsideMonitor() {
        assertTrue(source.contains("private fun validationSnapshot8794(): List<Trade> = synchronized(lock) { ArrayList(trades) }"))
        val nestedValidation = Regex("synchronized\\(lock\\)\\s*\\{\\s*trades\\.(?:filter|asReversed|asSequence)\\s*[.(]", RegexOption.DOT_MATCHES_ALL)
        assertFalse("No accounting/paper validator or label sanitizer can run under journal lock", nestedValidation.containsMatchIn(source))
        for (read in listOf("getAllTrades", "getTrades24h", "getSells24h", "getRecentValidClosedForMode",
            "computeRollingWinRatePct", "liveDecisive7706", "getTopMode", "getLaneWinRate", "backfillLifetimeFromTrades")) {
            assertTrue("Query must use a detached snapshot: $read", source.substringAfter("fun $read(")
                .substringBefore("\n    }").contains("validationSnapshot8794()") ||
                (read == "backfillLifetimeFromTrades" && source.substringAfter("fun $read(")
                    .substringBefore("\n    }").contains("computeAllSells()")))
        }
    }

    @Test fun callerSuppliedMemePredicateIsOffMonitor() {
        val fn = source.substringAfter("fun memeWrSnapshot(").substringBefore("\n    }")
        assertTrue(fn.contains("val list = validationSnapshot8794()"))
        assertFalse(fn.contains("val list = trades"))
        assertTrue(fn.contains("isMemeMode(t.tradingMode)"))
    }

    @Test fun recoveryRowsRemainNewestFirstEvenIfSqliteFails() {
        val journalRecovery = source.substringAfter("fun openMintsFromJournal(")
            .substringBefore("val latestSideSeen")
        assertTrue(journalRecovery.contains("sortedByDescending { it.ts }"))
        val dbRecovery = source.substringAfter("fun getAllTradesFromDb(")
            .substringBefore("/** MANUAL CLEAR")
        assertTrue(dbRecovery.split(".sortedByDescending { it.ts }").size >= 3)
    }
}

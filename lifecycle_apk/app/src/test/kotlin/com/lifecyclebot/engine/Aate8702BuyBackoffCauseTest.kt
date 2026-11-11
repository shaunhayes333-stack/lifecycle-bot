package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8702 — a failed LIVE buy and the tickets its backoff ended are counted by the failure's reason (8689: 30 tickets, 5 buys). */
class Aate8702BuyBackoffCauseTest {
    @Test fun aReasonIsFiledByItsFamilyNotItsCoin() {
        assertEquals("BUY_TERMINAL_NO_EXECUTABLE_ROUTE:PRETRADE_LP_UNLOCKED", ExecutionAttemptLease.reasonFamily8702("BUY_TERMINAL_NO_EXECUTABLE_ROUTE:PRETRADE_LP_UNLOCKED:mint=abc"))
        assertEquals("DEFERRED_DATA_SAFETY_PROOF_8270", ExecutionAttemptLease.reasonFamily8702("DEFERRED_DATA_SAFETY_PROOF_8270"))
        assertEquals("BUY_TERMINAL_PRE_BROADCAST_ECONOMICS:PRICE_MOVED__3_1_", ExecutionAttemptLease.reasonFamily8702("BUY_TERMINAL_PRE_BROADCAST_ECONOMICS:PRICE_MOVED +3.1%"))
        assertEquals("UNNAMED", ExecutionAttemptLease.reasonFamily8702("  "))
    }

    @Test fun aFailedBuyAndTheTicketItsBackoffEndsAreBothCounted() {
        val mint = "BACKOFF8702MINT"
        val first = ExecutionAttemptLease.acquire(side = "BUY", mint = mint, symbol = "T", processor = "MOONSHOT", mode = "LIVE", generation = 8702L)
        assertTrue(first.allowed)
        ExecutionAttemptLease.terminalFail(first.key, "BUY", mint, "T", "DEFERRED_DATA_SAFETY_PROOF_8270", "MOONSHOT")
        val second = ExecutionAttemptLease.acquire(side = "BUY", mint = mint, symbol = "T", processor = "MOONSHOT", mode = "LIVE", generation = 8702L)
        assertEquals("BACKOFF_ACTIVE", second.reason)
        val report = ExecutionAttemptLease.formatForReport()
        assertTrue(report, report.contains("buyFails8702{") && report.contains("DEFERRED_DATA_SAFETY_PROOF_8270="))
        assertTrue(report, report.substringAfter("backoffEndedTicketAfter8702{").contains("DEFERRED_DATA_SAFETY_PROOF_8270="))
    }
}

package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.9041 — 9039 (27 Nov 02:51): DEFERRED_DATA_SAFETY_PROOF_8270 x6 and DEFERRED_DATA_TOKEN_MAP_8080 x4 set the buy lease's
 * backoff, which then ended 30 later sealed tickets on the same coins (backoffEndedTicketAfter8702 18 + 12).
 */
class Aate9041DataIsNotAFailedCoinTest {
    private fun acquire(mint: String) = ExecutionAttemptLease.acquire(side = "BUY", mint = mint, symbol = "T", processor = "SHITCOIN", mode = "LIVE", generation = 9041L)

    @Test fun aProofThatHasNotArrivedDoesNotBlockTheNextTicket() {
        val mint = "DATA9041MINT${System.nanoTime()}"
        val first = acquire(mint)
        assertTrue(first.allowed)
        val before = ExecutionAttemptLease.dataEndings9041()
        ExecutionAttemptLease.terminalFail(first.key, "BUY", mint, "T", "DEFERRED_DATA_SAFETY_PROOF_8270", "SHITCOIN")
        assertEquals(before + 1, ExecutionAttemptLease.dataEndings9041())
        val second = acquire(mint)
        assertTrue("the next ticket runs (no backoff on missing data): ${second.reason}", second.allowed)
        ExecutionAttemptLease.terminalFail(second.key, "BUY", mint, "T", "DEFERRED_DATA_TOKEN_MAP_8080", "SHITCOIN")
        assertTrue(acquire(mint).allowed)
        assertTrue(ExecutionAttemptLease.formatForReport().contains("dataEndingsNoBackoff9041="))
    }

    @Test fun aRealFailureStillBacksOffAndADataEndingDoesNotEraseIt() {
        val mint = "REAL9041MINT${System.nanoTime()}"
        val first = acquire(mint)
        ExecutionAttemptLease.terminalFail(first.key, "BUY", mint, "T", "BUY_TERMINAL_ENTRY_CHASE_CONSUMED_8329_SHITCOIN", "SHITCOIN")
        val second = acquire(mint)
        assertEquals("BACKOFF_ACTIVE", second.reason)
        assertTrue(ExecutionAttemptLease.dataEnding9041("DEFERRED_DATA_SAFETY_PROOF_8270"))
        assertTrue(ExecutionAttemptLease.dataEnding9041(" DEFERRED_DATA_TOKEN_MAP_8080"))
        assertFalse(ExecutionAttemptLease.dataEnding9041("SAFETY_REFUSED_8136"))
        assertFalse(ExecutionAttemptLease.dataEnding9041("BUY_TERMINAL_ROUTE_FAIL:QUOTE_REJECTED"))
    }

    @Test fun theBuildIsRecorded() {
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## §226 — V5.0.9041: missing data is not a failed coin"))
        assertTrue(File("../../.github/workflows/build.yml").readText().contains("--tests '*Aate9041*'"))
    }
}

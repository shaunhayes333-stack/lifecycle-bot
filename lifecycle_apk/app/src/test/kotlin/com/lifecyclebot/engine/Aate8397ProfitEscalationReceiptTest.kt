package com.lifecyclebot.engine

import com.lifecyclebot.data.Position
import com.lifecyclebot.data.TokenState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** The production full-exit receipt adapter feeds the production rung dispatch boundary. */
class Aate8397ProfitEscalationReceiptTest {
    private val reason = "SCALP_TAKE_PROFIT_8351_T1"
    private val ladder = RunnerRungBook8153.LADDER_SCALP_EXIT_8394
    private val book: RunnerRungBook8153 get() = RunnerLive8153::class.java.getDeclaredField("book")
        .apply { isAccessible = true }.get(RunnerLive8153) as RunnerRungBook8153
    private fun token(at: Long) = TokenState(mint = "escalation8397-${System.nanoTime()}", position = Position(
        qtyToken = 100.0, entryPrice = 1.0, entryTime = at - 60_000L, tradingMode = "MOONSHOT", isPaperPosition = false))
    private fun key(ts: TokenState) = "${ts.mint}|${ts.position.entryTime}"
    @Suppress("UNCHECKED_CAST") private fun clean(ts: TokenState) {
        val rows = RunnerRungBook8153::class.java.getDeclaredField("book").apply { isAccessible = true }
            .get(book) as MutableMap<String, Any>
        rows.keys.removeAll { it.startsWith("${ts.mint}|") }
    }

    @Test fun eachRejectedOrUnconfirmedFullExitLeavesItsRungUnconsumedAndRetryable() {
        val failures = listOf(Executor.SellResult.FAILED_RETRYABLE, Executor.SellResult.ROUTE_FAILED_NO_SIGNATURE,
            Executor.SellResult.FAILED_FATAL, Executor.SellResult.NO_WALLET, Executor.SellResult.WAITING_BALANCE_PROOF)
        for (result in failures) {
            val now = System.currentTimeMillis(); val ts = token(now)
            var calls = 0
            try {
                assertTrue(RunnerLive8153.request8153(ts, ladder, 1, reason, now))
                runBlocking {
                    assertFalse(result.name, RungDispatch8397.dispatch8397(ts, reason) {
                        val receipt = ProfitEscalationReceipt8397.request8397 { calls++; result }
                        assertFalse(receipt.terminal)
                        assertEquals("ESCALATED_FULL_8189_${result.name}", receipt.authorityResult)
                        receipt.applied
                    })
                }
                assertEquals(1, calls)
                assertEquals(result.name, 0, book.confirmedTier8153(key(ts), ladder))
                assertNull(book.pending8153(key(ts)))
                assertTrue(RunnerLive8153.request8153(ts, ladder, 1, reason, now + 61_000L))
            } finally { clean(ts) }
        }
    }

    @Test fun confirmedFullExitReturnsATerminalAppliedReceiptFromItsActualResult() {
        for (result in listOf(Executor.SellResult.CONFIRMED, Executor.SellResult.PAPER_CONFIRMED, Executor.SellResult.ALREADY_CLOSED)) {
            var calls = 0
            val receipt = ProfitEscalationReceipt8397.request8397 { calls++; result }
            assertEquals(1, calls)
            assertTrue(receipt.applied); assertTrue(receipt.terminal)
            assertEquals("ESCALATED_FULL_8189_${result.name}", receipt.authorityResult)
            assertEquals("No fabricated signature/proceeds", "", receipt.operationId)
        }
    }

    @Test fun thrownEscalationStillReleasesTheActualDispatchAttempt() {
        val now = System.currentTimeMillis(); val ts = token(now)
        val failure = IllegalStateException("full-exit quote failed")
        try {
            assertTrue(RunnerLive8153.request8153(ts, ladder, 1, reason, now))
            var caught: Throwable? = null
            try {
                runBlocking {
                    RungDispatch8397.dispatch8397(ts, reason) {
                        ProfitEscalationReceipt8397.request8397 { throw failure }.applied
                    }
                }
            } catch (t: Throwable) { caught = t }
            assertSame(failure, caught)
            assertEquals(0, book.confirmedTier8153(key(ts), ladder))
            assertNull(book.pending8153(key(ts)))
        } finally { clean(ts) }
    }
}

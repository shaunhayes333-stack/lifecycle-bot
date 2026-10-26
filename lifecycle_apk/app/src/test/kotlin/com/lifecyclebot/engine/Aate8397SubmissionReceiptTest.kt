package com.lifecyclebot.engine

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.truth.EvidenceHold8336
import com.lifecyclebot.network.SolanaWallet
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercise the send observer used by all live-entry routes and the actual exception-budget receipt. */
class Aate8397SubmissionReceiptTest {
    private val now = 10_000_000L

    @After fun clean() { EvidenceHold8336.resetForTests8337(); EvidenceHold8336.enabled8336 = true }

    private fun reserve(suffix: String): BuySubmissionReceipt8397 {
        EvidenceHold8336.resetForTests8337()
        EvidenceHold8336.enabled8336 = true
        val ts = TokenState(mint = "submit8397-$suffix", symbol = "TEST")
        val ticket = ExecutableOpenGate.ExecutionIntent("attempt8397-$suffix", "candidate8397-$suffix", 1L,
            ts.mint, "LIVE", "MOONSHOT", "BUY", true, 1L, 0.03, now, "TEST")
        assertTrue(EvidenceHold8336.qualifyForTest8397(ts.mint, "MOONSHOT", 1L, now))
        assertNull(EvidenceHold8336.reserveEntry8397(ts, ticket, now + 1))
        return BuySubmissionReceipt8397(ticket.attemptId)
    }

    private fun finish(receipt: BuySubmissionReceipt8397) {
        EvidenceHold8336.finishEntry8397(receipt.attemptId, receipt.signature, now + 2)
        EvidenceHold8336.finishEntry8397(receipt.attemptId, receipt.signature, now + 3)
    }

    @Test fun confirmedUnknownFailedAndExpiredSubmissionsSpendExactlyOneAttempt() {
        val errors = listOf<Throwable?>(null,
            SolanaWallet.SentButUnconfirmed7733("unknown-signature", IllegalStateException("confirmation timeout")),
            SolanaWallet.OnChainFailed8085("failed-signature", "program error"),
            SolanaWallet.TxExpiredProven8085("expired-signature", 90))
        errors.forEachIndexed { i, error ->
            val receipt = reserve("outcome-$i")
            var thrown: Throwable? = null
            try {
                assertEquals("confirmed-signature", BuySubmissionReceipt8397.observe8397(receipt) {
                    if (error != null) throw error
                    "confirmed-signature"
                })
            } catch (t: Throwable) { thrown = t }
            assertSame("the caller keeps its exact outcome; failed/expired never become pending", error, thrown)
            assertNotNull(receipt.signature)
            finish(receipt)
            assertEquals(1L, EvidenceHold8336.exceptionCount8337())
        }
    }

    @Test fun aPreSubmitFailureOrAnOlderDuplicateDoesNotSpendThisAttempt() {
        val errors = listOf(IllegalStateException("build refused"),
            SolanaWallet.SubmitRejected8085("all transports refused"),
            SolanaWallet.DuplicateOrderPrevented8085("older-attempt-signature", "BUY"))
        errors.forEachIndexed { i, error ->
            val receipt = reserve("unsent-$i")
            var thrown: Throwable? = null
            try { BuySubmissionReceipt8397.observe8397<String>(receipt) { throw error } }
            catch (t: Throwable) { thrown = t }
            assertSame(error, thrown)
            assertNull("another attempt's signature is not this submission", receipt.signature)
            finish(receipt)
            assertEquals(0L, EvidenceHold8336.exceptionCount8337())
            assertTrue(EvidenceHold8336.rateAllows8337("MOONSHOT", now + 4))
        }
        val noRoute = reserve("no-route")
        assertNull(BuySubmissionReceipt8397.observe8397<String?>(noRoute) { null })
        finish(noRoute)
        assertEquals(0L, EvidenceHold8336.exceptionCount8337())
    }

    @Test fun safeFallbacksAndMultiTransactionBuildsRetainTheFirstSubmission() {
        val receipt = reserve("fallback")
        val failed = SolanaWallet.OnChainFailed8085("first-route-failed", "program error")
        try { BuySubmissionReceipt8397.observe8397<String>(receipt) { throw failed } }
        catch (t: Throwable) { assertSame(failed, t) }
        assertEquals("later-route-confirmed", BuySubmissionReceipt8397.observe8397(receipt) { "later-route-confirmed" })
        finish(receipt)
        assertEquals("first-route-failed", receipt.signature)
        assertEquals(1L, EvidenceHold8336.exceptionCount8337())

        val multi = reserve("multi")
        BuySubmissionReceipt8397.observe8397(multi) { "setup-transaction-confirmed" }
        val rejected = SolanaWallet.SubmitRejected8085("swap transaction preflight refused")
        try { BuySubmissionReceipt8397.observe8397<String>(multi) { throw rejected } }
        catch (t: Throwable) { assertSame(rejected, t) }
        assertEquals("setup-transaction-confirmed", multi.signature)
        finish(multi)
        assertEquals(1L, EvidenceHold8336.exceptionCount8337())
    }

    @Test fun everyBuyRouteCarriesTheSameReceiptIncludingTheBuilderFallback() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(source.contains("val submission8397 = BuySubmissionReceipt8397(recoveredLiveAttemptId)"))
        assertTrue(source.contains("buySend8085(wallet, ts.mint, tx, useJito, jitoTip, ultraReqId, c, submission8397)"))
        val pump = source.substring(source.indexOf("val pumpFirstResult:"), source.indexOf("val prepared8090 ="))
        assertTrue(pump.contains("submission8397 = submission8397"))
        assertEquals("all direct Raydium entry calls carry the receipt", 3,
            Regex("tryRaydiumBuy7325\\(ts, wallet, sol, tradeKey, submission8397\\)").findAll(source).count())
        assertTrue(source.contains("buildBuyTxOrRaydium8089(ts, wallet, sol, tradeKey, executionQuote7688, c, submission8397)"))
        assertTrue(source.contains("sendObserver8397 = { send -> BuySubmissionReceipt8397.observe8397(submission8397, send) }"))
        assertTrue(source.contains("intentId = submission8397.attemptId, route = \"RAYDIUM\""))
        assertTrue(source.contains("intentId = submission8397?.attemptId.orEmpty(), route = \"PUMPPORTAL\""))
        val ray = File("src/main/kotlin/com/lifecyclebot/network/RaydiumSellRoute7311.kt").readText()
        val loop = ray.indexOf("for (tx in built.transactions)")
        assertTrue("multi-transaction builds observe each send", ray.indexOf("sig = sendObserver8397?.invoke(send) ?: send()", loop) > loop)
    }
}

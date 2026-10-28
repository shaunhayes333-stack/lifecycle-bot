package com.lifecyclebot.engine

import com.lifecyclebot.network.SignedTxLedger8085
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** V5.0.8418 — PendingReconcileQueue books a late / recovered receipt to its owner exactly once. */
class Aate8418ReceiptBookingTest {
    private val mint = "MintBook8418"
    private val pid = "LIVE:pos-book-8418"

    private class FakeBooks(val buyAnswer: String = "APPLIED", val sellAnswer: String = "APPLIED") : ReceiptBooking8418.Books8418 {
        val buys = mutableListOf<Settlement8418.Fill8418>()
        val sells = mutableListOf<Pair<BigInteger, Double>>()
        override fun creditBuy(positionId: String, mint: String, signature: String, fill: Settlement8418.Fill8418): String {
            buys.add(fill); return buyAnswer
        }
        override fun bookSell(positionId: String, mint: String, signature: String, soldRaw: BigInteger, proceedsSol: Double): String {
            sells.add(soldRaw to proceedsSol); return sellAnswer
        }
    }

    private fun buy(sig: String, outcome: TradeVerifier.Outcome = TradeVerifier.Outcome.LANDED) = TradeVerifier.BuyResult(
        outcome, sig, mint, BigInteger.valueOf(3_000_000L), 3.0, 6, 25_010_000L, 0L, 0L, 10_000L, null,
    )

    private fun sell(sig: String, outcome: TradeVerifier.Outcome = TradeVerifier.Outcome.LANDED) = TradeVerifier.SellResult(
        outcome, sig, mint, BigInteger.valueOf(1_000_000L), 1.0, 6, 31_000_000L, false, null,
    )

    @Test fun aLateTopUpIsCreditedOnceFromItsReceiptAndThenStopsBlocking() {
        SignedTxLedger8085.resetForTest()
        try {
            val attempt = Settlement8418.topUpAttemptId8418(pid, mint, 1)
            SignedTxLedger8085.record("sigLate8418", "b64", "bh", SignedTxLedger8085.Intent(mint, "BUY", positionId = pid, intentId = attempt))
            SignedTxLedger8085.transition("sigLate8418", SignedTxLedger8085.State.CONFIRMED)
            assertNotNull("confirmed but unbooked: the next add for this attempt is blocked", SignedTxLedger8085.attemptSpent8418(attempt))
            assertEquals("a restart re-queues it", listOf("sigLate8418"), SignedTxLedger8085.confirmedUnbooked8418().map { it.signature })
            val books = FakeBooks()
            assertEquals(ReceiptBooking8418.Outcome8418.BOOKED,
                ReceiptBooking8418.onBuyReceipt8418(pid, attempt, mint, "sigLate8418", buy("sigLate8418"), false, books))
            val f = books.buys.single()
            assertEquals(BigInteger.valueOf(3_000_000L), f.rawQty)
            assertEquals(0.025, f.costSol, 1e-12)
            assertEquals(0.00001, f.feeSol, 1e-12)
            assertEquals("a restart re-registration of the same signature books nothing",
                ReceiptBooking8418.Outcome8418.DUPLICATE,
                ReceiptBooking8418.onBuyReceipt8418(pid, attempt, mint, "sigLate8418", buy("sigLate8418"), false, books))
            assertEquals(1, books.buys.size)
            assertNull("booked: the block clears", SignedTxLedger8085.attemptSpent8418(attempt))
            assertTrue(SignedTxLedger8085.isBooked8418(SignedTxLedger8085.get("sigLate8418")!!))
            assertTrue(SignedTxLedger8085.confirmedUnbooked8418().isEmpty())
            // The booked mark survives a restart with the row.
            val line = SignedTxLedger8085.encode(SignedTxLedger8085.get("sigLate8418")!!)
            SignedTxLedger8085.resetForTest()
            SignedTxLedger8085.restore(listOf(line))
            assertNull(SignedTxLedger8085.attemptSpent8418(attempt))
        } finally { SignedTxLedger8085.resetForTest() }
    }

    @Test fun anInFlowBookingMakesTheRecoveredCopyADuplicate() {
        val books = FakeBooks()
        ReceiptBooking8418.noteBookedInFlow8418("TOPUP_8418:inflow:1", "sigInFlow8418")
        assertEquals(ReceiptBooking8418.Outcome8418.DUPLICATE,
            ReceiptBooking8418.onBuyReceipt8418(pid, "TOPUP_8418:inflow:1", mint, "sigInFlow8418", buy("sigInFlow8418"), false, books))
        assertTrue(books.buys.isEmpty())
    }

    @Test fun failedOrMissingReceiptsEndWithNoEffect() {
        val books = FakeBooks()
        assertEquals(ReceiptBooking8418.Outcome8418.NO_EFFECT_FAILED, ReceiptBooking8418.onBuyReceipt8418(pid, "a1", mint, "sigF8418",
            buy("sigF8418", TradeVerifier.Outcome.FAILED_CONFIRMED), false, books))
        assertEquals(ReceiptBooking8418.Outcome8418.PENDING, ReceiptBooking8418.onBuyReceipt8418(pid, "a2", mint, "sigP8418",
            buy("sigP8418", TradeVerifier.Outcome.INCONCLUSIVE_PENDING), false, books))
        assertEquals(ReceiptBooking8418.Outcome8418.NO_EFFECT_NO_RECEIPT, ReceiptBooking8418.onBuyReceipt8418(pid, "a2", mint, "sigP8418",
            null, true, books))
        assertEquals(ReceiptBooking8418.Outcome8418.NO_EFFECT_FAILED, ReceiptBooking8418.onSellReceipt8418(pid, mint, "sigSF8418",
            sell("sigSF8418", TradeVerifier.Outcome.FAILED_CONFIRMED), false, books))
        assertEquals(ReceiptBooking8418.Outcome8418.NO_EFFECT_NO_RECEIPT, ReceiptBooking8418.onSellReceipt8418(pid, mint, "sigSN8418",
            null, true, books))
        assertTrue("no position was touched", books.buys.isEmpty() && books.sells.isEmpty())
    }

    @Test fun ownerGoneOrAnEntryIsNotCreditedHere() {
        val gone = FakeBooks(buyAnswer = "UNKNOWN_POSITION")
        assertEquals(ReceiptBooking8418.Outcome8418.NO_EFFECT_OWNER_GONE,
            ReceiptBooking8418.onBuyReceipt8418(pid, "a3", mint, "sigG8418", buy("sigG8418"), false, gone))
        assertTrue("not marked booked", !ReceiptBooking8418.isBooked8418("a3", "sigG8418"))
        val books = FakeBooks()
        assertEquals("an entry buy is the entry proof path's", ReceiptBooking8418.Outcome8418.ENTRY_PROOF_PATH,
            ReceiptBooking8418.onBuyReceipt8418("", "LIVE:QUALITY:entry", mint, "sigE8418", buy("sigE8418"), false, books))
        assertTrue(books.buys.isEmpty())
    }

    @Test fun aRecoveredSellBooksItsOwnReceiptProceedsOnce() {
        val books = FakeBooks()
        assertEquals(ReceiptBooking8418.Outcome8418.BOOKED,
            ReceiptBooking8418.onSellReceipt8418(pid, mint, "sigS8418", sell("sigS8418"), false, books))
        assertEquals(BigInteger.valueOf(1_000_000L) to 0.031, books.sells.single())
        assertEquals(ReceiptBooking8418.Outcome8418.DUPLICATE,
            ReceiptBooking8418.onSellReceipt8418(pid, mint, "sigS8418", sell("sigS8418"), false, books))
        assertEquals(1, books.sells.size)
        assertEquals(0.0125, ReceiptBooking8418.soldBasisSol8418(0.1, 0.05, BigInteger.valueOf(4), BigInteger.ONE), 1e-12)
        assertEquals("never more than the remaining basis", 0.05,
            ReceiptBooking8418.soldBasisSol8418(0.1, 0.05, BigInteger.valueOf(4), BigInteger.TEN), 1e-12)
    }

    @Test fun theQueueBooksThroughTheReceiptOwner() {
        val q = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PendingReconcileQueue.kt").readText()
        assertTrue(q.contains("ReceiptBooking8418.onBuyReceipt8418(owner8418, attempt8418, e.mint, e.sig,"))
        assertTrue(q.contains("ReceiptBooking8418.onSellReceipt8418(owner8418, e.mint, e.sig,"))
        assertTrue(q.contains("runOneCheck(e, cp, cp == CHECKPOINTS_MS.last())"))
    }
}

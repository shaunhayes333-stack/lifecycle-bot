package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635 as Events
import com.lifecyclebot.engine.truth.CanonicalPaperTransaction6486 as Transactions
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray

class Aate6736EconomicCompletionRaceTest {
    @Before fun setup() { Events.resetForTest() }
    @After fun cleanup() { Events.resetForTest() }

    private fun event(id: String) = Events.Event(
        economicEventId = id, positionId = "position-$id", mint = "mint-$id", canonicalMint = "mint-$id",
        symbol = "TEST", mode = "paper", lane = "QUALITY", side = Events.Side.SELL,
        timestampMs = System.currentTimeMillis() - 180_000L,
        qtyRaw = BigInteger.TEN, decimals = 0, executionPriceUsd = 1.0,
        executionPriceSol = 0.001, notionalSol = 0.05, feeSol = 0.001,
        cashDeltaSol = 0.049, positionQtyDeltaRaw = -BigInteger.TEN,
        realizedPnlDeltaSol = -0.001, terminalFillIndex = 0,
    )

    @Test fun `pending event completes only after its missing real journal receipt`() {
        val e = event("late-journal")
        assertTrue(Events.openEvent(e))
        val calls = AtomicInteger()
        assertTrue(Events.afterCommitted(e.economicEventId) { calls.incrementAndGet() })
        Events.Store.values().filter { it != Events.Store.JOURNAL }.forEach {
            assertTrue(Events.markCommitted(e.economicEventId, it, "test"))
        }
        Events.sweepPending6635(60_000L)
        assertFalse(Events.isCommitted(e.economicEventId))
        assertEquals(0, calls.get())
        assertTrue(Events.forensicReconciliationLine6635().contains("pending=1"))
        assertTrue(Events.markCommitted(e.economicEventId, Events.Store.JOURNAL, "late"))
        assertTrue(Events.isCommitted(e.economicEventId))
        assertEquals(1, calls.get())
        assertNotNull(Events.committedTerminalEventForPosition(e.positionId, e.economicEventId))
        assertTrue(Events.forensicReconciliationLine6635().contains("pending=0"))
        assertFalse(Events.markCommitted(e.economicEventId, Events.Store.JOURNAL, "duplicate"))
        assertEquals(1, calls.get())
    }

    @Test fun `timeout alone never manufactures completion or learning`() {
        val e = event("still-incomplete")
        Events.openEvent(e)
        val calls = AtomicInteger()
        Events.afterCommitted(e.economicEventId) { calls.incrementAndGet() }
        Events.markCommitted(e.economicEventId, Events.Store.LEDGER, "ledger-only")
        repeat(3) { Events.sweepPending6635(1L) }
        assertFalse(Events.isCommitted(e.economicEventId))
        assertEquals(0, calls.get())
        assertNull(Events.committedTerminalEventForPosition(e.positionId, e.economicEventId))
    }

    @Test(timeout = 10000L) fun `concurrent receipt duplicates and listener registration lose no callbacks`() {
        val e = event("concurrent")
        Events.openEvent(e)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val calls = AtomicIntegerArray(100)
        try {
            val listeners = (0 until 100).map { i ->
                pool.submit<Boolean> {
                    start.await()
                    Events.afterCommitted(e.economicEventId) { calls.incrementAndGet(i) }
                }
            }
            val receipts = (0 until 20).flatMap {
                Events.Store.values().map { store ->
                    pool.submit<Boolean> {
                        start.await()
                        Events.markCommitted(e.economicEventId, store, "concurrent")
                    }
                }
            }
            start.countDown()
            listeners.forEach { assertTrue(it.get(5, TimeUnit.SECONDS)) }
            assertEquals(5, receipts.count { it.get(5, TimeUnit.SECONDS) })
            assertTrue(Events.isCommitted(e.economicEventId))
            (0 until 100).forEach { assertEquals("listener $it", 1, calls.get(it)) }
            assertTrue(Events.statusLine6635().contains("committed=1 "))
        } finally { pool.shutdownNow() }
    }

    @Test fun `post commit listeners execute once and one failure cannot drop another`() {
        val e = event("listeners")
        Events.openEvent(e)
        val calls = AtomicInteger()
        Events.afterCommitted(e.economicEventId) { error("intentional test callback failure") }
        Events.afterCommitted(e.economicEventId) { calls.incrementAndGet() }
        Events.Store.values().forEach { Events.markCommitted(e.economicEventId, it, "test") }
        assertTrue(Events.afterCommitted(e.economicEventId) { calls.incrementAndGet() })
        assertEquals(2, calls.get())
    }

    @Test(timeout = 10000L) fun `completion callback does not hold the economic state lock`() {
        val e = event("outside-lock")
        Events.openEvent(e)
        val pool = Executors.newSingleThreadExecutor()
        val nested = AtomicInteger()
        try {
            Events.afterCommitted(e.economicEventId) {
                pool.submit<Boolean> {
                    Events.afterCommitted(e.economicEventId) { nested.incrementAndGet() }
                }.get(3, TimeUnit.SECONDS)
            }
            Events.Store.values().forEach { Events.markCommitted(e.economicEventId, it, "test") }
            assertEquals(1, nested.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun `unknown event cannot receive completion listeners`() {
        assertFalse(Events.afterCommitted("missing") { error("must not execute") })
        assertFalse(Events.afterCommitted("") { error("must not execute") })
    }

    @Test fun `raw repair is legacy only never an extra debit before a typed receipt`() {
        val legacy = Trade(side = "BUY", mode = "paper", sol = 0.05, price = 1.0, ts = 1L)
        assertTrue(Transactions.permitsLegacyQuantityRepair6736(legacy))
        assertFalse(Transactions.permitsLegacyQuantityRepair6736(legacy.copy(economicEventId = "BUY:1")))
        assertFalse(Transactions.permitsLegacyQuantityRepair6736(legacy.copy(operationId = "1")))
        val transaction = java.io.File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt").readText()
        val repair = transaction.substringAfter("private fun repairJournalQuantityDrift6666(").substringBefore("data class Result(")
        assertTrue(repair.indexOf("permitsLegacyQuantityRepair6736(seed)") >= 0)
        assertTrue(repair.indexOf("permitsLegacyQuantityRepair6736(seed)") < repair.indexOf("stampLedger("))
    }
}

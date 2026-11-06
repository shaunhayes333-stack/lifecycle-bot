package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635
import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.Side
import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * V5.0.8570 — the durable journal insert is normally the last of the five stores, so every post-commit listener ran on
 * the single journal write thread and later journal rows queued behind learning fan-out (CI receipts jrn=false for
 * 33-60 s, device journalIO8368 p50=620 s). The writer now hands its listeners to one FIFO listener thread.
 */
class Aate8570JournalWriterTest {
    private fun buy(pid: String) = CanonicalEconomicEvent6635.Event(
        economicEventId = "PAPER8570:OPEN:$pid", positionId = pid, mint = "MINT8570$pid", canonicalMint = "MINT8570$pid",
        symbol = "T", mode = "paper", lane = "SHITCOIN", side = Side.BUY, timestampMs = System.currentTimeMillis(),
        qtyRaw = BigInteger.valueOf(1_000L), decimals = 6, executionPriceUsd = 1.0, executionPriceSol = 0.01,
        notionalSol = 0.1, feeSol = 0.0, cashDeltaSol = -0.1, positionQtyDeltaRaw = BigInteger.valueOf(1_000L),
        realizedPnlDeltaSol = 0.0, terminalFillIndex = 0,
    )

    private fun commitAllButJournal(e: CanonicalEconomicEvent6635.Event) {
        assertTrue(CanonicalEconomicEvent6635.openEvent(e))
        for (s in listOf(Store.POSITION, Store.LEDGER, Store.TERMINAL_EXEC, Store.FILL_LOT)) {
            assertTrue(CanonicalEconomicEvent6635.markCommitted(e.economicEventId, s, "test8570"))
        }
    }

    @Test fun the_journal_writer_never_runs_listeners_and_they_still_run_after_commit_in_order() {
        val writer = Thread.currentThread()
        val ran = java.util.Collections.synchronizedList(mutableListOf<String>())
        val threads = java.util.Collections.synchronizedList(mutableListOf<Thread>())
        val done = CountDownLatch(2)
        val a = buy("A${System.nanoTime()}")
        val b = buy("B${System.nanoTime()}")
        for ((e, tag) in listOf(a to "a", b to "b")) {
            commitAllButJournal(e)
            assertTrue(CanonicalEconomicEvent6635.afterCommitted(e.economicEventId) {
                assertTrue(CanonicalEconomicEvent6635.isCommitted(e.economicEventId))
                threads += Thread.currentThread(); ran += tag; done.countDown()
            })
        }
        assertTrue(CanonicalEconomicEvent6635.markCommitted(a.economicEventId, Store.JOURNAL, "test8570", offloadListeners8570 = true))
        assertTrue(CanonicalEconomicEvent6635.markCommitted(b.economicEventId, Store.JOURNAL, "test8570", offloadListeners8570 = true))
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("a", "b"), ran.toList())
        threads.forEach { assertNotEquals(writer, it); assertEquals("CommitListener8570", it.name) }
        assertTrue(CanonicalEconomicEvent6635.listenerBatches8570() >= 2)
    }

    @Test fun other_stores_keep_running_listeners_inline() {
        val e = buy("C${System.nanoTime()}")
        commitAllButJournal(e)
        var inline: Thread? = null
        assertTrue(CanonicalEconomicEvent6635.afterCommitted(e.economicEventId) { inline = Thread.currentThread() })
        assertTrue(CanonicalEconomicEvent6635.markCommitted(e.economicEventId, Store.JOURNAL, "test8570"))
        assertEquals(Thread.currentThread(), inline)
    }

    @Test fun the_sqlite_journal_stamp_offloads_its_listeners() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        val fn = src.substringAfter("private fun stampDurableJournalCommit6641(").substringBefore("private fun tradeToContentValues(")
        assertTrue(fn.contains("\"TradeHistoryStore.SQLite.durable6641\",\n            offloadListeners8570 = true"))
    }
}

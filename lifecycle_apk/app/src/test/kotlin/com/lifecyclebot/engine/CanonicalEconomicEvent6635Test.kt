package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635
import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.Side
import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635.Store
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

class CanonicalEconomicEvent6635Test {

    @Before fun reset() { CanonicalEconomicEvent6635.resetForTest() }
    @After  fun tearDown() { CanonicalEconomicEvent6635.resetForTest() }

    private fun buy(id: String, pid: String, mint: String = "MintA"): CanonicalEconomicEvent6635.Event =
        CanonicalEconomicEvent6635.Event(
            economicEventId = id, positionId = pid, mint = mint, canonicalMint = mint,
            symbol = "SYM", mode = "paper", lane = "MEME", side = Side.BUY,
            timestampMs = System.currentTimeMillis(),
            qtyRaw = BigInteger.valueOf(1_000_000_000L), decimals = 9,
            executionPriceUsd = 0.001, executionPriceSol = 0.0000005,
            notionalSol = 0.05, feeSol = 0.001, cashDeltaSol = -0.051,
            positionQtyDeltaRaw = BigInteger.valueOf(1_000_000_000L),
            realizedPnlDeltaSol = 0.0, terminalFillIndex = 0,
        )

    @Test fun open_then_five_store_commits_finalises_ok() {
        val id = CanonicalEconomicEvent6635.mintEventId()
        assertTrue(CanonicalEconomicEvent6635.openEvent(buy(id, "pid-1")))
        for (s in Store.values()) {
            assertTrue(CanonicalEconomicEvent6635.markCommitted(id, s, "test.$s"))
        }
        val line = CanonicalEconomicEvent6635.forensicReconciliationLine6635()
        assertTrue(line.contains("status=RECONCILED"))
        assertTrue(line.contains("pending=0"))
    }

    @Test fun blank_position_id_refused() {
        val id = CanonicalEconomicEvent6635.mintEventId()
        assertFalse(CanonicalEconomicEvent6635.openEvent(buy(id, "")))
    }

    @Test fun duplicate_open_refused() {
        val id = CanonicalEconomicEvent6635.mintEventId()
        assertTrue(CanonicalEconomicEvent6635.openEvent(buy(id, "pid-2")))
        assertFalse(CanonicalEconomicEvent6635.openEvent(buy(id, "pid-2")))
    }

    @Test fun commit_without_open_is_defect() {
        val ok = CanonicalEconomicEvent6635.markCommitted(
            "unknown-event", Store.LEDGER, "test.orphan",
        )
        assertFalse(ok)
    }

    @Test fun partial_commit_moves_to_pending_after_ttl() {
        val id = CanonicalEconomicEvent6635.mintEventId()
        val e = buy(id, "pid-3").copy(timestampMs = System.currentTimeMillis() - 120_000L)
        CanonicalEconomicEvent6635.openEvent(e)
        CanonicalEconomicEvent6635.markCommitted(id, Store.LEDGER, "test.only")
        CanonicalEconomicEvent6635.sweepPending6635(ttlMs = 60_000L)
        val line = CanonicalEconomicEvent6635.forensicReconciliationLine6635()
        assertTrue("must record pending: $line", line.contains("pending=1"))
        assertTrue("must record journal missing: $line", line.contains("missingJournal=1"))
        assertTrue("must record ledger-only commit: $line", line.contains("ledgerOnlyCommits=1"))
        assertTrue("status must be FAILED: $line", line.contains("status=FAILED"))
    }

    @Test fun openEventForPosition_returns_buy_event_by_positionId() {
        val id = CanonicalEconomicEvent6635.mintEventId()
        CanonicalEconomicEvent6635.openEvent(buy(id, "pid-4"))
        val ev = CanonicalEconomicEvent6635.openEventForPosition("pid-4")
        assertNotNull(ev)
        assertEquals(id, ev!!.economicEventId)
    }
}

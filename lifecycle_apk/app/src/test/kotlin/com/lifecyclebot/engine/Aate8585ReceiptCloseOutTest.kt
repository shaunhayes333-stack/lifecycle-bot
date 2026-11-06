package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CanonicalEconomicEvent6635
import com.lifecyclebot.engine.truth.LearningQuarantineGate6470
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigInteger

/**
 * V5.0.8585 — 8572 / 8581 smokes: a paper sale cut its position, then the ledger refused its cash (pos=true led=false) and
 * the receipt stayed PENDING_RECONCILIATION for good. Two causes at the source: the bridge pre-check (8576) was a copy of
 * the ledger's rules without the mint quarantine, and nothing ever closed an event a refusal had ended.
 */
class Aate8585ReceiptCloseOutTest {
    private fun event(id: String, pid: String) = CanonicalEconomicEvent6635.Event(
        economicEventId = id, positionId = pid, mint = "M8585", canonicalMint = "M8585", symbol = "T", mode = "paper",
        lane = "SHITCOIN", side = CanonicalEconomicEvent6635.Side.SELL, timestampMs = System.currentTimeMillis() - 300_000L,
        qtyRaw = BigInteger.ONE, decimals = 6, executionPriceUsd = 0.0, executionPriceSol = 0.0, notionalSol = 0.1, feeSol = 0.0,
        cashDeltaSol = 0.1, positionQtyDeltaRaw = BigInteger.ONE.negate(), realizedPnlDeltaSol = 0.0, terminalFillIndex = 1,
    )

    @Test fun a_refused_event_with_nothing_committed_is_closed_and_a_half_commit_is_not_hidden() {
        CanonicalEconomicEvent6635.resetForTest()
        assertTrue(CanonicalEconomicEvent6635.openEvent(event("E_REFUSED_8585", "P1")))
        assertTrue(CanonicalEconomicEvent6635.abandon8585("E_REFUSED_8585", "LEDGER_BASIS_REJECTED"))
        assertFalse(CanonicalEconomicEvent6635.incompleteReceipts8253().contains("E_REFUSED_8585"))
        assertFalse("a closed event takes no late store", CanonicalEconomicEvent6635.markCommitted("E_REFUSED_8585",
            CanonicalEconomicEvent6635.Store.LEDGER, "test"))
        assertTrue(CanonicalEconomicEvent6635.abandonedCount8585() >= 1L)

        assertTrue(CanonicalEconomicEvent6635.openEvent(event("E_HALF_8585", "P2")))
        CanonicalEconomicEvent6635.markCommitted("E_HALF_8585", CanonicalEconomicEvent6635.Store.POSITION, "test")
        assertFalse(CanonicalEconomicEvent6635.abandon8585("E_HALF_8585", "LEDGER_COMMIT_FAILED"))
        CanonicalEconomicEvent6635.sweepPending6635(ttlMs = 1L)
        assertTrue("a real half-commit stays visible to the witness", CanonicalEconomicEvent6635.incompleteReceipts8253().contains("E_HALF_8585"))
        assertFalse(CanonicalEconomicEvent6635.abandon8585("NO_SUCH_EVENT_8585", "X"))
        CanonicalEconomicEvent6635.resetForTest()
    }

    @Test fun the_learning_quarantine_binds_the_quarantined_position_not_a_later_one_on_the_mint() {
        LearningQuarantineGate6470.resetForTest()
        LearningQuarantineGate6470.quarantineMint("MINT_Q_8585", "EARLIER_TRADE")
        assertNull("a later canonical position is sold on its own facts",
            PaperAccountLedger6430.quarantineRefusal8585("MINT_Q_8585", "PAPER:MINT_Q_8585:2", enforce = true))
        assertEquals("a legacy caller without a position id keeps the mint-wide rule", "QUARANTINED_HISTORICAL",
            PaperAccountLedger6430.quarantineRefusal8585("MINT_Q_8585", "", enforce = true))
        LearningQuarantineGate6470.quarantinePositionId("PAPER:MINT_Q_8585:3", "THIS_TRADE")
        assertEquals("QUARANTINED_HISTORICAL", PaperAccountLedger6430.quarantineRefusal8585("MINT_Q_8585", "PAPER:MINT_Q_8585:3", enforce = true))
        assertNull(PaperAccountLedger6430.quarantineRefusal8585("MINT_Q_8585", "", enforce = false))
        assertNull(PaperAccountLedger6430.quarantineRefusal8585("", "", enforce = true))
        assertEquals("NON_FINITE", PaperAccountLedger6430.sellRefusal8585(Double.NaN, 0.1, 0.0, "M", "P", true))
        LearningQuarantineGate6470.resetForTest()
    }

    @Test fun the_bridge_and_the_ledger_share_one_refusal_and_refused_events_are_closed() {
        val ledger = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt").readText()
        val sell = ledger.substringAfter("fun onSellAtomic6632(").substringBefore("cashPico.addAndGet(")
        assertTrue(sell.contains("quarantineRefusal8585(mint, positionId8585, enforceSolanaMintQuarantine)"))
        assertTrue(sell.contains("economicRefusal8576(grossProceedsSol, costBasisSoldSol, feeSol)"))
        val bridge = File("src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTerminalBridge6469.kt").readText()
        val fn = bridge.substringAfter("fun finalizeSell(")
        assertTrue(fn.indexOf("PaperAccountLedger6430.sellRefusal8585(") in 0 until fn.indexOf("val claim = claimBeforeSideEffects("))
        assertTrue(fn.contains("positionId8585 = positionId,"))
        assertEquals(2, Regex("CanonicalEconomicEvent6635\\.abandon8585\\(sellSig, ").findAll(fn).count())
        val ex = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertEquals(2, Regex("abandon8585\\(entryFinalityId6497, \"PAPER_BUY_ROLLBACK\"\\)").findAll(ex).count())
    }
}

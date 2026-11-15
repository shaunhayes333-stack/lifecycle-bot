package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.JournalEconomicReplay6619
import com.lifecyclebot.engine.truth.PaperAccountLedger6430
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8765 — code audit (accounting 1, 2): a position that left the books without a sale is a loss, not a refund at cost,
 * and the boot rebuilds report instead of rewriting realised and cash.
 */
class Aate8765NoRefundAtCostTest {
    @Test fun open_cost_the_projections_no_longer_hold_is_written_off_not_refunded() {
        PaperAccountLedger6430.resetForTest()
        PaperAccountLedger6430.initialize(10.0)
        assertTrue(PaperAccountLedger6430.onBuy(2.0, 0.0))
        assertEquals(8.0, PaperAccountLedger6430.cashSol(), 1e-9)
        // a 1.5 SOL position was quarantined: the projections hold 0.5 SOL of open cost
        PaperAccountLedger6430.overrideOpenCostFromProjections6508(0.5)
        assertEquals(0.5, PaperAccountLedger6430.openCostBasisSol(), 1e-9)
        assertEquals("written off as realised loss", -1.5, PaperAccountLedger6430.realizedPnlSol(), 1e-9)
        assertEquals("and not refunded as cash", 8.0, PaperAccountLedger6430.cashSol(), 1e-9)
        assertNull(PaperAccountLedger6430.assertInvariant())
        assertEquals(8.0, PaperAccountLedger6430.rebuildPaperCashFromIdentity6505(), 1e-9)
        assertEquals(8.0, PaperAccountLedger6430.cashSol(), 1e-9)
        // a projection above the ledger is reported, never written
        PaperAccountLedger6430.overrideOpenCostFromProjections6508(3.0)
        assertEquals(0.5, PaperAccountLedger6430.openCostBasisSol(), 1e-9)
    }

    @Test fun the_boot_rebuilds_only_report() {
        val ledger = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperAccountLedger6430.kt").readText()
        assertFalse(ledger.contains("fun overrideRealizedFromFillLots6504("))
        // superseded by V5.0.8785 — the identity writes cash again (6508 now writes removed basis off as a loss, so it cannot
        // refund it); was: assertFalse(cash.contains("cashPico.set(")).
        val cash = ledger.substringAfter("fun rebuildPaperCashFromIdentity6505(").substringBefore("\n    }\n")
        assertTrue(cash.contains("cashPico.set(toPico(recomputed))"))
        val realized = ledger.substringAfter("fun rebuildRealizedFromCanonicalEvents6502(").substringBefore("\n    }\n")
        assertFalse(realized.contains("realizedPnlPico.set("))
    }

    @Test fun an_orphaned_or_quarantined_paper_lot_is_journaled_as_a_zero_proceeds_loss() {
        assertTrue(JournalEconomicReplay6619.orphanRow8765("PAPER6619:ORPHAN_REFUND:p1:r1:b1"))
        assertTrue(JournalEconomicReplay6619.orphanRow8765("PAPER6619:ORPHAN_WRITE_OFF_8765:p1:r1:b1"))
        assertFalse(JournalEconomicReplay6619.orphanRow8765("PAPER6619:SELL:p1"))
        val r = File("src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt").readText()
        val repair = r.substringAfter("fun repairOrphanedOpenLots6662(").substringBefore("\n    fun statusLine")
        assertTrue(repair.contains("canonical.lifecycle != CanonicalPositionAuthority6441.Lifecycle.QUARANTINED"))
        assertTrue(repair.contains("grossProceedsSol = 0.0, economicEventId = eventId,"))
        assertEquals("ORPHANED_LOT_WRITTEN_OFF_8765", JournalEconomicReplay6619.ORPHAN_WRITE_OFF_REASON_8765)
    }
}
